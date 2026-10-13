package com.matjari.repository;

import com.matjari.testsupport.RealMysqlSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V9 상태의 DB(사용자·티켓·매칭 행이 이미 있는)를 V10(notification)으로 올리는 이관 시나리오 (실제 MySQL).
 * 다른 이관 테스트와 같이 `X_mig_it` 별도 DB 를 쓴다(이름은 반드시 `_it` 로 끝난다).
 * 확인: 기존 데이터 불변, 재실행 안전(임시 프로시저가 남지 않음), CHECK(type·match_id 필수)·FK·dedup UNIQUE 거부,
 * FK 인덱스는 단일 컬럼이고 read_at 이 FK 인덱스에 섞이지 않음, 생성 컬럼 unread_user_id 동작.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
class MigrationV10MysqlTest {

    private static String migrationUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "") + "_mig_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    private String freshV9Database() {
        RealMysqlSupport.assertSafe();
        String url = migrationUrl();
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(url.replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_mig_it");
        flyway(url, "9").clean();
        assertThat(flyway(url, "9").migrate().migrationsExecuted).isEqualTo(9);
        return url;
    }

    private SingleConnectionDataSource open(String url) {
        return new SingleConnectionDataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password(), true);
    }

    @Test
    void V9에서_V10을_올리고_다시_실행해도_안전하며_기존_데이터는_그대로다() {
        String url = freshV9Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_mig_it");
            seedV9(jdbc);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification'",
                    Integer.class)).isZero();

            assertThat(flyway(url, "10").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);

            // 재실행: history 에서 V10 만 지우고 다시 적용해도 가드로 아무것도 바뀌지 않는다
            jdbc.update("INSERT INTO notification (user_id, type, match_id, dedup_key, created_at) VALUES (1,'MATCH_RESERVED',1,NULL,NOW(6))");
            jdbc.update("DELETE FROM flyway_schema_history WHERE version = '10'");
            assertThat(flyway(url, "10").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).as("재실행이 데이터를 지우지 않는다").isEqualTo(1);
            jdbc.update("DELETE FROM notification");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                    .as("임시 프로시저가 남지 않는다").isZero();

            // 기존 데이터 불변
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_match", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = 1", String.class)).isEqualTo("CHATTING");
        }
    }

    @Test
    void 이관_뒤_CHECK_FK_UNIQUE가_잘못된_행을_거부하고_생성_컬럼이_읽음을_따라간다() {
        String url = freshV9Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            seedV9(jdbc);
            assertThat(flyway(url, "10").migrate().migrationsExecuted).isEqualTo(1);

            String insert = "INSERT INTO notification (user_id, type, match_id, dedup_key, created_at) VALUES (?,?,?,?,NOW(6))";
            assertRejected(jdbc, insert, "ck_notification_type", 1, "FOO", 1, null);
            assertRejected(jdbc, insert, "ck_notification_match_required", 1, "MATCH_RESERVED", null, null);
            assertRejected(jdbc, insert, "fk_notification_user", 999, "MATCH_RESERVED", 1, null);
            assertRejected(jdbc, insert, "fk_notification_match", 1, "MATCH_RESERVED", 999, null);
            assertThat(jdbc.update(insert, 2, "PROPOSAL_RECEIVED", 1, "PROPOSAL_RECEIVED:1")).isEqualTo(1);
            assertRejected(jdbc, insert, "uk_notification_dedup", 2, "PROPOSAL_RECEIVED", 1, "PROPOSAL_RECEIVED:1");
            // dedup_key NULL 은 여러 번 허용(예약/예약 취소는 반복마다 1건)
            assertThat(jdbc.update(insert, 1, "MATCH_RESERVED", 1, null)).isEqualTo(1);
            assertThat(jdbc.update(insert, 1, "MATCH_RESERVED", 1, null)).isEqualTo(1);
            assertThat(jdbc.update(insert, 1, "RESERVATION_CANCELED", 1, null)).isEqualTo(1);

            // 생성 컬럼: 안 읽음이면 user_id, 읽으면 NULL
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE unread_user_id = user_id", Integer.class)).isEqualTo(4);
            jdbc.update("UPDATE notification SET read_at = NOW(6) WHERE user_id = 1");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE unread_user_id IS NOT NULL", Integer.class)).isEqualTo(1);
            // 부모 삭제는 FK 로 막힌다(ON DELETE 동작 없음)
            assertThatThrownBy(() -> jdbc.update("DELETE FROM exchange_match WHERE id = 1")).isInstanceOf(DataAccessException.class);
        }
    }

    private static void assertRejected(JdbcTemplate jdbc, String sql, String constraint, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    /** 사용자 1~2, 공연·회차 1, 티켓 1~2, 요청 1~2, 매칭 1(CHATTING, 티켓 1-2). */
    private static void seedV9(JdbcTemplate jdbc) {
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,?,NOW(6))",
                    i, "u" + i + "@t", "x", "U" + i, "USER");
        }
        jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) VALUES (1,1,'k','http://x','T','V',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO performance_session(id,performance_id,starts_at,created_at) VALUES (1,1,'2030-01-01 19:00:00',NOW(6))");
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO ticket(id,user_id,performance_session_id,zone_label,zone_key,row_label,row_key,col_label,col_key,status,created_at,updated_at) "
                    + "VALUES (?,?,1,'A','A','1','1',?,?,'ACTIVE',NOW(6),NOW(6))", i, (i % 2) + 1, String.valueOf(i), String.valueOf(i));
        }
        jdbc.update("INSERT INTO exchange_request(id,ticket_id,status,created_at,updated_at) VALUES (1,1,'OPEN',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO exchange_request(id,ticket_id,status,created_at,updated_at) VALUES (2,2,'OPEN',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO exchange_match(id,request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,"
                + "created_at,updated_at,a_extra_type,b_extra_type) VALUES (1,1,2,1,2,2,1,'CHATTING',NOW(6),NOW(6),'ANY','ANY')");
    }

    private static void assertMigrated(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification'",
                Integer.class)).isEqualTo(1);
        List<String> columns = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'notification' ORDER BY ORDINAL_POSITION", String.class);
        assertThat(columns).containsExactly("id", "user_id", "type", "match_id", "dedup_key", "read_at", "created_at", "unread_user_id");
        // 인덱스는 모두 단일 컬럼: FK 인덱스(user, match)에 read_at 같은 갱신 컬럼이 섞이지 않는다
        for (String idx : List.of("idx_notification_user", "idx_notification_match", "idx_notification_unread", "uk_notification_dedup")) {
            assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'notification' AND INDEX_NAME = ?", String.class, idx)).as(idx).hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'notification' AND CONSTRAINT_TYPE = 'FOREIGN KEY'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME IN ('ck_notification_type','ck_notification_match_required')", Integer.class)).isEqualTo(2);
    }
}
