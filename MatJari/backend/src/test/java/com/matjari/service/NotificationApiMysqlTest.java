package com.matjari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matjari.domain.NotificationType;
import com.matjari.dto.request.TicketCreateRequest;
import com.matjari.dto.response.ExchangeMatchResponse;
import com.matjari.dto.response.NotificationResponse;
import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.UnreadCountResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.NotificationRepository;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 조회·읽음·삭제 API 의 실제 MySQL 통합 테스트 (서비스 + 트랜잭션 + 잠금 + 조인 SQL). MATJARI_IT_* 환경변수가 없으면 건너뛴다(RealMysqlSupport).
 * 확인: 조인 결과(role·좌석·닉네임·공연명·개인정보 없음), 닫힌 매칭 알림이 개수·목록에 남음, 읽은 알림 삭제는 읽은 것만(안 읽은 것·남의 것 보존),
 * 인가(남의 알림 404), read-all 이후/도중 새 알림은 안 읽음, 교착 스트레스(propose·reserve·read-all·삭제·TicketService.create 동시 실행, 1213 0건).
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "ticket.max-active-per-user=1000")
class NotificationApiMysqlTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.cleanDatabase();
        registry.add("spring.datasource.url", RealMysqlSupport::url);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private ExchangeMatchService matchService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private long seq;
    private long performanceId;
    private long session1;
    private final Map<Integer, Long> users = new HashMap<>();

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("chat_message", "chat_read_state", "notification", "exchange_ticket_lock", "exchange_match", "exchange_want_seat", "exchange_want_session",
                "exchange_want_range", "exchange_request", "ticket", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        seq = 0;
        users.clear();
        long reg = user("registrant");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', '테스트 공연', 'v', ?)", reg);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        session1 = session(LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0));
    }

    // ------------------------------------------------------------------ 조회

    @Test
    void 목록은_수신자_기준으로_좌석과_닉네임과_공연명을_조인하고_개인정보가_없다() throws Exception {
        Party p = party(1, "Z1");
        Party q = party(2, "Z2");
        mutual(p, q);
        ExchangeMatchResponse m = matchService.propose(p.userId, p.requestId, q.requestId);

        PageResponse<NotificationResponse> forQ = notificationService.list(q.userId, 0, 20, false);
        assertThat(forQ.totalElements()).isEqualTo(1);
        NotificationResponse n = forQ.content().get(0);
        assertThat(n.type()).isEqualTo("PROPOSAL_RECEIVED");
        assertThat(n.matchId()).isEqualTo(m.id());
        assertThat(n.role()).isEqualTo("RECEIVED");
        assertThat(n.read()).isFalse();
        assertThat(n.readAt()).isNull();
        assertThat(n.matchStatus()).isEqualTo("CHATTING");
        assertThat(n.matchClosed()).isFalse();
        assertThat(n.counterpartNickname()).isEqualTo("user1");
        assertThat(n.performanceTitle()).isEqualTo("테스트 공연");
        assertThat(n.mySeat()).isEqualTo(new NotificationResponse.Seat("Z2", "1", "2"));
        assertThat(n.counterpartSeat()).isEqualTo(new NotificationResponse.Seat("Z1", "1", "1"));
        assertThat(n.createdAt()).isNotNull();

        // 제안한 쪽(p)은 알림이 없고, q 가 예약을 제안하면 p 가 RESERVATION_PROPOSED 를 받는다(role 은 SENT/RECEIVED 가 수신자 기준)
        assertThat(notificationService.list(p.userId, 0, 20, false).totalElements()).isZero();
        matchService.reserve(q.userId, m.id());
        PageResponse<NotificationResponse> forP = notificationService.list(p.userId, 0, 20, false);
        assertThat(forP.content()).hasSize(1);
        NotificationResponse reserved = forP.content().get(0);
        assertThat(reserved.type()).isEqualTo("RESERVATION_PROPOSED");
        assertThat(reserved.role()).isEqualTo("SENT");
        assertThat(reserved.counterpartNickname()).isEqualTo("user2");
        assertThat(reserved.mySeat()).isEqualTo(new NotificationResponse.Seat("Z1", "1", "1"));
        assertThat(reserved.counterpartSeat()).isEqualTo(new NotificationResponse.Seat("Z2", "1", "2"));

        // p 가 수락하면 제안자 q 가 MATCH_RESERVED 를 받는다
        matchService.reserveAccept(p.userId, m.id());
        assertThat(notificationService.list(q.userId, 0, 20, false).content()).extracting(NotificationResponse::type)
                .containsExactly("MATCH_RESERVED", "PROPOSAL_RECEIVED");

        String json = objectMapper.writeValueAsString(n);
        System.out.println("[알림 응답 예시] " + json);
        assertThat(json).doesNotContain("@").doesNotContain("email").doesNotContain("password");
        assertThat(json).containsPattern("\"createdAt\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\"");
        assertThat(json).containsPattern("\"readAt\":null");
        System.out.println("[개수 응답 예시] " + objectMapper.writeValueAsString(notificationService.unreadCount(p.userId)));
    }

    @Test
    void 목록은_id_내림차순이고_unreadOnly와_페이지가_동작하며_size는_100으로_자른다() {
        Party r = party(1, "Z1");
        for (int i = 2; i <= 6; i++) {
            Party p = party(i, "Z1");
            mutual(p, r);
            matchService.propose(p.userId, p.requestId, r.requestId);
        }
        PageResponse<NotificationResponse> page0 = notificationService.list(r.userId, 0, 2, false);
        assertThat(page0.totalElements()).isEqualTo(5);
        assertThat(page0.totalPages()).isEqualTo(3);
        assertThat(page0.content()).hasSize(2);
        assertThat(page0.content().get(0).id()).isGreaterThan(page0.content().get(1).id());
        PageResponse<NotificationResponse> page2 = notificationService.list(r.userId, 2, 2, false);
        assertThat(page2.content()).hasSize(1);
        assertThat(page2.content().get(0).id()).isLessThan(page0.content().get(1).id());

        notificationService.markRead(r.userId, page0.content().get(0).id());
        assertThat(notificationService.list(r.userId, 0, 20, true).totalElements()).isEqualTo(4);
        assertThat(notificationService.list(r.userId, 0, 20, false).totalElements()).isEqualTo(5);
        assertThat(notificationService.list(r.userId, 0, 1000, false).size()).isEqualTo(100);

        assertThatThrownBy(() -> notificationService.list(r.userId, -1, 20, false)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> notificationService.list(r.userId, 0, 0, false)).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 닫힌_매칭의_알림도_개수와_목록에_남고_matchClosed만_표시된다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z2");
        mutual(p, q);
        ExchangeMatchResponse m = matchService.propose(p.userId, p.requestId, q.requestId);
        reserveFlow(p.userId, m.id());
        matchService.unreserve(p.userId, m.id());
        matchService.cancel(p.userId, m.id());   // CANCELED

        UnreadCountResponse count = notificationService.unreadCount(q.userId);
        assertThat(count.total()).as("새 제안 + 예약 제안 + 예약 취소 알림이 q 에게(수락 알림은 제안자 p 에게)").isEqualTo(3);
        assertThat(count.byType()).containsEntry("PROPOSAL_RECEIVED", 1L).containsEntry("RESERVATION_PROPOSED", 1L)
                .containsEntry("RESERVATION_CANCELED", 1L).containsEntry("MATCH_RESERVED", 0L);
        assertThat(count.byType().keySet()).containsExactly("PROPOSAL_RECEIVED", "MATCH_RESERVED", "RESERVATION_CANCELED",
                "RESERVATION_PROPOSED", "RESERVATION_REJECTED", "CHAT_MESSAGE");
        List<NotificationResponse> list = notificationService.list(q.userId, 0, 20, true).content();
        assertThat(list).hasSize(3);
        assertThat(list).allSatisfy(n -> {
            assertThat(n.matchStatus()).isEqualTo("CANCELED");
            assertThat(n.matchClosed()).isTrue();
        });
        // 키는 항상 6개. p 는 직접 한 제안·예약 제안·예약 취소의 알림은 받지 않고 q 의 수락 알림 1건만 받는다
        UnreadCountResponse mine = notificationService.unreadCount(p.userId);
        assertThat(mine.total()).isEqualTo(1);
        assertThat(mine.byType()).containsEntry("MATCH_RESERVED", 1L).hasSize(6);
    }

    // ------------------------------------------------------------------ 읽음 / 인가

    @Test
    void 단건_읽음은_멱등이고_남의_것과_없는_것은_404이며_다른_사람_알림은_변하지_않는다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z2");
        mutual(p, q);
        matchService.propose(p.userId, p.requestId, q.requestId);
        long id = jdbc.queryForObject("SELECT id FROM notification WHERE user_id = ?", Long.class, q.userId);

        assertThatThrownBy(() -> notificationService.markRead(p.userId, id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> notificationService.markRead(q.userId, 99999L)).isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id = ?", Object.class, id)).as("남이 눌러도 안 읽음").isNull();

        notificationService.markRead(q.userId, id);
        LocalDateTime first = jdbc.queryForObject("SELECT read_at FROM notification WHERE id = ?", LocalDateTime.class, id);
        assertThat(first).isNotNull();
        notificationService.markRead(q.userId, id);   // 멱등: 예외 없음, 읽은 시각 불변
        assertThat(jdbc.queryForObject("SELECT read_at FROM notification WHERE id = ?", LocalDateTime.class, id)).isEqualTo(first);
        assertThat(notificationService.unreadCount(q.userId).total()).isZero();
        assertThat(notificationService.list(q.userId, 0, 20, false).content().get(0).read()).isTrue();
    }

    @Test
    void 전체_읽음은_type으로_거를_수_있고_이후_들어온_새_알림은_안_읽음이다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z2");
        mutual(p, q);
        ExchangeMatchResponse m = matchService.propose(p.userId, p.requestId, q.requestId);
        reserveFlow(p.userId, m.id());   // q 에게 PROPOSAL_RECEIVED + RESERVATION_PROPOSED (수락 알림은 제안자 p 에게)
        assertThat(notificationService.unreadCount(q.userId).total()).isEqualTo(2);

        assertThatThrownBy(() -> notificationService.markAllRead(q.userId, "NOPE")).isInstanceOf(FieldValidationException.class);
        assertThat(notificationService.markAllRead(q.userId, "RESERVATION_PROPOSED")).isEqualTo(1);
        UnreadCountResponse after = notificationService.unreadCount(q.userId);
        assertThat(after.total()).isEqualTo(1);
        assertThat(after.byType()).containsEntry("PROPOSAL_RECEIVED", 1L).containsEntry("RESERVATION_PROPOSED", 0L);

        assertThat(notificationService.markAllRead(q.userId, null)).isEqualTo(1);
        assertThat(notificationService.unreadCount(q.userId).total()).isZero();
        assertThat(notificationService.markAllRead(q.userId, null)).as("읽을 게 없으면 0").isZero();

        // read-all 뒤에 생긴 새 알림은 안 읽음
        matchService.unreserve(p.userId, m.id());   // q 에게 RESERVATION_CANCELED
        UnreadCountResponse fresh = notificationService.unreadCount(q.userId);
        assertThat(fresh.total()).isEqualTo(1);
        assertThat(fresh.byType()).containsEntry("RESERVATION_CANCELED", 1L);
    }

    @Test
    void 전체_읽음_도중_수집_이후에_들어온_알림은_보존된다() {
        Party r = party(1, "Z1");
        Party p2 = party(2, "Z1");
        Party p3 = party(3, "Z1");
        mutual(p2, r);
        mutual(p3, r);
        matchService.propose(p2.userId, p2.requestId, r.requestId);
        // read-all 의 1단계(잠금 없는 id 수집)가 끝난 직후에 새 알림이 들어온 상황을 만든다
        List<Long> collected = notificationRepository.findUnreadIds(r.userId, null);
        assertThat(collected).hasSize(1);
        matchService.propose(p3.userId, p3.requestId, r.requestId);
        int updated = tx.execute(s -> notificationRepository.markAllRead(collected, r.userId, LocalDateTime.now()));
        assertThat(updated).isEqualTo(1);
        assertThat(notificationService.unreadCount(r.userId).total()).as("수집 이후의 알림은 안 읽음으로 남는다").isEqualTo(1);
    }

    // ------------------------------------------------------------------ 삭제

    @Test
    void 읽은_알림_삭제는_읽은_것만_지우고_안_읽은_것과_남의_것은_보존한다() {
        Party r = party(1, "Z1");
        Party other = party(9, "Z1");
        for (int i = 2; i <= 5; i++) {
            Party p = party(i, "Z1");
            mutual(p, r);
            matchService.propose(p.userId, p.requestId, r.requestId);
        }
        Party x = party(7, "Z1");
        mutual(x, other);
        matchService.propose(x.userId, x.requestId, other.requestId);
        notificationService.markAllRead(other.userId, null);   // 남의 읽은 알림

        List<NotificationResponse> mine = notificationService.list(r.userId, 0, 20, false).content();
        assertThat(mine).hasSize(4);
        notificationService.markRead(r.userId, mine.get(0).id());
        notificationService.markRead(r.userId, mine.get(2).id());

        assertThat(notificationService.deleteRead(r.userId)).isEqualTo(2);
        List<Long> left = notificationService.list(r.userId, 0, 20, false).content().stream().map(NotificationResponse::id).toList();
        assertThat(left).containsExactlyInAnyOrder(mine.get(1).id(), mine.get(3).id());
        assertThat(notificationService.unreadCount(r.userId).total()).as("안 읽은 알림은 그대로").isEqualTo(2);
        assertThat(notificationService.list(other.userId, 0, 20, false).totalElements()).as("남의 읽은 알림은 안 지워진다").isEqualTo(1);
        assertThat(notificationService.deleteRead(r.userId)).as("두 번째 호출은 0").isZero();

        // 닫힌 매칭의 읽은 알림도 삭제 대상이다(사용자가 누른 경우에만 사라진다)
        assertThat(notificationService.deleteRead(other.userId)).isEqualTo(1);
    }

    @Test
    void 대량_알림도_배치로_읽고_지운다() {
        Party r = party(1, "Z1");
        Party p = party(2, "Z1");
        mutual(p, r);
        ExchangeMatchResponse m = matchService.propose(p.userId, p.requestId, r.requestId);
        // 배치 크기(500)를 넘는 읽음/삭제 경로 확인용으로 직접 INSERT (유니크 키는 NULL)
        jdbc.batchUpdate("INSERT INTO notification (user_id, type, match_id, created_at) VALUES (?, 'MATCH_RESERVED', ?, NOW(6))",
                java.util.Collections.nCopies(1203, new Object[]{r.userId, m.id()}));
        assertThat(notificationService.unreadCount(r.userId).total()).isEqualTo(1204);
        assertThat(notificationService.markAllRead(r.userId, null)).isEqualTo(1204);
        assertThat(notificationService.deleteRead(r.userId)).isEqualTo(1204);
        assertThat(notificationService.list(r.userId, 0, 20, false).totalElements()).isZero();
    }

    // ------------------------------------------------------------------ 교착 스트레스

    @Test
    void 교착_스트레스_제안_예약과_읽음_삭제_티켓등록이_동시에_돌아도_1213이_없고_개수가_맞는다() throws Exception {
        final int rounds = 3;
        final int proposersPerRound = 14;
        Party r = party(1, "Z1");
        long ticketCreator = user("creator");
        AtomicInteger reserveCycles = new AtomicInteger();
        AtomicInteger receiverReserveCycles = new AtomicInteger();
        // 정확한 정산을 위해 이 테스트 동안만 INSERT 감사 트리거를 둔다(롤백되면 감사 행도 함께 사라진다). 끝나면 지운다.
        jdbc.execute("DROP TRIGGER IF EXISTS trg_notification_audit_it");
        jdbc.execute("DROP TABLE IF EXISTS notification_audit_it");
        jdbc.execute("CREATE TABLE notification_audit_it (user_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TRIGGER trg_notification_audit_it AFTER INSERT ON notification FOR EACH ROW "
                + "INSERT INTO notification_audit_it (user_id) VALUES (NEW.user_id)");
        try {
        AtomicInteger deletedByReceiver = new AtomicInteger();
        int expectedProposals = 0;
        int nextN = 2;
        for (int round = 0; round < rounds; round++) {
            List<Party> proposers = new ArrayList<>();
            for (int i = 0; i < proposersPerRound; i++) {
                Party p = party(nextN++, "Z1");
                mutual(p, r);
                proposers.add(p);
            }
            List<Callable<Object>> calls = new ArrayList<>();
            for (Party p : proposers) {
                calls.add(() -> {
                    ExchangeMatchResponse m = null;
                    for (int attempt = 0; m == null; attempt++) {
                        try {
                            m = matchService.propose(p.userId, p.requestId, r.requestId);
                        } catch (ConflictException | BusinessRuleException e) {
                            if (attempt > 500) {
                                throw e;
                            }
                            Thread.sleep(3);   // r 의 티켓이 다른 제안자의 예약으로 잠겨 있으면 풀릴 때까지 다시 시도
                        }
                    }
                    final long matchId = m.id();
                    for (int k = 0; k < 3; k++) {
                        try {
                            reserveFlow(p.userId, matchId);
                        } catch (ConflictException | BusinessRuleException e) {
                            continue;   // 다른 매칭이 r 의 티켓을 예약 중이면 409(알림 없음)
                        }
                        reserveCycles.incrementAndGet();
                        matchService.unreserve(p.userId, matchId);
                    }
                    return null;
                });
            }
            calls.add(() -> {   // 수신자: 읽음/삭제/조회를 반복
                for (int k = 0; k < 60; k++) {
                    notificationService.markAllRead(r.userId, null);
                    deletedByReceiver.addAndGet(notificationService.deleteRead(r.userId));
                    notificationService.list(r.userId, 0, 20, false);
                    notificationService.unreadCount(r.userId);
                    notificationService.markAllRead(r.userId, "MATCH_RESERVED");
                    // r 이 직접 예약/예약 취소: MATCH_RESERVED·RESERVATION_CANCELED 수신자가 상대(제안자)가 되는 반대 방향 경로
                    List<Long> mine = jdbc.queryForList("SELECT id FROM exchange_match WHERE user_b_id = ? AND status = 'CHATTING' "
                            + "ORDER BY id DESC LIMIT 1", Long.class, r.userId);
                    if (!mine.isEmpty()) {
                        try {
                            reserveFlow(r.userId, mine.get(0));
                            receiverReserveCycles.incrementAndGet();
                        } catch (ConflictException | BusinessRuleException e) {
                            // 다른 매칭이 먼저 잠갔다
                        }
                        matchService.unreserve(r.userId, mine.get(0));
                    }
                }
                return null;
            });
            for (int t = 0; t < 4; t++) {
                int tt = t + round * 10;
                // 절반은 수신자 r 본인의 티켓 등록: users(r) 행 X 잠금이 propose/reserve 의 users(r) FK S 잠금과 부딪힌다
                long owner = t % 2 == 0 ? r.userId : ticketCreator;
                calls.add(() -> {
                    for (int k = 0; k < 5; k++) {
                        ticketService.create(owner, new TicketCreateRequest(session1, "C" + tt, "1", String.valueOf(k + 1)));
                    }
                    return null;
                });
            }
            List<Object> results = runConcurrently(calls);
            for (Object res : results) {
                if (res instanceof Exception e) {
                    throw new AssertionError("예상 밖 예외: " + e, e);
                }
            }
            expectedProposals += proposersPerRound;
        }
        // 정산: (r 이 지운 것 + 남은 것) == 감사 트리거가 센 r 앞 INSERT 수(성공한 트랜잭션의 알림만)
        long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ?", Long.class, r.userId);
        long audited = jdbc.queryForObject("SELECT COUNT(*) FROM notification_audit_it WHERE user_id = ?", Long.class, r.userId);
        assertThat(deletedByReceiver.get() + remaining).isEqualTo(audited);
        assertThat(audited).isGreaterThanOrEqualTo(expectedProposals);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_audit_it WHERE user_id <> ?", Long.class, r.userId))
                .as("r 이 예약/취소한 만큼 제안자 쪽 알림도 생긴다").isEqualTo(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM notification WHERE user_id <> ?", Long.class, r.userId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE user_id = ?", Long.class, r.userId)).isGreaterThan(1);
        assertThat(reserveCycles.get()).as("예약이 한 번도 성공하지 않으면 스트레스가 약하다").isGreaterThan(0);
        long unread = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ? AND read_at IS NULL", Long.class, r.userId);
        assertThat(notificationService.unreadCount(r.userId).total()).isEqualTo(unread);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE unread_user_id IS NOT NULL AND read_at IS NOT NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_match WHERE status = 'RESERVED'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_ticket_lock", Long.class)).isZero();
        System.out.println("[스트레스] 제안 " + expectedProposals + "건, 예약 사이클 " + reserveCycles.get()
                + "회, 수신자(r) 예약 사이클 " + receiverReserveCycles.get() + "회, 수신자가 지운 알림 " + deletedByReceiver.get() + "건, 남은 알림 " + remaining + "건");
        // 마지막 정리: read-all 후에는 안 읽은 알림이 0
        notificationService.markAllRead(r.userId, null);
        assertThat(notificationService.unreadCount(r.userId).total()).isZero();
        assertThat(NotificationType.values()).hasSize(6);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_notification_audit_it");
            jdbc.execute("DROP TABLE IF EXISTS notification_audit_it");
        }
    }

    // ------------------------------------------------------------------ COMPLETED 매칭

    @Test
    void 교환_완료된_매칭의_알림도_교환_전_자리를_유지하고_matchClosed이다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z2");
        mutual(p, q);
        ExchangeMatchResponse m = matchService.propose(p.userId, p.requestId, q.requestId);
        reserveFlow(p.userId, m.id());
        matchService.complete(p.userId, m.id());
        assertThat(matchService.complete(q.userId, m.id()).status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, p.ticketId)).isEqualTo("EXCHANGED");

        // q 는 제안 + 예약 알림, 둘 다 매칭 행의 자리(교환 전)를 보여준다
        List<NotificationResponse> forQ = notificationService.list(q.userId, 0, 20, false).content();
        assertThat(forQ).hasSize(2);
        assertThat(forQ).allSatisfy(n -> {
            assertThat(n.matchStatus()).isEqualTo("COMPLETED");
            assertThat(n.matchClosed()).isTrue();
            assertThat(n.role()).isEqualTo("RECEIVED");
            assertThat(n.counterpartNickname()).isEqualTo("user1");
            assertThat(n.mySeat()).isEqualTo(new NotificationResponse.Seat("Z2", "1", "2"));
            assertThat(n.counterpartSeat()).isEqualTo(new NotificationResponse.Seat("Z1", "1", "1"));
        });
        assertThat(notificationService.unreadCount(q.userId).total()).as("완료된 매칭의 알림도 개수에 남는다").isEqualTo(2);
        assertThat(notificationService.list(q.userId, 0, 20, true).totalElements()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 도우미

    private List<Object> runConcurrently(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return call.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(120, TimeUnit.SECONDS));
            }
            for (Object r : results) {
                if (r instanceof org.springframework.dao.DataAccessException) {
                    throw new AssertionError("교착/락 대기 초과/DB 예외가 났다(1213 등): " + r);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Party(long userId, long ticketId, long requestId, String zone, String row, String col) {}

    private long user(String nickname) {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, 'x', 'USER')",
                "u" + seq + "@t.com", nickname);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long session(LocalDateTime startsAt) {
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)", startsAt, performanceId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** n 번째 사용자(닉네임 user{n})의 티켓·요청. 열은 "1", 번은 n. */
    private Party party(int n, String zone) {
        long userId = users.computeIfAbsent(n, k -> user("user" + k));
        String col = String.valueOf(n);
        jdbc.update("INSERT INTO ticket (performance_session_id, user_id, zone_label, zone_key, row_label, row_key, "
                + "col_label, col_key, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',NOW(6),NOW(6))",
                session1, userId, zone, zone, "1", "1", col, col);
        long ticketId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_request (ticket_id, status, created_at, updated_at) VALUES (?, 'OPEN', NOW(6), NOW(6))", ticketId);
        long requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,1)", requestId, session1);
        return new Party(userId, ticketId, requestId, zone, "1", col);
    }

    private void wants(Party a, Party b) {
        jdbc.update("INSERT IGNORE INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount) "
                + "VALUES (?,?,?,?,'ANY',NULL)", a.requestId, b.zone, b.row, b.col);
    }

    private void mutual(Party a, Party b) {
        wants(a, b);
        wants(b, a);
    }

    /** 예약 제안 -> 상대 수락으로 RESERVED 를 만든다(V15). */
    private com.matjari.dto.response.ExchangeMatchResponse reserveFlow(long proposerId, long matchId) {
        return com.matjari.testsupport.ReserveFlow.reserve(matchService, jdbc, proposerId, matchId);
    }
}
