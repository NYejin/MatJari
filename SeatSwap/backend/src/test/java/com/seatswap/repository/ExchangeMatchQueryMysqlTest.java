package com.seatswap.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.seatswap.dto.response.ExchangeMatchResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.service.ExchangeMatchQueryService;
import com.seatswap.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.flywaydb.core.Flyway;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 내 매칭 조회 SQL 의 실제 MySQL 테스트 (조인 정확성, 역할·상태 필터, 정렬·페이징, 개인정보 비노출, 쿼리 수).
 * 환경변수 SEATSWAP_IT_* 가 있을 때만 돈다(없으면 건너뜀, SEATSWAP_IT_REQUIRED/CI 면 실패). 안전장치는 RealMysqlSupport.
 * 한 연결(SingleConnectionDataSource)을 쓰므로 SHOW SESSION STATUS 의 Com_select 로 SELECT 횟수를 센다.
 *
 * 시나리오 (나 = u1):
 *   m1 a=r1(u1) b=r2(u2) CHATTING 10:00 / m2 a=r3(u3) b=r1(u1) RESERVED 11:00
 *   m3 a=r4(u1) b=r3(u3) CANCELED(u3) 09:00 / m4 a=r2(u2) b=r3(u3) COMPLETED (나는 비참여)
 */
@ExtendWith(RealMysqlSupport.Condition.class)
class ExchangeMatchQueryMysqlTest {

    private static SingleConnectionDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ExchangeMatchQueryRepository repository;
    private static ExchangeMatchQueryService service;

    private long u1, u2, u3;
    private long m1, m2, m3, m4;
    private long s1, s2;
    private long t1, t2, t3, t4;
    private long r1, r2, r3, r4;

    @BeforeAll
    static void connect() {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.assertSafe();
        Flyway flyway = Flyway.configure().dataSource(RealMysqlSupport.url(), RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        dataSource = new SingleConnectionDataSource(RealMysqlSupport.url(), RealMysqlSupport.user(), RealMysqlSupport.password(), true);
        jdbc = new JdbcTemplate(dataSource);
        repository = new ExchangeMatchQueryRepository(jdbc);
        service = new ExchangeMatchQueryService(repository);
    }

    @BeforeEach
    void seed() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("chat_message", "chat_read_state", "notification", "exchange_ticket_lock", "exchange_match", "exchange_want_seat", "exchange_want_session",
                "exchange_want_range", "exchange_request", "ticket", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");

        u1 = user("나");
        u2 = user("상대2");
        u3 = user("상대3");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', 't', 'v', ?)", u1);
        long perf = id();
        s1 = session(perf, LocalDateTime.of(2026, 11, 1, 19, 0));
        s2 = session(perf, LocalDateTime.of(2026, 11, 2, 19, 0));
        t1 = ticket(u1, s1, "A구역", "1", "1");
        t2 = ticket(u2, s1, "A구역", "1", "2");
        t3 = ticket(u3, s2, "B구역", "2", "3");
        t4 = ticket(u1, s2, "C구역", "4", "5");
        r1 = request(t1, "X", null);
        r2 = request(t2, "POS", 30000);
        r3 = request(t3, "NEG", -10000);
        r4 = request(t4, "ANY", null);
        m1 = match(r1, r2, t1, t2, u1, u2, "CHATTING", null, "2026-10-08 10:00:00");
        m2 = match(r3, r1, t3, t1, u3, u1, "RESERVED", null, "2026-10-08 11:00:00");
        m3 = match(r4, r3, t4, t3, u1, u3, "CANCELED", u3, "2026-10-08 09:00:00");
        m4 = match(r2, r3, t2, t3, u2, u3, "COMPLETED", null, "2026-10-08 12:00:00");
    }

    // ------------------------------------------------------------------ 조인 정확성

    @Test
    void 보낸_매칭은_내가_a측이고_좌석_회차_추가금_닉네임이_상대_기준으로_맞게_나온다() {
        ExchangeMatchResponse r = service.findOne(u1, m1);

        assertThat(r.id()).isEqualTo(m1);
        assertThat(r.status()).isEqualTo("CHATTING");
        assertThat(r.mySide()).isEqualTo("A");
        assertThat(r.role()).isEqualTo("SENT");
        assertThat(r.myRequestId()).isEqualTo(r1);
        assertThat(r.myTicketId()).isEqualTo(t1);
        assertThat(r.mySeat()).isEqualTo(new ExchangeMatchResponse.Seat("A구역", "1", "1", s1, LocalDateTime.of(2026, 11, 1, 19, 0)));
        assertThat(r.counterpartRequestId()).isEqualTo(r2);
        assertThat(r.counterpartTicketId()).isEqualTo(t2);
        assertThat(r.counterpartSeat()).isEqualTo(new ExchangeMatchResponse.Seat("A구역", "1", "2", s1, LocalDateTime.of(2026, 11, 1, 19, 0)));
        assertThat(r.counterpartNickname()).isEqualTo("상대2");
        assertThat(r.myExtraType()).isEqualTo("X");
        assertThat(r.myExtraAmount()).isNull();
        assertThat(r.counterpartExtraType()).isEqualTo("POS");
        assertThat(r.counterpartExtraAmount()).isEqualTo(30000);
        assertThat(r.canceledBy()).isNull();
        assertThat(r.createdAt()).isNotNull();
        assertThat(r.updatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 0, 0));
    }

    @Test
    void 추가금은_매칭_스냅샷에서_읽고_요청이_삭제되어도_기록과_삭제_표시가_남는다() {
        // 요청 r2 를 소프트 삭제하고(하위 행도 서비스처럼 정리), 매칭 m1 의 스냅샷은 그대로여야 한다
        jdbc.update("UPDATE exchange_request SET status='DELETED', deleted_at=NOW(6) WHERE id=?", r2);

        ExchangeMatchResponse mine = service.findOne(u1, m1);
        ExchangeMatchResponse theirs = service.findOne(u2, m1);

        assertThat(mine.counterpartExtraType()).isEqualTo("POS");
        assertThat(mine.counterpartExtraAmount()).isEqualTo(30000);
        assertThat(mine.myRequestDeleted()).isFalse();
        assertThat(mine.counterpartRequestDeleted()).isTrue();
        // 상대(b) 입장에서는 방향이 뒤집힌다
        assertThat(theirs.myRequestDeleted()).isTrue();
        assertThat(theirs.counterpartRequestDeleted()).isFalse();
        assertThat(theirs.myExtraType()).isEqualTo("POS");
        assertThat(theirs.counterpartExtraType()).isEqualTo("X");
        // 내 매칭 목록에도 삭제된 요청의 매칭이 남는다
        assertThat(ids(service.findMine(u1, "ALL", null, 0, 20))).contains(m1);
    }

    @Test
    void 요청_행의_추가금_변화와_무관하게_스냅샷_값이_나온다() {
        // 같은 요청의 범위를 지우고 다른 값으로 다시 만들어도(수정) 이미 만들어진 매칭의 값은 변하지 않는다
        jdbc.update("DELETE FROM exchange_want_seat WHERE request_id IN (?,?)", r1, r2);
        jdbc.update("DELETE FROM exchange_want_range WHERE request_id IN (?,?)", r1, r2);

        ExchangeMatchResponse r = service.findOne(u1, m1);

        assertThat(r.myExtraType()).isEqualTo("X");
        assertThat(r.counterpartExtraType()).isEqualTo("POS");
        assertThat(r.counterpartExtraAmount()).isEqualTo(30000);
        assertThat(r.myRequestDeleted()).isFalse();
    }

    @Test
    void 받은_매칭은_내가_b측이고_양쪽_정보가_뒤집혀_나온다() {
        ExchangeMatchResponse r = service.findOne(u1, m2);

        assertThat(r.mySide()).isEqualTo("B");
        assertThat(r.role()).isEqualTo("RECEIVED");
        assertThat(r.status()).isEqualTo("RESERVED");
        assertThat(r.myRequestId()).isEqualTo(r1);
        assertThat(r.myTicketId()).isEqualTo(t1);
        assertThat(r.mySeat().zone()).isEqualTo("A구역");
        assertThat(r.counterpartRequestId()).isEqualTo(r3);
        assertThat(r.counterpartTicketId()).isEqualTo(t3);
        assertThat(r.counterpartSeat()).isEqualTo(new ExchangeMatchResponse.Seat("B구역", "2", "3", s2, LocalDateTime.of(2026, 11, 2, 19, 0)));
        assertThat(r.counterpartNickname()).isEqualTo("상대3");
        assertThat(r.myExtraType()).isEqualTo("X");
        assertThat(r.counterpartExtraType()).isEqualTo("NEG");
        assertThat(r.counterpartExtraAmount()).isEqualTo(-10000);
    }

    @Test
    void 예약자_예약_시각_수락_표시와_취소_주체가_호출자_기준으로_바뀐다() {
        // m2: RESERVED, 예약자는 a측(u3), 예약 시각 10:30
        ExchangeMatchResponse byReserver = service.findOne(u3, m2);
        ExchangeMatchResponse byOther = service.findOne(u1, m2);
        assertThat(byReserver.reservedBy()).isEqualTo("ME");
        assertThat(byOther.reservedBy()).isEqualTo("COUNTERPART");
        assertThat(byReserver.reservedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 30));
        assertThat(byOther.reservedAt()).isEqualTo(LocalDateTime.of(2026, 10, 8, 10, 30));
        // 예약 전(CHATTING)·취소·완료 매칭에는 예약자가 없다
        ExchangeMatchResponse chatting = service.findOne(u1, m1);
        assertThat(chatting.reservedBy()).isNull();
        assertThat(chatting.reservedAt()).isNull();
        // 목록에서도 호출자 기준으로 갈린다 (m2: 예약자 u3 = a측, u1 = b측)
        assertThat(service.findMine(u3, "SENT", List.of("RESERVED"), 0, 20).content())
                .extracting(ExchangeMatchResponse::reservedBy).containsExactly("ME");
        assertThat(service.findMine(u1, "RECEIVED", List.of("RESERVED"), 0, 20).content())
                .extracting(ExchangeMatchResponse::reservedBy).containsExactly("COUNTERPART");
        assertThat(service.findOne(u1, m3).reservedBy()).isNull();
        assertThat(service.findOne(u2, m4).reservedBy()).isNull();
        // 교환 수락 표시(a/b_completed_at)는 호출자 기준 불리언: m4 는 양쪽 수락, m2 는 아직 없음
        assertThat(service.findOne(u2, m4).myAccepted()).isTrue();
        assertThat(service.findOne(u2, m4).counterpartAccepted()).isTrue();
        assertThat(byReserver.myAccepted()).isFalse();
        assertThat(byReserver.counterpartAccepted()).isFalse();
        // RESERVED 중 한쪽(b측 u1)만 수락한 상태를 만들면 호출자 기준으로 뒤집혀 보인다
        jdbc.update("UPDATE exchange_match SET b_completed_at = '2026-10-08 10:40:00' WHERE id = ?", m2);
        assertThat(service.findOne(u1, m2).myAccepted()).isTrue();
        assertThat(service.findOne(u1, m2).counterpartAccepted()).isFalse();
        assertThat(service.findOne(u3, m2).myAccepted()).isFalse();
        assertThat(service.findOne(u3, m2).counterpartAccepted()).isTrue();
        assertThat(service.findOne(u2, m1).mySide()).isEqualTo("B");

        assertThat(service.findOne(u1, m3).canceledBy()).isEqualTo("COUNTERPART");
        assertThat(service.findOne(u3, m3).canceledBy()).isEqualTo("ME");
        jdbc.update("UPDATE exchange_match SET canceled_by_id = NULL WHERE id = ?", m3);
        assertThat(service.findOne(u1, m3).canceledBy()).isEqualTo("SYSTEM");
        assertThat(service.findOne(u1, m3).canceledAt()).isNotNull();
    }

    @Test
    void 비참여자와_없는_매칭은_똑같이_404() {
        assertThatThrownBy(() -> service.findOne(u1, m4)).isInstanceOf(NotFoundException.class)
                .hasMessage("매칭을 찾을 수 없습니다.");
        assertThatThrownBy(() -> service.findOne(u1, 999999L)).isInstanceOf(NotFoundException.class)
                .hasMessage("매칭을 찾을 수 없습니다.");
    }

    // ------------------------------------------------------------------ 필터·정렬·페이징

    @Test
    void 역할_필터_보낸_받은_전체() {
        assertThat(ids(service.findMine(u1, "SENT", null, 0, 20))).containsExactly(m1, m3);
        assertThat(ids(service.findMine(u1, "RECEIVED", null, 0, 20))).containsExactly(m2);
        assertThat(ids(service.findMine(u1, "ALL", null, 0, 20))).containsExactly(m2, m1, m3);
        assertThat(ids(service.findMine(u1, null, null, 0, 20))).as("role 기본값은 ALL").containsExactly(m2, m1, m3);
        assertThat(ids(service.findMine(u2, "SENT", null, 0, 20))).containsExactly(m4);
        assertThat(ids(service.findMine(u2, "RECEIVED", null, 0, 20))).containsExactly(m1);
        service.findMine(u1, "SENT", null, 0, 20).content().forEach(r -> assertThat(r.role()).isEqualTo("SENT"));
        service.findMine(u1, "RECEIVED", null, 0, 20).content().forEach(r -> assertThat(r.role()).isEqualTo("RECEIVED"));
    }

    @Test
    void 상태_필터는_하나_여러개_쉼표를_지원하고_역할과_조합된다() {
        assertThat(ids(service.findMine(u1, null, List.of("CHATTING"), 0, 20))).containsExactly(m1);
        assertThat(ids(service.findMine(u1, null, List.of("CHATTING", "CANCELED"), 0, 20))).containsExactly(m1, m3);
        assertThat(ids(service.findMine(u1, null, List.of("RESERVED,CHATTING"), 0, 20))).containsExactly(m2, m1);
        assertThat(ids(service.findMine(u1, "SENT", List.of("RESERVED"), 0, 20))).isEmpty();
        assertThat(ids(service.findMine(u1, "RECEIVED", List.of("RESERVED"), 0, 20))).containsExactly(m2);
        assertThat(ids(service.findMine(u1, null, List.of("COMPLETED"), 0, 20))).as("비참여 COMPLETED 는 안 보인다").isEmpty();
        assertThat(service.findMine(u1, null, List.of("COMPLETED"), 0, 20).totalElements()).isZero();
    }

    @Test
    void 잘못된_필터와_페이지는_400_필드오류() {
        assertThatThrownBy(() -> service.findMine(u1, "BOTH", null, 0, 20))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsKey("role"));
        assertThatThrownBy(() -> service.findMine(u1, null, List.of("DONE"), 0, 20))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsKey("status"));
        assertThatThrownBy(() -> service.findMine(u1, null, null, -1, 20))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsKey("page"));
    }

    @Test
    void 정렬은_updated_at_내림차순이고_같으면_id_내림차순() {
        jdbc.update("UPDATE exchange_match SET updated_at = '2026-10-08 13:00:00' WHERE id IN (?, ?)", m1, m3);
        // m1, m3 동률(13:00) -> id 큰 m3 먼저, m2(11:00) 마지막
        assertThat(ids(service.findMine(u1, null, null, 0, 20))).containsExactly(m3, m1, m2);
    }

    @Test
    void 페이징은_총계와_페이지를_맞게_돌려준다() {
        PageResponse<ExchangeMatchResponse> p0 = service.findMine(u1, null, null, 0, 2);
        PageResponse<ExchangeMatchResponse> p1 = service.findMine(u1, null, null, 1, 2);
        PageResponse<ExchangeMatchResponse> p2 = service.findMine(u1, null, null, 2, 2);

        assertThat(p0.totalElements()).isEqualTo(3);
        assertThat(p0.totalPages()).isEqualTo(2);
        assertThat(p0.size()).isEqualTo(2);
        assertThat(ids(p0)).containsExactly(m2, m1);
        assertThat(ids(p1)).containsExactly(m3);
        assertThat(p1.page()).isEqualTo(1);
        assertThat(p2.content()).isEmpty();
        assertThat(service.findMine(u1, null, null, 0, 1000).size()).as("size 상한 100").isEqualTo(100);
        assertThatThrownBy(() -> service.findMine(u1, null, null, 0, 0))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsKey("size"));
        assertThatThrownBy(() -> service.findMine(u1, null, null, 0, -5)).isInstanceOf(FieldValidationException.class);
    }

    // ------------------------------------------------------------------ 개인정보·쿼리 수

    @Test
    void 응답_JSON에_상대_이메일_등_개인정보가_없다() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(service.findMine(u1, null, null, 0, 20))
                + mapper.writeValueAsString(service.findOne(u1, m1));

        assertThat(json).doesNotContain("@t.com").doesNotContainIgnoringCase("email").doesNotContainIgnoringCase("password")
                .doesNotContain("userId").doesNotContain("UserId");
        assertThat(json).contains("상대2", "상대3");
    }

    @Test
    void 목록은_행_수와_무관하게_SELECT_2회_단건은_1회() {
        // 매칭을 더 늘려도 쿼리 수는 그대로여야 한다(N+1 없음).
        for (int i = 0; i < 30; i++) {
            long other = user("추가" + i);
            long t = ticket(other, s1, "Z" + i, "9", "9");
            long r = request(t, "ANY", null);
            long tt = ticket(u1, s2, "Y" + i, "8", "8");
            long rr = request(tt, "ANY", null);
            match(rr, r, tt, t, u1, other, "CHATTING", null, "2026-10-07 10:00:00");
        }
        long before = comSelect();
        PageResponse<ExchangeMatchResponse> page = service.findMine(u1, null, null, 0, 20);
        long afterList = comSelect();
        service.findOne(u1, m1);
        long afterOne = comSelect();

        assertThat(page.content()).hasSize(20);
        assertThat(page.totalElements()).isEqualTo(33);
        assertThat(afterList - before).as("COUNT 1 + 목록 1").isEqualTo(2);
        assertThat(afterOne - afterList).isEqualTo(1);
        // comSelect 자신의 SHOW 는 Com_select 로 세지 않는다.
    }

    @Test
    void 실행계획은_매칭_테이블_전체_스캔_없이_사용자_인덱스를_쓴다() {
        // 사용자 수가 많은 상황을 흉내내기 위해 다른 사용자의 매칭을 대량으로 넣고 통계를 갱신한다.
        for (int i = 0; i < 400; i++) {
            long a = user("bulk-a" + i);
            long b = user("bulk-b" + i);
            long ta = ticket(a, s1, "BZ" + i, "7", "7");
            long tb = ticket(b, s2, "BY" + i, "6", "6");
            match(request(ta, "ANY", null), request(tb, "ANY", null), ta, tb, a, b, "CHATTING", null, "2026-10-06 10:00:00");
        }
        jdbc.execute("ANALYZE TABLE exchange_match, ticket, users, exchange_request, performance_session");
        for (ExchangeMatchQueryRepository.Role role : ExchangeMatchQueryRepository.Role.values()) {
            String sql = ExchangeMatchQueryRepository.listSql(role, 0);
            Object[] params = role == ExchangeMatchQueryRepository.Role.ALL ? new Object[]{u1, u1, 20, 0} : new Object[]{u1, 20, 0};
            List<java.util.Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + sql, params);
            java.util.Map<String, Object> first = plan.stream().filter(r -> "m".equals(r.get("table"))).findFirst().orElseThrow();
            assertThat(String.valueOf(first.get("type"))).as(role + " " + plan).isNotEqualTo("ALL");
            plan.stream().filter(r -> !"m".equals(r.get("table"))).forEach(r ->
                    assertThat(String.valueOf(r.get("type"))).as(role + " " + r).isIn("eq_ref", "const"));
        }
    }

    // ------------------------------------------------------------------ 도우미

    private static List<Long> ids(PageResponse<ExchangeMatchResponse> page) {
        return page.content().stream().map(ExchangeMatchResponse::id).toList();
    }

    private long comSelect() {
        return jdbc.query("SHOW SESSION STATUS LIKE 'Com_select'", rs -> {
            rs.next();
            return rs.getLong(2);
        });
    }

    private long id() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long user(String nickname) {
        String email = "u" + System.nanoTime() + "@t.com";
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, 'x', 'USER')", email, nickname);
        return id();
    }

    private long session(long performanceId, LocalDateTime startsAt) {
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)", startsAt, performanceId);
        return id();
    }

    private long ticket(long userId, long sessionId, String zone, String row, String col) {
        jdbc.update("INSERT INTO ticket (performance_session_id, user_id, zone_label, zone_key, row_label, row_key, "
                + "col_label, col_key, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',NOW(6),NOW(6))",
                sessionId, userId, zone, zone, row, row, col, col);
        return id();
    }

    /** 요청별로 "그 요청이 매칭에서 쓴 추가금"을 기억해 두었다가 match() 가 스냅샷 컬럼에 넣는다(요청 행에는 추가금이 없다). */
    private final java.util.Map<Long, Object[]> requestExtras = new java.util.HashMap<>();

    private long request(long ticketId, String extraType, Integer amount) {
        jdbc.update("INSERT INTO exchange_request (ticket_id, status, created_at, updated_at) "
                + "VALUES (?, 'OPEN', NOW(6), NOW(6))", ticketId);
        long id = id();
        requestExtras.put(id, new Object[]{extraType, amount});
        return id;
    }

    private long match(long ra, long rb, long ta, long tb, long ua, long ub, String status, Long canceledBy, String updatedAt) {
        boolean canceled = "CANCELED".equals(status);
        boolean reserved = "RESERVED".equals(status);
        boolean completed = "COMPLETED".equals(status);
        // V8 CHECK: RESERVED 는 예약자(a측)와 시각 필수, COMPLETED 는 양쪽 수락 시각 필수
        jdbc.update("INSERT INTO exchange_match (request_a_id, request_b_id, ticket_a_id, ticket_b_id, user_a_id, user_b_id, status, "
                + "canceled_by_id, canceled_at, reserved_by_id, reserved_at, a_completed_at, b_completed_at, "
                + "created_at, updated_at, a_extra_type, a_extra_amount, b_extra_type, b_extra_amount) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?, '2026-10-08 08:00:00', ?, ?,?,?,?)",
                ra, rb, ta, tb, ua, ub, status, canceledBy, canceled ? "2026-10-08 09:00:00" : null,
                reserved ? ua : null, reserved ? "2026-10-08 10:30:00" : null,
                completed ? "2026-10-08 11:00:00" : null, completed ? "2026-10-08 11:30:00" : null, updatedAt,
                requestExtras.get(ra)[0], requestExtras.get(ra)[1], requestExtras.get(rb)[0], requestExtras.get(rb)[1]);
        return id();
    }
}
