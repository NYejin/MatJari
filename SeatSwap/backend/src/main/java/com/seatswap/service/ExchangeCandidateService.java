package com.seatswap.service;

import com.seatswap.domain.ExchangeRequest;
import com.seatswap.domain.ExtraType;
import com.seatswap.domain.Ticket;
import com.seatswap.dto.response.CandidateSliceResponse;
import com.seatswap.dto.response.ExchangeCandidateResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.ExchangeCandidateRepository;
import com.seatswap.repository.ExchangeRequestRepository;
import com.seatswap.repository.ExchangeTicketLockRepository;
import com.seatswap.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 매칭 후보 조회 (읽기 전용). 후보는 조건 일치 판정으로만 찾고 점수화·랭킹·신뢰도는 쓰지 않는다.
 * 잠금을 잡지 않으므로 잠금 순서 규약(티켓 -> 요청)과 충돌하지 않는다.
 * 권한: 남의 요청 403, 없는 요청 404. 내 요청이 CLOSED 이거나 내 티켓이 INACTIVE 이거나 내 티켓 회차가 마감되면 422,
 * 내 티켓이 예약 잠금 상태(RESERVED)여도 422 TICKET_LOCKED (이미 교환 상대가 정해졌다).
 * 후보에서 빠지는 것: 상대 티켓이 예약 잠금, 같은 요청 쌍의 열린 매칭이 있는 상대(차단·이용 정지 사용자도 SQL 에서 제외한다).
 */
@Service
@RequiredArgsConstructor
public class ExchangeCandidateService {

    static final int MAX_PAGE_SIZE = 100;
    static final String REQUEST_NOT_FOUND_MESSAGE = "교환 요청을 찾을 수 없습니다.";
    static final String FORBIDDEN_MESSAGE = "본인의 교환 요청만 조회할 수 있습니다.";
    static final String NOT_ACTIVE_MESSAGE = "내린 티켓이나 닫힌 요청은 후보를 조회할 수 없습니다.";
    static final String SESSION_CLOSED_MESSAGE = "회차 당일이 지난 티켓은 후보를 조회할 수 없습니다.";
    static final String TICKET_LOCKED_CODE = "TICKET_LOCKED";
    static final String TICKET_LOCKED_MESSAGE = "이미 교환이 예약된 티켓은 후보를 조회할 수 없습니다. 예약을 취소하면 다시 조회할 수 있어요.";

    private final ExchangeRequestRepository requestRepository;
    private final TicketRepository ticketRepository;
    private final ExchangeCandidateRepository candidateRepository;
    private final SessionTimePolicy timePolicy;
    private final ExchangeTicketLockRepository lockRepository;

    @Transactional(readOnly = true)
    public PageResponse<ExchangeCandidateResponse> findCandidates(Long userId, Long requestId, int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LocalDateTime todayStart = validatedTodayStart(userId, requestId);
        long offset = (long) page * pageSize;
        List<ExchangeCandidateRepository.Row> rows =
                candidateRepository.findCandidates(requestId, todayStart, pageSize, offset);
        long total = candidateRepository.countCandidates(requestId, todayStart);
        List<ExchangeCandidateResponse> content = rows.stream().map(ExchangeCandidateService::toResponse).toList();
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return new PageResponse<>(content, page, pageSize, total, totalPages);
    }

    /**
     * keyset(커서) 방식 조회. 같은 정렬·같은 판정이지만 전체 개수를 세지 않고 offset 을 쓰지 않아, 후보가 수천 건이어도 뒤쪽 조각 비용이 일정하다.
     * 첫 조각은 cursor 를 비우고, 응답의 nextCursor 를 그대로 다음 요청에 넣는다. 잘못된 cursor 는 400(cursor 필드).
     * 조각 사이에 후보가 생기거나 사라져도 커서 뒤의 행만 읽으므로 중복·누락 없이 이어진다(새로 생긴 후보가 커서보다 앞이면 다음 첫 조각에서 보인다).
     */
    @Transactional(readOnly = true)
    public CandidateSliceResponse findCandidateSlice(Long userId, Long requestId, String cursorText, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        ExchangeCandidateRepository.Cursor cursor = null;
        if (cursorText != null && !cursorText.isBlank()) {
            try {
                cursor = ExchangeCandidateRepository.Cursor.decode(cursorText.trim());
            } catch (IllegalArgumentException e) {
                throw new FieldValidationException("cursor", "올바르지 않은 cursor입니다.");
            }
        }
        LocalDateTime todayStart = validatedTodayStart(userId, requestId);
        List<ExchangeCandidateRepository.Row> rows = candidateRepository.findCandidatesAfter(requestId, todayStart, cursor, pageSize + 1);
        boolean hasNext = rows.size() > pageSize;
        List<ExchangeCandidateRepository.Row> page = hasNext ? rows.subList(0, pageSize) : rows;
        String next = hasNext ? ExchangeCandidateRepository.Cursor.of(page.get(page.size() - 1)).encode() : null;
        return new CandidateSliceResponse(page.stream().map(ExchangeCandidateService::toResponse).toList(), pageSize, hasNext, next);
    }

    /** 권한·상태 검증(남의 요청 403, 없음 404, 삭제 409, 닫힘·마감 422, 내 티켓 잠김 422). 통과하면 오늘 0시(KST)를 돌려준다. */
    private LocalDateTime validatedTodayStart(Long userId, Long requestId) {
        ExchangeRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new NotFoundException(REQUEST_NOT_FOUND_MESSAGE));
        Ticket ticket = ticketRepository.findWithSessionById(request.getTicket().getId())
                .orElseThrow(() -> new NotFoundException(ExchangeRequestService.TICKET_NOT_FOUND_MESSAGE));
        if (!ticket.isOwnedBy(userId)) {
            throw new ForbiddenException(FORBIDDEN_MESSAGE);
        }
        if (request.isDeleted()) {
            throw new ConflictException(ExchangeRequestService.REQUEST_DELETED_MESSAGE,
                    Map.of("code", ExchangeRequestService.REQUEST_DELETED_CODE));
        }
        if (!ticket.isActive() || !request.isOpen()) {
            throw new BusinessRuleException(ExchangeRequestService.TICKET_NOT_ACTIVE_CODE, NOT_ACTIVE_MESSAGE);
        }
        LocalDateTime now = timePolicy.now();
        if (!now.isBefore(ticket.getPerformanceSession().registrationDeadline())) {
            throw new BusinessRuleException(ExchangeRequestService.TICKET_SESSION_CLOSED_CODE, SESSION_CLOSED_MESSAGE);
        }
        if (lockRepository.existsByTicketId(ticket.getId())) {
            throw new BusinessRuleException(TICKET_LOCKED_CODE, TICKET_LOCKED_MESSAGE);
        }

        return now.toLocalDate().atStartOfDay();
    }

    static ExchangeCandidateResponse toResponse(ExchangeCandidateRepository.Row r) {
        return new ExchangeCandidateResponse(r.requestId(), r.ticketId(), r.zone(), r.row(), r.col(),
                r.sessionId(), r.startsAt(), r.nickname(), r.wantPriority(),
                r.extraType(), r.extraAmount(), r.myExtraType(), r.myExtraAmount(),
                settlementHint(r.myExtraType(), r.myExtraAmount(), r.extraType(), r.extraAmount()),
                r.requestedAt());
    }

    /**
     * 참고 구간. 한쪽 POS(받아야 하는 최소 m) - 다른 쪽 NEG(낼 수 있는 최대 p = -금액)이고 둘 다 금액이 있을 때만 계산한다.
     * p >= m 이면 [m, p], 아니면 null. 매칭 판정과 무관한 참고값이다.
     */
    static ExchangeCandidateResponse.SettlementHint settlementHint(String myType, Integer myAmount,
                                                                   String theirType, Integer theirAmount) {
        if (myAmount == null || theirAmount == null) {
            return null;
        }
        long min;
        long max;
        if (ExtraType.POS.name().equals(myType) && ExtraType.NEG.name().equals(theirType)) {
            min = myAmount;
            max = -(long) theirAmount;
        } else if (ExtraType.NEG.name().equals(myType) && ExtraType.POS.name().equals(theirType)) {
            min = theirAmount;
            max = -(long) myAmount;
        } else {
            return null;
        }
        return max >= min ? new ExchangeCandidateResponse.SettlementHint(min, max) : null;
    }
}
