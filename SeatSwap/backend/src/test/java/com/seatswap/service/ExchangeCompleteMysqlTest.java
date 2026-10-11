package com.seatswap.service;

import com.seatswap.dto.response.ExchangeMatchResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
 * 교환 수락 -> 교환 완료(V9)의 실제 MySQL 통합 테스트 (서비스 + 트랜잭션 + 잠금 + 제약). 환경변수가 없으면 건너뛴다(ExchangeMatchMysqlTest 와 같은 방식):
 *   SEATSWAP_IT_JDBC_URL=jdbc:mysql://localhost:13391/seatswap_xc1_it  SEATSWAP_IT_USER=root  SEATSWAP_IT_PASSWORD=...
 * 경쟁 시나리오는 CountDownLatch 로 동시에 출발시키고 여러 라운드 반복한다. 교착·락 대기 초과(DataAccessException)는 허용하지 않는다(잠금 순서 규약 검증).
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "ticket.max-active-per-user=1000")
class ExchangeCompleteMysqlTest {

    private static final int ROUNDS = 20;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;   // 조건이 클래스를 건너뛴다(컨텍스트는 만들어지지 않는다)
        }
        RealMysqlSupport.cleanDatabase();
        registry.add("spring.datasource.url", RealMysqlSupport::url);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private ExchangeMatchService matchService;
    @Autowired
    private ExchangeMatchQueryService queryService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private ExchangeRequestService requestService;
    @Autowired
    private JdbcTemplate jdbc;

    private long seq;
    private long zoneSeq;
    private long performanceId;
    private long session1;
    private long session2;
    private final Map<Integer, Long> users = new java.util.HashMap<>();

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("chat_message", "chat_read_state", "notification", "exchange_history", "exchange_ticket_lock", "exchange_match", "exchange_want_seat", "exchange_want_session",
                "exchange_want_range", "exchange_request", "ticket", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        seq = 0;
        zoneSeq = 0;
        users.clear();
        long reg = user("registrant");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', '교환 공연', '올림픽홀', ?)", reg);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        session1 = session(LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0));
        session2 = session(LocalDateTime.now().plusDays(31).withMinute(0).withSecond(0).withNano(0));
    }

    // ------------------------------------------------------------------ 전체 흐름

    @Test
    void 두_사람이_모두_수락하면_티켓_교환_새티켓_이력_요청닫기_잠금해제가_한_번에_일어난다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        // 다른 회차 티켓끼리의 교환도 확인하기 위해 q 의 티켓 회차를 session2 로 옮긴다(공연 단위 교환)
        jdbc.update("UPDATE ticket SET performance_session_id = ? WHERE id = ?", session2, q.ticketId);
        long activeBeforeP = activeTickets(p.userId);
        long activeBeforeQ = activeTickets(q.userId);

        assertThatThrownBy(() -> matchService.complete(p.userId, m)).as("예약 전")
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getMessage()).isEqualTo("예약한 뒤에 교환 수락할 수 있어요."));
        matchService.reserve(q.userId, m);

        ExchangeMatchResponse first = matchService.complete(p.userId, m);
        assertThat(first.status()).isEqualTo("RESERVED");
        assertThat(first.myAccepted()).isTrue();
        assertThat(first.counterpartAccepted()).isFalse();
        assertThat(locks()).isEqualTo(2);
        assertThat(ticketStatus(p.ticketId)).isEqualTo("ACTIVE");
        assertThat(count("SELECT COUNT(*) FROM exchange_history")).isZero();
        // 멱등: 같은 사람이 다시 눌러도 200, 아무것도 바뀌지 않는다
        Object acceptedAt = jdbc.queryForObject("SELECT a_completed_at FROM exchange_match WHERE id = ?", Object.class, m);
        assertThat(matchService.complete(p.userId, m).status()).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT a_completed_at FROM exchange_match WHERE id = ?", Object.class, m)).isEqualTo(acceptedAt);
        assertThat(queryService.findOne(q.userId, m).counterpartAccepted()).as("q 화면: 상대(p)가 수락함").isTrue();

        ExchangeMatchResponse done = matchService.complete(q.userId, m);

        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.myAccepted()).isTrue();
        assertThat(done.counterpartAccepted()).isTrue();
        assertThat(done.reservedBy()).isNull();
        // 매칭 행의 좌석은 교환 전 자리다(q 화면: mySeat = q 의 기존 자리 Z1-1-2 @session2, counterpartSeat = p 의 기존 자리)
        assertThat(done.mySeat().col()).isEqualTo("2");
        assertThat(done.counterpartSeat().col()).isEqualTo("1");
        assertThat(done.myTicketId()).isEqualTo(q.ticketId);
        assertThat(done.myTicketExchanged()).isTrue();
        assertThat(done.counterpartTicketExchanged()).isTrue();
        assertThat(done.myTicketReservedElsewhere()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT reserved_by_id FROM exchange_match WHERE id = ?", Long.class, m)).isNull();
        assertThat(jdbc.queryForObject("SELECT reserved_at FROM exchange_match WHERE id = ?", Object.class, m)).isNull();
        assertThat(jdbc.queryForObject("SELECT a_completed_at IS NOT NULL AND b_completed_at IS NOT NULL FROM exchange_match WHERE id = ?",
                Boolean.class, m)).isTrue();

        // 기존 두 티켓 EXCHANGED, 요청 CLOSED, 잠금 0행
        assertThat(ticketStatus(p.ticketId)).isEqualTo("EXCHANGED");
        assertThat(ticketStatus(q.ticketId)).isEqualTo("EXCHANGED");
        assertThat(requestStatus(p.requestId)).isEqualTo("CLOSED");
        assertThat(requestStatus(q.requestId)).isEqualTo("CLOSED");
        assertThat(locks()).isZero();

        // 새 티켓 2개: 소유자 그대로, 좌석은 상대의 기존 자리, ACTIVE. 사용자별 활성 수는 그대로
        Map<String, Object> pNew = activeTicketOf(p.userId);
        Map<String, Object> qNew = activeTicketOf(q.userId);
        assertThat(pNew.get("zone_label")).isEqualTo("Z1");
        assertThat(pNew.get("row_label")).isEqualTo("1");
        assertThat(pNew.get("col_label")).isEqualTo("2");
        assertThat(((Number) pNew.get("performance_session_id")).longValue()).isEqualTo(session2);
        assertThat(qNew.get("col_label")).isEqualTo("1");
        assertThat(((Number) qNew.get("performance_session_id")).longValue()).isEqualTo(session1);
        assertThat(activeTickets(p.userId)).isEqualTo(activeBeforeP);
        assertThat(activeTickets(q.userId)).isEqualTo(activeBeforeQ);

        // 이력 2행: (기존 자리) -> (바꾼 자리) 스냅샷과 old/new_ticket_id
        List<Map<String, Object>> history = jdbc.queryForList("SELECT * FROM exchange_history WHERE match_id = ? ORDER BY user_id", m);
        assertThat(history).hasSize(2);
        Map<String, Object> hp = history.stream().filter(h -> ((Number) h.get("user_id")).longValue() == p.userId).findFirst().orElseThrow();
        assertThat(((Number) hp.get("old_ticket_id")).longValue()).isEqualTo(p.ticketId);
        assertThat(((Number) hp.get("new_ticket_id")).longValue()).isEqualTo(((Number) pNew.get("id")).longValue());
        assertThat(hp.get("performance_title")).isEqualTo("교환 공연");
        assertThat(hp.get("venue_name")).isEqualTo("올림픽홀");
        assertThat(((Number) hp.get("performance_id")).longValue()).isEqualTo(performanceId);
        assertThat(hp.get("old_col_label")).isEqualTo("1");
        assertThat(hp.get("new_col_label")).isEqualTo("2");
        assertThat(hp.get("old_zone_label")).isEqualTo("Z1");
        assertThat(hp.get("old_starts_at")).isNotEqualTo(hp.get("new_starts_at"));
        Map<String, Object> hq = history.stream().filter(h -> ((Number) h.get("user_id")).longValue() == q.userId).findFirst().orElseThrow();
        assertThat(((Number) hq.get("old_ticket_id")).longValue()).isEqualTo(q.ticketId);
        assertThat(((Number) hq.get("new_ticket_id")).longValue()).isEqualTo(((Number) qNew.get("id")).longValue());
        assertThat(hq.get("old_col_label")).isEqualTo("2");
        assertThat(hq.get("new_col_label")).isEqualTo("1");
        assertThat(hq.get("created_at")).isNotNull();

        // 완료 후: 취소·재수락 불가(409), EXCHANGED 티켓 내리기 409 TICKET_EXCHANGED, 새 티켓은 내릴 수 있다
        assertThatThrownBy(() -> matchService.cancel(p.userId, m)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> matchService.unreserve(p.userId, m)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> matchService.complete(p.userId, m)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT").containsEntry("status", "COMPLETED"));
        assertThatThrownBy(() -> ticketService.deactivate(p.userId, p.ticketId)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_EXCHANGED"));
        assertThat(ticketStatus(p.ticketId)).isEqualTo("EXCHANGED");
        assertThatThrownBy(() -> matchService.complete(99999L, m)).isInstanceOf(NotFoundException.class);
        assertInvariants();
    }

    @Test
    void 한쪽_수락_뒤_예약_취소하면_수락_표시가_초기화되고_다시_예약해_양쪽_수락하면_완료된다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        matchService.reserve(p.userId, m);
        matchService.complete(p.userId, m);

        ExchangeMatchResponse back = matchService.unreserve(q.userId, m);
        assertThat(back.status()).isEqualTo("CHATTING");
        assertThat(back.myAccepted()).isFalse();
        assertThat(back.counterpartAccepted()).isFalse();
        assertThat(locks()).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE id = " + m + " AND a_completed_at IS NULL AND b_completed_at IS NULL")).isEqualTo(1);
        assertThatThrownBy(() -> matchService.complete(q.userId, m)).isInstanceOf(ConflictException.class);

        matchService.reserve(q.userId, m);
        matchService.complete(q.userId, m);
        assertThat(matchService.complete(p.userId, m).status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM exchange_history WHERE match_id = " + m)).isEqualTo(2);
        assertInvariants();
    }

    @Test
    void 마감_지난_회차의_채팅도_예약_수락_완료할_수_있다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        matchService.reserve(p.userId, m);
        // 회차 당일이 지났다(등록 마감 후). 이미 시작한 채팅에는 마감 검사를 하지 않는다.
        jdbc.update("UPDATE performance_session SET starts_at = ? WHERE id = ?", LocalDateTime.now().minusDays(10).withNano(0), session1);

        matchService.complete(p.userId, m);
        assertThat(matchService.complete(q.userId, m).status()).isEqualTo("COMPLETED");
        assertThat(count("SELECT COUNT(*) FROM ticket WHERE status = 'EXCHANGED'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM ticket WHERE status = 'ACTIVE'")).isEqualTo(2);
        assertInvariants();
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    void 같은_매칭의_두_사람이_동시에_수락해도_완료는_정확히_한_번이고_이력은_2행이다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            matchService.reserve(p.userId, m);

            List<Object> results = runConcurrently(new ArrayList<>(List.of(
                    () -> matchService.complete(p.userId, m),
                    () -> matchService.complete(q.userId, m))));

            assertThat(results).as("round " + round).allSatisfy(res -> assertThat(res).isInstanceOf(ExchangeMatchResponse.class));
            assertThat(results).as("round " + round).anyMatch(res -> "COMPLETED".equals(((ExchangeMatchResponse) res).status()));
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE id = " + m + " AND status = 'COMPLETED'")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM exchange_history WHERE match_id = " + m)).as("round " + round).isEqualTo(2);
            assertThat(ticketStatus(p.ticketId)).isEqualTo("EXCHANGED");
            assertThat(ticketStatus(q.ticketId)).isEqualTo("EXCHANGED");
            assertThat(locks()).isZero();
            assertInvariants();
        }
        assertThat(count("SELECT COUNT(*) FROM exchange_history")).isEqualTo(2L * ROUNDS);
    }

    @Test
    void 같은_사람이_동시에_여러_번_눌러도_멱등이다() throws Exception {
        for (int round = 0; round < ROUNDS / 2; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            matchService.reserve(p.userId, m);

            List<Object> results = runConcurrently(new ArrayList<>(List.of(
                    () -> matchService.complete(p.userId, m),
                    () -> matchService.complete(p.userId, m),
                    () -> matchService.complete(p.userId, m))));

            assertThat(results).allSatisfy(res -> assertThat(res).isInstanceOf(ExchangeMatchResponse.class));
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE id = " + m + " AND status = 'RESERVED' AND a_completed_at IS NOT NULL AND b_completed_at IS NULL"))
                    .as("한 사람만 수락했으므로 완료되지 않는다").isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM exchange_history WHERE match_id = " + m)).isZero();
            matchService.unreserve(p.userId, m);
            matchService.cancel(p.userId, m);
        }
        assertInvariants();
    }

    @Test
    void 같은_티켓을_건_두_매칭에서_하나가_완료되는_동안_다른_매칭의_예약은_409이고_교착이_없다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            Party r = party(3, "Z" + zoneSeq);
            wants(p, q, r);
            wants(q, p);
            wants(r, p);
            long m1 = matchService.propose(p.userId, p.requestId, q.requestId).id();
            long m2 = matchService.propose(r.userId, r.requestId, p.requestId).id();
            matchService.reserve(p.userId, m1);
            matchService.complete(p.userId, m1);

            // M1 이 RESERVED 인 동안 M2 카드(r 화면)에는 상대(p) 티켓이 다른 매칭에서 예약 중이라고 보인다
            ExchangeMatchResponse viewR = queryService.findOne(r.userId, m2);
            assertThat(viewR.counterpartTicketReservedElsewhere()).isTrue();
            assertThat(viewR.counterpartTicketExchanged()).isFalse();
            assertThat(viewR.myTicketReservedElsewhere()).isFalse();

            List<Object> results = runConcurrently(new ArrayList<>(List.of(
                    () -> matchService.complete(q.userId, m1),
                    () -> matchService.reserve(r.userId, m2))));

            Object m2Result = results.get(1);
            assertThat(m2Result).as("round " + round + " " + results).isInstanceOfSatisfying(ConflictException.class,
                    e -> assertThat((String) e.getDetails().get("code")).isIn("TICKET_EXCHANGED", "TICKET_ALREADY_RESERVED"));
            assertThat(results.get(0)).isInstanceOf(ExchangeMatchResponse.class);
            assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m1)).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m2)).isEqualTo("CHATTING");

            // 이제 M2 카드는 교환된 좌석 안내 + 서버 안전장치 409 TICKET_EXCHANGED, 거절·채팅 종료는 가능
            ExchangeMatchResponse after = queryService.findOne(r.userId, m2);
            assertThat(after.counterpartTicketExchanged()).isTrue();
            assertThat(after.myTicketExchanged()).isFalse();
            assertThat(queryService.findOne(p.userId, m2).myTicketExchanged()).as("p 화면에서도 보인다").isTrue();
            assertThatThrownBy(() -> matchService.reserve(r.userId, m2)).isInstanceOfSatisfying(ConflictException.class,
                    e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_EXCHANGED"));
            assertThat(matchService.cancel(r.userId, m2).status()).isEqualTo("CANCELED");
            assertInvariants();
        }
    }

    @Test
    void 교환_완료는_예약_취소_티켓_내리기_요청_삭제와_동시에_와도_교착이_없다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            matchService.reserve(p.userId, m);
            matchService.complete(p.userId, m);

            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> matchService.complete(q.userId, m),
                    () -> matchService.unreserve(p.userId, m),
                    () -> { ticketService.deactivate(q.userId, q.ticketId); return "deactivated"; },
                    () -> { requestService.delete(p.userId, p.requestId); return "deleted"; }));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(res -> assertThat(
                    !(res instanceof Exception) || res instanceof ConflictException || res instanceof BusinessRuleException
                            || res instanceof NotFoundException).as(String.valueOf(res)).isTrue());
            String status = jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m);
            assertThat(status).isIn("COMPLETED", "CHATTING", "RESERVED", "CANCELED");
            if (status.equals("COMPLETED")) {
                assertThat(count("SELECT COUNT(*) FROM exchange_history WHERE match_id = " + m)).isEqualTo(2);
                assertThat(ticketStatus(p.ticketId)).isEqualTo("EXCHANGED");
                assertThat(ticketStatus(q.ticketId)).isEqualTo("EXCHANGED");
            } else {
                assertThat(count("SELECT COUNT(*) FROM exchange_history WHERE match_id = " + m)).isZero();
                assertThat(ticketStatus(p.ticketId)).isNotEqualTo("EXCHANGED");
            }
            assertInvariants();
        }
    }

    // ------------------------------------------------------------------ 제약

    @Test
    void V9_제약을_직접_SQL로_위반하면_제약_이름과_함께_거부된다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party r = party(3, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();

        assertRejected("ck_ticket_status", () -> jdbc.update("UPDATE ticket SET status = 'FOO' WHERE id = ?", p.ticketId));
        assertThat(jdbc.update("UPDATE ticket SET status = 'EXCHANGED' WHERE id = ?", p.ticketId)).isEqualTo(1);
        jdbc.update("UPDATE ticket SET status = 'ACTIVE' WHERE id = ?", p.ticketId);

        String insert = "INSERT INTO exchange_history (match_id, user_id, old_ticket_id, new_ticket_id, performance_id, performance_title, venue_name, "
                + "old_starts_at, new_starts_at, old_zone_label, old_row_label, old_col_label, new_zone_label, new_row_label, new_col_label, created_at) "
                + "VALUES (?,?,?,?,?,'t','v',NOW(6),NOW(6),'a','1','1','b','2','2',NOW(6))";
        assertRejected("ck_exchange_history_tickets", () -> jdbc.update(insert, m, p.userId, p.ticketId, p.ticketId, performanceId));
        assertRejected("fk_exchange_history_match", () -> jdbc.update(insert, 999999L, p.userId, p.ticketId, q.ticketId, performanceId));
        jdbc.update(insert, m, p.userId, p.ticketId, q.ticketId, performanceId);
        assertRejected("uk_exchange_history_old_ticket", () -> jdbc.update(insert, m, q.userId, p.ticketId, 999L, performanceId));
        assertRejected("uk_exchange_history_new_ticket", () -> jdbc.update(insert, m, q.userId, r.ticketId, q.ticketId, performanceId));
    }

    private static void assertRejected(String constraint, Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining(constraint);
    }

    // ------------------------------------------------------------------ 도우미

    private void assertInvariants() {
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN exchange_match m ON m.id = l.match_id WHERE m.status <> 'RESERVED'"))
                .as("RESERVED 가 아닌 매칭에 잠금이 남았다").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match m WHERE m.status = 'RESERVED' AND "
                + "(SELECT COUNT(*) FROM exchange_ticket_lock l WHERE l.match_id = m.id) <> 2"))
                .as("RESERVED 매칭의 잠금이 2행이 아니다").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN ticket t ON t.id = l.ticket_id WHERE t.status <> 'ACTIVE'"))
                .as("ACTIVE 가 아닌 티켓에 잠금이 남았다").isZero();
        assertThat(count("SELECT COUNT(*) FROM (SELECT request_low_id, request_high_id FROM exchange_match WHERE open_flag = 1 "
                + "GROUP BY request_low_id, request_high_id HAVING COUNT(*) > 1) d")).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match m WHERE m.status = 'COMPLETED' AND "
                + "(SELECT COUNT(*) FROM exchange_history h WHERE h.match_id = m.id) <> 2"))
                .as("COMPLETED 매칭의 이력이 2행이 아니다").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_history h JOIN ticket t ON t.id = h.old_ticket_id WHERE t.status <> 'EXCHANGED'"))
                .as("이력의 기존 티켓이 EXCHANGED 가 아니다").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_history h JOIN ticket t ON t.id = h.new_ticket_id WHERE t.status = 'EXCHANGED'"))
                .as("새 티켓이 바로 EXCHANGED 가 되었다").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_request r JOIN ticket t ON t.id = r.ticket_id "
                + "WHERE t.status = 'EXCHANGED' AND r.status = 'OPEN'")).as("EXCHANGED 티켓에 OPEN 요청이 남았다").isZero();
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private long locks() {
        return count("SELECT COUNT(*) FROM exchange_ticket_lock");
    }

    private long activeTickets(long userId) {
        return count("SELECT COUNT(*) FROM ticket WHERE user_id = " + userId + " AND status = 'ACTIVE'");
    }

    private String ticketStatus(long ticketId) {
        return jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private String requestStatus(long requestId) {
        return jdbc.queryForObject("SELECT status FROM exchange_request WHERE id = ?", String.class, requestId);
    }

    /** 사용자의 새 자리 티켓(ACTIVE 중 가장 나중에 만들어진 것). */
    private Map<String, Object> activeTicketOf(long userId) {
        return jdbc.queryForMap("SELECT * FROM ticket WHERE user_id = ? AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1", userId);
    }

    /** 모든 호출을 같은 순간에 출발시키고 결과(응답 또는 던진 예외)를 순서대로 모은다. 교착·락 대기 초과·DB 예외는 테스트 실패로 본다. */
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
                if (r instanceof org.springframework.dao.DataAccessException || r instanceof IllegalStateException) {
                    throw new AssertionError("교착/락 대기 초과/DB 예외/불변식 위반이 났다: " + r);
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

    /** 사용자 n 번째(고정 사용자 풀)의 티켓·요청. 열은 "1", 번은 사용자 번호로 둔다. */
    private Party party(int n, String zone) {
        long userId = users.computeIfAbsent(n, k -> user("user" + k));
        String col = String.valueOf(n);
        jdbc.update("INSERT INTO ticket (performance_session_id, user_id, zone_label, zone_key, row_label, row_key, "
                + "col_label, col_key, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',NOW(6),NOW(6))",
                session1, userId, zone, zone, "1", "1", col, col);
        long ticketId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_request (ticket_id, status, created_at, updated_at) "
                + "VALUES (?, 'OPEN', NOW(6), NOW(6))", ticketId);
        long requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,1)", requestId, session1);
        return new Party(userId, ticketId, requestId, zone, "1", col);
    }

    /** a 가 원하는 좌석 = 주어진 상대들의 좌석 전부. */
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
