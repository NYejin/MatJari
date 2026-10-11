package com.seatswap.service;

import com.seatswap.domain.PerformanceSession;
import com.seatswap.domain.Ticket;
import com.seatswap.domain.TicketStatus;
import com.seatswap.domain.User;
import com.seatswap.dto.request.TicketCreateRequest;
import com.seatswap.dto.response.TicketResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.ExchangeMatchRepository;
import com.seatswap.repository.ExchangeRequestRepository;
import com.seatswap.repository.ExchangeTicketLockRepository;
import com.seatswap.repository.PerformanceSessionRepository;
import com.seatswap.repository.TicketRepository;
import com.seatswap.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 티켓 등록·내 티켓 조회·내리기 (FR-03). 모두 로그인 사용자 본인 기준이다.
 * - 같은 회차·구역·열·번의 활성 티켓은 1개: 사전 조회로 친절한 409, 동시 요청은 uk_ticket_active_seat 가 최종 방어.
 * - 사용자당 활성 티켓 상한(ticket.max-active-per-user, 기본 20): users 행 FOR UPDATE 로 사용자 단위 직렬화 후 COUNT.
 * - 회차 당일 끝(다음날 0시 KST)까지만 등록할 수 있다. 자동 비활성(스케줄러)은 이 서비스의 범위가 아니다.
 */
@Slf4j
@Service
public class TicketService {

    static final String SEAT_ALREADY_REGISTERED_MESSAGE =
            "이미 등록된 좌석입니다. 본인의 티켓이라면 '내 티켓 인증'을 이용해주세요.";
    static final String MY_TICKET_ALREADY_REGISTERED_MESSAGE = "이미 내 티켓으로 등록된 좌석입니다.";
    static final String LIMIT_REACHED_CODE = "TICKET_LIMIT_REACHED";
    static final String SESSION_NOT_FOUND_MESSAGE = "존재하지 않는 회차입니다.";
    static final String SESSION_CLOSED_MESSAGE = "회차 당일이 지나 티켓을 등록할 수 없습니다.";
    static final String TICKET_NOT_FOUND_MESSAGE = "티켓을 찾을 수 없습니다.";
    static final String TICKET_RESERVED_CODE = "TICKET_RESERVED";
    static final String TICKET_EXCHANGED_CODE = "TICKET_EXCHANGED";
    static final String TICKET_EXCHANGED_MESSAGE = "교환이 완료된 티켓은 내릴 수 없습니다. 교환으로 받은 새 티켓을 이용해주세요.";
    static final String TICKET_RESERVED_MESSAGE = "교환이 예약된 티켓은 내릴 수 없습니다. 먼저 예약(매칭)을 취소해주세요.";

    private final TicketRepository ticketRepository;
    private final PerformanceSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final ExchangeRequestRepository exchangeRequestRepository;
    private final ExchangeMatchRepository matchRepository;
    private final ExchangeTicketLockRepository lockRepository;
    private final SessionTimePolicy sessionTimePolicy;
    private final TransactionTemplate transactionTemplate;
    private final int maxActivePerUser;
    private final int maxRowNumber;
    private final int maxColNumber;

    public TicketService(TicketRepository ticketRepository,
                         PerformanceSessionRepository sessionRepository,
                         UserRepository userRepository,
                         ExchangeRequestRepository exchangeRequestRepository,
                         ExchangeMatchRepository matchRepository,
                         ExchangeTicketLockRepository lockRepository,
                         SessionTimePolicy sessionTimePolicy,
                         PlatformTransactionManager transactionManager,
                         @Value("${ticket.max-active-per-user:20}") int maxActivePerUser,
                         @Value("${ticket.max-row-number:999}") int maxRowNumber,
                         @Value("${ticket.max-col-number:999}") int maxColNumber) {
        this.ticketRepository = ticketRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.exchangeRequestRepository = exchangeRequestRepository;
        this.matchRepository = matchRepository;
        this.lockRepository = lockRepository;
        this.sessionTimePolicy = sessionTimePolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.maxActivePerUser = maxActivePerUser;
        this.maxRowNumber = maxRowNumber;
        this.maxColNumber = maxColNumber;
    }

    public TicketResponse create(Long userId, TicketCreateRequest request) {
        // 구역·열·번 오류는 첫 오류에서 멈추지 않고 모아서 한 번에 돌려준다 (L5).
        Map<String, String> errors = new LinkedHashMap<>();
        String[] zone = normalize(errors, SeatKeyNormalizer.ZONE,
                () -> new String[]{SeatKeyNormalizer.zoneLabel(request.zone()), SeatKeyNormalizer.zoneKey(request.zone())});
        String[] row = normalize(errors, SeatKeyNormalizer.ROW,
                () -> new String[]{SeatKeyNormalizer.rowLabel(request.row()), SeatKeyNormalizer.rowKey(request.row(), maxRowNumber)});
        String[] col = normalize(errors, SeatKeyNormalizer.COL,
                () -> new String[]{SeatKeyNormalizer.colLabel(request.col()), SeatKeyNormalizer.colKey(request.col(), maxColNumber)});
        if (!errors.isEmpty()) {
            throw FieldValidationException.ofAll(errors);
        }
        String zoneLabel = zone[0];
        String zoneKey = zone[1];
        String rowLabel = row[0];
        String rowKey = row[1];
        String colLabel = col[0];
        String colKey = col[1];

        try {
            return transactionTemplate.execute(status -> {
                // 주의: 사용자 행 잠금(FOR UPDATE)이 이 트랜잭션의 첫 쿼리여야 한다. MySQL REPEATABLE READ는
                // 첫 일관 읽기에서 스냅샷이 고정되므로, 잠금 전에 다른 조회가 있으면 대기 후에도 옛 스냅샷으로
                // 중복·상한 검사를 하게 된다(잠금 읽기 자체는 최신 데이터를 읽지만 이후 일반 SELECT는 스냅샷).
                User user = userRepository.findByIdForUpdate(userId)
                        .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
                PerformanceSession session = sessionRepository.findWithPerformanceById(request.sessionId())
                        .orElseThrow(() -> new FieldValidationException("sessionId", SESSION_NOT_FOUND_MESSAGE));
                if (!sessionTimePolicy.now().isBefore(session.registrationDeadline())) {
                    throw new FieldValidationException("sessionId", SESSION_CLOSED_MESSAGE);
                }

                ticketRepository.findActiveBySeat(session.getId(), zoneKey, rowKey, colKey).ifPresent(existing -> {
                    throw duplicateSeat(existing.isOwnedBy(userId));
                });
                if (ticketRepository.countByUser_IdAndStatus(userId, TicketStatus.ACTIVE) >= maxActivePerUser) {
                    throw new BusinessRuleException(LIMIT_REACHED_CODE,
                            "등록할 수 있는 티켓은 최대 " + maxActivePerUser + "개입니다. 사용하지 않는 티켓을 내린 뒤 다시 시도해주세요.");
                }

                Ticket ticket = ticketRepository.saveAndFlush(Ticket.create(user, session,
                        zoneLabel, zoneKey, rowLabel, rowKey, colLabel, colKey));
                return TicketResponse.from(ticket);
            });
        } catch (DataIntegrityViolationException e) {
            if (!DataIntegrityViolations.isViolationOf(e, Ticket.UNIQUE_ACTIVE_SEAT)) {
                throw e;
            }
            log.info("Ticket create race on seat (session={}, zone={}, row={}, col={})",
                    request.sessionId(), zoneKey, rowKey, colKey);
            throw duplicateSeat(false);
        }
    }

    private static String[] normalize(Map<String, String> errors, String field, Supplier<String[]> action) {
        try {
            return action.get();
        } catch (FieldValidationException e) {
            errors.put(field, e.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> listMine(Long userId) {
        return ticketRepository.findActiveByUser(userId).stream().map(TicketResponse::from).toList();
    }

    /**
     * 티켓 내리기(소프트 삭제). 본인 티켓이 아니면 존재 여부를 드러내지 않도록 404.
     * 이미 INACTIVE면 그대로 성공(멱등). 내릴 수 없는 상태(예약 잠금 등) 검사는 ensureCanDeactivate 에 모은다.
     * 티켓 행을 FOR UPDATE 로 잠가(트랜잭션의 첫 쿼리) 같은 티켓의 교환 요청 등록과 직렬화하고,
     * 그 티켓의 교환 요청은 CLOSED 로 바꾸고(설계 1.2: CLOSED = 티켓 내림), 그 티켓이 참여한 CHATTING 매칭은
     * 시스템 취소(canceled_by NULL)한다. 예약 잠금이 걸린 티켓(RESERVED)은 409 TICKET_RESERVED, 교환 완료로 EXCHANGED 가 된 티켓은 409 TICKET_EXCHANGED 로 내릴 수 없다.
     * 잠금 순서는 티켓 -> 요청 -> 매칭이다.
     */
    @Transactional
    public void deactivate(Long userId, Long ticketId) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .filter(t -> t.isOwnedBy(userId))
                .orElseThrow(() -> new NotFoundException(TICKET_NOT_FOUND_MESSAGE));
        deactivateLocked(ticket);
    }

    /**
     * 소유자 검사 없는 내리기 본체. 호출자가 이미 활성 트랜잭션 안에서 이 티켓 행을 FOR UPDATE 로 잠갔어야 한다
     * (사용자 내리기는 deactivate, 관리자 좌석 인증 승인은 SeatClaimService 가 호출). 잠금 순서는 티켓 -> 요청 -> 매칭.
     * 반환 true = 이번 호출로 내렸다, false = 이미 INACTIVE(멱등). EXCHANGED 409 TICKET_EXCHANGED, 예약 잠금 409 TICKET_RESERVED.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean deactivateLocked(Ticket ticket) {
        if (ticket.isExchanged()) {
            // 교환 완료로 닫힌 기존 티켓: 내릴 수 없다(INACTIVE 로 되돌리면 이력과 어긋난다). INACTIVE 는 멱등.
            throw new ConflictException(TICKET_EXCHANGED_MESSAGE, Map.of("code", TICKET_EXCHANGED_CODE));
        }
        if (!ticket.isActive()) {
            return false;
        }
        ensureCanDeactivate(ticket);
        ticket.deactivate();
        LocalDateTime now = sessionTimePolicy.now();
        exchangeRequestRepository.closeByTicketId(ticket.getId(), now);
        matchRepository.cancelChattingByTicketA(ticket.getId(), now);
        matchRepository.cancelChattingByTicketB(ticket.getId(), now);
        return true;
    }

    /** 내릴 수 없는 상태 검사: 예약 잠금이 걸린 티켓은 409. 티켓 행 잠금을 잡은 뒤에 호출한다. */
    private void ensureCanDeactivate(Ticket ticket) {
        if (lockRepository.existsByTicketId(ticket.getId())) {
            throw new ConflictException(TICKET_RESERVED_MESSAGE, Map.of("code", TICKET_RESERVED_CODE));
        }
    }

    private static ConflictException duplicateSeat(boolean mine) {
        return new ConflictException(
                mine ? MY_TICKET_ALREADY_REGISTERED_MESSAGE : SEAT_ALREADY_REGISTERED_MESSAGE,
                Map.of("code", mine ? "MY_TICKET_ALREADY_REGISTERED" : "SEAT_ALREADY_REGISTERED"));
    }
}
