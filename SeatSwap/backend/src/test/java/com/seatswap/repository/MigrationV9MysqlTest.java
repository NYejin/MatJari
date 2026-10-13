package com.seatswap.repository;

import com.seatswap.testsupport.RealMysqlSupport;
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
 * V8 상태의 DB(티켓·매칭 행이 이미 있는)를 V9(ticket.status 에 EXCHANGED, exchange_history)로 올리는 이관 시나리오 (실제 MySQL).
 * 다른 이관 테스트와 같이 환경변수의 DB 이름 `X_it` 대신 `X_mig_it` 라는 별도 DB 를 만들어 쓴다(이름은 반드시 `_it` 로 끝난다).
 * 확인: CHECK 가 EXCHANGED 를 허용하고 FOO 는 거부, 기존 데이터 불변, 이력 테이블의 제약·인덱스(FK 인덱스는 단일 컬럼), 두 번 실행해도 안전,
 * 중간까지만 적용된 상태에서 이어서 적용.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
class MigrationV9MysqlTest {

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

    private String freshV8Database() {
        RealMysqlSupport.assertSafe();
        String url = migrationUrl();
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(url.replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_mig_it");
        flyway(url, "8").clean();
        assertThat(flyway(url, "8").migrate().migrationsExecuted).isEqualTo(8);
        return url;
    }

    private SingleConnectionDataSource open(String url) {
        return new SingleConnectionDataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password(), true);
    }

    @Test
    void V8에서_V9를_올리면_EXCHANGED가_허용되고_FOO는_거부되며_기존_데이터는_그대로다() {
        String url = freshV8Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_mig_it");
            seedV8(jdbc);
            assertRejected(jdbc, "UPDATE ticket SET status = 'EXCHANGED' WHERE id = 1", "ck_ticket_status");   // V9 이전에는 거부

            assertThat(flyway(url, "9").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);

            // 재실행: history 에서 V9 만 지우고 다시 적용해도 가드로 아무것도 바뀌지 않는다
            jdbc.update("DELETE FROM flyway_schema_history WHERE version = '9'");
            assertThat(flyway(url, "9").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                    .as("임시 프로시저가 남지 않는다").isZero();
        }
    }

    @Test
    void V9가_중간까지만_적용된_DB에서도_이어서_끝까지_적용된다() {
        String url = freshV8Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            seedV8(jdbc);
            // 옛 CHECK 만 지워진 상태(1단계만 끝남)
            jdbc.execute("ALTER TABLE ticket DROP CHECK ck_ticket_status");
            assertThat(flyway(url, "9").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);

            // 새 CHECK 까지만 끝나고 테이블이 없는 상태
            jdbc.update("DELETE FROM flyway_schema_history WHERE version = '9'");
            jdbc.execute("DROP TABLE exchange_history");
            assertThat(flyway(url, "9").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);
        }
    }

    @Test
    void 이관_뒤_이력_제약이_잘못된_행을_거부한다() {
        String url = freshV8Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            seedV8(jdbc);
            assertThat(flyway(url, "9").migrate().migrationsExecuted).isEqualTo(1);

            String insert = "INSERT INTO exchange_history (match_id, user_id, old_ticket_id, new_ticket_id, performance_id, performance_title, venue_name, "
                    + "old_starts_at, new_starts_at, old_zone_label, old_row_label, old_col_label, new_zone_label, new_row_label, new_col_label, created_at) "
                    + "VALUES (?,?,?,?,?,'t','v',NOW(6),NOW(6),'a','1','1','b','2','2',NOW(6))";
            assertRejected(jdbc, insert, "ck_exchange_history_tickets", 1, 1, 1, 1, 1);
            assertRejected(jdbc, insert, "fk_exchange_history_match", 999, 1, 1, 2, 1);
            assertRejected(jdbc, insert, "fk_exchange_history_user", 1, 999, 1, 2, 1);
            assertRejected(jdbc, insert, "fk_exchange_history_old_ticket", 1, 1, 999, 2, 1);
            assertRejected(jdbc, insert, "fk_exchange_history_new_ticket", 1, 1, 1, 999, 1);
            assertRejected(jdbc, insert, "fk_exchange_history_performance", 1, 1, 1, 2, 999);
            assertThat(jdbc.update(insert, 1, 1, 1, 2, 1)).isEqualTo(1);
            assertRejected(jdbc, insert, "uk_exchange_history_old_ticket", 1, 2, 1, 3, 1);
            assertRejected(jdbc, insert, "uk_exchange_history_new_ticket", 1, 2, 3, 2, 1);
            // 같은 매칭·사용자 조합은 복합 UNIQUE 가 없다(이력 2행은 사용자가 다르지만 제약으로 묶지 않는다)
            assertThat(jdbc.update(insert, 1, 1, 3, 4, 1)).isEqualTo(1);
        }
    }

    private static void assertRejected(JdbcTemplate jdbc, String sql, String constraint, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    /** 사용자 1~2, 공연·회차 1, 티켓 1~4(ACTIVE x3, INACTIVE x1), 요청 1~2, 매칭 1(CHATTING, 티켓 1-2). */
    private static void seedV8(JdbcTemplate jdbc) {
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,?,NOW(6))",
                    i, "u" + i + "@t", "x", "U" + i, "USER");
        }
        jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) VALUES (1,1,'k','http://x','T','V',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO performance_session(id,performance_id,starts_at,created_at) VALUES (1,1,'2030-01-01 19:00:00',NOW(6))");
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO ticket(id,user_id,performance_session_id,zone_label,zone_key,row_label,row_key,col_label,col_key,status,created_at,updated_at) "
                    + "VALUES (?,?,1,'A','A','1','1',?,?,?,NOW(6),NOW(6))", i, (i % 2) + 1, String.valueOf(i), String.valueOf(i), i == 4 ? "INACTIVE" : "ACTIVE");
        }
        jdbc.update("INSERT INTO exchange_request(id,ticket_id,status,created_at,updated_at) VALUES (1,1,'OPEN',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO exchange_request(id,ticket_id,status,created_at,updated_at) VALUES (2,2,'OPEN',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO exchange_match(id,request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,"
                + "created_at,updated_at,a_extra_type,b_extra_type) VALUES (1,1,2,1,2,2,1,'CHATTING',NOW(6),NOW(6),'ANY','ANY')");
    }

    private static void assertMigrated(JdbcTemplate jdbc) {
        // CHECK: EXCHANGED 허용, FOO 거부. 새 정의 하나만 있다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME = 'ck_ticket_status' AND CHECK_CLAUSE LIKE '%EXCHANGED%'", Integer.class)).isEqualTo(1);
        assertRejected(jdbc, "UPDATE ticket SET status = ? WHERE id = 1", "ck_ticket_status", "FOO");
        // EXCHANGED 는 active_flag 가 NULL 이라 같은 좌석의 새 ACTIVE 티켓과 공존할 수 있다(롤백해 데이터는 그대로 둔다)
        assertThat(jdbc.update("UPDATE ticket SET status = 'EXCHANGED' WHERE id = 3")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT active_flag FROM ticket WHERE id = 3", Object.class)).isNull();
        jdbc.update("INSERT INTO ticket(id,user_id,performance_session_id,zone_label,zone_key,row_label,row_key,col_label,col_key,status,created_at,updated_at) "
                + "VALUES (100,1,1,'A','A','1','1','3','3','ACTIVE',NOW(6),NOW(6))");
        jdbc.update("DELETE FROM ticket WHERE id = 100");
        jdbc.update("UPDATE ticket SET status = 'ACTIVE' WHERE id = 3");

        // 기존 데이터 불변
        assertThat(jdbc.queryForList("SELECT status FROM ticket ORDER BY id", String.class))
                .containsExactly("ACTIVE", "ACTIVE", "ACTIVE", "INACTIVE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_match", Integer.class)).isEqualTo(1);

        // exchange_history: 컬럼·UNIQUE·단일 컬럼 FK 인덱스·FK 5개·CHECK
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_history'", Integer.class)).isEqualTo(1);
        List<String> columns = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_history' ORDER BY ORDINAL_POSITION", String.class);
        assertThat(columns).containsExactly("id", "match_id", "user_id", "old_ticket_id", "new_ticket_id", "performance_id",
                "performance_title", "venue_name", "old_starts_at", "new_starts_at", "old_zone_label", "old_row_label", "old_col_label",
                "new_zone_label", "new_row_label", "new_col_label", "created_at");
        for (String idx : List.of("idx_exchange_history_match", "idx_exchange_history_user", "idx_exchange_history_performance",
                "uk_exchange_history_old_ticket", "uk_exchange_history_new_ticket")) {
            assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'exchange_history' AND INDEX_NAME = ?", String.class, idx))
                    .as(idx + " 는 단일 컬럼").hasSize(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_history' AND CONSTRAINT_TYPE = 'FOREIGN KEY'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_history' AND CONSTRAINT_NAME = 'ck_exchange_history_tickets'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_history' AND INDEX_NAME NOT IN ('PRIMARY','idx_exchange_history_match','idx_exchange_history_user',"
                + "'idx_exchange_history_performance','uk_exchange_history_old_ticket','uk_exchange_history_new_ticket')", Integer.class))
                .as("복합 UNIQUE(match_id,user_id) 등 다른 인덱스는 없다").isZero();
    }
}
