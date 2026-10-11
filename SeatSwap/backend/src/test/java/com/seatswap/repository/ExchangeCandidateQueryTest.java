package com.seatswap.repository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 후보 조회 네이티브 SQL을 실제 MySQL에서 검증한다 (H2 등으로는 생성 컬럼·utf8mb4_bin 동작이 달라 의미가 없다).
 * 기본(./gradlew test)에서는 환경변수가 없어 건너뛴다. 실행하려면 임시 MySQL 컨테이너를 띄우고 환경변수를 준다:
 *   SEATSWAP_IT_JDBC_URL=jdbc:mysql://localhost:13307/seatswap_it  SEATSWAP_IT_USER=root  SEATSWAP_IT_PASSWORD=tmp
 * 안전장치: DB 이름이 `_it` 로 끝나야 하며(개발 DB `seatswap` 보호) 실행할 때마다 Flyway clean 후 V1~V7을 새로 적용한다.
 */
class ExchangeCandidateQueryTest {

    private static final LocalDateTime TODAY = LocalDateTime.of(2026, 10, 10, 0, 0);
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 1, 19, 0);

    private static SingleConnectionDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ExchangeCandidateRepository repository;

    private long seq;
    private long performanceId;
    private long s1;
    private long s2;
    private long s3;

    private static String url;
    private static String user;
    private static String password;

    /** 환경변수가 있어야 도는 테스트다. SEATSWAP_IT_REQUIRED=true 또는 CI 환경변수가 있으면 건너뛰지 않고 실패한다. */
    private static boolean required() {
        return "true".equalsIgnoreCase(System.getenv("SEATSWAP_IT_REQUIRED")) || System.getenv("CI") != null;
    }

    private static void skipOrFail(String message) {
        if (required()) {
            fail("[SEATSWAP_IT_REQUIRED/CI] " + message);
        }
        String line = "[SKIPPED ExchangeCandidateQueryTest] " + message;
        System.out.println(line);
        assumeTrue(false, line);
    }

    /** jdbc:mysql://host[:port]/db[?opts] 에서 (host, db)를 뽑는다(쿼리스트링 제외). 형식이 다르면 null. */
    static String[] parseHostAndDb(String jdbcUrl) {
        Matcher m = Pattern.compile("^jdbc:mysql://([^/:?]+)(?::\\d+)?/([^/?]+)(?:\\?.*)?$").matcher(jdbcUrl.trim());
        return m.matches() ? new String[]{m.group(1), m.group(2)} : null;
    }

    @BeforeAll
    static void connect() throws Exception {
        url = System.getenv("SEATSWAP_IT_JDBC_URL");
        if (url == null || url.isBlank()) {
            skipOrFail("SEATSWAP_IT_JDBC_URL 이 없어 실제 MySQL 후보 SQL 테스트 전체를 건너뜁니다(README '매칭 후보 조회 검증' 참고).");
            return;
        }
        // 안전장치 1: 환경변수가 있는데 안전장치에 걸리면 skip 이 아니라 실패한다.
        String[] parts = parseHostAndDb(url);
        if (parts == null) {
            fail("SEATSWAP_IT_JDBC_URL 형식을 해석할 수 없습니다(jdbc:mysql://localhost:port/name_it): " + url);
        }
        if (!(parts[0].equals("localhost") || parts[0].equals("127.0.0.1"))) {
            fail("안전장치: 호스트가 localhost/127.0.0.1 이 아닙니다: " + parts[0]);
        }
        if (!parts[1].endsWith("_it")) {
            fail("안전장치: DB 이름이 _it 로 끝나야 합니다: " + parts[1]);
        }
        user = System.getenv().getOrDefault("SEATSWAP_IT_USER", "root");
        password = System.getenv().getOrDefault("SEATSWAP_IT_PASSWORD", "");
        // 안전장치 2: 연결 직후 실제 DATABASE() 를 다시 확인한 뒤에만 clean/TRUNCATE
        try (Connection c = DriverManager.getConnection(url, user, password);
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT DATABASE()")) {
            rs.next();
            String db = rs.getString(1);
            if (db == null || !db.endsWith("_it")) {
                fail("안전장치: SELECT DATABASE() 가 _it 로 끝나지 않습니다: " + db);
            }
        }
        Flyway flyway = Flyway.configure().dataSource(url, user, password).cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        dataSource = new SingleConnectionDataSource(url, user, password, true);
        jdbc = new JdbcTemplate(dataSource);
        repository = new ExchangeCandidateRepository(jdbc);
    }

    @AfterAll
    static void close() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @BeforeEach
    void resetData() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).as("TRUNCATE 전 DATABASE() 재확인").endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("notification", "exchange_ticket_lock", "exchange_match", "exchange_want_seat", "exchange_want_session",
                "exchange_want_range", "exchange_request", "ticket", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        seq = 0;
        long reg = user("registrant");
        performanceId = performance(reg);
        s1 = session(performanceId, BASE);
        s2 = session(performanceId, BASE.plusDays(1));
        s3 = session(performanceId, BASE.plusDays(2));
    }

    // ---------- 판정 ----------

    @Test
    void mutualMatchIsFoundAndCarriesDisplayFields() {
        long u1 = user("me");
        long u2 = user("other");
        Req a = request(u1, s1, "A", "1", "1", "X", null, List.of(s1), List.of(seat("A", "1", "2")));
        Req b = request(u2, s1, "A", "1", "2", "NEG", -3000, List.of(s1), List.of(seat("A", "1", "1")));

        var rows = repository.findCandidates(a.id, TODAY, 20, 0);

        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.requestId()).isEqualTo(b.id);
        assertThat(row.ticketId()).isEqualTo(b.ticketId);
        assertThat(row.zone()).isEqualTo("A");
        assertThat(row.row()).isEqualTo("1");
        assertThat(row.col()).isEqualTo("2");
        assertThat(row.sessionId()).isEqualTo(s1);
        assertThat(row.nickname()).isEqualTo("other");
        assertThat(row.wantPriority()).isEqualTo(1);
        assertThat(row.extraType()).isEqualTo("NEG");
        assertThat(row.extraAmount()).isEqualTo(-3000);
        assertThat(row.myExtraType()).isEqualTo("X");
        assertThat(row.myExtraAmount()).isNull();
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(1);
        // 상대 입장에서도 내가 후보다 (대칭)
        assertThat(repository.findCandidates(b.id, TODAY, 20, 0)).extracting(ExchangeCandidateRepository.Row::requestId)
                .containsExactly(a.id);
    }

    @Test
    void onlyMySeatInTheirWantIsNotEnough() {
        long u1 = user("me");
        long u2 = user("other");
        // 상대는 내 좌석을 원하지만, 나는 상대 좌석을 원하지 않는다 (내 희망: A열 9번)
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "9")));
        request(u2, s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).isEmpty();
        assertThat(repository.countCandidates(a.id, TODAY)).isZero();
    }

    @Test
    void onlyTheirSeatInMyWantIsNotEnough() {
        long u1 = user("me");
        long u2 = user("other");
        // 나는 상대 좌석을 원하지만, 상대는 내 좌석을 원하지 않는다
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        request(u2, s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "9")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).isEmpty();
    }

    /** 4x4 전 조합. 불성립은 (POS,POS), (POS,X), (X,POS) 뿐이다. */
    @ParameterizedTest(name = "내 {0} / 상대 {1} -> {2}")
    @CsvSource({
            "X,X,true", "X,ANY,true", "X,POS,false", "X,NEG,true",
            "ANY,X,true", "ANY,ANY,true", "ANY,POS,true", "ANY,NEG,true",
            "POS,X,false", "POS,ANY,true", "POS,POS,false", "POS,NEG,true",
            "NEG,X,true", "NEG,ANY,true", "NEG,POS,true", "NEG,NEG,true"
    })
    void extraTypeCompatibilityTable(String mine, String theirs, boolean matches) {
        long u1 = user("me");
        long u2 = user("other");
        Req a = request(u1, s1, "A", "1", "1", mine, amountOf(mine), List.of(s1), List.of(seat("A", "1", "2")));
        request(u2, s1, "A", "1", "2", theirs, amountOf(theirs), List.of(s1), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).hasSize(matches ? 1 : 0);
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(matches ? 1 : 0);
    }

    @Test
    void amountsAreNotUsedForMatching() {
        long u1 = user("me");
        long u2 = user("other");
        // 내가 받아야 하는 100000, 상대가 낼 수 있는 최대 1000 -> 금액이 안 맞아도 유형이 성립이면 후보다
        Req a = request(u1, s1, "A", "1", "1", "POS", 100000, List.of(s1), List.of(seat("A", "1", "2")));
        request(u2, s1, "A", "1", "2", "NEG", -1000, List.of(s1), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).hasSize(1);
    }

    // ---------- 회차 ----------

    @Test
    void sessionMustBeInMyWantAndMySessionInTheirWant() {
        long u1 = user("me");
        // 내 티켓: s1, 희망 회차 s2만
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s2), List.of(seat("A", "1", "2")));
        // (1) 상대 티켓 s2, 상대 희망 s1 -> 일치
        Req ok = request(user("ok"), s2, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        // (2) 상대 티켓 s3(내 희망 회차 아님) -> 제외. 같은 좌석 문자열이지만 회차가 다르므로 티켓은 별개
        request(user("notMyWant"), s3, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).extracting(ExchangeCandidateRepository.Row::requestId)
                .containsExactly(ok.id);
    }

    @Test
    void theirWantSessionsMustIncludeMySession() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s2), List.of(seat("A", "1", "2")));
        // 상대 티켓 s2, 상대 희망 회차는 s3뿐(내 회차 s1 아님) -> 제외
        request(user("o"), s2, "A", "1", "2", "ANY", null, List.of(s3), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).isEmpty();
    }

    @Test
    void multipleWantedSessionsMatchAnyOfThem() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1, s2, s3), List.of(seat("A", "1", "2")));
        Req b2 = request(user("b2"), s2, "A", "1", "2", "ANY", null, List.of(s1, s3), List.of(seat("A", "1", "1")));
        Req b3 = request(user("b3"), s3, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).extracting(ExchangeCandidateRepository.Row::requestId)
                .containsExactlyInAnyOrder(b2.id, b3.id);
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(2);
    }

    @Test
    void otherPerformanceIsExcludedEvenWithSameSeatAndWantedSessionIdAbused() {
        long u1 = user("me");
        long otherPerf = performance(user("reg2"));
        long otherSession = session(otherPerf, BASE);
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        // 다른 공연의 티켓: 희망 회차는 그 공연 회차, 좌석은 서로 원함이어도 후보가 될 수 없다
        request(user("o"), otherSession, "A", "1", "2", "ANY", null, List.of(otherSession), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).isEmpty();
        // 방어 검증: 내 희망 회차에 다른 공연 회차가 (서비스 검사를 우회해) 섞여 있어도 같은 공연 조건이 걸러낸다
        jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,2)",
                a.id, otherSession);
        long otherReq = jdbc.queryForObject("SELECT id FROM exchange_request WHERE ticket_id = (SELECT id FROM ticket WHERE performance_session_id = ?)",
                Long.class, otherSession);
        jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,2)",
                otherReq, s1);
        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).isEmpty();
    }

    @Test
    void seatMustMatchExactlyZoneRowCol() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1),
                List.of(seat("A", "1", "2"), seat("B", "1", "3")));
        // 구역이 다른 같은 열·번은 후보가 아니다
        request(user("o1"), s1, "C", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req ok = request(user("o2"), s1, "B", "1", "3", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).extracting(ExchangeCandidateRepository.Row::requestId)
                .containsExactly(ok.id);
    }

    // ---------- 제외 ----------

    @Test
    void candidateWithClosedSessionIsExcluded() {
        long u1 = user("me");
        long past = session(performanceId, TODAY.minusDays(1).withHour(20));    // 어제 20:00 -> 오늘 0시에 마감
        long todaysEarlier = session(performanceId, TODAY.withHour(0).withMinute(30)); // 오늘 00:30 -> 내일 0시 마감
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1, past, todaysEarlier), List.of(seat("A", "1", "2"), seat("A", "1", "3")));
        request(user("pastOwner"), past, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req ok = request(user("todayOwner"), todaysEarlier, "A", "1", "3", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).extracting(ExchangeCandidateRepository.Row::requestId)
                .containsExactly(ok.id);
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(1);
    }

    @Test
    void sameUserAndMyOwnRequestAreExcluded() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        // 같은 사용자의 다른 티켓이 서로를 원함 -> 후보 아님
        request(u1, s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).isEmpty();
    }

    @Test
    void closedRequestAndInactiveTicketAreExcluded() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1),
                List.of(seat("A", "1", "2"), seat("A", "1", "3"), seat("A", "1", "4")));
        Req closed = request(user("c"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req inactive = request(user("i"), s1, "A", "1", "3", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req ok = request(user("ok"), s1, "A", "1", "4", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        jdbc.update("UPDATE exchange_request SET status='CLOSED' WHERE id=?", closed.id);
        jdbc.update("UPDATE ticket SET status='INACTIVE' WHERE id=?", inactive.ticketId);

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).extracting(ExchangeCandidateRepository.Row::requestId)
                .containsExactly(ok.id);
    }

    @Test
    void closedMyRequestReturnsNothing() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        request(user("o"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        jdbc.update("UPDATE exchange_request SET status='CLOSED' WHERE id=?", a.id);

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).isEmpty();
    }

    // ---------- 정렬·페이징·쿼리 수 ----------

    @Test
    void sortedByMyWantPriorityThenNewestRequest() {
        long u1 = user("me");
        // 우선순위: s3=1, s1=2, s2=3 (사용자가 정한 순서이며 일시 순서와 무관)
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(), List.of(seat("A", "1", "2")));
        jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,3),(?,?,1),(?,?,2)",
                a.id, s2, a.id, s3, a.id, s1);
        Req p2old = request(user("p2old"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req p3 = request(user("p3"), s2, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req p1 = request(user("p1"), s3, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        // 같은 우선순위(s1)에서는 최신 등록이 먼저: p2new 가 p2old 보다 나중에 생성
        long u5 = user("p2new");
        Req p2new = request(u5, s1, "A", "2", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        insertSeat(a, "A", "2", "2");
        jdbc.update("UPDATE exchange_request SET created_at = ? WHERE id = ?", BASE.minusDays(10), p2old.id);
        jdbc.update("UPDATE exchange_request SET created_at = ? WHERE id = ?", BASE.minusDays(1), p2new.id);

        var rows = repository.findCandidates(a.id, TODAY, 20, 0);

        assertThat(rows).extracting(ExchangeCandidateRepository.Row::requestId)
                .containsExactly(p1.id, p2new.id, p2old.id, p3.id);
        assertThat(rows).extracting(ExchangeCandidateRepository.Row::wantPriority).containsExactly(1, 2, 2, 3);
    }

    @Test
    void pagingSplitsResultsWithoutOverlap() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of());
        for (int col = 2; col <= 8; col++) {
            insertSeat(a, "A", "1", String.valueOf(col));
            request(user("o" + col), s1, "A", "1", String.valueOf(col), "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        }
        var p0 = repository.findCandidates(a.id, TODAY, 3, 0);
        var p1 = repository.findCandidates(a.id, TODAY, 3, 3);
        var p2 = repository.findCandidates(a.id, TODAY, 3, 6);

        assertThat(p0).hasSize(3);
        assertThat(p1).hasSize(3);
        assertThat(p2).hasSize(1);
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(7);
        List<Long> all = new java.util.ArrayList<>();
        for (var list : List.of(p0, p1, p2)) {
            list.forEach(r -> all.add(r.requestId()));
        }
        assertThat(all).doesNotHaveDuplicates().hasSize(7);
    }

    @Test
    void candidateQueryIsASingleSelectRegardlessOfResultSize() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1, s2), List.of());
        for (int col = 2; col <= 31; col++) {
            insertSeat(a, "A", "1", String.valueOf(col));
            request(user("o" + col), col % 2 == 0 ? s1 : s2, "A", "1", String.valueOf(col), "ANY", null,
                    List.of(s1), List.of(seat("A", "1", "1")));
        }
        long before = comSelect();
        var rows = repository.findCandidates(a.id, TODAY, 20, 0);
        long after = comSelect();

        assertThat(rows).hasSize(20);
        assertThat(after - before).as("후보 20건을 한 번의 SELECT 로 읽는다(N+1 없음)").isEqualTo(1);
    }

    // ---------- 리뷰 반영 추가 테스트 ----------

    @Test
    void oneCounterpartMatchingManySessionsAndSeatsStaysOneRowAndCountOne() {
        long u1 = user("me");
        // 내 희망: 회차 3개 x 좌석 4개. 상대는 티켓 한 장이지만 희망 회차 3개 x 희망 좌석 4개(내 좌석 포함)
        Req a = request(u1, s2, "A", "1", "1", "ANY", null, List.of(s1, s2, s3),
                List.of(seat("A", "1", "2"), seat("A", "1", "3"), seat("A", "1", "4"), seat("A", "1", "5")));
        Req b = request(user("other"), s2, "A", "1", "2", "ANY", null, List.of(s1, s2, s3),
                List.of(seat("A", "1", "1"), seat("A", "1", "3"), seat("A", "1", "4"), seat("A", "1", "6")));

        var rows = repository.findCandidates(a.id, TODAY, 20, 0);

        assertThat(rows).extracting(ExchangeCandidateRepository.Row::requestId).containsExactly(b.id);
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(1);
    }

    @Test
    void deadlineBoundaryTodayMidnightIncludedMinusOneMinuteExcluded() {
        long u1 = user("me");
        long atMidnight = session(performanceId, TODAY);                       // 오늘 00:00 -> 내일 0시 마감, 포함
        long beforeMidnight = session(performanceId, TODAY.minusMinutes(1));   // 어제 23:59 -> 오늘 0시 마감, 제외
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1, atMidnight, beforeMidnight),
                List.of(seat("A", "1", "2"), seat("A", "1", "3")));
        Req in = request(user("in"), atMidnight, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        request(user("out"), beforeMidnight, "A", "1", "3", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).extracting(ExchangeCandidateRepository.Row::requestId)
                .containsExactly(in.id);
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(1);
    }

    @Test
    void samePriorityAndSameCreatedAtOrdersByRequestIdDesc() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1),
                List.of(seat("A", "1", "2"), seat("A", "1", "3"), seat("A", "1", "4")));
        Req b1 = request(user("b1"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req b2 = request(user("b2"), s1, "A", "1", "3", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req b3 = request(user("b3"), s1, "A", "1", "4", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        jdbc.update("UPDATE exchange_request SET created_at = ? WHERE id IN (?,?,?)", BASE, b1.id, b2.id, b3.id);

        assertThat(repository.findCandidates(a.id, TODAY, 20, 0)).extracting(ExchangeCandidateRepository.Row::requestId)
                .containsExactly(b3.id, b2.id, b1.id);
    }

    /** COUNT 전용 쿼리(users 조인 생략)의 총계가 목록 쿼리(users 조인 포함)의 전체 행 수와 같아야 한다. */
    @Test
    void countMatchesListTotalAcrossMixedScenario() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "NEG", -1000, List.of(s1, s2, s3), List.of());
        long[] sessions = {s1, s2, s3};
        String[] types = {"X", "ANY", "POS", "NEG"};
        for (int col = 2; col <= 25; col++) {
            insertSeat(a, "A", "1", String.valueOf(col));
            String type = types[col % 4];
            Integer amount = null;
            if (type.equals("POS")) { amount = 100; } else if (type.equals("NEG")) { amount = -100; }
            // 일부는 내 회차(s1)를 원하지 않아 제외된다
            List<Long> wants = col % 5 == 0 ? List.of(s2) : List.of(s1, s2);
            request(user("o" + col), sessions[col % 3], "A", "1", String.valueOf(col), type, amount, wants,
                    List.of(seat("A", "1", "1")));
        }
        var all = repository.findCandidates(a.id, TODAY, 100, 0);
        assertThat(all).isNotEmpty().hasSizeLessThan(24);
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(all.size());
    }

    @Test
    void listAndCountUseTheSameConnectionInsideAReadOnlyTransaction() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        request(user("o"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));

        // 풀 없이 호출마다 새 연결을 여는 DataSource: 트랜잭션 밖이면 연결이 달라지고, 안이면 하나를 공유한다.
        var plain = new org.springframework.jdbc.datasource.DriverManagerDataSource(url, user, password);
        var template = new JdbcTemplate(plain);
        var repo = new ExchangeCandidateRepository(template);
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(plain));
        tx.setReadOnly(true);

        List<Long> ids = tx.execute(status -> {
            long c1 = template.queryForObject("SELECT CONNECTION_ID()", Long.class);
            repo.findCandidates(a.id, TODAY, 20, 0);
            long c2 = template.queryForObject("SELECT CONNECTION_ID()", Long.class);
            repo.countCandidates(a.id, TODAY);
            long c3 = template.queryForObject("SELECT CONNECTION_ID()", Long.class);
            return List.of(c1, c2, c3);
        });
        assertThat(ids).as("읽기 전용 트랜잭션 안에서는 한 연결").containsOnly(ids.get(0));

        long o1 = template.queryForObject("SELECT CONNECTION_ID()", Long.class);
        long o2 = template.queryForObject("SELECT CONNECTION_ID()", Long.class);
        assertThat(o1).as("트랜잭션 밖(대조군)에서는 호출마다 새 연결").isNotEqualTo(o2);
    }


    // ---------- V5 제외 조건: 같은 쌍 열린 매칭, 예약 잠금 ----------

    /** a(제안자)·b 사이의 매칭 행을 직접 넣는다. CANCELED 는 CHECK 때문에 canceled_at 도 채운다. */
    private long match(Req a, Req b, String status) {
        jdbc.update("INSERT INTO exchange_match (request_a_id, request_b_id, ticket_a_id, ticket_b_id, user_a_id, user_b_id, "
                        + "status, canceled_at, reserved_by_id, reserved_at, a_completed_at, b_completed_at, created_at, updated_at, a_extra_type, b_extra_type) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,NOW(6),NOW(6),'ANY','ANY')",
                a.id, b.id, a.ticketId, b.ticketId, a.userId, b.userId, status,
                status.equals("CANCELED") ? java.sql.Timestamp.valueOf(BASE) : null,
                // V8 CHECK: RESERVED 는 예약자·시각 필수, COMPLETED 는 양쪽 수락 시각 필수
                status.equals("RESERVED") ? a.userId : null, status.equals("RESERVED") ? java.sql.Timestamp.valueOf(BASE) : null,
                status.equals("COMPLETED") ? java.sql.Timestamp.valueOf(BASE) : null, status.equals("COMPLETED") ? java.sql.Timestamp.valueOf(BASE) : null);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void lock(Req r, long matchId) {
        jdbc.update("INSERT INTO exchange_ticket_lock (ticket_id, match_id, created_at) VALUES (?,?,NOW(6))", r.ticketId, matchId);
    }

    /** 서로 후보인 한 쌍 (a: A열 1번 <-> b: A열 2번). */
    private Req[] pair() {
        Req a = request(user("me"), s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        Req b = request(user("other"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        return new Req[]{a, b};
    }

    private List<Long> candidateIds(Req of) {
        return repository.findCandidates(of.id, TODAY, 20, 0).stream().map(ExchangeCandidateRepository.Row::requestId).toList();
    }

    @ParameterizedTest(name = "같은 쌍의 {0} 매칭은 후보에서 뺀다 (제안자 쪽 / 상대 쪽 양방향)")
    @CsvSource({"CHATTING", "RESERVED"})
    void openMatchOfTheSamePairIsExcludedFromBothSides(String status) {
        Req[] p = pair();
        match(p[0], p[1], status);

        assertThat(candidateIds(p[0])).isEmpty();
        assertThat(candidateIds(p[1])).isEmpty();
        assertThat(repository.countCandidates(p[0].id, TODAY)).isZero();
        assertThat(repository.countCandidates(p[1].id, TODAY)).isZero();
    }

    @ParameterizedTest(name = "같은 쌍의 {0} 매칭은 후보를 빼지 않는다 (재매칭 허용)")
    @CsvSource({"CANCELED", "COMPLETED"})
    void closedMatchOfTheSamePairDoesNotExclude(String status) {
        Req[] p = pair();
        match(p[0], p[1], status);

        assertThat(candidateIds(p[0])).containsExactly(p[1].id);
        assertThat(candidateIds(p[1])).containsExactly(p[0].id);
        assertThat(repository.countCandidates(p[0].id, TODAY)).isEqualTo(1);
    }

    @Test
    void openMatchWithAnotherRequestDoesNotExcludeThisCandidate() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2"), seat("A", "1", "3")));
        Req b = request(user("b"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req c = request(user("c"), s1, "A", "1", "3", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        match(a, c, "CHATTING");   // 한 요청에 채팅이 여러 개 동시에 열릴 수 있다: c 만 빠지고 b 는 그대로

        assertThat(candidateIds(a)).containsExactly(b.id);
    }

    @Test
    void candidateWhoseTicketIsLockedIsExcludedAndReturnsAfterRelease() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        Req b = request(user("b"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        // b 의 티켓이 '다른 사람과' 예약 잠금 상태
        Req c = request(user("c"), s1, "A", "1", "9", "ANY", null, List.of(s1), List.of(seat("A", "1", "8")));
        long m = match(b, c, "RESERVED");
        lock(b, m);
        lock(c, m);

        assertThat(candidateIds(a)).isEmpty();
        assertThat(repository.countCandidates(a.id, TODAY)).isZero();

        jdbc.update("DELETE FROM exchange_ticket_lock WHERE match_id = ?", m);   // 예약 취소로 잠금 해제
        jdbc.update("UPDATE exchange_match SET status='CANCELED', canceled_at=NOW(6), reserved_by_id=NULL, reserved_at=NULL WHERE id=?", m);
        assertThat(candidateIds(a)).containsExactly(b.id);
    }

    @Test
    void exclusionsKeepListAndCountInSync() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1),
                List.of(seat("A", "1", "2"), seat("A", "1", "3"), seat("A", "1", "4")));
        Req openOne = request(user("o1"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req locked = request(user("o2"), s1, "A", "1", "3", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req ok = request(user("o3"), s1, "A", "1", "4", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        match(a, openOne, "CHATTING");
        Req far = request(user("far"), s1, "Z", "9", "9", "ANY", null, List.of(s1), List.of(seat("Z", "9", "8")));
        long m = match(locked, far, "RESERVED");
        lock(locked, m);
        lock(far, m);

        assertThat(candidateIds(a)).containsExactly(ok.id);
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(1);
    }

    // ---------- 쌍 단위 재검증 (isCandidatePair) ----------

    @Test
    void isCandidatePairAcceptsMutualMatchAndIsSymmetric() {
        Req[] p = pair();

        assertThat(repository.isCandidatePair(p[0].id, p[1].id, TODAY)).isTrue();
        assertThat(repository.isCandidatePair(p[1].id, p[0].id, TODAY)).isTrue();
    }

    @Test
    void isCandidatePairIgnoresOpenMatchAndLockBecauseServiceChecksThemSeparately() {
        Req[] p = pair();
        long m = match(p[0], p[1], "RESERVED");
        lock(p[0], m);
        lock(p[1], m);

        assertThat(repository.isCandidatePair(p[0].id, p[1].id, TODAY)).isTrue();
        assertThat(candidateIds(p[0])).isEmpty();
    }

    @Test
    void isCandidatePairRejectsEveryBrokenCondition() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "POS", 5000, List.of(s1),
                List.of(seat("A", "1", "2"), seat("A", "1", "3"), seat("A", "1", "4"), seat("A", "1", "5")));
        // 한쪽만 원함 (상대는 내 좌석을 원하지 않는다)
        Req oneSided = request(user("one"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "9", "9")));
        assertThat(repository.isCandidatePair(a.id, oneSided.id, TODAY)).isFalse();
        // 추가금 POS-POS 불성립 (좌석·회차는 서로 맞다)
        Req posPos = request(user("pp"), s1, "A", "1", "3", "POS", 100, List.of(s1), List.of(seat("A", "1", "1")));
        assertThat(repository.isCandidatePair(a.id, posPos.id, TODAY)).isFalse();
        // 내 희망 회차에 없는 회차 (a 의 희망은 s1 만)
        Req wrongSession = request(user("ws"), s3, "A", "1", "4", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        assertThat(repository.isCandidatePair(a.id, wrongSession.id, TODAY)).isFalse();
        // 같은 사용자
        Req mine = request(u1, s1, "A", "1", "5", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        assertThat(repository.isCandidatePair(a.id, mine.id, TODAY)).isFalse();
        // 상대 요청 CLOSED / 티켓 INACTIVE
        Req closed = request(user("cl"), s1, "A", "2", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        insertSeat(a, "A", "2", "2");
        assertThat(repository.isCandidatePair(a.id, closed.id, TODAY)).isTrue();
        jdbc.update("UPDATE exchange_request SET status='CLOSED' WHERE id=?", closed.id);
        assertThat(repository.isCandidatePair(a.id, closed.id, TODAY)).isFalse();
        Req inactive = request(user("in"), s1, "A", "3", "3", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        insertSeat(a, "A", "3", "3");
        jdbc.update("UPDATE ticket SET status='INACTIVE' WHERE id=?", inactive.ticketId);
        assertThat(repository.isCandidatePair(a.id, inactive.id, TODAY)).isFalse();
        // 내 요청이 CLOSED
        Req p0 = request(user("p0"), s2, "A", "1", "1", "ANY", null, List.of(s2), List.of(seat("A", "1", "2")));
        Req p1 = request(user("p1"), s2, "A", "1", "2", "ANY", null, List.of(s2), List.of(seat("A", "1", "1")));
        assertThat(repository.isCandidatePair(p0.id, p1.id, TODAY)).isTrue();
        jdbc.update("UPDATE exchange_request SET status='CLOSED' WHERE id=?", p0.id);
        assertThat(repository.isCandidatePair(p0.id, p1.id, TODAY)).isFalse();
    }

    @Test
    void isCandidatePairRespectsDeadlineOfTheirSession() {
        long u1 = user("me");
        long past = session(performanceId, TODAY.minusDays(1).withHour(20));
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1, past), List.of(seat("A", "1", "2")));
        Req b = request(user("pastOwner"), past, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));

        assertThat(repository.isCandidatePair(a.id, b.id, TODAY)).isFalse();
    }

    // ---------- V5 스키마 제약 ----------

    @Test
    void openPairIsUniqueInEitherDirectionButNotAfterCancel() {
        Req[] p = pair();
        long first = match(p[0], p[1], "CHATTING");

        // 같은 방향·반대 방향 모두 열린 매칭 중복은 DB 가 거부 (uk_exchange_match_open_pair)
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> match(p[0], p[1], "CHATTING"));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> match(p[1], p[0], "RESERVED"));

        // 취소하면 open_flag 가 NULL 이 되어 새 매칭을 다시 열 수 있다. 취소 이력은 여러 개 쌓여도 된다.
        jdbc.update("UPDATE exchange_match SET status='CANCELED', canceled_at=NOW(6) WHERE id=?", first);
        match(p[1], p[0], "CHATTING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_match WHERE open_flag = 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_match", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT request_low_id < request_high_id FROM exchange_match WHERE id = ?",
                Boolean.class, first)).isTrue();
    }

    @Test
    void matchCheckConstraintsRejectBadRows() {
        Req[] p = pair();
        // CANCELED 인데 canceled_at 이 없음
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                jdbc.update("INSERT INTO exchange_match (request_a_id, request_b_id, ticket_a_id, ticket_b_id, user_a_id, user_b_id, "
                        + "status, created_at, updated_at, a_extra_type, b_extra_type) VALUES (?,?,?,?,?,?,'CANCELED',NOW(6),NOW(6),'ANY','ANY')",
                        p[0].id, p[1].id, p[0].ticketId, p[1].ticketId, p[0].userId, p[1].userId));
        // 열린 매칭인데 canceled_at 이 있음
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                jdbc.update("INSERT INTO exchange_match (request_a_id, request_b_id, ticket_a_id, ticket_b_id, user_a_id, user_b_id, "
                        + "status, canceled_at, created_at, updated_at, a_extra_type, b_extra_type) VALUES (?,?,?,?,?,?,'CHATTING',NOW(6),NOW(6),NOW(6),'ANY','ANY')",
                        p[0].id, p[1].id, p[0].ticketId, p[1].ticketId, p[0].userId, p[1].userId));
        // 상태 값 오류
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                jdbc.update("INSERT INTO exchange_match (request_a_id, request_b_id, ticket_a_id, ticket_b_id, user_a_id, user_b_id, "
                        + "status, created_at, updated_at, a_extra_type, b_extra_type) VALUES (?,?,?,?,?,?,'CLOSED',NOW(6),NOW(6),'ANY','ANY')",
                        p[0].id, p[1].id, p[0].ticketId, p[1].ticketId, p[0].userId, p[1].userId));
        // 같은 요청·같은 티켓끼리
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                jdbc.update("INSERT INTO exchange_match (request_a_id, request_b_id, ticket_a_id, ticket_b_id, user_a_id, user_b_id, "
                        + "status, created_at, updated_at, a_extra_type, b_extra_type) VALUES (?,?,?,?,?,?,'CHATTING',NOW(6),NOW(6),'ANY','ANY')",
                        p[0].id, p[0].id, p[0].ticketId, p[1].ticketId, p[0].userId, p[1].userId));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class, () ->
                jdbc.update("INSERT INTO exchange_match (request_a_id, request_b_id, ticket_a_id, ticket_b_id, user_a_id, user_b_id, "
                        + "status, created_at, updated_at, a_extra_type, b_extra_type) VALUES (?,?,?,?,?,?,'CHATTING',NOW(6),NOW(6),'ANY','ANY')",
                        p[0].id, p[1].id, p[0].ticketId, p[0].ticketId, p[0].userId, p[1].userId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_match", Integer.class)).isZero();
    }

    @Test
    void ticketLockPrimaryKeyAllowsOneMatchPerTicketAndCascadesOnMatchDelete() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        Req b = request(user("b"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        Req c = request(user("c"), s1, "A", "1", "3", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        long m1 = match(a, b, "RESERVED");
        long m2 = match(a, c, "CHATTING");
        lock(a, m1);
        lock(b, m1);

        // a 의 티켓은 이미 m1 에 잠겼다: 다른 매칭 m2 로는 잠글 수 없다 (교차 충돌도 PK 하나로 막는다)
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> lock(a, m2));
        // 다른 사람의 b 측으로서도 마찬가지: b 티켓이 c 의 매칭에서 잠기려 해도 PK 충돌
        long m3 = match(c, b, "CHATTING");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> lock(b, m3));

        jdbc.update("DELETE FROM exchange_match WHERE id = ?", m1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_ticket_lock", Integer.class)).as("매칭 삭제 시 잠금도 CASCADE").isZero();
    }

    // ---------- 추가금은 범위(좌석) 단위 (V6) ----------

    @Test
    void extraIsJudgedPerSeatRangeNotPerRequest() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of());
        insertSeat(a, "A", "1", "2", "POS", 5000);   // 이 좌석 보유자와는 POS
        insertSeat(a, "A", "1", "3", "ANY", null);
        insertSeat(a, "A", "1", "4", "X", null);
        insertSeat(a, "A", "1", "5", "POS", 3000);

        // b1: 내가 POS 로 원하는 좌석, 상대는 내 좌석을 X 로 원함 -> POS-X 불성립
        Req b1 = request(user("b1"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of());
        insertSeat(b1, "A", "1", "1", "X", null);
        // b2: ANY-X 성립
        Req b2 = request(user("b2"), s1, "A", "1", "3", "ANY", null, List.of(s1), List.of());
        insertSeat(b2, "A", "1", "1", "X", null);
        // b3: 내 쪽 X, 상대 쪽 POS -> X-POS 불성립. 상대의 다른 범위(A열 2행 2번)는 NEG 라도 이 쌍에는 무관
        Req b3 = request(user("b3"), s1, "A", "1", "4", "ANY", null, List.of(s1), List.of());
        insertSeat(b3, "A", "1", "1", "POS", 7000);
        insertSeat(b3, "A", "2", "2", "NEG", -100);
        // b4: 내 쪽 POS 3000, 상대 쪽 NEG -500 -> 성립. 상대의 다른 범위(POS 100)는 영향 없음
        Req b4 = request(user("b4"), s1, "A", "1", "5", "ANY", null, List.of(s1), List.of());
        insertSeat(b4, "A", "1", "1", "NEG", -500);
        insertSeat(b4, "A", "9", "9", "POS", 100);

        var rows = repository.findCandidates(a.id, TODAY, 20, 0);

        assertThat(rows).extracting(ExchangeCandidateRepository.Row::requestId).containsExactlyInAnyOrder(b2.id, b4.id);
        var r2 = rows.stream().filter(r -> r.requestId() == b2.id).findFirst().orElseThrow();
        assertThat(r2.myExtraType()).isEqualTo("ANY");
        assertThat(r2.extraType()).isEqualTo("X");
        var r4 = rows.stream().filter(r -> r.requestId() == b4.id).findFirst().orElseThrow();
        assertThat(r4.myExtraType()).isEqualTo("POS");
        assertThat(r4.myExtraAmount()).isEqualTo(3000);
        assertThat(r4.extraType()).isEqualTo("NEG");
        assertThat(r4.extraAmount()).isEqualTo(-500);
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(2);
        // 같은 판정이 쌍 단위에서도 같다
        assertThat(repository.isCandidatePair(a.id, b1.id, TODAY)).isFalse();
        assertThat(repository.isCandidatePair(a.id, b3.id, TODAY)).isFalse();
        assertThat(repository.isCandidatePair(a.id, b4.id, TODAY)).isTrue();
        // 상대 입장에서도 같은 쌍은 같은 결과(대칭)
        assertThat(candidateIds(b4)).containsExactly(a.id);
        assertThat(candidateIds(b1)).isEmpty();
    }

    @Test
    void findCandidatePairReturnsBothSidesExtrasForTheSnapshot() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of());
        insertSeat(a, "A", "1", "2", "POS", 5000);
        Req b = request(user("b"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of());
        insertSeat(b, "A", "1", "1", "NEG", -2500);

        var pair = repository.findCandidatePair(a.id, b.id, TODAY).orElseThrow();
        assertThat(pair.my().type().name()).isEqualTo("POS");
        assertThat(pair.my().amount()).isEqualTo(5000);
        assertThat(pair.their().type().name()).isEqualTo("NEG");
        assertThat(pair.their().amount()).isEqualTo(-2500);
        // 반대 방향이면 my/their 가 뒤바뀐다
        var reverse = repository.findCandidatePair(b.id, a.id, TODAY).orElseThrow();
        assertThat(reverse.my().type().name()).isEqualTo("NEG");
        assertThat(reverse.their().type().name()).isEqualTo("POS");
        assertThat(repository.findCandidatePair(a.id, a.id, TODAY)).isEmpty();
    }

    // ---------- 소프트 삭제 (V7) ----------

    private void softDelete(long requestId) {
        jdbc.update("UPDATE exchange_request SET status='DELETED', deleted_at=NOW(6) WHERE id=?", requestId);
    }

    @Test
    void deletedRequestIsNeverACandidateEvenIfItsChildRowsRemain() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        Req b = request(user("b"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        assertThat(candidateIds(a)).containsExactly(b.id);

        softDelete(b.id);   // 서비스는 하위 행도 지우지만 SQL 은 하위 행이 남아 있어도 DELETED 를 거른다

        assertThat(candidateIds(a)).isEmpty();
        assertThat(repository.countCandidates(a.id, TODAY)).isZero();
        assertThat(repository.isCandidatePair(a.id, b.id, TODAY)).isFalse();
        // 내 요청이 DELETED 이면 조회 자체가 비어 있다
        Req c = request(user("c"), s1, "A", "1", "3", "ANY", null, List.of(s1), List.of(seat("A", "1", "4")));
        request(user("d"), s1, "A", "1", "4", "ANY", null, List.of(s1), List.of(seat("A", "1", "3")));
        softDelete(c.id);
        assertThat(candidateIds(c)).isEmpty();
    }

    @Test
    void onlyTheLiveRequestOfATicketIsJoinedAfterDeleteAndRecreate() {
        long u1 = user("me");
        Req a = request(u1, s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        Req old = request(user("b"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of(seat("A", "1", "1")));
        softDelete(old.id);
        // 같은 티켓에 새 요청 (옛 요청은 DELETED 로 남아 있다)
        jdbc.update("INSERT INTO exchange_request (ticket_id, status, created_at, updated_at) VALUES (?,'OPEN',NOW(6),NOW(6))", old.ticketId);
        long fresh = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,1)", fresh, s1);
        insertSeat(new Req(fresh, old.ticketId, old.userId, "NEG", -700), "A", "1", "1", "NEG", -700);

        var rows = repository.findCandidates(a.id, TODAY, 20, 0);

        assertThat(rows).extracting(ExchangeCandidateRepository.Row::requestId).containsExactly(fresh);
        assertThat(rows.get(0).extraType()).isEqualTo("NEG");
        assertThat(repository.countCandidates(a.id, TODAY)).isEqualTo(1);
    }

    @Test
    void liveTicketUniqueKeyAllowsOneLiveRequestPerTicketButManyDeleted() {
        Req r = request(user("me"), s1, "A", "1", "1", "ANY", null, List.of(s1), List.of(seat("A", "1", "2")));
        String insert = "INSERT INTO exchange_request (ticket_id, status, deleted_at, created_at, updated_at) VALUES (?,?,?,NOW(6),NOW(6))";
        // 미삭제(OPEN/CLOSED) 요청이 이미 있으면 같은 티켓에 또 만들 수 없다
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update(insert, r.ticketId(), "OPEN", null));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update(insert, r.ticketId(), "CLOSED", null));
        // 삭제 행은 여러 개 허용
        jdbc.update(insert, r.ticketId(), "DELETED", java.sql.Timestamp.valueOf(BASE));
        jdbc.update(insert, r.ticketId(), "DELETED", java.sql.Timestamp.valueOf(BASE));
        // 삭제하면 새 요청을 만들 수 있고, 삭제 행을 다시 OPEN 으로 되돌려 둘이 되는 것은 막힌다
        softDelete(r.id());
        jdbc.update(insert, r.ticketId(), "OPEN", null);
        long deletedId = jdbc.queryForObject("SELECT id FROM exchange_request WHERE ticket_id=? AND status='DELETED' LIMIT 1", Long.class, r.ticketId());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
                () -> jdbc.update("UPDATE exchange_request SET status='OPEN', deleted_at=NULL WHERE id=?", deletedId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_request WHERE ticket_id=? AND live_flag=1", Integer.class, r.ticketId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_request WHERE ticket_id=?", Integer.class, r.ticketId())).isEqualTo(4);
    }

    @Test
    void liveTicketUniqueKeyStartsWithLiveFlagAndFkKeepsItsOwnIndex() {
        // FK 인덱스 규칙: 갱신 컬럼(live_flag)이 들어간 유일 키가 ticket FK 의 인덱스가 되면 안 된다
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME='exchange_request' AND INDEX_NAME='uk_exchange_request_live_ticket' ORDER BY SEQ_IN_INDEX", String.class))
                .containsExactly("live_flag", "ticket_id");
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME='exchange_request' AND INDEX_NAME='idx_exchange_request_ticket' ORDER BY SEQ_IN_INDEX", String.class))
                .containsExactly("ticket_id");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME='exchange_request' AND INDEX_NAME='uk_exchange_request_ticket'", Integer.class)).isZero();
    }

    @Test
    void v6V7CheckConstraintsRejectBadRows() {
        Req r = request(user("me"), s1, "A", "1", "1", "ANY", null, List.of(s1), List.of());
        String range = "INSERT INTO exchange_want_range (request_id, zone_label, zone_key, row_from, row_to, col_from, col_to, "
                + "extra_type, extra_amount, sort_order) VALUES (?,'A','A','1','1','1','1',?,?,0)";
        assertRejected("ck_exchange_want_range_amount", () -> jdbc.update(range, r.id(), "POS", null));
        assertRejected("ck_exchange_want_range_amount", () -> jdbc.update(range, r.id(), "POS", -5));
        assertRejected("ck_exchange_want_range_amount", () -> jdbc.update(range, r.id(), "NEG", 5));
        assertRejected("ck_exchange_want_range_amount", () -> jdbc.update(range, r.id(), "X", 100));
        // 알 수 없는 유형은 유형 CHECK 와 금액 CHECK 가 모두 걸린다(MySQL 이 어느 쪽을 먼저 보고하든 상관없다)
        assertRejected("ck_exchange_want_range", () -> jdbc.update(range, r.id(), "FREE", null));
        assertRejected("extra_type", () -> jdbc.update(range, r.id(), null, null));
        jdbc.update(range, r.id(), "NEG", -5);

        String seat = "INSERT INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount) VALUES (?,'A','1','9',?,?)";
        assertRejected("ck_exchange_want_seat_amount", () -> jdbc.update(seat, r.id(), "POS", 0));
        assertRejected("ck_exchange_want_seat_amount", () -> jdbc.update(seat, r.id(), "ANY", 1));
        assertRejected("ck_exchange_want_seat", () -> jdbc.update(seat, r.id(), "pos2", 5));
        jdbc.update(seat, r.id(), "POS", 1);

        Req other = request(user("o"), s1, "A", "1", "2", "ANY", null, List.of(s1), List.of());
        String match = "INSERT INTO exchange_match (request_a_id, request_b_id, ticket_a_id, ticket_b_id, user_a_id, user_b_id, status, "
                + "a_extra_type, a_extra_amount, b_extra_type, b_extra_amount, created_at, updated_at) VALUES (?,?,?,?,?,?,'CHATTING',?,?,?,?,NOW(6),NOW(6))";
        assertRejected("ck_exchange_match_a_extra", () -> jdbc.update(match, r.id(), other.id(), r.ticketId(), other.ticketId(),
                r.userId(), other.userId(), "POS", null, "X", null));
        assertRejected("ck_exchange_match_b_extra", () -> jdbc.update(match, r.id(), other.id(), r.ticketId(), other.ticketId(),
                r.userId(), other.userId(), "X", null, "NEG", 3));
        assertRejected("a_extra_type", () -> jdbc.update(match, r.id(), other.id(), r.ticketId(), other.ticketId(),
                r.userId(), other.userId(), null, null, "X", null));
        jdbc.update(match, r.id(), other.id(), r.ticketId(), other.ticketId(), r.userId(), other.userId(), "POS", 10, "NEG", -10);

        String req = "INSERT INTO exchange_request (ticket_id, status, deleted_at, created_at, updated_at) VALUES (?,?,?,NOW(6),NOW(6))";
        assertRejected("ck_exchange_request_status", () -> jdbc.update(req, r.ticketId(), "GONE", null));
        assertRejected("ck_exchange_request_deleted", () -> jdbc.update(req, r.ticketId(), "DELETED", null));
        assertRejected("ck_exchange_request_deleted", () -> jdbc.update(req, r.ticketId(), "OPEN", java.sql.Timestamp.valueOf(BASE)));
        jdbc.update(req, r.ticketId(), "DELETED", java.sql.Timestamp.valueOf(BASE));
    }

    private static void assertRejected(String constraint, Runnable action) {
        org.assertj.core.api.Assertions.assertThatThrownBy(action::run)
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining(constraint);
    }

    // ---------- 도우미 ----------

    private long comSelect() {
        // SHOW STATUS 는 Com_select 로 세지 않는다(측정 자체가 값을 올리지 않음).
        return jdbc.query("SHOW SESSION STATUS LIKE 'Com_select'", rs -> {
            rs.next();
            return rs.getLong(2);
        });
    }

    private static Integer amountOf(String type) {
        return switch (type) {
            case "POS" -> 5000;
            case "NEG" -> -3000;
            default -> null;
        };
    }

    private record Seat(String zone, String row, String col) {}

    private record Req(long id, long ticketId, long userId, String extraType, Integer extraAmount) {}

    private static Seat seat(String zone, String row, String col) {
        return new Seat(zone, row, col);
    }

    private long user(String nickname) {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, 'x', 'USER')",
                "u" + seq + "@t.com", nickname);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long performance(long registrantId) {
        seq++;
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), ?, ?, 't', 'v', ?)", "k" + seq, "http://x/" + seq, registrantId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long session(long perfId, LocalDateTime startsAt) {
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)",
                startsAt, perfId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 티켓 + 요청 + 희망 회차(리스트 순서가 priority 1,2,...) + 희망 좌석. */
    private Req request(long userId, long sessionId, String zone, String row, String col,
                        String extraType, Integer extraAmount, List<Long> wantSessions, List<Seat> wantSeats) {
        jdbc.update("INSERT INTO ticket (performance_session_id, user_id, zone_label, zone_key, row_label, row_key, "
                        + "col_label, col_key, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',NOW(6),NOW(6))",
                sessionId, userId, zone, zone, row, row, col, col);
        long ticketId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_request (ticket_id, status, created_at, updated_at) "
                + "VALUES (?,'OPEN',NOW(6),NOW(6))", ticketId);
        long requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (int i = 0; i < wantSessions.size(); i++) {
            jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,?)",
                    requestId, wantSessions.get(i), i + 1);
        }
        Req req = new Req(requestId, ticketId, userId, extraType, extraAmount);
        for (Seat s : wantSeats) {
            insertSeat(req, s.zone(), s.row(), s.col());
        }
        return req;
    }

    /** 요청 r 의 희망 좌석 1개를 r 의 추가금(요청 단위로 만든 헬퍼의 값)으로 넣는다. */
    private void insertSeat(Req r, String zone, String row, String col) {
        insertSeat(r, zone, row, col, r.extraType(), r.extraAmount());
    }

    private void insertSeat(Req r, String zone, String row, String col, String extraType, Integer extraAmount) {
        jdbc.update("INSERT INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount) "
                + "VALUES (?,?,?,?,?,?)", r.id(), zone, row, col, extraType, extraAmount);
    }
}
