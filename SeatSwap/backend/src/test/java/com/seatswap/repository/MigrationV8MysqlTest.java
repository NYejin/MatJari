package com.seatswap.repository;

import com.seatswap.testsupport.RealMysqlSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V7 상태의 DB(매칭 행이 이미 있는)를 V8(한 명 예약 방식: reserved_by_id / reserved_at)로 올리는 이관 시나리오 (실제 MySQL).
 * V6V7 이관 테스트와 같이 환경변수의 DB 이름 `X_it` 대신 `X_mig_it` 라는 별도 DB 를 만들어 쓴다(이름은 반드시 `_it` 로 끝난다).
 * 설계 10.5의 7가지 매칭 행: ①CHATTING(무예약) ②CHATTING(a만 reserved_at, 반쪽 동의) ③RESERVED(a 이른 시각) ④RESERVED(b 이른 시각)
 * ⑤RESERVED(두 시각 동일) ⑥RESERVED(두 시각 NULL, 비정상) ⑦CANCELED(옛 예약 시각이 남음). 잠금 행은 RESERVED 4건에 2행씩.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
class MigrationV8MysqlTest {

    private static String migrationUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("SEATSWAP_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "") + "_mig_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    private String freshV7Database() {
        RealMysqlSupport.assertSafe();   // 환경변수 DB 안전장치 (호스트 localhost, 이름 _it)
        String url = migrationUrl();
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(url.replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_mig_it");
        flyway(url, "7").clean();
        assertThat(flyway(url, "7").migrate().migrationsExecuted).isEqualTo(7);
        return url;
    }

    private SingleConnectionDataSource open(String url) {
        return new SingleConnectionDataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password(), true);
    }

    @Test
    void V7에서_매칭_행이_있는_채로_V8을_올리면_예약자가_백필되고_제약이_생기며_재실행도_안전하다() {
        String url = freshV7Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_mig_it");
            seedV7(jdbc);
            Map<Long, Map<String, Object>> legacyBefore = legacyColumns(jdbc);
            long locksBefore = jdbc.queryForObject("SELECT COUNT(*) FROM exchange_ticket_lock", Long.class);

            assertThat(flyway(url, "8").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc, legacyBefore, locksBefore);

            // 재실행: history 에서 V8 만 지우고 다시 적용해도 프로시저 가드로 아무것도 바뀌지 않는다
            jdbc.update("DELETE FROM flyway_schema_history WHERE version = '8'");
            assertThat(flyway(url, "8").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc, legacyBefore, locksBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                    .as("임시 프로시저가 남지 않는다").isZero();
        }
    }

    @Test
    void V8이_중간까지만_적용된_DB에서도_이어서_끝까지_적용된다() {
        String url = freshV7Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            seedV7(jdbc);
            Map<Long, Map<String, Object>> legacyBefore = legacyColumns(jdbc);
            long locksBefore = jdbc.queryForObject("SELECT COUNT(*) FROM exchange_ticket_lock", Long.class);
            // 1~2 단계(컬럼 + 인덱스)와 백필 일부만 끝난 상태: 이미 백필된 행(m3)은 건드리지 않아야 한다
            jdbc.execute("ALTER TABLE exchange_match ADD COLUMN reserved_by_id BIGINT NULL AFTER b_reserved_at, "
                    + "ADD COLUMN reserved_at DATETIME(6) NULL AFTER reserved_by_id");
            jdbc.execute("ALTER TABLE exchange_match ADD KEY idx_exchange_match_reserved_by (reserved_by_id)");
            jdbc.update("UPDATE exchange_match SET reserved_by_id = user_a_id, reserved_at = '2030-03-03 03:03:03.000000' WHERE id = 3");

            assertThat(flyway(url, "8").migrate().migrationsExecuted).isEqualTo(1);

            Map<String, Object> m3 = jdbc.queryForMap("SELECT reserved_by_id, reserved_at FROM exchange_match WHERE id = 3");
            assertThat(m3.get("reserved_at").toString()).startsWith("2030-03-03T03:03:03");
            assertThat(jdbc.queryForObject("SELECT reserved_by_id FROM exchange_match WHERE id = 4", Long.class))
                    .isEqualTo(jdbc.queryForObject("SELECT user_b_id FROM exchange_match WHERE id = 4", Long.class));
            assertThat(constraints(jdbc)).contains("fk_exchange_match_reserved_by", "ck_exchange_match_reserved",
                    "ck_exchange_match_reserved_by_party", "ck_exchange_match_completed");
            assertThat(legacyColumns(jdbc)).isEqualTo(legacyBefore);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_ticket_lock", Long.class)).isEqualTo(locksBefore);
        }
    }

    @Test
    void 완료_시각이_상태와_맞지_않는_행이_있으면_V8은_아무것도_바꾸기_전에_실패하고_고친_뒤_이어서_적용된다() {
        String url = freshV7Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            seedV7(jdbc);
            Map<Long, Map<String, Object>> legacyBefore = legacyColumns(jdbc);
            long locksBefore = jdbc.queryForObject("SELECT COUNT(*) FROM exchange_ticket_lock", Long.class);
            // CHATTING 인데 완료 시각이 있는 행 (V5 에는 이를 막는 CHECK 가 없었다)
            jdbc.update("UPDATE exchange_match SET a_completed_at = NOW(6) WHERE id = 1");

            org.junit.jupiter.api.Assertions.assertThrows(org.flywaydb.core.api.FlywayException.class, () -> flyway(url, "8").migrate());

            // 원본 보존: 새 컬럼도 새 제약도 하나도 만들어지지 않았다
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME IN ('reserved_by_id','reserved_at')", Integer.class)).isZero();
            assertThat(constraints(jdbc)).doesNotContain("ck_exchange_match_reserved", "ck_exchange_match_completed");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class)).isEqualTo(1);

            // 복구: 행을 고치고 repair 후 다시 적용
            jdbc.update("UPDATE exchange_match SET a_completed_at = NULL WHERE id = 1");
            flyway(url, "8").repair();
            assertThat(flyway(url, "8").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc, legacyBefore, locksBefore);
        }
    }

    @Test
    void COMPLETED인데_완료_시각이_없는_행이_있어도_V8은_SIGNAL로_중단된다() {
        String url = freshV7Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            seedV7(jdbc);
            jdbc.update("UPDATE exchange_match SET status = 'COMPLETED' WHERE id = 1");

            org.junit.jupiter.api.Assertions.assertThrows(org.flywaydb.core.api.FlywayException.class, () -> flyway(url, "8").migrate());

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME = 'reserved_by_id'", Integer.class)).isZero();
        }
    }

    @Test
    void 이관_뒤_새_제약이_잘못된_행을_거부한다() {
        String url = freshV7Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            seedV7(jdbc);
            assertThat(flyway(url, "8").migrate().migrationsExecuted).isEqualTo(1);

            // CHATTING 에 예약자 / RESERVED 를 예약자 NULL 로 / 예약자가 제3자 / CHATTING 에 수락 표시 / COMPLETED 에 수락 표시 없음
            assertRejected(jdbc, "UPDATE exchange_match SET reserved_by_id = user_a_id, reserved_at = NOW(6) WHERE id = 1", "ck_exchange_match_reserved");
            assertRejected(jdbc, "UPDATE exchange_match SET reserved_by_id = NULL, reserved_at = NULL WHERE id = 3", "ck_exchange_match_reserved");
            assertRejected(jdbc, "UPDATE exchange_match SET reserved_by_id = (SELECT id FROM users WHERE email = 'h@t' LIMIT 1) WHERE id = 3",
                    "ck_exchange_match_reserved_by_party");
            assertRejected(jdbc, "UPDATE exchange_match SET a_completed_at = NOW(6) WHERE id = 1", "ck_exchange_match_completed");
            assertRejected(jdbc, "UPDATE exchange_match SET status = 'COMPLETED', reserved_by_id = NULL, reserved_at = NULL WHERE id = 3",
                    "ck_exchange_match_completed");
            // RESERVED -> CHATTING 은 reserved_* 를 같은 문장에서 비워야 통과한다
            assertRejected(jdbc, "UPDATE exchange_match SET status = 'CHATTING' WHERE id = 3", "ck_exchange_match_reserved");
            assertThat(jdbc.update("UPDATE exchange_match SET status = 'CHATTING', reserved_by_id = NULL, reserved_at = NULL, "
                    + "a_completed_at = NULL, b_completed_at = NULL WHERE id = 3")).isEqualTo(1);
        }
    }

    private static void assertRejected(JdbcTemplate jdbc, String sql, String constraint) {
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    private static List<String> constraints(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_match'", String.class);
    }

    /** 레거시 컬럼 값(이관 전후 불변이어야 한다). */
    private static Map<Long, Map<String, Object>> legacyColumns(JdbcTemplate jdbc) {
        Map<Long, Map<String, Object>> result = new java.util.TreeMap<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT id, a_reserved_at, b_reserved_at FROM exchange_match")) {
            result.put(((Number) row.get("id")).longValue(), row);
        }
        return result;
    }

    /**
     * 사용자 1~8 (각자 티켓·요청 1개), 사용자 9(제3자 'h@t'). 매칭 쌍: m1(1,2) m2(3,4) m3(5,6) m4(7,8) m5(1,3) m6(2,4) m7(1,2 취소).
     * 열린 매칭의 요청 쌍이 겹치지 않고, RESERVED 4건(m3~m6)은 티켓이 겹치지 않아 잠금 PK 와 충돌하지 않는다.
     */
    private static void seedV7(JdbcTemplate jdbc) {
        for (int i = 1; i <= 8; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,?,NOW(6))",
                    i, "u" + i + "@t", "x", "U" + i, "USER");
        }
        jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (9,'h@t','x','H','USER',NOW(6))");
        jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) VALUES (1,1,'k','http://x','T','V',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO performance_session(id,performance_id,starts_at,created_at) VALUES (1,1,'2030-01-01 19:00:00',NOW(6))");
        for (int i = 1; i <= 8; i++) {
            jdbc.update("INSERT INTO ticket(id,user_id,performance_session_id,zone_label,zone_key,row_label,row_key,col_label,col_key,status,created_at,updated_at) "
                    + "VALUES (?,?,1,'A','A','1','1',?,?,'ACTIVE',NOW(6),NOW(6))", i, i, String.valueOf(i), String.valueOf(i));
            jdbc.update("INSERT INTO exchange_request(id,ticket_id,status,created_at,updated_at) VALUES (?,?,'OPEN',NOW(6),NOW(6))", i, i);
        }
        String ins = "INSERT INTO exchange_match(id,request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,"
                + "a_reserved_at,b_reserved_at,canceled_at,created_at,updated_at,a_extra_type,b_extra_type) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,NOW(6),?,'ANY','ANY')";
        String updated = "2030-02-02 10:00:00.000000";
        // ① CHATTING 무예약 ② CHATTING 반쪽 동의(a만)
        jdbc.update(ins, 1, 1, 2, 1, 2, 1, 2, "CHATTING", null, null, null, updated);
        jdbc.update(ins, 2, 3, 4, 3, 4, 3, 4, "CHATTING", "2030-01-10 09:00:00", null, null, updated);
        // ③ a 가 이르다 ④ b 가 이르다 ⑤ 동시 ⑥ 둘 다 NULL(비정상)
        jdbc.update(ins, 3, 5, 6, 5, 6, 5, 6, "RESERVED", "2030-01-10 10:00:00", "2030-01-10 11:00:00", null, updated);
        jdbc.update(ins, 4, 7, 8, 7, 8, 7, 8, "RESERVED", "2030-01-10 11:00:00", "2030-01-10 10:00:00", null, updated);
        jdbc.update(ins, 5, 1, 3, 1, 3, 1, 3, "RESERVED", "2030-01-10 10:00:00", "2030-01-10 10:00:00", null, updated);
        jdbc.update(ins, 6, 2, 4, 2, 4, 2, 4, "RESERVED", null, null, null, updated);
        // ⑦ CANCELED 인데 옛 예약 시각이 남아 있다
        jdbc.update(ins, 7, 1, 2, 1, 2, 1, 2, "CANCELED", "2030-01-09 10:00:00", "2030-01-09 10:30:00", "2030-01-09 12:00:00", updated);
        // 잠금 행 (RESERVED 4건 x 2)
        for (long[] lock : new long[][]{{5, 3}, {6, 3}, {7, 4}, {8, 4}, {1, 5}, {3, 5}, {2, 6}, {4, 6}}) {
            jdbc.update("INSERT INTO exchange_ticket_lock(ticket_id,match_id,created_at) VALUES (?,?,NOW(6))", lock[0], lock[1]);
        }
    }

    private static void assertMigrated(JdbcTemplate jdbc, Map<Long, Map<String, Object>> legacyBefore, long locksBefore) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, user_a_id, user_b_id, status, reserved_by_id, reserved_at, updated_at FROM exchange_match ORDER BY id");
        assertThat(rows).hasSize(7);
        // ①②⑦ 와 그 밖의 비RESERVED 행: reserved_* 는 NULL (반쪽 동의는 변환하지 않는다)
        for (long id : new long[]{1, 2, 7}) {
            Map<String, Object> r = rows.get((int) id - 1);
            assertThat(r.get("reserved_by_id")).as("m" + id + " reserved_by_id").isNull();
            assertThat(r.get("reserved_at")).as("m" + id + " reserved_at").isNull();
        }
        // ③ a 이른 시각 -> a측, 그 시각
        assertThat(((Number) rows.get(2).get("reserved_by_id")).longValue()).isEqualTo(((Number) rows.get(2).get("user_a_id")).longValue());
        assertThat(rows.get(2).get("reserved_at").toString()).startsWith("2030-01-10T10:00");
        // ④ b 이른 시각 -> b측, 그 시각
        assertThat(((Number) rows.get(3).get("reserved_by_id")).longValue()).isEqualTo(((Number) rows.get(3).get("user_b_id")).longValue());
        assertThat(rows.get(3).get("reserved_at").toString()).startsWith("2030-01-10T10:00");
        // ⑤ 동시 -> a측
        assertThat(((Number) rows.get(4).get("reserved_by_id")).longValue()).isEqualTo(((Number) rows.get(4).get("user_a_id")).longValue());
        assertThat(rows.get(4).get("reserved_at").toString()).startsWith("2030-01-10T10:00");
        // ⑥ 둘 다 NULL -> a측, updated_at 폴백(CHECK 만족)
        assertThat(((Number) rows.get(5).get("reserved_by_id")).longValue()).isEqualTo(((Number) rows.get(5).get("user_a_id")).longValue());
        assertThat(rows.get(5).get("reserved_at")).isEqualTo(rows.get(5).get("updated_at"));

        // 레거시 컬럼 값 불변, 잠금 행 불변
        assertThat(legacyColumns(jdbc)).isEqualTo(legacyBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_ticket_lock", Long.class)).isEqualTo(locksBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_match m WHERE m.status = 'RESERVED' AND "
                + "(SELECT COUNT(*) FROM exchange_ticket_lock l WHERE l.match_id = m.id) <> 2", Long.class)).isZero();

        // 새 FK·인덱스·CHECK, 레거시 컬럼의 DEPRECATED 표시와 타입 유지
        assertThat(constraints(jdbc)).contains("fk_exchange_match_reserved_by", "ck_exchange_match_reserved",
                "ck_exchange_match_reserved_by_party", "ck_exchange_match_completed", "ck_exchange_match_canceled");
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_match' AND INDEX_NAME = 'idx_exchange_match_reserved_by'", String.class))
                .as("FK 전용 단일 컬럼 인덱스").containsExactly("reserved_by_id");
        assertThat(jdbc.queryForList("SELECT COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME IN ('a_reserved_at','b_reserved_at')", String.class))
                .hasSize(2).allSatisfy(c -> assertThat(c).startsWith("DEPRECATED"));
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME = 'a_reserved_at'", String.class)).isEqualTo("YES");
    }
}
