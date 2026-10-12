package com.seatswap.service;

import com.seatswap.dto.request.ExchangeRequestUpdateRequest;
import com.seatswap.dto.request.WantRangeInput;
import com.seatswap.dto.request.WantSessionInput;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 매칭 생성·예약·취소의 실제 MySQL 통합 테스트 (서비스 + 트랜잭션 + 잠금 + 제약). 환경변수가 없으면 건너뛴다:
 *   SEATSWAP_IT_JDBC_URL=jdbc:mysql://localhost:13310/seatswap_it  SEATSWAP_IT_USER=root  SEATSWAP_IT_PASSWORD=tmp
 * 안전장치와 Flyway clean 은 RealMysqlSupport 가 한다(DB 이름 `_it`). 경쟁 시나리오는 여러 스레드를 CountDownLatch 로 동시에 출발시키고
 * 여러 라운드 반복한다. 교착·락 대기 초과(PessimisticLockingFailure)는 허용하지 않는다(잠금 순서 규약 검증).
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "ticket.max-active-per-user=1000")
class ExchangeMatchMysqlTest {

    private static final int ROUNDS = 15;
    private static final int DEACTIVATE_ROUNDS = 40;

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
    private TicketService ticketService;
    @Autowired
    private ExchangeRequestService requestService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;

    private long seq;
    private long zoneSeq;
    private long performanceId;
    private long session1;

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
        zoneSeq = 0;
        long reg = user("registrant");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', 't', 'v', ?)", reg);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        session1 = session(LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0));
    }

    // ------------------------------------------------------------------ 단일 흐름

    @Test
    void 제안_한명예약_예약취소_취소의_전체_흐름과_잠금_행() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);

        ExchangeMatchResponse proposed = matchService.propose(p.userId, p.requestId, q.requestId);
        assertThat(proposed.status()).isEqualTo("CHATTING");
        assertThat(proposed.reservedBy()).isNull();

        // 한 명이 제안하면 상태는 그대로이고 잠금도 없다. 상대가 수락해야 RESERVED + 잠금 2행
        ExchangeMatchResponse offer = matchService.reserve(p.userId, proposed.id());
        assertThat(offer.status()).isEqualTo("CHATTING");
        assertThat(offer.reservationProposal()).isEqualTo("MINE");
        assertThat(locks()).isZero();
        ExchangeMatchResponse reserved = matchService.reserveAccept(q.userId, proposed.id());
        assertThat(reserved.status()).isEqualTo("RESERVED");
        assertThat(reserved.reservationProposal()).isEqualTo("NONE");
        assertThat(reserved.reservedBy()).as("reserved_by_id 는 제안자(p), 수락자(q) 시점이므로 COUNTERPART").isEqualTo("COUNTERPART");
        assertThat(reserved.reservedAt()).isNotNull();
        assertThat(reserved.myAccepted()).isFalse();
        assertThat(reserved.counterpartAccepted()).isFalse();
        assertThat(locks()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reserved_by_id FROM exchange_match WHERE id=?", Long.class, proposed.id())).isEqualTo(p.userId);
        assertThat(matchService.reserveAccept(q.userId, proposed.id()).status()).as("수락자의 재호출은 멱등").isEqualTo("RESERVED");
        assertThatThrownBy(() -> matchService.reserveAccept(p.userId, proposed.id())).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_OWN"));
        assertThatThrownBy(() -> matchService.reserve(p.userId, proposed.id())).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT"));
        assertThat(locks()).isEqualTo(2);

        // 예약 중에는 채팅 종료(cancel)와 거절(reject)이 막힌다
        assertThatThrownBy(() -> matchService.cancel(q.userId, proposed.id()))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("먼저 예약을 취소해주세요.");
                    assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT");
                });
        assertThatThrownBy(() -> matchService.reject(q.userId, proposed.id())).isInstanceOf(ConflictException.class);
        // 잠긴 티켓은 내릴 수 없다 (409), 요청 삭제도 409 (열린 매칭)
        assertThatThrownBy(() -> ticketService.deactivate(p.userId, p.ticketId)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> requestService.delete(p.userId, p.requestId)).isInstanceOf(ConflictException.class);
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE status='RESERVED'")).isEqualTo(1);

        // 예약한 사람이 아닌 상대가 예약을 취소 -> CHATTING 복귀, 잠금 해제
        ExchangeMatchResponse back = matchService.unreserve(q.userId, proposed.id());
        assertThat(back.status()).isEqualTo("CHATTING");
        assertThat(back.reservedBy()).isNull();
        assertThat(back.reservedAt()).isNull();
        assertThat(locks()).isZero();
        assertThat(matchService.unreserve(q.userId, proposed.id()).status()).as("CHATTING 에서 예약 취소는 멱등").isEqualTo("CHATTING");

        // 같은 쌍이 바로 다시 예약하고, 이번엔 상대가 예약자
        assertThat(reserveFlow(q.userId, proposed.id()).reservedBy()).as("이번엔 q 가 제안자(수락자 p 시점이라 COUNTERPART)").isEqualTo("COUNTERPART");
        assertThat(jdbc.queryForObject("SELECT reserved_by_id FROM exchange_match WHERE id=?", Long.class, proposed.id())).isEqualTo(q.userId);
        assertThat(locks()).isEqualTo(2);
        matchService.unreserve(q.userId, proposed.id());

        ExchangeMatchResponse canceled = matchService.cancel(q.userId, proposed.id());
        assertThat(canceled.status()).isEqualTo("CANCELED");
        assertThat(canceled.canceledBy()).isEqualTo("ME");
        assertThat(locks()).isZero();
        assertThatThrownBy(() -> reserveFlow(p.userId, proposed.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> matchService.unreserve(p.userId, proposed.id())).isInstanceOf(ConflictException.class);

        // 취소 후 같은 쌍 재매칭 허용
        assertThat(matchService.propose(q.userId, q.requestId, p.requestId).status()).isEqualTo("CHATTING");
        assertThat(count("SELECT COUNT(*) FROM exchange_match")).isEqualTo(2);
        assertInvariants();
    }

    @Test
    void 티켓이_INACTIVE이거나_요청이_닫힘_삭제면_예약은_거절되고_예약_취소와_채팅_종료는_가능하다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();

        // 티켓 내리기 경로를 거치지 않고 직접 INACTIVE 로 만든 불일치 상태(방어선 검증)
        jdbc.update("UPDATE ticket SET status = 'INACTIVE' WHERE id = ?", q.ticketId);
        assertThatThrownBy(() -> reserveFlow(p.userId, m)).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_ACTIVE"));
        jdbc.update("UPDATE ticket SET status = 'ACTIVE' WHERE id = ?", q.ticketId);

        jdbc.update("UPDATE exchange_request SET status = 'CLOSED' WHERE id = ?", q.requestId);
        assertThatThrownBy(() -> reserveFlow(p.userId, m)).isInstanceOf(BusinessRuleException.class);
        jdbc.update("UPDATE exchange_request SET status = 'DELETED', deleted_at = NOW(6) WHERE id = ?", q.requestId);
        assertThatThrownBy(() -> reserveFlow(p.userId, m)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "REQUEST_DELETED"));
        assertThat(locks()).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE id = " + m + " AND status = 'CHATTING' AND reserved_by_id IS NULL")).isEqualTo(1);
        // 이미 열린 매칭은 닫을 수 있다
        assertThat(matchService.cancel(p.userId, m).status()).isEqualTo("CANCELED");

        // RESERVED 인 뒤 요청이 닫혀도 멱등 reserve 는 200, unreserve 는 가능
        Party r = party(3, "Z2");
        Party s = party(4, "Z2");
        mutual(r, s);
        long m2 = matchService.propose(r.userId, r.requestId, s.requestId).id();
        reserveFlow(r.userId, m2);
        jdbc.update("UPDATE exchange_request SET status = 'CLOSED' WHERE id = ?", s.requestId);
        assertThat(matchService.reserveAccept(s.userId, m2).status()).as("수락자의 멱등 수락").isEqualTo("RESERVED");
        assertThat(matchService.unreserve(s.userId, m2).status()).isEqualTo("CHATTING");
        assertInvariants();
    }

    @Test
    void 교환_수락_표시가_있어도_예약_취소는_허용되고_표시가_초기화된다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        reserveFlow(q.userId, m);
        // 교환 수락 기능은 아직 없으므로 한쪽이 수락을 눌러 둔 상태를 직접 만든다(RESERVED 에서는 CHECK 허용)
        jdbc.update("UPDATE exchange_match SET a_completed_at = NOW(6) WHERE id = ?", m);
        assertThat(matchService.reserveAccept(p.userId, m).myAccepted()).as("a측(p) 화면: 내가 수락(멱등 수락이 현재 상태를 돌려준다)").isTrue();
        assertThat(matchService.reserveDecline(q.userId, m).counterpartAccepted()).isTrue();

        ExchangeMatchResponse back = matchService.unreserve(p.userId, m);

        assertThat(back.status()).isEqualTo("CHATTING");
        assertThat(back.myAccepted()).isFalse();
        assertThat(back.counterpartAccepted()).isFalse();
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE a_completed_at IS NOT NULL OR b_completed_at IS NOT NULL")).isZero();
        assertThat(locks()).isZero();
    }

    @Test
    void 예약_중에도_이미_열려있던_다른_채팅은_유지되고_새_예약과_새_제안만_막힌다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party r = party(3, "Z1");
        wants(p, q, r);
        wants(q, p);
        wants(r, p);
        long m1 = matchService.propose(p.userId, p.requestId, q.requestId).id();
        long m2 = matchService.propose(p.userId, p.requestId, r.requestId).id();   // 한 요청에 채팅 여러 개

        reserveFlow(q.userId, m1);                                          // m1 RESERVED, p/q 티켓 잠김

        assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id=?", String.class, m2)).as("다른 채팅은 유지").isEqualTo("CHATTING");
        // 누가 눌러도 p 의 티켓은 잠겨 있어 m2 예약은 409
        for (long who : new long[]{p.userId, r.userId}) {
            assertThatThrownBy(() -> reserveFlow(who, m2))
                    .isInstanceOfSatisfying(ConflictException.class,
                            e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_ALREADY_RESERVED"));
        }
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE id = " + m2 + " AND reserved_by_id IS NOT NULL"))
                .as("실패한 예약은 흔적을 남기지 않는다").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE status='RESERVED'")).isEqualTo(1);
        // 잠긴 티켓의 새 제안은 막힌다: 같은 쌍은 열린 매칭(m2)이 먼저 걸리고, 새 상대로는 내 티켓이 잠겨 TICKET_LOCKED
        assertThatThrownBy(() -> matchService.propose(p.userId, p.requestId, r.requestId))
                .isInstanceOf(ConflictException.class);
        Party s = party(4, "Z1");
        mutual(p, s);
        assertThatThrownBy(() -> matchService.propose(p.userId, p.requestId, s.requestId))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("TICKET_LOCKED"));
        assertThatThrownBy(() -> matchService.propose(s.userId, s.requestId, p.requestId))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_A_CANDIDATE"));
        // m1 의 예약을 취소하면 m2 를 예약할 수 있다 (m2 의 채팅은 계속 유지됐다)
        matchService.unreserve(p.userId, m1);
        assertThat(locks()).isZero();
        assertThat(reserveFlow(r.userId, m2).status()).isEqualTo("RESERVED");
        assertThat(locks()).isEqualTo(2);
        assertInvariants();
    }

    @Test
    void 티켓을_내리면_그_티켓의_CHATTING_매칭이_시스템_취소된다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();

        ticketService.deactivate(q.userId, q.ticketId);

        assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id=?", String.class, m)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT canceled_by_id FROM exchange_match WHERE id=?", Long.class, m)).isNull();
        assertThat(jdbc.queryForObject("SELECT canceled_at IS NOT NULL FROM exchange_match WHERE id=?", Boolean.class, m)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_request WHERE id=?", String.class, q.requestId)).isEqualTo("CLOSED");
        // 상대(p)는 더 이상 q 를 후보로 보지 못하고 새로 제안해도 422
        assertThatThrownBy(() -> matchService.propose(p.userId, p.requestId, q.requestId)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 취소된_매칭이_있어도_요청을_삭제할_수_있고_매칭_기록과_스냅샷은_남는다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        matchService.cancel(p.userId, m);

        requestService.delete(p.userId, p.requestId);

        assertThat(count("SELECT COUNT(*) FROM exchange_match")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT a_extra_type FROM exchange_match WHERE id=?", String.class, m)).isEqualTo("ANY");
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_request WHERE id=?", String.class, p.requestId)).isEqualTo("DELETED");
        assertThat(count("SELECT COUNT(*) FROM exchange_request WHERE id = " + p.requestId + " AND deleted_at IS NOT NULL AND live_flag IS NULL"))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM exchange_want_seat WHERE request_id = " + p.requestId)).isZero();
        // 삭제 멱등
        requestService.delete(p.userId, p.requestId);
    }

    @Test
    void 삭제하면_CHATTING_매칭은_시스템_취소되고_완료_매칭이_있어도_삭제된다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();

        requestService.delete(q.userId, q.requestId);

        assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id=?", String.class, m)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT canceled_by_id FROM exchange_match WHERE id=?", Long.class, m)).isNull();
        // COMPLETED 매칭(교환 완료 기능 전이므로 직접 상태를 만든다)이 있어도 p 의 요청 삭제는 허용된다
        jdbc.update("UPDATE exchange_match SET status='COMPLETED', canceled_at=NULL, canceled_by_id=NULL, a_completed_at=NOW(6), b_completed_at=NOW(6) WHERE id=?", m);
        requestService.delete(p.userId, p.requestId);
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_request WHERE id=?", String.class, p.requestId)).isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id=?", String.class, m)).isEqualTo("COMPLETED");
    }

    @Test
    void 삭제한_뒤_같은_티켓에_새_요청을_만들_수_있고_삭제된_요청은_후보에서_빠진다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        requestService.delete(q.userId, q.requestId);

        assertThatThrownBy(() -> matchService.propose(p.userId, p.requestId, q.requestId)).isInstanceOf(BusinessRuleException.class);

        var created = requestService.create(q.userId, new com.seatswap.dto.request.ExchangeRequestCreateRequest(q.ticketId,
                List.of(new WantSessionInput(session1, 1)),
                List.of(new WantRangeInput("Z1", "1", "1", "1", "1", "NEG", -1000))));
        assertThat(created.id()).isNotEqualTo(q.requestId);
        assertThat(count("SELECT COUNT(*) FROM exchange_request WHERE ticket_id = " + q.ticketId)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM exchange_request WHERE ticket_id = " + q.ticketId + " AND live_flag = 1")).isEqualTo(1);
        assertThat(requestService.listMine(q.userId, q.ticketId)).extracting(r -> r.id()).containsExactly(created.id());
    }

    @Test
    void 티켓을_내려도_삭제된_요청은_CLOSED로_되살아나지_않는다() {
        Party p = party(1, "Z1");
        requestService.delete(p.userId, p.requestId);

        ticketService.deactivate(p.userId, p.ticketId);

        assertThat(jdbc.queryForObject("SELECT status FROM exchange_request WHERE id=?", String.class, p.requestId)).isEqualTo("DELETED");
    }

    @Test
    void 요청_수정이_범위별_추가금을_범위와_좌석에_싣는다() {
        Party p = party(1, "Z1");
        requestService.update(p.userId, p.requestId, new ExchangeRequestUpdateRequest(
                List.of(new WantSessionInput(session1, 1)),
                List.of(new WantRangeInput("Z1", "1", "1", "2", "3", "POS", 5000),
                        new WantRangeInput("Z1", "1", "1", "5", "5", "NEG", -2000))));

        assertThat(jdbc.queryForList("SELECT col_key, extra_type, extra_amount FROM exchange_want_seat WHERE request_id = ? ORDER BY col_key",
                p.requestId)).extracting(r -> r.get("col_key") + ":" + r.get("extra_type") + ":" + r.get("extra_amount"))
                .containsExactly("2:POS:5000", "3:POS:5000", "5:NEG:-2000");
        assertThat(jdbc.queryForList("SELECT extra_type, extra_amount FROM exchange_want_range WHERE request_id = ? ORDER BY sort_order",
                p.requestId)).extracting(r -> r.get("extra_type") + ":" + r.get("extra_amount"))
                .containsExactly("POS:5000", "NEG:-2000");

        // 겹치는 좌석의 추가금이 다르면 422 이고 기존 데이터는 그대로
        assertThatThrownBy(() -> requestService.update(p.userId, p.requestId, new ExchangeRequestUpdateRequest(
                List.of(new WantSessionInput(session1, 1)),
                List.of(new WantRangeInput("Z1", "1", "1", "2", "3", "POS", 5000),
                        new WantRangeInput("Z1", "1", "1", "3", "4", "X", null)))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("WANT_EXTRA_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM exchange_want_seat WHERE request_id = " + p.requestId)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ 경쟁

    @Test
    void 같은_쌍_동시_제안은_하나만_성공하고_나머지는_409() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> matchService.propose(p.userId, p.requestId, q.requestId));
                calls.add(() -> matchService.propose(q.userId, q.requestId, p.requestId));   // 반대 방향도 같은 쌍
            }
            List<Object> results = runConcurrently(calls);

            long created = results.stream().filter(r -> r instanceof ExchangeMatchResponse).count();
            long conflicts = results.stream().filter(r -> r instanceof ConflictException).count();
            assertThat(created).as("round " + round + " results=" + results).isEqualTo(1);
            assertThat(conflicts).as("round " + round).isEqualTo(7);
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE open_flag = 1 AND request_low_id = LEAST("
                    + p.requestId + "," + q.requestId + ")")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'PROPOSAL_RECEIVED'"))
                    .as("round " + round + ": 같은 쌍 동시 제안은 매칭 1개당 알림 1건(누적)").isEqualTo(round + 1);
        }
        assertInvariants();
    }

    @Test
    void 같은_티켓을_걸고_두_매칭이_동시에_예약하려_하면_하나만_RESERVED() throws Exception {
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

            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> reserveFlow(p.userId, m1), () -> reserveFlow(q.userId, m1),
                    () -> reserveFlow(p.userId, m2), () -> reserveFlow(r.userId, m2)));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(res ->
                    assertThat(res instanceof ExchangeMatchResponse || res instanceof ConflictException).as(String.valueOf(res)).isTrue());
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE status='RESERVED' AND id IN (" + m1 + "," + m2 + ")"))
                    .as("round " + round + " results=" + results).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock WHERE ticket_id = " + p.ticketId)).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock WHERE match_id IN (" + m1 + "," + m2 + ")")).isEqualTo(2);
        }
        assertInvariants();
    }

    @Test
    void 예약과_취소_거절이_동시에_와도_잠금_행이_상태와_어긋나지_않는다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> reserveFlow(p.userId, m), () -> reserveFlow(q.userId, m),
                    () -> matchService.cancel(p.userId, m), () -> matchService.reject(q.userId, m)));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(res ->
                    assertThat(res instanceof ExchangeMatchResponse || res instanceof ConflictException).as(String.valueOf(res)).isTrue());
            String status = jdbc.queryForObject("SELECT status FROM exchange_match WHERE id=?", String.class, m);
            assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock WHERE match_id = " + m))
                    .as("round " + round + " status=" + status).isEqualTo(status.equals("RESERVED") ? 2 : 0);
        }
        assertInvariants();
    }

    @Test
    void 티켓_내리기와_예약이_동시에_와도_잠긴_티켓이_INACTIVE가_되지_않는다() throws Exception {
        int deactivated = 0;
        int reserved = 0;
        for (int round = 0; round < DEACTIVATE_ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            final int finalRound = round;
            Party victim = round % 2 == 0 ? q : p;   // 번갈아 id 가 큰/작은 티켓을 내린다 (잠금 순서 양방향 검증)
            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> reserveFlow(p.userId, m), () -> reserveFlow(q.userId, m),
                    () -> {
                        Thread.sleep((long) (finalRound % 8) * 6);   // 라운드 번호로 정한 고정 지연(0~42ms): 양쪽 승부가 모두 나오되 무작위가 아니다
                        ticketService.deactivate(victim.userId, victim.ticketId);
                        return "deactivated";
                    }));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(res -> assertThat(
                    res instanceof ExchangeMatchResponse || res instanceof ConflictException || "deactivated".equals(res))
                    .as(String.valueOf(res)).isTrue());
            String ticketStatus = jdbc.queryForObject("SELECT status FROM ticket WHERE id=?", String.class, victim.ticketId);
            String status = jdbc.queryForObject("SELECT status FROM exchange_match WHERE id=?", String.class, m);
            if (ticketStatus.equals("INACTIVE")) {
                deactivated++;
                assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock WHERE ticket_id = " + victim.ticketId)).isZero();
                assertThat(status).as("내려진 티켓의 매칭은 취소").isEqualTo("CANCELED");
            } else {
                assertThat(status).isEqualTo("RESERVED");
                reserved++;
            }
        }
        assertInvariants();
        System.out.println("[deactivate-vs-reserve] deactivated=" + deactivated + " reserved=" + reserved);
        assertThat(deactivated).as("티켓 내리기가 이기는 라운드가 있어야 한다").isPositive();
        assertThat(reserved).as("예약이 이기는 라운드가 있어야 한다").isPositive();
    }

    @Test
    void 요청_수정과_제안이_동시에_와도_수정은_항상_성공하고_열린_매칭이_남지_않는다() throws Exception {
        int proposedThenCanceled = 0;
        int updatedFirst = 0;
        for (int round = 0; round < ROUNDS; round++) {
            final int finalRound = round;
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            ExchangeRequestUpdateRequest patch = new ExchangeRequestUpdateRequest(
                    List.of(new WantSessionInput(session1, 1)),
                    List.of(new WantRangeInput("Z" + zoneSeq, "1", "1", "9", "9", "ANY", null)));   // p 의 좌석(1번)을 더 이상 원하지 않는다
            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> matchService.propose(p.userId, p.requestId, q.requestId),
                    () -> {
                        Thread.sleep((long) (finalRound % 5) * 10);   // 고정 지연: 제안이 먼저인 라운드와 수정이 먼저인 라운드가 모두 나온다
                        return requestService.update(q.userId, q.requestId, patch);
                    }));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(res -> assertThat(
                    res instanceof ExchangeMatchResponse || res instanceof BusinessRuleException
                            || res instanceof com.seatswap.dto.response.ExchangeRequestResponse)
                    .as(String.valueOf(res)).isTrue());
            assertThat(results).as("수정은 CHATTING 만 있으면 항상 성공한다 " + results)
                    .anyMatch(x -> x instanceof com.seatswap.dto.response.ExchangeRequestResponse);
            boolean proposed = results.stream().anyMatch(x -> x instanceof ExchangeMatchResponse);
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE open_flag = 1")).as("round " + round).isZero();
            if (proposed) {
                proposedThenCanceled++;
                assertThat(jdbc.queryForObject("SELECT status FROM exchange_match", String.class)).isEqualTo("CANCELED");
                assertThat(jdbc.queryForObject("SELECT canceled_by_id FROM exchange_match", Long.class)).as("시스템 취소").isNull();
            } else {
                updatedFirst++;
                assertThat(results).anyMatch(x -> x instanceof BusinessRuleException);
                assertThat(count("SELECT COUNT(*) FROM exchange_match")).isZero();
            }
            jdbc.update("DELETE FROM notification");
            jdbc.update("DELETE FROM exchange_match");
        }
        System.out.println("[update-vs-propose] proposedThenCanceled=" + proposedThenCanceled + " updatedFirst=" + updatedFirst);
        assertThat(proposedThenCanceled).as("제안이 먼저 이기는 라운드가 있어야 한다").isPositive();
        assertThat(updatedFirst).as("수정이 먼저 이기는 라운드가 있어야 한다").isPositive();
        assertInvariants();
    }

    @Test
    void 요청_수정과_예약이_동시에_와도_예약된_매칭의_요청은_수정되지_않는다() throws Exception {
        int reservedWins = 0;
        int updateWins = 0;
        for (int round = 0; round < DEACTIVATE_ROUNDS; round++) {
            final int finalRound = round;
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            ExchangeRequestUpdateRequest patch = new ExchangeRequestUpdateRequest(
                    List.of(new WantSessionInput(session1, 1)),
                    List.of(new WantRangeInput("Z" + zoneSeq, "1", "1", "1", "1", "NEG", -500)));
            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> reserveFlow(p.userId, m), () -> reserveFlow(q.userId, m),
                    () -> {
                        Thread.sleep((long) (finalRound % 8) * 6);   // 고정 지연 0~42ms: 양쪽 승부가 모두 나온다
                        return requestService.update(q.userId, q.requestId, patch);
                    }));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(res -> assertThat(
                    res instanceof ExchangeMatchResponse || res instanceof ConflictException
                            || res instanceof com.seatswap.dto.response.ExchangeRequestResponse)
                    .as(String.valueOf(res)).isTrue());
            boolean updated = results.stream().anyMatch(x -> x instanceof com.seatswap.dto.response.ExchangeRequestResponse);
            String status = jdbc.queryForObject("SELECT status FROM exchange_match WHERE id=?", String.class, m);
            if (updated) {
                updateWins++;
                assertThat(status).as("round " + round + " 수정이 성공했다면 매칭은 취소 " + results).isEqualTo("CANCELED");
                assertThat(locks()).isZero();
            } else {
                reservedWins++;
                assertThat(status).as("round " + round + " 수정이 409 였다면 매칭은 예약 " + results).isEqualTo("RESERVED");
                assertThat(results).anyMatch(x -> x instanceof ConflictException c && "ACTIVE_MATCH_EXISTS".equals(c.getDetails().get("code")));
                assertThat(locks()).isEqualTo(2);
            }
            jdbc.update("DELETE FROM exchange_ticket_lock");
            jdbc.update("DELETE FROM notification");
            jdbc.update("DELETE FROM exchange_match");
        }
        System.out.println("[update-vs-reserve] updateWins=" + updateWins + " reservedWins=" + reservedWins);
        assertThat(updateWins).as("수정이 이기는 라운드가 있어야 한다").isPositive();
        assertThat(reservedWins).as("예약이 이기는 라운드가 있어야 한다").isPositive();
        assertInvariants();
    }

    @Test
    void 같은_매칭을_건드리는_양쪽_요청의_동시_수정과_삭제도_교착없이_한번만_취소한다() throws Exception {
        for (int round = 0; round < ROUNDS * 2; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            ExchangeRequestUpdateRequest pPatch = new ExchangeRequestUpdateRequest(List.of(new WantSessionInput(session1, 1)),
                    List.of(new WantRangeInput("Z" + zoneSeq, "1", "1", "8", "8", "NEG", -100)));
            ExchangeRequestUpdateRequest qPatch = new ExchangeRequestUpdateRequest(List.of(new WantSessionInput(session1, 1)),
                    List.of(new WantRangeInput("Z" + zoneSeq, "1", "1", "9", "9", "POS", 100)));
            Callable<Object> pCall;
            Callable<Object> qCall;
            switch (round % 3) {
                case 0 -> {   // 양쪽 수정
                    pCall = () -> requestService.update(p.userId, p.requestId, pPatch);
                    qCall = () -> requestService.update(q.userId, q.requestId, qPatch);
                }
                case 1 -> {   // 양쪽 삭제
                    pCall = () -> { requestService.delete(p.userId, p.requestId); return "deleted"; };
                    qCall = () -> { requestService.delete(q.userId, q.requestId); return "deleted"; };
                }
                default -> {  // 한쪽 수정, 한쪽 삭제
                    pCall = () -> requestService.update(p.userId, p.requestId, pPatch);
                    qCall = () -> { requestService.delete(q.userId, q.requestId); return "deleted"; };
                }
            }
            List<Callable<Object>> calls = new ArrayList<>(List.of(pCall, qCall));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(res -> assertThat(
                    "deleted".equals(res) || res instanceof com.seatswap.dto.response.ExchangeRequestResponse)
                    .as(String.valueOf(res)).isTrue());
            assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id=?", String.class, m)).isEqualTo("CANCELED");
            assertThat(jdbc.queryForObject("SELECT canceled_by_id FROM exchange_match WHERE id=?", Long.class, m)).isNull();
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE open_flag = 1")).isZero();
            assertThat(locks()).isZero();
            if (round % 3 != 0) {
                assertThat(count("SELECT COUNT(*) FROM exchange_request WHERE status = 'DELETED' AND id IN ("
                        + p.requestId + "," + q.requestId + ")")).isEqualTo(round % 3 == 1 ? 2 : 1);
            }
        }
        assertInvariants();
    }

    @Test
    void 삭제와_새_요청_생성이_동시에_와도_미삭제_요청은_티켓당_하나이고_교착이_없다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            var createBody = new com.seatswap.dto.request.ExchangeRequestCreateRequest(p.ticketId,
                    List.of(new WantSessionInput(session1, 1)),
                    List.of(new WantRangeInput("Z" + zoneSeq, "1", "1", "8", "8", "X", null)));
            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> { requestService.delete(p.userId, p.requestId); return "deleted"; },
                    () -> requestService.create(p.userId, createBody),
                    () -> requestService.create(p.userId, createBody)));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(res -> assertThat(
                    "deleted".equals(res) || res instanceof com.seatswap.dto.response.ExchangeRequestResponse
                            || res instanceof ConflictException).as(String.valueOf(res)).isTrue());
            assertThat(results).contains("deleted");
            assertThat(count("SELECT COUNT(*) FROM exchange_request WHERE ticket_id = " + p.ticketId + " AND live_flag = 1"))
                    .as("round " + round + " " + results).isLessThanOrEqualTo(1);
            long created = results.stream().filter(x -> x instanceof com.seatswap.dto.response.ExchangeRequestResponse).count();
            assertThat(created).as("round " + round).isLessThanOrEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM exchange_request WHERE ticket_id = " + p.ticketId)).isEqualTo(1 + created);
        }
        assertInvariants();
    }

    @Test
    void 제안과_상대의_요청_삭제가_동시에_와도_삭제는_항상_성공하고_열린_매칭이_남지_않는다() throws Exception {
        int proposedThenCanceled = 0;
        int deletedFirst = 0;
        for (int round = 0; round < ROUNDS; round++) {
            final int finalRound = round;
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> matchService.propose(p.userId, p.requestId, q.requestId),
                    () -> {
                        Thread.sleep((long) (finalRound % 5) * 10);   // 고정 지연 0~40ms
                        requestService.delete(q.userId, q.requestId);
                        return "deleted";
                    }));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).contains("deleted");
            assertThat(jdbc.queryForObject("SELECT status FROM exchange_request WHERE id=?", String.class, q.requestId)).isEqualTo("DELETED");
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE open_flag = 1")).as("round " + round + " " + results).isZero();
            boolean proposed = results.stream().anyMatch(x -> x instanceof ExchangeMatchResponse);
            if (proposed) {
                proposedThenCanceled++;
                assertThat(jdbc.queryForObject("SELECT status FROM exchange_match", String.class)).isEqualTo("CANCELED");
                assertThat(jdbc.queryForObject("SELECT canceled_by_id FROM exchange_match", Long.class)).isNull();
            } else {
                deletedFirst++;
                assertThat(results).anyMatch(x -> x instanceof BusinessRuleException);
            }
            jdbc.update("DELETE FROM notification");
            jdbc.update("DELETE FROM exchange_match");
        }
        System.out.println("[propose-vs-delete] proposedThenCanceled=" + proposedThenCanceled + " deletedFirst=" + deletedFirst);
        assertThat(proposedThenCanceled).as("제안이 먼저 이기는 라운드가 있어야 한다").isPositive();
        assertThat(deletedFirst).as("삭제가 먼저 이기는 라운드가 있어야 한다").isPositive();
        assertInvariants();
    }

    @Test
    void 제안과_상대_티켓_내리기가_동시에_와도_내려간_티켓에_열린_매칭이_남지_않는다() throws Exception {
        int proposedThenDeactivated = 0;
        int deactivatedFirst = 0;
        for (int round = 0; round < ROUNDS; round++) {
            final int finalRound = round;
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> matchService.propose(p.userId, p.requestId, q.requestId),
                    () -> {
                        Thread.sleep((long) (finalRound % 5) * 12);   // 고정 지연 0~48ms: 제안이 먼저인 라운드와 내리기가 먼저인 라운드가 모두 나온다
                        ticketService.deactivate(q.userId, q.ticketId);
                        return "deactivated";
                    }));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).contains("deactivated");   // 잠긴 티켓이 아니므로 내리기는 항상 성공
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE open_flag = 1"))
                    .as("round " + round + " 내려진 티켓의 열린 매칭 " + results).isZero();
            boolean proposed = results.stream().anyMatch(x -> x instanceof ExchangeMatchResponse);
            if (proposed) {
                proposedThenDeactivated++;
                assertThat(jdbc.queryForObject("SELECT status FROM exchange_match", String.class)).isEqualTo("CANCELED");
                assertThat(jdbc.queryForObject("SELECT canceled_by_id FROM exchange_match", Long.class)).as("시스템 취소").isNull();
            } else {
                deactivatedFirst++;
                assertThat(results).anyMatch(x -> x instanceof BusinessRuleException);
            }
            jdbc.update("DELETE FROM notification");
            jdbc.update("DELETE FROM exchange_match");
        }
        System.out.println("[propose-vs-deactivate] proposedThenCanceled=" + proposedThenDeactivated + " deactivatedFirst=" + deactivatedFirst);
        assertThat(proposedThenDeactivated).as("제안이 먼저 이기는 라운드가 있어야 한다").isPositive();
        assertThat(deactivatedFirst).as("내리기가 먼저 이기는 라운드가 있어야 한다").isPositive();
        assertInvariants();
    }

    @Test
    void 두_사람이_동시에_제안하면_한쪽만_제안이_서고_다른쪽은_PENDING_409이며_수락으로_RESERVED_한_번() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> matchService.reserve(p.userId, m), () -> matchService.reserve(q.userId, m)));
            List<Object> results = runConcurrently(calls);

            assertThat(results.stream().filter(x -> x instanceof ExchangeMatchResponse).count()).as("round " + round + " " + results).isEqualTo(1);
            assertThat(results).filteredOn(x -> x instanceof ConflictException).singleElement().satisfies(x ->
                    assertThat(((ConflictException) x).getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_PENDING"));
            assertThat(locks()).as("제안은 티켓을 잠그지 않는다").isZero();
            long proposer = jdbc.queryForObject("SELECT reserve_proposed_by_id FROM exchange_match WHERE id = ?", Long.class, m);
            long acceptor = proposer == p.userId ? q.userId : p.userId;
            assertThat(count("SELECT COUNT(*) FROM notification WHERE match_id = " + m + " AND type = 'RESERVATION_PROPOSED'"))
                    .as("제안 알림은 성공한 제안 1건").isEqualTo(1);

            assertThat(matchService.reserveAccept(acceptor, m).status()).isEqualTo("RESERVED");
            assertThat(locks()).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE id = " + m + " AND reserved_by_id = " + proposer
                    + " AND reserved_at IS NOT NULL AND reserve_proposed_by_id IS NULL AND reserve_proposed_at IS NULL")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM notification WHERE match_id = " + m + " AND type = 'MATCH_RESERVED' AND user_id = " + proposer))
                    .as("수락 알림은 제안자에게 1건").isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM notification WHERE match_id = " + m)).as("새 제안 + 예약 제안 + 수락 총 3건").isEqualTo(3);
            matchService.unreserve(p.userId, m);
            matchService.cancel(p.userId, m);
        }
        assertInvariants();
    }

    @Test
    void 예약_취소_예약_재예약_채팅종료가_동시에_와도_교착이_없고_불변식이_유지된다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            reserveFlow(p.userId, m);
            assertThat(locks()).isEqualTo(2);

            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> matchService.unreserve(q.userId, m), () -> reserveFlow(q.userId, m),
                    () -> matchService.cancel(p.userId, m), () -> matchService.unreserve(p.userId, m)));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(res ->
                    assertThat(res instanceof ExchangeMatchResponse || res instanceof ConflictException).as(String.valueOf(res)).isTrue());
            String status = jdbc.queryForObject("SELECT status FROM exchange_match WHERE id=?", String.class, m);
            assertThat(locks()).as("round " + round + " status=" + status + " " + results).isEqualTo(status.equals("RESERVED") ? 2 : 0);
            assertInvariants();

            // 정리 후 같은 쌍으로 다시 진행: 새 매칭이 필요하면 만들고 예약까지 된다
            if (status.equals("RESERVED")) {
                matchService.unreserve(p.userId, m);
            }
            if (!status.equals("CANCELED")) {
                matchService.cancel(p.userId, m);
            }
            long m2 = matchService.propose(q.userId, q.requestId, p.requestId).id();
            assertThat(reserveFlow(q.userId, m2).status()).isEqualTo("RESERVED");
            assertThat(locks()).isEqualTo(2);
            matchService.unreserve(p.userId, m2);
            matchService.cancel(p.userId, m2);
        }
        assertInvariants();
    }

    @Test
    void 티켓_등록_취소_예약이_동시에_와도_교착이_없다() throws Exception {
        // TicketService.create 는 users 행을 잠그고, cancel 의 canceled_by FK 는 users 행에 S 잠금을 건다.
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            final int r = round;
            List<Callable<Object>> calls = new ArrayList<>(List.of(
                    () -> ticketService.create(p.userId, new com.seatswap.dto.request.TicketCreateRequest(session1, "N" + r, "1", "1")),
                    () -> matchService.cancel(p.userId, m),
                    () -> reserveFlow(q.userId, m),
                    () -> matchService.propose(q.userId, q.requestId, p.requestId)));
            Collections.shuffle(calls);
            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(res -> assertThat(
                    !(res instanceof Exception) || res instanceof ConflictException || res instanceof BusinessRuleException)
                    .as(String.valueOf(res)).isTrue());
            assertThat(results).anyMatch(res -> res instanceof com.seatswap.dto.response.TicketResponse);   // 티켓 등록은 항상 성공
        }
        assertInvariants();
    }

    @Test
    void V8_제약을_직접_SQL로_위반하면_제약_이름과_함께_거부된다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party r = party(3, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();

        // ck_exchange_match_reserved: CHATTING 인데 예약자, RESERVED 인데 예약자 없음
        assertRejected("ck_exchange_match_reserved", () -> jdbc.update(
                "UPDATE exchange_match SET reserved_by_id = ?, reserved_at = NOW(6) WHERE id = ?", p.userId, m));
        assertRejected("ck_exchange_match_reserved", () -> jdbc.update("UPDATE exchange_match SET status = 'RESERVED' WHERE id = ?", m));
        // ck_exchange_match_reserved_by_party: 예약자는 참여자여야 한다
        assertRejected("ck_exchange_match_reserved_by_party", () -> jdbc.update(
                "UPDATE exchange_match SET status = 'RESERVED', reserved_by_id = ?, reserved_at = NOW(6) WHERE id = ?", r.userId, m));
        // ck_exchange_match_completed: CHATTING 인데 수락 표시, COMPLETED 인데 수락 표시 없음
        assertRejected("ck_exchange_match_completed", () -> jdbc.update("UPDATE exchange_match SET a_completed_at = NOW(6) WHERE id = ?", m));
        assertRejected("ck_exchange_match_completed", () -> jdbc.update("UPDATE exchange_match SET status = 'COMPLETED' WHERE id = ?", m));

        // RESERVED 를 벗어나는 UPDATE 가 reserved_* 를 비우지 않으면 DB 가 거부한다(안전망)
        reserveFlow(p.userId, m);
        assertRejected("ck_exchange_match_reserved", () -> jdbc.update("UPDATE exchange_match SET status = 'CHATTING' WHERE id = ?", m));
        assertRejected("ck_exchange_match_reserved", () -> jdbc.update(
                "UPDATE exchange_match SET status = 'CANCELED', canceled_at = NOW(6) WHERE id = ?", m));
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id=?", String.class, m)).isEqualTo("RESERVED");
        // 올바른 형태의 되돌림은 통과한다
        assertThat(jdbc.update("UPDATE exchange_match SET status = 'CHATTING', reserved_by_id = NULL, reserved_at = NULL WHERE id = ?", m)).isEqualTo(1);
        // 레거시 컬럼은 남아 있으나 새 코드는 쓰지 않는다
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE a_reserved_at IS NOT NULL OR b_reserved_at IS NOT NULL")).isZero();
    }

    @Test
    void V5_V6_제약을_직접_INSERT로_위반하면_제약_이름과_함께_거부된다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        String cols = "(request_a_id, request_b_id, ticket_a_id, ticket_b_id, user_a_id, user_b_id, status, canceled_at, canceled_by_id, "
                + "created_at, updated_at, a_extra_type, b_extra_type)";
        String vals = "VALUES (?,?,?,?,?,?,?,?,?,NOW(6),NOW(6),'ANY','ANY')";
        // 상태 값
        assertRejected("ck_exchange_match_status", () -> jdbc.update("INSERT INTO exchange_match " + cols + " " + vals,
                p.requestId, q.requestId, p.ticketId, q.ticketId, p.userId, q.userId, "CLOSED", null, null));
        // 같은 요청끼리 / 같은 티켓끼리
        assertRejected("ck_exchange_match_distinct_requests", () -> jdbc.update("INSERT INTO exchange_match " + cols + " " + vals,
                p.requestId, p.requestId, p.ticketId, q.ticketId, p.userId, q.userId, "CHATTING", null, null));
        assertRejected("ck_exchange_match_distinct_tickets", () -> jdbc.update("INSERT INTO exchange_match " + cols + " " + vals,
                p.requestId, q.requestId, p.ticketId, p.ticketId, p.userId, q.userId, "CHATTING", null, null));
        // CANCELED 일관성 (canceled_at 없음 / 열린 매칭에 canceled_by)
        assertRejected("ck_exchange_match_canceled", () -> jdbc.update("INSERT INTO exchange_match " + cols + " " + vals,
                p.requestId, q.requestId, p.ticketId, q.ticketId, p.userId, q.userId, "CANCELED", null, null));
        assertRejected("ck_exchange_match_canceled", () -> jdbc.update("INSERT INTO exchange_match " + cols + " " + vals,
                p.requestId, q.requestId, p.ticketId, q.ticketId, p.userId, q.userId, "CHATTING", null, p.userId));
        // FK: 없는 요청
        assertRejected("fk_exchange_match_request_a", () -> jdbc.update("INSERT INTO exchange_match " + cols + " " + vals,
                999999L, q.requestId, p.ticketId, q.ticketId, p.userId, q.userId, "CHATTING", null, null));
        // UNIQUE: 같은 쌍의 열린 매칭 (반대 방향 포함)
        jdbc.update("INSERT INTO exchange_match " + cols + " " + vals,
                p.requestId, q.requestId, p.ticketId, q.ticketId, p.userId, q.userId, "CHATTING", null, null);
        assertRejected("uk_exchange_match_open_pair", () -> jdbc.update("INSERT INTO exchange_match " + cols + " " + vals,
                q.requestId, p.requestId, q.ticketId, p.ticketId, q.userId, p.userId, "CHATTING", null, null));
        // 잠금 PK: 같은 티켓을 다른 매칭이 잠글 수 없다
        long m = jdbc.queryForObject("SELECT id FROM exchange_match", Long.class);
        jdbc.update("INSERT INTO exchange_ticket_lock (ticket_id, match_id, created_at) VALUES (?,?,NOW(6))", p.ticketId, m);
        assertRejected("PRIMARY", () -> jdbc.update("INSERT INTO exchange_ticket_lock (ticket_id, match_id, created_at) VALUES (?,?,NOW(6))", p.ticketId, m));
        assertRejected("fk_exchange_ticket_lock_match", () -> jdbc.update("INSERT INTO exchange_ticket_lock (ticket_id, match_id, created_at) VALUES (?,?,NOW(6))", q.ticketId, 999999L));
        assertThat(count("SELECT COUNT(*) FROM exchange_match")).isEqualTo(1);
    }

    private static void assertRejected(String constraint, Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining(constraint);
    }

    // ------------------------------------------------------------------ 알림 쓰기 경로 (V10)

    @Test
    void 알림은_트랜잭션_없이는_만들_수_없고_롤백되면_함께_사라진다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        long before = count("SELECT COUNT(*) FROM notification");   // 제안 알림 1건
        assertThat(before).isEqualTo(1);

        assertThatThrownBy(() -> notificationService.notify(q.userId, com.seatswap.domain.NotificationType.MATCH_RESERVED, m, null))
                .as("MANDATORY: 호출자 트랜잭션이 없으면 거부").isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            notificationService.notify(q.userId, com.seatswap.domain.NotificationType.MATCH_RESERVED, m, null);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("SELECT COUNT(*) FROM notification")).as("롤백 뒤 알림 증가 없음").isEqualTo(before);

        // 상태 변경 서비스 경로에서 예외가 나면(예약 중 취소 409) 알림도 없다
        reserveFlow(p.userId, m);
        long afterReserve = count("SELECT COUNT(*) FROM notification");
        assertThat(afterReserve).as("새 제안 + 예약 제안 + 수락 = 3건").isEqualTo(3);
        assertThatThrownBy(() -> matchService.cancel(q.userId, m)).isInstanceOf(ConflictException.class);
        assertThat(count("SELECT COUNT(*) FROM notification")).isEqualTo(afterReserve);
    }

    @Test
    void MATCH_ALREADY_OPEN_409와_멱등_호출은_알림을_만들지_않는다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        assertThat(count("SELECT COUNT(*) FROM notification")).isEqualTo(1);

        assertThatThrownBy(() -> matchService.propose(p.userId, p.requestId, q.requestId))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "MATCH_ALREADY_OPEN"));
        assertThatThrownBy(() -> matchService.propose(q.userId, q.requestId, p.requestId)).isInstanceOf(ConflictException.class);
        matchService.unreserve(p.userId, m);   // CHATTING 에서 멱등
        assertThat(count("SELECT COUNT(*) FROM notification")).as("409 와 멱등 unreserve 는 0건").isEqualTo(1);
        reserveFlow(p.userId, m);
        matchService.reserveAccept(q.userId, m);     // RESERVED 에서 수락자의 재호출은 멱등
        matchService.reserveDecline(q.userId, m);    // 제안이 없는 거절·철회도 멱등
        matchService.reserveWithdraw(p.userId, m);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'MATCH_RESERVED'")).as("멱등 호출은 추가 알림 없음").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'RESERVATION_PROPOSED'")).isEqualTo(1);
    }

    @Test
    void 예약_예약취소를_3번_반복하면_알림_9행이고_수신자는_호출자가_아닌_쪽이며_유니크_위반이_없다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();

        for (int i = 0; i < 3; i++) {
            reserveFlow(p.userId, m);     // 제안(수신자 q: RESERVATION_PROPOSED) -> 수락(수신자 p: MATCH_RESERVED)
            matchService.unreserve(q.userId, m);   // 수신자 p
        }

        assertThat(count("SELECT COUNT(*) FROM notification WHERE match_id = " + m + " AND type IN ('RESERVATION_PROPOSED','MATCH_RESERVED','RESERVATION_CANCELED')"))
                .isEqualTo(9);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'RESERVATION_PROPOSED' AND user_id = " + q.userId + " AND dedup_key IS NULL"))
                .isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'MATCH_RESERVED' AND user_id = " + p.userId + " AND dedup_key IS NULL"))
                .isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'RESERVATION_CANCELED' AND user_id = " + p.userId + " AND dedup_key IS NULL"))
                .isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'PROPOSAL_RECEIVED' AND user_id = " + q.userId
                + " AND dedup_key = 'PROPOSAL_RECEIVED:" + m + "' AND read_at IS NULL AND unread_user_id = " + q.userId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE read_at IS NULL AND unread_user_id IS NULL")).isZero();
        jdbc.update("UPDATE notification SET read_at = NOW(6) WHERE user_id = ?", q.userId);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = " + q.userId + " AND unread_user_id IS NOT NULL"))
                .as("읽으면 생성 컬럼이 NULL").isZero();
        assertInvariants();
    }

    // ------------------------------------------------------------------ 도우미

    private void assertInvariants() {
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN exchange_match m ON m.id = l.match_id WHERE m.status <> 'RESERVED'"))
                .as("RESERVED 가 아닌 매칭에 잠금이 남았다").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match m WHERE m.status = 'RESERVED' AND "
                + "(SELECT COUNT(*) FROM exchange_ticket_lock l WHERE l.match_id = m.id) <> 2"))
                .as("RESERVED 매칭의 잠금이 2행이 아니다").isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN ticket t ON t.id = l.ticket_id WHERE t.status <> 'ACTIVE'"))
                .as("INACTIVE 티켓에 잠금이 남았다").isZero();
        assertThat(count("SELECT COUNT(*) FROM (SELECT request_low_id, request_high_id FROM exchange_match WHERE open_flag = 1 "
                + "GROUP BY request_low_id, request_high_id HAVING COUNT(*) > 1) d")).isZero();
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private long locks() {
        return count("SELECT COUNT(*) FROM exchange_ticket_lock");
    }

    /** 모든 호출을 같은 순간에 출발시키고 결과(응답 또는 던진 예외)를 모은다. 교착·락 대기 초과는 테스트 실패로 본다. */
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
                if (r instanceof org.springframework.dao.PessimisticLockingFailureException
                        || r instanceof org.springframework.dao.DataAccessException) {
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
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, 'x', 'USER')",
                "u" + seq + "@t.com", nickname);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long session(LocalDateTime startsAt) {
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)", startsAt, performanceId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 사용자 n 번째(고정 사용자 풀: 같은 n 은 같은 사용자)의 티켓·요청. 열은 "1", 번은 사용자 번호(1~3)로 둔다. */
    private final java.util.Map<Integer, Long> users = new java.util.HashMap<>();

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

    /** 예약 제안 -> 상대 수락으로 RESERVED 를 만든다(V15). */
    private com.seatswap.dto.response.ExchangeMatchResponse reserveFlow(long proposerId, long matchId) {
        return com.seatswap.testsupport.ReserveFlow.reserve(matchService, jdbc, proposerId, matchId);
    }
}
