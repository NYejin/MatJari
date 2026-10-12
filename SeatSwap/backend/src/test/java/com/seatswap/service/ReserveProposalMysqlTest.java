package com.seatswap.service;

import com.seatswap.domain.NotificationType;
import com.seatswap.dto.response.ChatDtos;
import com.seatswap.dto.response.ExchangeMatchResponse;
import com.seatswap.dto.response.NotificationResponse;
import com.seatswap.exception.ConflictException;
import com.seatswap.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예약 제안/수락/거절/철회(V15)와 채팅 새 메시지 알림의 실제 MySQL 통합 테스트. 환경변수(SEATSWAP_IT_*)가 없으면 건너뛴다.
 * 경쟁 시나리오는 CountDownLatch 로 동시에 출발시켜 여러 라운드 반복하고, 교착·락 대기 초과는 허용하지 않으며 매 라운드 불변식을 확인한다:
 * 잠금 행 수 = 2 x RESERVED 매칭 수, 제안은 CHATTING 에만 존재.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "ticket.max-active-per-user=100000")
class ReserveProposalMysqlTest {

    private static final int ROUNDS = 15;

    /** 알림 푸시 기록: 커밋 뒤(트랜잭션 밖)에 호출되는지, 알림 행이 이미 보이는지 확인한다. */
    static final class RecordingNotificationPublisher implements NotificationPushPublisher {
        final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        final AtomicInteger insideTransaction = new AtomicInteger();
        final AtomicInteger notVisible = new AtomicInteger();
        volatile JdbcTemplate verifier;

        @Override
        public void publishCreated(Long recipientUserId, Long notificationId, NotificationType type, Long matchId) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                insideTransaction.incrementAndGet();
            }
            Integer visible = verifier == null ? 1 : verifier.queryForObject("SELECT COUNT(*) FROM notification WHERE id = ?", Integer.class, notificationId);
            if (visible == null || visible == 0) {
                notVisible.incrementAndGet();
            }
            events.add(recipientUserId + ":" + type + ":" + matchId);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        RecordingNotificationPublisher recordingNotificationPublisher() {
            return new RecordingNotificationPublisher();
        }

        @Bean
        @Primary
        ChatRateLimiter unlimitedRateLimiter(Clock clock) {
            return new ChatRateLimiter(clock) {
                @Override
                public void acquire(Long userId) {
                    // 이 테스트에서는 제한 없음
                }
            };
        }
    }

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
    private ExchangeMatchService matchService;
    @Autowired
    private ExchangeRequestService requestService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private BlockService blockService;
    @Autowired
    private OpenMatchCanceller canceller;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private RecordingNotificationPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private long seq;
    private long zoneSeq;
    private long performanceId;
    private long session1;
    private final Map<Integer, Long> users = new HashMap<>();

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("user_sanction", "abuse_report", "user_block", "exchange_history", "chat_message", "chat_read_state", "notification",
                "exchange_ticket_lock", "exchange_match", "exchange_want_seat", "exchange_want_session", "exchange_want_range",
                "exchange_request", "ticket", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        seq = 0;
        zoneSeq = 0;
        users.clear();
        publisher.events.clear();
        publisher.insideTransaction.set(0);
        publisher.notVisible.set(0);
        publisher.verifier = jdbc;
        long reg = user("registrant");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', '제안 공연', '홀', ?)", reg);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)",
                LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0), performanceId);
        session1 = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    // ------------------------------------------------------------------ 단일 흐름·알림

    @Test
    void 제안_철회_재제안_거절_재제안_수락의_상태_컬럼_알림_푸시() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();

        ExchangeMatchResponse offered = matchService.reserve(p.userId, m);
        assertThat(offered.reservationProposal()).isEqualTo("MINE");
        assertThat(matchService.reserve(p.userId, m).reservationProposal()).as("내 제안 재호출은 멱등").isEqualTo("MINE");
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'RESERVATION_PROPOSED' AND user_id = " + q.userId + " AND dedup_key = 'RESERVATION_PROPOSED:" + m + ":" + q.userId + "'"))
                .as("멱등 재호출은 알림 없음").isEqualTo(1);
        assertThatThrownBy(() -> matchService.reserve(q.userId, m)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_PENDING"));
        assertThat(proposalOf(m)).isEqualTo(p.userId);
        assertThat(locks()).isZero();

        // 철회: 알림 없음, 제안 컬럼은 둘 다 비워진다(CHECK), 멱등
        assertThat(matchService.reserveWithdraw(p.userId, m).reservationProposal()).isEqualTo("NONE");
        assertThat(matchService.reserveWithdraw(p.userId, m).reservationProposal()).isEqualTo("NONE");
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE id = " + m + " AND reserve_proposed_by_id IS NULL AND reserve_proposed_at IS NULL")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE match_id = " + m + " AND type NOT IN ('PROPOSAL_RECEIVED','RESERVATION_PROPOSED')")).isZero();

        // q 가 제안 -> p 가 거절: 제안자 q 에게 REJECTED, CHATTING 유지
        matchService.reserve(q.userId, m);
        assertThat(matchService.reserveDecline(p.userId, m).reservationProposal()).isEqualTo("NONE");
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'RESERVATION_REJECTED' AND user_id = " + q.userId + " AND dedup_key IS NULL")).isEqualTo(1);
        assertThat(matchService.reserveDecline(p.userId, m).status()).as("제안 없으면 거절은 멱등").isEqualTo("CHATTING");
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'RESERVATION_REJECTED'")).isEqualTo(1);

        // p 가 다시 제안 -> q 가 수락: 제안자 p 에게 MATCH_RESERVED, reserved_by = p, 잠금 2행
        matchService.reserve(p.userId, m);
        assertThat(notificationService.unreadCount(q.userId).byType()).containsEntry("RESERVATION_PROPOSED", 1L);   // (수신자, 매칭)당 1행 재사용
        ExchangeMatchResponse accepted = matchService.reserveAccept(q.userId, m);
        assertThat(accepted.status()).isEqualTo("RESERVED");
        assertThat(accepted.reservedBy()).isEqualTo("COUNTERPART");
        assertThat(jdbc.queryForObject("SELECT reserved_by_id FROM exchange_match WHERE id = ?", Long.class, m)).isEqualTo(p.userId);
        assertThat(locks()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'MATCH_RESERVED' AND user_id = " + p.userId + " AND dedup_key IS NULL")).isEqualTo(1);
        assertThat(notificationService.list(p.userId, 0, 20, false).content()).extracting(NotificationResponse::type).contains("MATCH_RESERVED");
        // RESERVED 이후의 늦은 거절·철회는 멱등 200 이고 알림이 없다
        long notifications = count("SELECT COUNT(*) FROM notification");
        assertThat(matchService.reserveDecline(q.userId, m).status()).isEqualTo("RESERVED");
        assertThat(matchService.reserveWithdraw(p.userId, m).status()).isEqualTo("RESERVED");
        assertThat(count("SELECT COUNT(*) FROM notification")).isEqualTo(notifications);

        // 예약 취소 뒤에는 다시 제안부터: RESERVED -> CHATTING, 제안 없음
        ExchangeMatchResponse back = matchService.unreserve(q.userId, m);
        assertThat(back.status()).isEqualTo("CHATTING");
        assertThat(back.reservationProposal()).isEqualTo("NONE");
        assertThat(locks()).isZero();
        assertThatThrownBy(() -> matchService.reserveAccept(p.userId, m)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_NOT_FOUND"));

        // 푸시: afterCommit 에서만, 알림 행이 다른 연결에서 이미 보이는 상태(커밋 뒤)에서
        assertThat(publisher.notVisible.get()).isZero();
        assertThat(publisher.events).contains(q.userId + ":RESERVATION_PROPOSED:" + m, q.userId + ":RESERVATION_REJECTED:" + m,
                p.userId + ":MATCH_RESERVED:" + m, p.userId + ":RESERVATION_CANCELED:" + m);
        invariants();
    }

    @Test
    void 권한과_오류_코드_비참여자_404_내_제안_수락_상대_제안_철회() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party x = party(3, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        matchService.reserve(p.userId, m);

        for (Callable<Object> call : List.<Callable<Object>>of(() -> matchService.reserve(x.userId, m), () -> matchService.reserveAccept(x.userId, m),
                () -> matchService.reserveDecline(x.userId, m), () -> matchService.reserveWithdraw(x.userId, m))) {
            assertThatThrownBy(call::call).isInstanceOf(com.seatswap.exception.NotFoundException.class);
        }
        assertThatThrownBy(() -> matchService.reserveAccept(p.userId, m)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_OWN"));
        assertThatThrownBy(() -> matchService.reserveDecline(p.userId, m)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_OWN"));
        assertThatThrownBy(() -> matchService.reserveWithdraw(q.userId, m)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_NOT_OWN"));
        assertThat(proposalOf(m)).as("오류는 제안을 바꾸지 않는다").isEqualTo(p.userId);

        // 다른 매칭에서 이미 잠긴 티켓: 제안 시점에도 409
        Party r = party(4, "Z1");
        mutual(p, r);
        long m2 = matchService.propose(r.userId, r.requestId, p.requestId).id();
        matchService.reserveAccept(q.userId, m);   // m RESERVED, p 의 티켓 잠김
        assertThatThrownBy(() -> matchService.reserve(r.userId, m2)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_ALREADY_RESERVED"));
        assertThatThrownBy(() -> matchService.reserve(p.userId, m2)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_ALREADY_RESERVED"));
        assertThat(proposalOf(m2)).isNull();
        // 이미 시작된 다른 채팅은 유지된다
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m2)).isEqualTo("CHATTING");
        // RESERVED 매칭에는 제안할 수 없다
        assertThatThrownBy(() -> matchService.reserve(q.userId, m)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT"));
        invariants();
    }

    // ------------------------------------------------------------------ 취소 계열이 제안을 비운다

    @Test
    void 모든_취소_경로가_제안_컬럼을_같은_문장에서_비운다_벌크_JPQL_포함() {
        // cancel / reject
        Party p = party(1, "Z1"), q = party(2, "Z1");
        mutual(p, q);
        long m1 = proposed(p, q, p);
        matchService.cancel(q.userId, m1);
        assertCanceledWithoutProposal(m1);

        long m2 = proposed(p, q, q);
        matchService.reject(q.userId, m2);
        assertCanceledWithoutProposal(m2);

        // 티켓 내리기: a측 티켓(벌크 JPQL cancelChattingByTicketA) / b측 티켓(cancelChattingByTicketB)
        long m3 = proposed(p, q, q);
        ticketService.deactivate(p.userId, p.ticketId);
        assertCanceledWithoutProposal(m3);
        Party a = party(3, "Z2"), b = party(4, "Z2");
        mutual(a, b);
        long m4 = proposed(a, b, a);
        ticketService.deactivate(b.userId, b.ticketId);
        assertCanceledWithoutProposal(m4);

        // 요청 삭제(m.cancel 경로)
        Party c = party(5, "Z3"), d = party(6, "Z3");
        mutual(c, d);
        long m5 = proposed(c, d, d);
        requestService.delete(c.userId, c.requestId);
        assertCanceledWithoutProposal(m5);

        // 차단(OpenMatchCanceller)
        Party e = party(7, "Z4"), f = party(8, "Z4");
        mutual(e, f);
        long m6 = proposed(e, f, e);
        blockService.blockFromMatch(f.userId, m6);
        assertCanceledWithoutProposal(m6);

        // 이용 정지(OpenMatchCanceller.cancelAllOf)
        Party g = party(9, "Z5"), h = party(10, "Z5");
        mutual(g, h);
        long m7 = proposed(g, h, h);
        readCommitted().executeWithoutResult(s -> canceller.cancelAllOf(g.userId));
        assertCanceledWithoutProposal(m7);

        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE reserve_proposed_by_id IS NOT NULL OR reserve_proposed_at IS NOT NULL")).isZero();
        invariants();
    }

    private long proposed(Party a, Party b, Party proposer) {
        long m = matchService.propose(a.userId, a.requestId, b.requestId).id();
        assertThat(matchService.reserve(proposer.userId, m).reservationProposal()).isEqualTo("MINE");
        assertThat(proposalOf(m)).isEqualTo(proposer.userId);
        return m;
    }

    private void assertCanceledWithoutProposal(long m) {
        assertThat(jdbc.queryForMap("SELECT status, reserve_proposed_by_id, reserve_proposed_at FROM exchange_match WHERE id = ?", m))
                .containsEntry("status", "CANCELED").containsEntry("reserve_proposed_by_id", null).containsEntry("reserve_proposed_at", null);
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    void 제안과_상대의_철회_수락_거절_동시에_와도_일관되다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            matchService.reserve(p.userId, m);

            // 제안 vs 철회: p 의 철회와 q 의 새 제안이 겹친다(철회가 먼저면 q 제안 성공, 아니면 PENDING 409)
            List<Object> r1 = runConcurrently(shuffled(() -> matchService.reserveWithdraw(p.userId, m), () -> matchService.reserve(q.userId, m)));
            assertThat(r1).as("round " + round).allSatisfy(x -> assertThat(x instanceof ExchangeMatchResponse || x instanceof ConflictException).isTrue());
            Long proposer = proposalOf(m);
            assertThat(proposer == null || proposer == q.userId).as("p 의 제안은 철회됐다 " + r1).isTrue();
            invariants();

            // 수락 vs 거절 vs 철회: 제안이 남아 있으면 그 제안자 기준으로 수락자·철회자를 정한다
            if (proposer == null) {
                matchService.reserve(p.userId, m);
                proposer = p.userId;
            }
            final long who = proposer;
            final long other = who == p.userId ? q.userId : p.userId;
            List<Object> r2 = runConcurrently(shuffled(() -> matchService.reserveAccept(other, m), () -> matchService.reserveDecline(other, m),
                    () -> matchService.reserveWithdraw(who, m)));
            assertThat(r2).as("round " + round).allSatisfy(x -> assertThat(x instanceof ExchangeMatchResponse || x instanceof ConflictException).isTrue());
            String status = jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m);
            assertThat(locks()).as("round " + round + " " + status + " " + r2).isEqualTo(status.equals("RESERVED") ? 2 : 0);
            assertThat(proposalOf(m)).as("수락·거절·철회 어느 쪽이든 제안은 남지 않는다").isNull();
            long acceptedNotifications = count("SELECT COUNT(*) FROM notification WHERE match_id = " + m + " AND type = 'MATCH_RESERVED'");
            assertThat(acceptedNotifications).as("수락 알림은 RESERVED 가 된 횟수와 같다").isEqualTo(status.equals("RESERVED") ? 1 : 0);
            invariants();
            if (status.equals("RESERVED")) {
                matchService.unreserve(p.userId, m);
            }
            matchService.cancel(p.userId, m);
        }
    }

    @Test
    void 같은_티켓의_다른_매칭_수락이_동시에_와도_하나만_RESERVED이고_교착이_없다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String zone = "Z" + (++zoneSeq);
            Party p = party(1, zone);
            Party q = party(2, zone);
            Party r = party(3, zone);
            wants(p, q, r);
            wants(q, p);
            wants(r, p);
            long m1 = matchService.propose(p.userId, p.requestId, q.requestId).id();
            long m2 = matchService.propose(p.userId, p.requestId, r.requestId).id();
            matchService.reserve(p.userId, m1);
            matchService.reserve(p.userId, m2);

            List<Object> results = runConcurrently(shuffled(() -> matchService.reserveAccept(q.userId, m1), () -> matchService.reserveAccept(r.userId, m2)));

            assertThat(results.stream().filter(x -> x instanceof ExchangeMatchResponse).count()).as("round " + round + " " + results).isEqualTo(1);
            assertThat(results).filteredOn(x -> x instanceof ConflictException).singleElement().satisfies(x ->
                    assertThat(((ConflictException) x).getDetails()).containsEntry("code", "TICKET_ALREADY_RESERVED"));
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE status = 'RESERVED' AND id IN (" + m1 + "," + m2 + ")")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE status = 'CHATTING' AND reserve_proposed_by_id IS NOT NULL AND id IN ("
                    + m1 + "," + m2 + ")")).as("진 쪽의 제안은 그대로 남아 있다").isEqualTo(1);
            invariants();
        }
    }

    @Test
    void 상대_티켓을_공유하는_두_매칭에서_같은_사용자가_동시에_수락해도_하나만_RESERVED() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String zone = "Z" + (++zoneSeq);
            Party p = party(1, zone);
            Party q = party(2, zone);
            Party r = party(3, zone);
            wants(q, p, r);
            wants(p, q);
            wants(r, q);
            long m1 = matchService.propose(p.userId, p.requestId, q.requestId).id();
            long m2 = matchService.propose(r.userId, r.requestId, q.requestId).id();
            matchService.reserve(p.userId, m1);
            matchService.reserve(r.userId, m2);

            List<Object> results = runConcurrently(shuffled(() -> matchService.reserveAccept(q.userId, m1), () -> matchService.reserveAccept(q.userId, m2)));

            assertThat(results.stream().filter(x -> x instanceof ExchangeMatchResponse).count()).as("round " + round + " " + results).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE status = 'RESERVED' AND id IN (" + m1 + "," + m2 + ")")).isEqualTo(1);
            invariants();
        }
    }

    @Test
    void 수락과_취소_차단_정지_티켓내리기_요청삭제가_동시에_와도_잠금과_상태가_어긋나지_않는다() throws Exception {
        List<Disruptor> disruptors = List.of(
                new Disruptor("cancel", (c) -> matchService.cancel(c.p.userId, c.m)),
                new Disruptor("block", (c) -> blockService.blockFromMatch(c.q.userId, c.m)),
                new Disruptor("suspend", (c) -> readCommitted().executeWithoutResult(s -> canceller.cancelAllOf(c.p.userId))),
                new Disruptor("deactivateA", (c) -> ticketService.deactivate(c.p.userId, c.p.ticketId)),
                new Disruptor("deactivateB", (c) -> ticketService.deactivate(c.q.userId, c.q.ticketId)),
                new Disruptor("deleteRequest", (c) -> requestService.delete(c.p.userId, c.p.requestId)));
        for (Disruptor d : disruptors) {
            int accepted = 0;
            for (int round = 0; round < 8; round++) {
                String zone = "Z" + (++zoneSeq);
                Party p = party(1, zone);
                Party q = party(2, zone);
                mutual(p, q);
                long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
                matchService.reserve(p.userId, m);
                Ctx ctx = new Ctx(p, q, m);

                final int finalRound = round;
                List<Object> results = runConcurrently(shuffled(() -> matchService.reserveAccept(q.userId, m), () -> {
                    Thread.sleep((long) (finalRound % 8) * 6);   // 라운드 번호로 정한 고정 지연(0~42ms): 양쪽 승부가 모두 나오되 무작위가 아니다
                    d.action.accept(ctx);
                    return "done";
                }));
                for (Object res : results) {
                    boolean ok = !(res instanceof Exception) || res instanceof ConflictException
                            || res instanceof com.seatswap.exception.BusinessRuleException;
                    assertThat(ok).as(d.name + " round " + round + " " + results).isTrue();
                }
                String status = jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m);
                if (status.equals("RESERVED")) {
                    accepted++;
                    assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, p.ticketId)).isEqualTo("ACTIVE");
                    assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, q.ticketId)).isEqualTo("ACTIVE");
                }
                invariants();
                // 정리: 다음 라운드는 새 구역(새 티켓)을 쓴다. 사용자 상태를 되돌린다.
                jdbc.update("DELETE FROM user_block");
                jdbc.update("DELETE FROM exchange_ticket_lock");
                jdbc.update("UPDATE exchange_match SET status = 'CANCELED', canceled_at = NOW(6), reserved_by_id = NULL, reserved_at = NULL, "
                        + "a_completed_at = NULL, b_completed_at = NULL, reserve_proposed_by_id = NULL, reserve_proposed_at = NULL WHERE status <> 'COMPLETED'");
            }
            System.out.println("[accept-vs-" + d.name + "] RESERVED 로 끝난 라운드=" + accepted + "/8");
        }
    }

    @Test
    void 예약_취소와_재제안_수락_취소가_동시에_와도_교착이_없고_불변식이_유지된다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            matchService.reserve(p.userId, m);
            matchService.reserveAccept(q.userId, m);
            assertThat(locks()).isEqualTo(2);

            List<Object> results = runConcurrently(shuffled(() -> matchService.unreserve(q.userId, m), () -> matchService.reserve(q.userId, m),
                    () -> matchService.reserve(p.userId, m), () -> matchService.reserveAccept(p.userId, m)));

            assertThat(results).as("round " + round).allSatisfy(x -> assertThat(x instanceof ExchangeMatchResponse || x instanceof ConflictException).isTrue());
            String status = jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m);
            assertThat(locks()).as("round " + round + " status=" + status + " " + results).isEqualTo(status.equals("RESERVED") ? 2 : 0);
            invariants();
            // 같은 쌍은 다시 제안부터 진행해 RESERVED 까지 간다
            if (status.equals("RESERVED")) {
                matchService.unreserve(p.userId, m);
            }
            if (proposalOf(m) == null) {
                matchService.reserve(p.userId, m);
            }
            long proposer = proposalOf(m);
            assertThat(matchService.reserveAccept(proposer == p.userId ? q.userId : p.userId, m).status()).isEqualTo("RESERVED");
            assertThat(locks()).isEqualTo(2);
            matchService.unreserve(p.userId, m);
            matchService.cancel(p.userId, m);
        }
    }

    private record Ctx(Party p, Party q, long m) {}

    private record Disruptor(String name, Consumer<Ctx> action) {}

    // ------------------------------------------------------------------ 채팅 새 메시지 알림

    @Test
    void 채팅_알림은_수신자_매칭당_한_행을_재사용하고_읽음_삭제_뒤_재생성되며_푸시는_커밋_뒤다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        String key = "CHAT_MESSAGE:" + m + ":" + q.userId;

        chatService.send(p.userId, m, "안녕하세요", "11111111-aaaa");
        Map<String, Object> row = jdbc.queryForMap("SELECT id, user_id, type, match_id, dedup_key, read_at FROM notification WHERE type = 'CHAT_MESSAGE'");
        assertThat(row).containsEntry("user_id", q.userId).containsEntry("match_id", m).containsEntry("dedup_key", key).containsEntry("read_at", null);
        long firstId = ((Number) row.get("id")).longValue();
        LocalDateTime firstCreated = jdbc.queryForObject("SELECT created_at FROM notification WHERE id = ?", LocalDateTime.class, firstId);

        // 같은 수신자·매칭의 다음 메시지는 같은 행을 되살린다(행 수 1 유지, created_at 갱신)
        chatService.send(p.userId, m, "하나 더", "22222222-aaaa");
        chatService.send(p.userId, m, "둘 더", "33333333-aaaa");
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE id = " + firstId + " AND read_at IS NULL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_at FROM notification WHERE id = ?", LocalDateTime.class, firstId)).isAfterOrEqualTo(firstCreated);
        UnreadCountResponseView counts = new UnreadCountResponseView(notificationService.unreadCount(q.userId));
        assertThat(counts.chat).as("안 읽은 채팅 알림은 매칭당 최대 1건").isEqualTo(1);
        assertThat(counts.total).as("새 제안 알림 1건 + 채팅 알림 1건").isEqualTo(2);
        NotificationResponse listed = notificationService.list(q.userId, 0, 20, true).content().stream()
                .filter(n -> n.type().equals("CHAT_MESSAGE")).findFirst().orElseThrow();
        assertThat(listed.matchId()).isEqualTo(m);
        assertThat(listed.counterpartNickname()).isEqualTo("user1");
        assertThat(listed.read()).isFalse();

        // 반대 방향은 다른 수신자의 별도 행
        chatService.send(q.userId, m, "답장", "44444444-aaaa");
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE dedup_key = 'CHAT_MESSAGE:" + m + ":" + p.userId + "'")).isEqualTo(1);

        // 멱등 재전송(같은 clientMessageId)은 알림을 만들지도 되살리지도 않는다: 먼저 q 가 읽음 포인터를 올려 알림을 읽음 처리
        long last = jdbc.queryForObject("SELECT MAX(id) FROM chat_message WHERE sender_id = ?", Long.class, p.userId);
        chatService.markRead(q.userId, m, last);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE id = " + firstId + " AND read_at IS NOT NULL")).as("읽음 포인터 연동").isEqualTo(1);
        int pushesBefore = publisher.events.size();
        ChatService.SendResult again = chatService.send(p.userId, m, "하나 더", "22222222-aaaa");
        assertThat(again.created()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM notification WHERE id = " + firstId + " AND read_at IS NOT NULL")).as("멱등 재전송은 알림을 되살리지 않는다").isEqualTo(1);
        assertThat(publisher.events.size()).as("멱등 재전송은 푸시도 없다").isEqualTo(pushesBefore);

        // 새 메시지는 읽은 행을 되살린다(같은 id)
        chatService.send(p.userId, m, "새 메시지", "55555555-aaaa");
        assertThat(count("SELECT COUNT(*) FROM notification WHERE id = " + firstId + " AND read_at IS NULL")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE' AND user_id = " + q.userId)).isEqualTo(1);

        // 읽은 알림 삭제 뒤 새 메시지는 새 행을 만든다
        chatService.markRead(q.userId, m, jdbc.queryForObject("SELECT MAX(id) FROM chat_message", Long.class));
        assertThat(notificationService.deleteRead(q.userId)).isGreaterThanOrEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE' AND user_id = " + q.userId)).isZero();
        chatService.send(p.userId, m, "삭제 뒤 메시지", "66666666-aaaa");
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE' AND user_id = " + q.userId + " AND read_at IS NULL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM notification WHERE type = 'CHAT_MESSAGE' AND user_id = ?", Long.class, q.userId)).isGreaterThan(firstId);

        // 푸시: 커밋 뒤에만, 알림 행이 이미 보이는 상태에서. 신호 종류는 CHAT_MESSAGE
        assertThat(publisher.notVisible.get()).isZero();
        assertThat(publisher.events).contains(q.userId + ":CHAT_MESSAGE:" + m, p.userId + ":CHAT_MESSAGE:" + m);
        invariants();
    }

    @Test
    void 닫힌_매칭에서는_새_채팅_알림이_생기지_않지만_기존_알림은_남고_읽음_처리된다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        chatService.send(p.userId, m, "마지막 말", "11111111-bbbb");
        matchService.cancel(p.userId, m);

        assertThatThrownBy(() -> chatService.send(p.userId, m, "안 갑니다", "22222222-bbbb")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> chatService.send(q.userId, m, "안 갑니다", "33333333-bbbb")).isInstanceOf(ConflictException.class);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE'")).as("기존 1건만").isEqualTo(1);
        NotificationResponse n = notificationService.list(q.userId, 0, 20, false).content().stream()
                .filter(x -> x.type().equals("CHAT_MESSAGE")).findFirst().orElseThrow();
        assertThat(n.matchClosed()).isTrue();
        assertThat(n.read()).isFalse();
        // 닫힌 매칭에서도 읽음 포인터를 올리면 알림이 읽음 처리된다
        chatService.markRead(q.userId, m, jdbc.queryForObject("SELECT MAX(id) FROM chat_message", Long.class));
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE' AND read_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void 채팅_전송_읽음_알림삭제_예약_제안이_동시에_와도_교착이_없고_채팅_알림은_수신자_매칭당_하나다() throws Exception {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        AtomicInteger seqNo = new AtomicInteger();
        for (int round = 0; round < 6; round++) {
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final boolean fromP = i % 2 == 0;
                calls.add(() -> {
                    for (int k = 0; k < 5; k++) {
                        chatService.send(fromP ? p.userId : q.userId, m, "메시지", String.format("c%07d-xxxx", seqNo.incrementAndGet()));
                    }
                    return "sent";
                });
            }
            calls.add(() -> {
                for (int k = 0; k < 10; k++) {
                    Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM chat_message", Long.class);
                    chatService.markRead(q.userId, m, max);
                    chatService.markRead(p.userId, m, max);
                    notificationService.deleteRead(q.userId);
                    notificationService.markAllRead(p.userId, null);
                    notificationService.unreadCount(q.userId);
                }
                return "read";
            });
            calls.add(() -> {
                for (int k = 0; k < 5; k++) {
                    try {
                        matchService.reserve(p.userId, m);
                        matchService.reserveWithdraw(p.userId, m);
                    } catch (ConflictException e) {
                        // 상대 제안과 겹치면 409
                    }
                    try {
                        matchService.reserve(q.userId, m);
                        matchService.reserveDecline(p.userId, m);
                    } catch (ConflictException e) {
                        // 겹침
                    }
                }
                return "proposals";
            });
            for (Object res : runConcurrently(calls)) {
                assertThat(res).as("round " + round).isNotInstanceOf(Exception.class);
            }
            assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE'")).isLessThanOrEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM (SELECT dedup_key FROM notification WHERE type = 'CHAT_MESSAGE' GROUP BY dedup_key HAVING COUNT(*) > 1) d")).isZero();
        }
        invariants();
    }

    // ------------------------------------------------------------------ 알림 재사용·정렬·읽음 가드

    @Test
    void 제안_철회_재제안을_반복해도_수신자_알림_행은_1개이고_읽음_삭제_뒤에는_새_행_읽음_뒤_재제안은_다시_안_읽음() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        String key = "RESERVATION_PROPOSED:" + m + ":" + q.userId;
        String sql = "SELECT COUNT(*) FROM notification WHERE type = 'RESERVATION_PROPOSED' AND user_id = " + q.userId;

        for (int i = 0; i < 4; i++) {
            matchService.reserve(p.userId, m);
            matchService.reserveWithdraw(p.userId, m);
        }
        assertThat(count(sql)).as("제안→철회 4회 반복해도 1행").isEqualTo(1);
        assertThat(count(sql + " AND dedup_key = '" + key + "' AND read_at IS NULL")).isEqualTo(1);
        long firstId = jdbc.queryForObject("SELECT id FROM notification WHERE dedup_key = ?", Long.class, key);

        // 읽은 뒤 재제안하면 같은 행이 다시 안 읽음
        notificationService.markRead(q.userId, firstId);
        assertThat(count(sql + " AND read_at IS NOT NULL")).isEqualTo(1);
        matchService.reserve(p.userId, m);
        assertThat(count(sql + " AND read_at IS NULL AND id = " + firstId)).as("읽음 후 재제안은 다시 안 읽음").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'RESERVATION_PROPOSED'")).isEqualTo(1);
        assertThat(publisher.events.stream().filter(e -> e.equals(q.userId + ":RESERVATION_PROPOSED:" + m)).count())
                .as("제안 성공마다 푸시 1건").isEqualTo(5);

        // 읽은 알림 삭제 뒤 재제안은 새 행
        matchService.reserveWithdraw(p.userId, m);
        notificationService.markRead(q.userId, firstId);
        assertThat(notificationService.deleteRead(q.userId)).isGreaterThanOrEqualTo(1);
        assertThat(count(sql)).isZero();
        matchService.reserve(p.userId, m);
        assertThat(count(sql + " AND read_at IS NULL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT id FROM notification WHERE dedup_key = ?", Long.class, key)).isGreaterThan(firstId);

        // 거절 알림은 반복마다 쌓이는 기존 방식(dedup_key NULL)을 유지한다
        matchService.reserveDecline(q.userId, m);
        matchService.reserve(p.userId, m);
        matchService.reserveDecline(q.userId, m);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'RESERVATION_REJECTED' AND dedup_key IS NULL AND user_id = " + p.userId)).isEqualTo(2);
        assertThat(publisher.notVisible.get()).isZero();
        invariants();
    }

    @Test
    void 되살린_알림은_알림_목록과_미리보기_크기_모두에서_최신순_맨_위에_온다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party r = party(3, "Z1");
        mutual(p, q);
        mutual(r, q);
        long m1 = matchService.propose(p.userId, p.requestId, q.requestId).id();
        chatService.send(p.userId, m1, "안녕하세요", "11111111-ssss");     // q 의 채팅 알림 = 가장 작은 id 쪽
        long m2 = matchService.propose(r.userId, r.requestId, q.requestId).id();   // id 가 더 큰 새 제안 알림
        matchService.reserve(r.userId, m2);                                         // id 가 더 큰 예약 제안 알림
        long chatId = jdbc.queryForObject("SELECT id FROM notification WHERE type = 'CHAT_MESSAGE' AND user_id = ?", Long.class, q.userId);
        assertThat(chatId).isLessThan(jdbc.queryForObject("SELECT MAX(id) FROM notification WHERE user_id = ?", Long.class, q.userId));

        // 시각을 전부 과거로 고정한 뒤 채팅 알림을 되살린다
        jdbc.update("UPDATE notification SET created_at = '2020-01-01 00:00:00.000000' WHERE user_id = ?", q.userId);
        chatService.send(p.userId, m1, "다시 메시지", "22222222-ssss");

        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE' AND user_id = " + q.userId)).isEqualTo(1);
        for (int size : new int[] {1, 2, 5, 20}) {
            assertThat(notificationService.list(q.userId, 0, size, false).content().get(0).id())
                    .as("size=" + size + " 의 첫 알림은 되살린 채팅 알림").isEqualTo(chatId);
            assertThat(notificationService.list(q.userId, 0, size, true).content().get(0).id()).isEqualTo(chatId);
        }
        // 같은 created_at 이면 id 내림차순
        List<Long> ids = notificationService.list(q.userId, 0, 20, false).content().stream().map(NotificationResponse::id).toList();
        assertThat(ids.get(0)).isEqualTo(chatId);
        assertThat(ids.subList(1, ids.size())).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        // 두 번째 페이지도 이어서 정렬된다(중복·누락 없음)
        assertThat(notificationService.list(q.userId, 1, 1, false).content().get(0).id()).isEqualTo(ids.get(1));
    }

    @Test
    void 읽음_포인터_뒤에_상대_메시지가_남아_있으면_채팅_알림은_읽음_처리되지_않는다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        long first = chatService.send(p.userId, m, "하나", "11111111-gggg").message().id();
        chatService.markRead(q.userId, m, first);
        String chat = "SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE' AND user_id = " + q.userId;
        assertThat(count(chat + " AND read_at IS NOT NULL")).isEqualTo(1);

        long second = chatService.send(p.userId, m, "둘", "22222222-gggg").message().id();   // 되살아남(안 읽음)
        assertThat(count(chat + " AND read_at IS NULL")).isEqualTo(1);

        // 늦게 도착한 옛 읽음 처리(포인터는 first 인 상태)는 새 메시지 알림을 읽음으로 만들지 않는다
        notificationService.markChatRead(q.userId, m);
        assertThat(count(chat + " AND read_at IS NULL")).as("가드: 포인터 뒤 상대 메시지가 있다").isEqualTo(1);

        // 내가 보낸 메시지는 가드에 걸리지 않는다: 포인터가 상대 마지막 메시지까지 오면 읽음 처리
        chatService.send(q.userId, m, "답장", "33333333-gggg");
        chatService.markRead(q.userId, m, jdbc.queryForObject("SELECT MAX(id) FROM chat_message", Long.class));
        assertThat(count(chat + " AND read_at IS NOT NULL")).isEqualTo(1);
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void 읽음_처리와_새_메시지_revive가_겹쳐도_포인터_뒤_메시지가_있으면_알림은_안_읽음으로_남는다() throws Exception {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        AtomicInteger seqNo = new AtomicInteger();
        for (int round = 0; round < 25; round++) {
            chatService.send(p.userId, m, "먼저", String.format("r%07d-aaaa", seqNo.incrementAndGet()));
            List<Callable<Object>> calls = List.of(
                    () -> {
                        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM chat_message", Long.class);
                        chatService.markRead(q.userId, m, max);
                        return "read";
                    },
                    () -> chatService.send(p.userId, m, "겹치는 새 메시지", String.format("r%07d-bbbb", seqNo.incrementAndGet())));
            for (Object res : runConcurrently(calls)) {
                assertThat(res).as("round " + round).isNotInstanceOf(Exception.class);
            }
            long pointer = count("SELECT COALESCE(MAX(last_read_message_id), 0) FROM chat_read_state WHERE user_id = " + q.userId + " AND match_id = " + m);
            long lastFromP = count("SELECT MAX(id) FROM chat_message WHERE sender_id = " + p.userId + " AND match_id = " + m);
            boolean unread = count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE' AND user_id = " + q.userId + " AND read_at IS NULL") == 1;
            assertThat(unread || pointer >= lastFromP).as("round " + round + ": 포인터 뒤 상대 메시지가 있으면 알림은 안 읽음이어야 한다 (pointer=" + pointer
                    + ", lastFromP=" + lastFromP + ")").isTrue();
        }
        invariants();
    }

    // ------------------------------------------------------------------ 도우미

    /** 운영 AdminModerationService.resolve 와 같은 READ_COMMITTED 로 OpenMatchCanceller 를 호출한다. */
    private TransactionTemplate readCommitted() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        return t;
    }

    private record UnreadCountResponseView(long total, long chat) {
        UnreadCountResponseView(com.seatswap.dto.response.UnreadCountResponse r) {
            this(r.total(), r.byType().getOrDefault("CHAT_MESSAGE", 0L));
        }
    }

    @SafeVarargs
    private static List<Callable<Object>> shuffled(Callable<Object>... calls) {
        List<Callable<Object>> list = new ArrayList<>(List.of(calls));
        java.util.Collections.shuffle(list);
        return list;
    }

    private Long proposalOf(long matchId) {
        List<Long> v = jdbc.queryForList("SELECT reserve_proposed_by_id FROM exchange_match WHERE id = ?", Long.class, matchId);
        return v.isEmpty() ? null : v.get(0);
    }

    /** 잠금 행 수 = 2 x RESERVED 매칭 수, 제안은 CHATTING 에만, RESERVED 가 아닌 매칭엔 잠금 없음, 잠긴 티켓은 ACTIVE. */
    private void invariants() {
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock")).as("잠금 행 수 = 2 x RESERVED").isEqualTo(2 * count("SELECT COUNT(*) FROM exchange_match WHERE status = 'RESERVED'"));
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN exchange_match m ON m.id = l.match_id WHERE m.status <> 'RESERVED'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match m WHERE m.status = 'RESERVED' AND (SELECT COUNT(*) FROM exchange_ticket_lock l WHERE l.match_id = m.id) <> 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE status <> 'CHATTING' AND (reserve_proposed_by_id IS NOT NULL OR reserve_proposed_at IS NOT NULL)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN ticket t ON t.id = l.ticket_id WHERE t.status <> 'ACTIVE'")).isZero();
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private long locks() {
        return count("SELECT COUNT(*) FROM exchange_ticket_lock");
    }

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
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            for (Object r : results) {
                if (r instanceof org.springframework.dao.DataAccessException) {
                    throw new AssertionError("교착/락 대기 초과/DB 예외가 났다: " + r);
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
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, 'x', 'USER')", "u" + seq + "@t.com", nickname);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

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

    private void wants(Party a, Party... others) {
        for (Party o : others) {
            jdbc.update("INSERT IGNORE INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount) "
                    + "VALUES (?,?,?,?,'ANY',NULL)", a.requestId, o.zone, o.row, o.col);
        }
    }

    private void mutual(Party a, Party b) {
        wants(a, b);
        wants(b, a);
    }
}
