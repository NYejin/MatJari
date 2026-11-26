package com.matjari.repository;

import com.matjari.testsupport.RealMysqlSupport;
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
 * V31 상태의 DB(공연이 이미 있는)를 V32(performance_source)로 올리는 이관 시나리오 (실제 MySQL).
 * 별도 DB `X_mig32_it` 를 만든다(이름은 `_it` 로 끝난다). 확인: 기존 공연마다 대표 링크 한 행 백필(등록자·키·URL·생성 시각), 두 번 실행해도
 * 중복 행이 없음, 유일 키·FK·단일 컬럼 FK 인덱스, 임시 프로시저가 남지 않음.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
class MigrationV32MysqlTest {

    private static String migrationUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "") + "_mig32_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    @Test
    void V31에서_V32를_올리면_기존_공연의_대표_링크가_백필되고_재실행해도_안전하다() {
        RealMysqlSupport.assertSafe();
        String url = migrationUrl();
        flyway(url, "31").clean();
        assertThat(flyway(url, "31").migrate().migrationsExecuted).isEqualTo(31);
        try (SingleConnectionDataSource ds = new SingleConnectionDataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password(), true)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_mig32_it");
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (1,'u1@t','x','U1','USER',NOW(6))");
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (2,'u2@t','x','U2','USER',NOW(6))");
            jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) "
                    + "VALUES (1,1,'melon:100','https://ticket.melon.com/performance/index.htm?prodId=100','T1','V','2026-10-01 10:00:00','2026-10-01 10:00:00')");
            jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) "
                    + "VALUES (2,2,'url:Example.com/A','https://Example.com/A','T2','V','2026-10-02 10:00:00','2026-10-02 10:00:00')");

            assertThat(flyway(url, "32").migrate().migrationsExecuted).isEqualTo(1);
            assertBackfilled(jdbc);

            // 재실행: history 에서 V32 만 지우고 다시 적용해도 중복 없이 같다
            jdbc.update("DELETE FROM flyway_schema_history WHERE version = '32'");
            assertThat(flyway(url, "32").migrate().migrationsExecuted).isEqualTo(1);
            assertBackfilled(jdbc);
            // 백필이 일부만 된 상태에서 이어서 적용해도 빠진 공연만 채운다
            jdbc.update("DELETE FROM performance_source WHERE performance_id = 2");
            jdbc.update("DELETE FROM flyway_schema_history WHERE version = '32'");
            assertThat(flyway(url, "32").migrate().migrationsExecuted).isEqualTo(1);
            assertBackfilled(jdbc);

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                    .as("임시 프로시저가 남지 않는다").isZero();

            // 제약: 키 유일(대소문자 구분), FK, FK 인덱스는 단일 컬럼
            assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_source(performance_id,source_key,source_url,created_at) VALUES (2,'melon:100','u',NOW(6))"))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_performance_source_source_key");
            jdbc.update("INSERT INTO performance_source(performance_id,source_key,source_url,created_at) VALUES (2,'url:example.com/a','u',NOW(6))");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_source(performance_id,source_key,source_url,created_at) VALUES (999,'melon:7','u',NOW(6))"))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_performance_source_performance");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_source(performance_id,source_key,source_url,added_by_id,created_at) VALUES (2,'melon:8','u',999,NOW(6))"))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_performance_source_added_by");
            for (String idx : List.of("idx_performance_source_performance", "idx_performance_source_added_by")) {
                assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'performance_source' AND INDEX_NAME = ?", String.class, idx)).as(idx + " 는 단일 컬럼").hasSize(1);
            }
        }
    }

    private static void assertBackfilled(JdbcTemplate jdbc) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT performance_id, source_key, source_url, added_by_id, created_at FROM performance_source ORDER BY performance_id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("source_key", "melon:100").containsEntry("added_by_id", 1L);
        assertThat(rows.get(0).get("source_url")).isEqualTo("https://ticket.melon.com/performance/index.htm?prodId=100");
        assertThat(rows.get(1)).containsEntry("source_key", "url:Example.com/A").containsEntry("added_by_id", 2L);
        assertThat(rows.get(1).get("created_at")).isEqualTo(java.time.LocalDateTime.of(2026, 10, 2, 10, 0));
    }
}
