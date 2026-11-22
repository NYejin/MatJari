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
 * V28 상태의 DB 를 V29(메일 알림·새 후보 알림)로 올리는 이관 시나리오 (실제 MySQL, MATJARI_IT_* 가 있을 때만).
 * 다른 이관 테스트처럼 `X_mig_it` 별도 DB 를 쓴다. CHECK 검증은 FK 부모 행을 만들지 않으려고 세션의 FOREIGN_KEY_CHECKS 를 끄고 한다
 * (FK 자체는 information_schema 로 확인한다). 확인: 재실행 안전, 기존 행 보존, CHECK 7종·match/request 상호 배타·NEW_CANDIDATE dedup 형식,
 * FK 인덱스 단일 컬럼, 설정 기본값, candidate_notice PK.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
class MigrationV29MysqlTest {

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

    @Test
    void V28에서_V29를_올리고_다시_실행해도_안전하며_제약이_동작한다() {
        RealMysqlSupport.assertSafe();
        String url = migrationUrl();
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(url.replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_mig_it");
        flyway(url, "28").clean();
        assertThat(flyway(url, "28").migrate().migrationsExecuted).isEqualTo(28);
        try (SingleConnectionDataSource ds = new SingleConnectionDataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password(), true)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_mig_it");
            for (int i = 1; i <= 2; i++) {
                jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,'USER',NOW(6))",
                        i, "u" + i + "@t", "x", "U" + i);
            }
            // V28 상태의 기존 알림(매칭 알림)은 새 CHECK 를 만족한다
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
            jdbc.update("INSERT INTO notification (user_id, type, match_id, dedup_key, created_at) VALUES (1,'MATCH_RESERVED',9,NULL,NOW(6))");

            assertThat(flyway(url, "29").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);
            // 재실행: history 에서 V29 만 지우고 다시 적용해도 가드로 아무것도 바뀌지 않는다
            jdbc.update("DELETE FROM flyway_schema_history WHERE version = '29'");
            assertThat(flyway(url, "29").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class)).as("재실행이 데이터를 지우지 않는다").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE emailed_at IS NULL AND request_id IS NULL", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                    .as("임시 프로시저가 남지 않는다").isZero();

            String insert = "INSERT INTO notification (user_id, type, match_id, request_id, dedup_key, created_at) VALUES (?,?,?,?,?,NOW(6))";
            // NEW_CANDIDATE 는 match_id NULL·request_id NOT NULL, dedup 형식 NEW_CANDIDATE:{request}:{user}
            assertThat(jdbc.update(insert, 1, "NEW_CANDIDATE", null, 5, "NEW_CANDIDATE:5:1")).isEqualTo(1);
            assertRejected(jdbc, insert, "ck_notification_new_candidate_dedup", 1, "NEW_CANDIDATE", null, 6, "NEW_CANDIDATE:5:1");
            assertRejected(jdbc, insert, "ck_notification_new_candidate_dedup", 1, "NEW_CANDIDATE", null, 6, null);
            assertRejected(jdbc, insert, "ck_notification_match_required", 1, "NEW_CANDIDATE", 9, 7, "NEW_CANDIDATE:7:1");
            assertRejected(jdbc, insert, "ck_notification_match_required", 1, "NEW_CANDIDATE", null, null, "NEW_CANDIDATE:null:1");
            assertRejected(jdbc, insert, "ck_notification_match_required", 1, "PROPOSAL_RECEIVED", 9, 7, null);
            assertRejected(jdbc, insert, "ck_notification_match_required", 1, "PROPOSAL_RECEIVED", null, 7, null);
            assertRejected(jdbc, insert, "ck_notification_type", 1, "FOO", 9, null, null);
            // 같은 (요청, 수신자) NEW_CANDIDATE 는 dedup 유니크로 1행
            assertRejected(jdbc, insert, "uk_notification_dedup", 1, "NEW_CANDIDATE", null, 5, "NEW_CANDIDATE:5:1");
            assertThat(jdbc.update(insert, 2, "CHAT_MESSAGE", 9, null, "CHAT_MESSAGE:9:2")).isEqualTo(1);

            // user_notification_setting: 행을 만들면 기본은 켜짐, last_email_at 은 NULL
            jdbc.update("INSERT INTO user_notification_setting (user_id, updated_at) VALUES (1, NOW(6))");
            assertThat(jdbc.queryForObject("SELECT email_enabled FROM user_notification_setting WHERE user_id = 1", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notification_setting WHERE last_email_at IS NULL", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_notification_setting WHERE email_verified_at IS NULL", Integer.class))
                    .as("이메일 소유 확인 시각은 기본 NULL(미확인)").isEqualTo(1);

            // candidate_notice: (받는 요청, 후보 티켓) 쌍은 하나(요청을 지웠다 다시 만들어도 같은 티켓이면 다시 알리지 않는다)
            jdbc.update("INSERT INTO candidate_notice (recipient_request_id, candidate_ticket_id, created_at) VALUES (1, 2, NOW(6))");
            assertThat(jdbc.update("INSERT IGNORE INTO candidate_notice (recipient_request_id, candidate_ticket_id, created_at) VALUES (1, 2, NOW(6))"))
                    .as("이미 알린 쌍은 다시 들어가지 않는다").isZero();
            assertThat(jdbc.update("INSERT IGNORE INTO candidate_notice (recipient_request_id, candidate_ticket_id, created_at) VALUES (2, 1, NOW(6))"))
                    .isEqualTo(1);
            jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private static void assertMigrated(JdbcTemplate jdbc) {
        for (String column : List.of("request_id", "emailed_at")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'notification' AND COLUMN_NAME = ?", Integer.class, column)).as(column).isEqualTo(1);
        }
        // FK 인덱스는 단일 컬럼이고 갱신 컬럼이 섞이지 않는다
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'notification' AND INDEX_NAME = 'idx_notification_request' ORDER BY SEQ_IN_INDEX", String.class))
                .containsExactly("request_id");
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'candidate_notice' AND INDEX_NAME = 'idx_candidate_notice_ticket' ORDER BY SEQ_IN_INDEX", String.class))
                .containsExactly("candidate_ticket_id");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_TYPE = 'FOREIGN KEY' AND CONSTRAINT_NAME IN ('fk_notification_request', 'fk_user_notification_setting_user', "
                + "'fk_candidate_notice_recipient', 'fk_candidate_notice_ticket')", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME = 'ck_notification_type'", String.class)).contains("NEW_CANDIDATE", "CHAT_MESSAGE", "PROPOSAL_RECEIVED");
    }

    private static void assertRejected(JdbcTemplate jdbc, String sql, String constraint, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }
}
