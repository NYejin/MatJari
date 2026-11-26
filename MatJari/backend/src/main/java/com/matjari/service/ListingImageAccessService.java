package com.matjari.service;

import com.matjari.domain.ListingImage;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ExchangeCandidateRepository;
import com.matjari.repository.ExchangeRequestRepository;
import com.matjari.repository.ListingImageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 티켓·교환 희망 조건 사진 열람 권한 판정과 읽기 (V33). 읽기 전용이고 행을 잠그지 않는다.
 * <p>볼 수 있는 사람(하나라도 해당하면):
 * <ol>
 *   <li>소유자(올린 사람)</li>
 *   <li>관리자</li>
 *   <li>그 사진의 티켓(요청 사진이면 요청과 그 요청의 티켓)과 엮인 매칭의 참여자 — 매칭 상태와 무관하다(CANCELED·COMPLETED 뒤에도 대화 상대는 열람 가능)</li>
 *   <li><b>지금 후보 관계</b>: 조회자의 열린 교환 요청 중 하나와 사진 주인의 요청(티켓 사진이면 그 티켓의 열린 요청)이 후보 판정을 만족한다.
 *       후보 목록과 같은 조인·판정을 쓰므로 차단·이용 정지·예약 잠금 제외가 그대로 적용된다({@link ExchangeCandidateRepository#isVisibleCandidate}).</li>
 * </ol>
 * 그 밖은 사진이 있는지 없는지 알 수 없게 404다(남의 사진과 없는 사진이 같다). 권한을 통과한 뒤에 정리된 사진(deleted_at 또는 파일 없음)이면 410 IMAGE_EXPIRED.
 * 이미지 바이트·경로는 로그에 남기지 않는다.
 */
@Slf4j
@Service
public class ListingImageAccessService {

    static final String NOT_FOUND_MESSAGE = "사진을 찾을 수 없습니다.";

    private final ListingImageRepository imageRepository;
    private final ListingImageStore store;
    private final ExchangeRequestRepository requestRepository;
    private final ExchangeCandidateRepository candidateRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ListingImageAccessService(ListingImageRepository imageRepository, ListingImageStore store,
                                     ExchangeRequestRepository requestRepository,
                                     ExchangeCandidateRepository candidateRepository, JdbcTemplate jdbc, Clock clock) {
        this.imageRepository = imageRepository;
        this.store = store;
        this.requestRepository = requestRepository;
        this.candidateRepository = candidateRepository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ChatImageStore.ImageData view(Long viewerId, boolean admin, Long imageId) {
        ListingImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
        if (!canView(viewerId, admin, image)) {
            throw new NotFoundException(NOT_FOUND_MESSAGE);
        }
        if (image.isFileDeleted()) {
            throw new ChatImageStore.ImageExpiredException();
        }
        return store.load(image.getImageKey());
    }

    boolean canView(Long viewerId, boolean admin, ListingImage image) {
        if (viewerId.equals(image.getOwnerId()) || admin) {
            return true;
        }
        Long requestId = image.getRequestId();
        Long ticketId = image.getTicketId();
        if (requestId != null) {
            // 요청 사진: 요청이 걸린 티켓을 알아 두면 티켓 단위 매칭 관계도 본다
            Optional<Long> ticketOfRequest = requestRepository.findTicketIdById(requestId);
            if (ticketOfRequest.isEmpty()) {
                return false;
            }
            ticketId = ticketOfRequest.get();
        }
        if (isMatchParticipant(viewerId, ticketId, requestId)) {
            return true;
        }
        return isCurrentCandidate(viewerId, ticketId, requestId);
    }

    /** 조회자가 이 티켓(또는 요청)이 들어간 매칭의 참여자인가. 상태는 보지 않는다. */
    private boolean isMatchParticipant(Long viewerId, Long ticketId, Long requestId) {
        String sql = "SELECT EXISTS (SELECT 1 FROM exchange_match m WHERE m.ticket_a_id = ? AND (m.user_a_id = ? OR m.user_b_id = ?))"
                + " OR EXISTS (SELECT 1 FROM exchange_match m WHERE m.ticket_b_id = ? AND (m.user_a_id = ? OR m.user_b_id = ?))"
                + (requestId == null ? ""
                : " OR EXISTS (SELECT 1 FROM exchange_match m WHERE m.request_a_id = ? AND (m.user_a_id = ? OR m.user_b_id = ?))"
                + " OR EXISTS (SELECT 1 FROM exchange_match m WHERE m.request_b_id = ? AND (m.user_a_id = ? OR m.user_b_id = ?))");
        Object[] args = requestId == null
                ? new Object[]{ticketId, viewerId, viewerId, ticketId, viewerId, viewerId}
                : new Object[]{ticketId, viewerId, viewerId, ticketId, viewerId, viewerId,
                        requestId, viewerId, viewerId, requestId, viewerId, viewerId};
        Boolean found = jdbc.queryForObject(sql, Boolean.class, args);
        return Boolean.TRUE.equals(found);
    }

    /** 조회자의 열린 요청과 사진 주인의 열린 요청이 지금 후보 관계인가. */
    private boolean isCurrentCandidate(Long viewerId, Long ticketId, Long requestId) {
        List<Long> mine = jdbc.queryForList(
                "SELECT r.id FROM exchange_request r JOIN ticket t ON t.id = r.ticket_id"
                        + " WHERE t.user_id = ? AND t.status = 'ACTIVE' AND r.status = 'OPEN' ORDER BY r.id",
                Long.class, viewerId);
        if (mine.isEmpty()) {
            return false;
        }
        List<Long> targets = requestId != null ? List.of(requestId)
                : jdbc.queryForList("SELECT id FROM exchange_request WHERE ticket_id = ? AND status = 'OPEN'", Long.class, ticketId);
        LocalDateTime todayStart = LocalDateTime.now(clock).toLocalDate().atStartOfDay();
        for (Long target : targets) {
            for (Long myRequestId : mine) {
                if (candidateRepository.isVisibleCandidate(myRequestId, target, todayStart)) {
                    return true;
                }
            }
        }
        return false;
    }
}
