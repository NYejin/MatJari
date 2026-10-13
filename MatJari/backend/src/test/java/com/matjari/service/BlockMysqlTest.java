package com.matjari.service;

import com.matjari.dto.response.BlockDtos.BlockResult;
import com.matjari.dto.response.ExchangeCandidateResponse;
import com.matjari.dto.response.ExchangeMatchResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.NotFoundException;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 사용자 차단의 실제 MySQL 통합 테스트(시스템 취소 구현체·차단 서비스·후보 제외·동시성). MATJARI_IT_* 환경변수가 있을 때만 실행한다:
 *   MATJARI_IT_JDBC_URL=jdbc:mysql://localhost:PORT/matjari_blockbe_it  MATJARI_IT_USER=root  MATJARI_IT_PASSWORD=tmp
 * 경쟁 시나리오는 CountDownLatch 로 동시에 출발시키고 여러 라운드 반복한다. 교착·락 대기 초과는 허용하지 않는다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "ticket.max-active-per-user=1000")
class BlockMysqlTest {

    private static final int ROUNDS = 20;

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
    private ExchangeCandidateService candidateService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private BlockService blockService;
    @Autowired
    private OpenMatchCanceller canceller;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private long seq;
    private long adminId;
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
        users.clear();
        adminId = user("registrant");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', 't', 'v', ?)", adminId);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)",
                LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0), performanceId);
        session1 = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    // ------------------------------------------------------------------ 취소 범위

    @Test
    void 차단하면_둘_사이의_CHATTING_RESERVED만_시스템_취소되고_잠금이_풀리며_다른_쌍은_그대로다() {
        Party p1 = party(1, "Z1"), p2 = party(1, "Z2");
        Party q1 = party(2, "Z1"), q2 = party(2, "Z2");
        Party r = party(3, "Z1");
        Party s = party(4, "Z3"), t = party(5, "Z3");
        mutual(p1, q1);
        mutual(p2, q2);
        mutual(p1, r);
        mutual(s, t);
        long m1 = matchService.propose(p1.userId, p1.requestId, q1.requestId).id();           // CHATTING
        long m2 = matchService.propose(q2.userId, q2.requestId, p2.requestId).id();           // RESERVED + 한쪽 수락
        reserveFlow(p2.userId, m2);
        matchService.complete(p2.userId, m2);
        long m3 = matchService.propose(r.userId, r.requestId, p1.requestId).id();             // 다른 쌍, CHATTING
        long m4 = matchService.propose(s.userId, s.requestId, t.requestId).id();              // 다른 쌍, RESERVED
        reserveFlow(s.userId, m4);
        assertThat(locks()).isEqualTo(4);

        BlockResult result = blockService.blockFromMatch(q1.userId, m1);

        assertThat(result.alreadyBlocked()).isFalse();
        assertThat(result.canceledMatches()).isEqualTo(2);
        assertThat(result.blockedNickname()).isEqualTo("user1");
        for (long m : new long[]{m1, m2}) {
            assertThat(jdbc.queryForMap("SELECT status, canceled_by_id, canceled_at, reserved_by_id, reserved_at, a_completed_at, b_completed_at "
                    + "FROM exchange_match WHERE id = ?", m))
                    .containsEntry("status", "CANCELED").containsEntry("canceled_by_id", null).containsEntry("reserved_by_id", null)
                    .containsEntry("reserved_at", null).containsEntry("a_completed_at", null).containsEntry("b_completed_at", null)
                    .doesNotContainEntry("canceled_at", null);
        }
        assertThat(status(m3)).isEqualTo("CHATTING");
        assertThat(status(m4)).isEqualTo("RESERVED");
        assertThat(locks()).as("m4 의 잠금만 남는다").isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM exchange_request WHERE status <> 'OPEN'")).as("요청은 건드리지 않는다").isZero();
        assertThat(count("SELECT COUNT(*) FROM user_block WHERE blocker_id = " + q1.userId + " AND blocked_id = " + p1.userId)).isEqualTo(1);
        assertInvariants();

        // 멱등: 같은 요청을 다시 보내도 같은 차단 행, 취소 0건
        BlockResult again = blockService.blockFromMatch(q1.userId, m1);
        assertThat(again.alreadyBlocked()).isTrue();
        assertThat(again.blockId()).isEqualTo(result.blockId());
        assertThat(again.canceledMatches()).isZero();
        assertThat(count("SELECT COUNT(*) FROM user_block")).isEqualTo(1);
    }

    @Test
    void COMPLETED_매칭과_교환_이력은_차단해도_그대로다() {
        Party w = party(1, "Z1");
        Party x = party(2, "Z1");
        mutual(w, x);
        long m = matchService.propose(w.userId, w.requestId, x.requestId).id();
        reserveFlow(w.userId, m);
        matchService.complete(w.userId, m);
        ExchangeMatchResponse done = matchService.complete(x.userId, m);
        assertThat(done.status()).isEqualTo("COMPLETED");
        long history = count("SELECT COUNT(*) FROM exchange_history");

        BlockResult result = blockService.blockFromMatch(w.userId, m);   // 닫힌 매칭 화면에서도 차단 가능

        assertThat(result.canceledMatches()).isZero();
        assertThat(status(m)).isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM exchange_history")).isEqualTo(history).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM ticket WHERE status = 'EXCHANGED'")).isEqualTo(2);
    }

    @Test
    void cancelAllOf는_한_사용자의_모든_열린_매칭을_취소하고_트랜잭션_밖_호출은_거부된다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party r = party(3, "Z1");
        Party s = party(4, "Z1"), t = party(5, "Z1");
        mutual(p, q);
        mutual(p, r);
        mutual(s, t);
        long m1 = matchService.propose(p.userId, p.requestId, q.requestId).id();
        long m2 = matchService.propose(r.userId, r.requestId, p.requestId).id();
        reserveFlow(r.userId, m2);
        long m3 = matchService.propose(s.userId, s.requestId, t.requestId).id();

        assertThatThrownBy(() -> canceller.cancelAllOf(p.userId)).isInstanceOf(IllegalTransactionStateException.class);
        Integer canceled = new TransactionTemplate(transactionManager).execute(st -> canceller.cancelAllOf(p.userId));

        assertThat(canceled).isEqualTo(2);
        assertThat(status(m1)).isEqualTo("CANCELED");
        assertThat(status(m2)).isEqualTo("CANCELED");
        assertThat(status(m3)).isEqualTo("CHATTING");
        assertThat(locks()).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE status = 'CANCELED' AND canceled_by_id IS NULL")).isEqualTo(2);
        assertInvariants();
    }

    // ------------------------------------------------------------------ 후보 제외·제안

    @Test
    void 차단은_양방향으로_후보에서_빼고_제안을_NOT_A_CANDIDATE로_막으며_해제하면_복귀한다() {
        for (boolean blockerIsFirst : new boolean[]{true, false}) {
            reset();
            Party p = party(1, "Z1");
            Party q = party(2, "Z1");
            Party other = party(3, "Z1");
            mutual(p, q);
            mutual(p, other);
            assertThat(candidates(p)).containsExactlyInAnyOrder(q.requestId, other.requestId);
            assertThat(candidates(q)).containsExactly(p.requestId);

            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            Party blocker = blockerIsFirst ? p : q;
            long blockId = blockService.blockFromMatch(blocker.userId, m).blockId();

            assertThat(candidates(p)).as("차단 쌍은 서로 후보에서 사라진다").containsExactly(other.requestId);
            assertThat(candidates(q)).isEmpty();
            assertThat(ExchangeCandidateServiceCount(p)).isEqualTo(1);
            assertNotACandidate(p, q);
            assertNotACandidate(q, p);

            blockService.unblock(blocker.userId, blockId);
            assertThat(candidates(p)).containsExactlyInAnyOrder(q.requestId, other.requestId);
            assertThat(status(m)).as("해제해도 취소된 매칭은 되살아나지 않는다").isEqualTo("CANCELED");
            assertThat(matchService.propose(q.userId, q.requestId, p.requestId).status()).as("해제 후 다시 제안 가능").isEqualTo("CHATTING");
        }
    }

    @Test
    void 활성_제재_사용자의_티켓은_후보와_제안에서_빠지고_해제_만료_뒤에는_돌아온다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        LocalDateTime now = LocalDateTime.now();
        sanction(q.userId, now.minusDays(1), null, null);   // 무기한
        assertThat(candidates(p)).isEmpty();
        assertNotACandidate(p, q);

        jdbc.update("UPDATE user_sanction SET revoked_at = NOW(6), revoked_by_id = ?", adminId);   // 해제
        assertThat(candidates(p)).containsExactly(q.requestId);

        jdbc.update("DELETE FROM user_sanction");
        sanction(q.userId, now.minusDays(10), now.minusDays(3), null);                              // 만료
        assertThat(candidates(p)).containsExactly(q.requestId);
        sanction(q.userId, now.minusDays(1), now.plusDays(6), null);                                // 7일 정지 중
        assertThat(candidates(p)).isEmpty();
        assertThat(matchService.propose(q.userId, q.requestId, p.requestId)).as("정지 강제는 필터 몫 - 서비스 단독 호출은 상대가 후보인지만 본다").isNotNull();
    }

    // ------------------------------------------------------------------ 상한·권한

    @Test
    void 차단은_사용자당_200명까지이고_이미_차단한_상대는_상한에서도_멱등이다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party r = party(3, "Z1");
        mutual(p, q);
        mutual(p, r);
        long mq = matchService.propose(p.userId, p.requestId, q.requestId).id();
        long mr = matchService.propose(p.userId, p.requestId, r.requestId).id();
        BlockResult first = blockService.blockFromMatch(p.userId, mq);   // 1명 차단
        for (int i = 0; i < 198; i++) {                                    // 합쳐서 199
            long dummy = user("dummy" + i);
            jdbc.update("INSERT INTO user_block (blocker_id, blocked_id, created_at) VALUES (?, ?, NOW(6))", p.userId, dummy);
        }
        assertThat(count("SELECT COUNT(*) FROM user_block WHERE blocker_id = " + p.userId)).isEqualTo(199);

        // 199명 -> 200번째는 허용
        assertThat(blockService.blockFromMatch(p.userId, mr).alreadyBlocked()).isFalse();
        // 201번째는 422 (새 매칭을 만들 수 없으니 기존 상대를 풀고 다시 제안해 새 상대로 시도하는 대신 더미 행으로 상한을 채워 확인)
        Party u = party(6, "Z1");
        mutual(p, u);
        long mu = matchService.propose(u.userId, u.requestId, p.requestId).id();
        assertThatThrownBy(() -> blockService.blockFromMatch(p.userId, mu))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("BLOCK_LIMIT_REACHED"));
        assertThat(status(mu)).as("상한 실패는 롤백").isEqualTo("CHATTING");
        assertThat(blockService.blockFromMatch(p.userId, mq).blockId()).as("이미 차단한 상대는 상한과 무관하게 멱등").isEqualTo(first.blockId());
    }

    @Test
    void 비참여자는_404이고_남의_차단은_해제할_수_없으며_목록은_내_것만_보인다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party x = party(3, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        assertThatThrownBy(() -> blockService.blockFromMatch(x.userId, m)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> blockService.blockFromMatch(p.userId, 9999L)).isInstanceOf(NotFoundException.class);
        assertThat(count("SELECT COUNT(*) FROM user_block")).isZero();
        assertThat(status(m)).isEqualTo("CHATTING");

        BlockResult b = blockService.blockFromMatch(p.userId, m);
        assertThatThrownBy(() -> blockService.unblock(x.userId, b.blockId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> blockService.unblock(q.userId, b.blockId())).as("차단당한 쪽도 해제할 수 없다").isInstanceOf(NotFoundException.class);
        assertThat(blockService.listMine(q.userId, 0, 20).content()).isEmpty();
        var mine = blockService.listMine(p.userId, 0, 20);
        assertThat(mine.totalElements()).isEqualTo(1);
        assertThat(mine.content().get(0).blockedNickname()).isEqualTo("user2");
        blockService.unblock(p.userId, b.blockId());
        assertThatThrownBy(() -> blockService.unblock(p.userId, b.blockId())).as("멱등 아님").isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    void 차단과_동시_예약_예약취소_티켓내리기_재제안_맞차단이_경합해도_교착이_없고_열린_매칭이_남지_않는다() throws Exception {
        int base = 100;
        for (int round = 0; round < ROUNDS; round++) {
            Party a = party(base + round * 3, "Z1");
            Party b = party(base + round * 3 + 1, "Z1");
            Party c = party(base + round * 3 + 2, "Z1");
            mutual(a, b);
            mutual(a, c);
            long m = matchService.propose(a.userId, a.requestId, b.requestId).id();
            if (round % 2 == 0) {
                reserveFlow(a.userId, m);
            }
            runConcurrently(List.<Callable<Object>>of(
                    () -> blockService.blockFromMatch(a.userId, m),
                    () -> blockService.blockFromMatch(b.userId, m),
                    () -> reserveFlow(b.userId, m),
                    () -> matchService.unreserve(a.userId, m),
                    () -> {
                        ticketService.deactivate(b.userId, b.ticketId);
                        return null;
                    },
                    () -> matchService.propose(a.userId, a.requestId, b.requestId),
                    () -> matchService.propose(c.userId, c.requestId, a.requestId)));

            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE status IN ('CHATTING','RESERVED') AND "
                    + "((user_a_id = " + a.userId + " AND user_b_id = " + b.userId + ") OR (user_a_id = " + b.userId + " AND user_b_id = " + a.userId + "))"))
                    .as("round " + round + ": 차단 뒤 둘 사이에 열린 매칭이 남았다").isZero();
            assertThat(count("SELECT COUNT(*) FROM user_block WHERE (blocker_id = " + a.userId + " AND blocked_id = " + b.userId
                    + ") OR (blocker_id = " + b.userId + " AND blocked_id = " + a.userId + ")")).isEqualTo(2);
            assertInvariants();
        }
    }

    @Test
    void 이용정지_취소_경로도_동시_제안_예약과_교착없이_직렬화된다() throws Exception {
        int base = 500;
        for (int round = 0; round < ROUNDS; round++) {
            Party a = party(base + round * 3, "Z1");
            Party b = party(base + round * 3 + 1, "Z1");
            Party c = party(base + round * 3 + 2, "Z1");
            mutual(a, b);
            mutual(a, c);
            long m = matchService.propose(b.userId, b.requestId, a.requestId).id();
            if (round % 2 == 0) {
                reserveFlow(b.userId, m);
            }
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            runConcurrently(List.<Callable<Object>>of(
                    () -> tx.execute(st -> canceller.cancelAllOf(a.userId)),
                    () -> reserveFlow(a.userId, m),
                    () -> matchService.unreserve(b.userId, m),
                    () -> matchService.propose(c.userId, c.requestId, a.requestId),
                    () -> matchService.propose(a.userId, a.requestId, c.requestId)));
            assertInvariants();
        }
    }

    // ------------------------------------------------------------------ 도우미

    private void assertNotACandidate(Party from, Party to) {
        assertThatThrownBy(() -> matchService.propose(from.userId, from.requestId, to.requestId))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_A_CANDIDATE"));
    }

    private List<Long> candidates(Party of) {
        return candidateService.findCandidates(of.userId, of.requestId, 0, 50).content().stream()
                .map(ExchangeCandidateResponse::requestId).toList();
    }

    private long ExchangeCandidateServiceCount(Party of) {
        return candidateService.findCandidates(of.userId, of.requestId, 0, 50).totalElements();
    }

    private void sanction(long userId, LocalDateTime starts, LocalDateTime ends, LocalDateTime revoked) {
        jdbc.update("INSERT INTO user_sanction (user_id, type, note, starts_at, ends_at, created_by_id, created_at) "
                + "VALUES (?, 'SUSPEND', 'n', ?, ?, ?, NOW(6))", userId, starts, ends, adminId);
    }

    private String status(long matchId) {
        return jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, matchId);
    }

    private void assertInvariants() {
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN exchange_match m ON m.id = l.match_id WHERE m.status <> 'RESERVED'"))
                .as("RESERVED 가 아닌 매칭에 잠금이 남았다").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match m WHERE m.status = 'RESERVED' AND "
                + "(SELECT COUNT(*) FROM exchange_ticket_lock l WHERE l.match_id = m.id) <> 2"))
                .as("RESERVED 매칭의 잠금이 2행이 아니다").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN ticket t ON t.id = l.ticket_id WHERE t.status <> 'ACTIVE'"))
                .as("INACTIVE 티켓에 잠금이 남았다").isZero();
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
                if (r instanceof org.springframework.dao.DataAccessException
                        || r instanceof org.springframework.transaction.TransactionException
                        || r instanceof IllegalStateException) {
                    throw new AssertionError("교착/락 대기 초과/DB·상태 예외가 났다: " + r, (Throwable) r);
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

    /** 예약 제안 -> 상대 수락으로 RESERVED 를 만든다(V15). */
    private com.matjari.dto.response.ExchangeMatchResponse reserveFlow(long proposerId, long matchId) {
        return com.matjari.testsupport.ReserveFlow.reserve(matchService, jdbc, proposerId, matchId);
    }
}
