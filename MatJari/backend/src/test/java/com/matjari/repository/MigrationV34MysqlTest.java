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
 * V33 상태의 DB(공연·링크·구역이 이미 있는)를 V34(추가 링크 지도)로 올리는 이관 시나리오 (실제 MySQL).
 * 별도 DB `X_mig34_it` 를 만든다(이름은 `_it` 로 끝난다). 확인: 기존 구역 데이터 유지(source_id NULL), 기존 링크 행 map_status NONE,
 * 이름 유일 제약이 (name_key, performance_id, source_scope) 로 바뀜(대표 지도 안에서는 여전히 유일, 추가 지도에는 같은 이름 가능),
 * 지도 컬럼 CHECK, FK 인덱스 단일 컬럼, 두 번 실행·일부만 적용된 상태에서 이어 적용해도 안전, 임시 프로시저가 남지 않음.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
class MigrationV34MysqlTest {

    private static String migrationUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "") + "_mig34_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    private Flyway flyway(String url, String target) {
        return Flyway.configure().dataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    @Test
    void V33에서_V34를_올리면_기존_데이터가_유지되고_구역_유일_제약이_지도_단위가_되며_재실행해도_안전하다() {
        RealMysqlSupport.assertSafe();
        String url = migrationUrl();
        flyway(url, "33").clean();
        assertThat(flyway(url, "33").migrate().migrationsExecuted).isEqualTo(33);
        try (SingleConnectionDataSource ds = new SingleConnectionDataSource(url, RealMysqlSupport.user(), RealMysqlSupport.password(), true)) {
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_mig34_it");
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (1,'u1@t','x','U1','USER',NOW(6))");
            jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) "
                    + "VALUES (1,1,'melon:100','https://ticket.melon.com/performance/index.htm?prodId=100','T1','V',NOW(6),NOW(6))");
            jdbc.update("INSERT INTO performance_source(performance_id,source_key,source_url,added_by_id,created_at) VALUES (1,'melon:100','u',1,NOW(6))");
            jdbc.update("INSERT INTO performance_zone(performance_id,name,name_key,sort_order,created_at) VALUES (1,'A','A',0,NOW(6))");
            jdbc.update("INSERT INTO performance_zone(performance_id,name,name_key,sort_order,created_at) VALUES (1,'B','B',1,NOW(6))");

            assertThat(flyway(url, "34").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);

            // 재실행: history 에서 V34 만 지우고 다시 적용해도 같다
            jdbc.update("DELETE FROM flyway_schema_history WHERE version = '34'");
            assertThat(flyway(url, "34").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);

            // 일부만 적용된 상태(새 유니크 없이 옛 유니크만 있는 상태)에서 이어 적용해도 끝까지 간다
            jdbc.execute("ALTER TABLE performance_zone DROP INDEX uk_performance_zone_name_key");
            jdbc.execute("ALTER TABLE performance_zone ADD CONSTRAINT uk_performance_zone_name_key UNIQUE (name_key, performance_id)");
            jdbc.update("DELETE FROM flyway_schema_history WHERE version = '34'");
            assertThat(flyway(url, "34").migrate().migrationsExecuted).isEqualTo(1);
            assertMigrated(jdbc);

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                    .as("임시 프로시저가 남지 않는다").isZero();
        }
    }

    private static void assertMigrated(JdbcTemplate jdbc) {
        // 기존 구역은 그대로이고 source_id 는 NULL(대표 지도)
        assertThat(jdbc.queryForList("SELECT name FROM performance_zone WHERE source_id IS NULL ORDER BY sort_order", String.class))
                .containsExactly("A", "B");
        // 기존 링크 행은 지도 없음
        assertThat(jdbc.queryForObject("SELECT map_status FROM performance_source WHERE source_key = 'melon:100'", String.class)).isEqualTo("NONE");

        // 유일 제약: (name_key, performance_id, source_scope), 이름은 그대로, 비유일 인덱스가 아니다
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'performance_zone' AND INDEX_NAME = 'uk_performance_zone_name_key' AND NON_UNIQUE = 0 ORDER BY SEQ_IN_INDEX",
                String.class)).containsExactly("name_key", "performance_id", "source_scope");
        // FK 인덱스는 단일 컬럼
        for (String idx : List.of("idx_performance_zone_performance", "idx_performance_zone_source")) {
            assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'performance_zone' AND INDEX_NAME = ?", String.class, idx)).as(idx + " 는 단일 컬럼").hasSize(1);
        }

        // 대표 지도 안에서는 같은 이름이 여전히 안 된다
        assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_zone(performance_id,name,name_key,sort_order,created_at) VALUES (1,'A','A',9,NOW(6))"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_performance_zone_name_key");
        // 추가 링크 지도에는 같은 이름이 하나씩 가능하고, 그 안에서는 유일하다
        jdbc.update("INSERT INTO performance_source(performance_id,source_key,source_url,created_at,map_status) "
                + "VALUES (1,'interpark:1','u',NOW(6),'READY')");
        Long sid = jdbc.queryForObject("SELECT id FROM performance_source WHERE source_key = 'interpark:1'", Long.class);
        try {
            jdbc.update("INSERT INTO performance_zone(performance_id,source_id,name,name_key,sort_order,created_at) VALUES (1,?,'A','A',0,NOW(6))", sid);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_zone(performance_id,source_id,name,name_key,sort_order,created_at) "
                    + "VALUES (1,?,'A','A',1,NOW(6))", sid)).isInstanceOf(DataAccessException.class).hasMessageContaining("uk_performance_zone_name_key");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_zone(performance_id,source_id,name,name_key,sort_order,created_at) "
                    + "VALUES (1,999999,'Z','Z',0,NOW(6))")).isInstanceOf(DataAccessException.class).hasMessageContaining("fk_performance_zone_source");
        } finally {
            jdbc.update("DELETE FROM performance_zone WHERE source_id IS NOT NULL");
            jdbc.update("DELETE FROM performance_source WHERE source_key = 'interpark:1'");
        }

        // CHECK: 상태 값, 비율, 이미지 위치(다섯 값이 함께)
        assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_source(performance_id,source_key,source_url,created_at,map_status) "
                + "VALUES (1,'interpark:2','u',NOW(6),'BOGUS')")).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_performance_source_map_status");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_source(performance_id,source_key,source_url,created_at,seatmap_aspect) "
                + "VALUES (1,'interpark:3','u',NOW(6),0)")).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_performance_source_seatmap_aspect");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_source(performance_id,source_key,source_url,created_at,seatmap_image_url) "
                + "VALUES (1,'interpark:4','u',NOW(6),'https://x')")).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_performance_source_seatmap_image");
    }
}
