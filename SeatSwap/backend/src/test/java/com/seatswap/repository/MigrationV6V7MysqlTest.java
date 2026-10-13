package com.seatswap.repository;

import com.seatswap.testsupport.RealMysqlSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 요청 행이 이미 있는 V5 상태의 DB 를 V6, V7 로 올리는 이관 시나리오 (실제 MySQL). 다른 IT 클래스와 스키마를 공유하지 않도록
 * 환경변수의 DB 이름 `X_it` 대신 `X_mig_it` 라는 별도 DB 를 만들어(createDatabaseIfNotExist) 쓴다. 이름은 반드시 `_it` 로 끝난다.
 * 검증: 요청 단위 추가금이 모든 범위·좌석·매칭 스냅샷으로 복사되고, 요청 컬럼과 옛 CHECK/UNIQUE 가 사라지며,
 * 이미 적용된 상태에서 history 만 지우고 다시 적용해도(재실행) 데이터가 그대로이고 오류가 없다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
class MigrationV6V7MysqlTest {

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

    @Test
    void V5에서_요청_행이_있는_채로_V7까지_올려도_추가금이_범위_좌석_매칭으로_이관된다() {
        RealMysqlSupport.assertSafe();   // 환경변수 DB 안전장치 (호스트 localhost, 이름 _it)
        String url = migrationUrl();
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(url.replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_mig_it");

        flyway(url, "5").clean();
        assertThat(flyway(url, "5").migrate().migrationsExecuted).isEqualTo(5);

        try (SingleConnectionDataSource ds = new SingleConnectionDataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password(), true)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_mig_it");
            seedV5(jdbc);

            // V6, V7 적용
            assertThat(flyway(url, "7").migrate().migrationsExecuted).isEqualTo(2);
            assertMigrated(jdbc);

            // 재실행: history 만 지우고 다시 적용해도 프로시저 가드로 아무것도 바뀌지 않는다
            jdbc.update("DELETE FROM flyway_schema_history WHERE version IN ('6','7')");
            assertThat(flyway(url, "7").migrate().migrationsExecuted).isEqualTo(2);
            assertMigrated(jdbc);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                    .as("임시 프로시저가 남지 않는다").isZero();
        }
    }

    private String freshV5Database() {
        RealMysqlSupport.assertSafe();
        String url = migrationUrl();
        flyway(url, "5").clean();
        assertThat(flyway(url, "5").migrate().migrationsExecuted).isEqualTo(5);
        return url;
    }

    private SingleConnectionDataSource open(String url) {
        return new SingleConnectionDataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password(), true);
    }

    @Test
    void V6가_중간까지만_적용된_DB에서도_이어서_끝까지_적용된다() {
        String url = freshV5Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            seedV5(jdbc);
            // V6 의 1~3 단계 중 범위 테이블 쪽만 끝난 상태를 만든다 (컬럼 추가 -> 백필 -> NOT NULL + CHECK), 좌석·매칭·요청은 그대로
            jdbc.execute("ALTER TABLE exchange_want_range ADD COLUMN extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER col_to, "
                    + "ADD COLUMN extra_amount INT NULL AFTER extra_type");
            jdbc.update("UPDATE exchange_want_range r JOIN exchange_request q ON q.id = r.request_id SET r.extra_type = q.extra_type, r.extra_amount = q.extra_amount");
            jdbc.execute("ALTER TABLE exchange_want_range MODIFY COLUMN extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, "
                    + "ADD CONSTRAINT ck_exchange_want_range_extra_type CHECK (extra_type IN ('X','ANY','POS','NEG')), "
                    + "ADD CONSTRAINT ck_exchange_want_range_amount CHECK ((extra_type IN ('X','ANY') AND extra_amount IS NULL) "
                    + "OR (extra_type = 'POS' AND extra_amount IS NOT NULL AND extra_amount > 0) OR (extra_type = 'NEG' AND extra_amount IS NOT NULL AND extra_amount < 0))");
            // 좌석 쪽은 컬럼만 추가된 채 중단된 상태
            jdbc.execute("ALTER TABLE exchange_want_seat ADD COLUMN extra_type VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL, ADD COLUMN extra_amount INT NULL");

            assertThat(flyway(url, "7").migrate().migrationsExecuted).isEqualTo(2);
            assertMigrated(jdbc);
        }
    }

    @Test
    void 레거시_POS_금액NULL_행이_있으면_V6는_아무것도_바꾸기_전에_실패하고_고친_뒤_이어서_적용된다() {
        String url = freshV5Database();
        try (SingleConnectionDataSource ds = open(url)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            seedV5(jdbc);
            // V4 의 요청 CHECK 는 식이 NULL 이면 통과하므로 이런 행을 만들 수 있었다
            jdbc.update("UPDATE exchange_request SET extra_type='POS', extra_amount=NULL WHERE id=3");

            org.junit.jupiter.api.Assertions.assertThrows(org.flywaydb.core.api.FlywayException.class, () -> flyway(url, "7").migrate());

            // 원본 보존: 요청 컬럼과 값이 그대로, 새 컬럼은 하나도 만들어지지 않았다
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'exchange_request' AND COLUMN_NAME IN ('extra_type','extra_amount')", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT extra_type FROM exchange_request WHERE id=3", String.class)).isEqualTo("POS");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND COLUMN_NAME IN ('extra_type','extra_amount') AND TABLE_NAME IN ('exchange_want_range','exchange_want_seat')", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_request", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class)).isEqualTo(1);

            // 복구: 행을 고치고 repair 후 다시 적용
            jdbc.update("UPDATE exchange_request SET extra_type='X', extra_amount=NULL WHERE id=3");
            flyway(url, "7").repair();
            assertThat(flyway(url, "7").migrate().migrationsExecuted).isEqualTo(2);
            assertMigrated(jdbc);
        }
    }

    private static void seedV5(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES "
                + "(1,'a@t','x','A','USER',NOW(6)),(2,'b@t','x','B','USER',NOW(6)),(3,'c@t','x','C','USER',NOW(6)),(4,'d@t','x','D','USER',NOW(6))");
        jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) VALUES (1,1,'k','http://x','T','V',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO performance_session(id,performance_id,starts_at,created_at) VALUES (1,1,'2030-01-01 19:00:00',NOW(6)),(2,1,'2030-01-02 19:00:00',NOW(6))");
        jdbc.update("INSERT INTO ticket(id,user_id,performance_session_id,zone_label,zone_key,row_label,row_key,col_label,col_key,status,created_at,updated_at) VALUES "
                + "(1,1,1,'A','A','1','1','1','1','ACTIVE',NOW(6),NOW(6)),(2,2,1,'A','A','2','2','2','2','ACTIVE',NOW(6),NOW(6)),"
                + "(3,3,1,'A','A','3','3','3','3','ACTIVE',NOW(6),NOW(6)),(4,4,1,'A','A','4','4','4','4','ACTIVE',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO exchange_request(id,ticket_id,extra_type,extra_amount,status,created_at,updated_at) VALUES "
                + "(1,1,'POS',5000,'OPEN',NOW(6),NOW(6)),(2,2,'NEG',-3000,'OPEN',NOW(6),NOW(6)),(3,3,'X',NULL,'OPEN',NOW(6),NOW(6)),(4,4,'ANY',NULL,'CLOSED',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO exchange_want_range(request_id,zone_label,zone_key,row_from,row_to,col_from,col_to,sort_order) VALUES "
                + "(1,'A','A','2','2','2','2',0),(1,'A','A','5','5','1','2',1),(2,'A','A','1','1','1','1',0),(3,'A','A','4','4','4','4',0),(4,'A','A','3','3','3','3',0)");
        jdbc.update("INSERT INTO exchange_want_seat(request_id,zone_key,row_key,col_key) VALUES "
                + "(1,'A','2','2'),(1,'A','5','1'),(1,'A','5','2'),(2,'A','1','1'),(3,'A','4','4'),(4,'A','3','3')");
        jdbc.update("INSERT INTO exchange_want_session(request_id,performance_session_id,priority) VALUES (1,1,1),(2,1,1),(3,1,1),(4,1,1)");
        jdbc.update("INSERT INTO exchange_match(id,request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,created_at,updated_at) "
                + "VALUES (1,1,2,1,2,1,2,'CHATTING',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO exchange_match(id,request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,canceled_at,created_at,updated_at) "
                + "VALUES (2,3,4,3,4,3,4,'CANCELED',NOW(6),NOW(6),NOW(6))");
    }

    private static void assertMigrated(JdbcTemplate jdbc) {
        // 범위: 요청의 추가금이 그 요청의 모든 범위로
        assertThat(jdbc.queryForList("SELECT request_id, extra_type, extra_amount FROM exchange_want_range ORDER BY id"))
                .extracting(r -> r.get("request_id") + ":" + r.get("extra_type") + ":" + r.get("extra_amount"))
                .containsExactly("1:POS:5000", "1:POS:5000", "2:NEG:-3000", "3:X:null", "4:ANY:null");
        // 좌석
        assertThat(jdbc.queryForList("SELECT request_id, extra_type, extra_amount FROM exchange_want_seat ORDER BY request_id, row_key, col_key"))
                .extracting(r -> r.get("request_id") + ":" + r.get("extra_type") + ":" + r.get("extra_amount"))
                .containsExactly("1:POS:5000", "1:POS:5000", "1:POS:5000", "2:NEG:-3000", "3:X:null", "4:ANY:null");
        // 매칭 스냅샷: a/b 각 요청의 값
        List<Map<String, Object>> matches = jdbc.queryForList(
                "SELECT id, a_extra_type, a_extra_amount, b_extra_type, b_extra_amount FROM exchange_match ORDER BY id");
        assertThat(matches).extracting(r -> r.get("a_extra_type") + ":" + r.get("a_extra_amount") + "/" + r.get("b_extra_type") + ":" + r.get("b_extra_amount"))
                .containsExactly("POS:5000/NEG:-3000", "X:null/ANY:null");
        // 요청: 추가금 컬럼 제거, 기존 행은 모두 live (CLOSED 포함), 옛 CHECK/UNIQUE 제거, 새 키
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_request' AND COLUMN_NAME IN ('extra_type','extra_amount')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_request WHERE live_flag = 1 AND deleted_at IS NULL", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_request'", String.class))
                .contains("uk_exchange_request_live_ticket", "ck_exchange_request_status", "ck_exchange_request_deleted")
                .doesNotContain("uk_exchange_request_ticket", "ck_exchange_request_amount", "ck_exchange_request_extra_type");
        // NOT NULL 로 전환됐다
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_want_seat' AND COLUMN_NAME = 'extra_type'", String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME = 'b_extra_type'", String.class)).isEqualTo("NO");
    }
}
