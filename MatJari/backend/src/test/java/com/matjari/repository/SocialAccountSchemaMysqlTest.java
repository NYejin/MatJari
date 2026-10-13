package com.matjari.repository;

import com.matjari.testsupport.RealMysqlSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V14(social_account) 스키마 검증 (실제 MySQL, MATJARI_IT_* 환경변수가 있을 때만).
 * 다른 IT 와 데이터가 섞이지 않게 `X_socialschema_it` 별도 DB 를 쓴다. Spring 컨텍스트 없이 Flyway + JdbcTemplate 만 쓴다
 * (엔티티 매핑 검증은 엔티티 담당 테스트 몫).
 * 확인 항목: V1~V14 전체 적용과 V14 재실행 안전, 컬럼·제약·인덱스 이름, FK 인덱스 단일 컬럼,
 * UNIQUE(provider, provider_user_id) 위반, provider CHECK 위반, FK 위반, 같은 사용자의 다른 provider 허용.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
class SocialAccountSchemaMysqlTest {

    private static final String INSERT = "INSERT INTO social_account (user_id, provider, provider_user_id) VALUES (?,?,?)";

    private static String socialUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "").replaceAll("_socialschema$", "") + "_socialschema_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    private JdbcTemplate jdbc;

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(socialUrl(), RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    @BeforeEach
    void setUp() {
        RealMysqlSupport.assertSafe();
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(socialUrl().replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_socialschema_it");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(socialUrl(), RealMysqlSupport.user(), RealMysqlSupport.password()));
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_socialschema_it");
        flyway("14").clean();
        assertThat(flyway("14").migrate().migrationsExecuted).as("V1~V14 전체 적용").isEqualTo(14);
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,'USER',NOW(6))",
                    i, "u" + i + "@t", "x", "U" + i);
        }
    }

    private void assertRejected(String constraint, Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    private List<String> indexColumns(String index) {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'social_account' AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX", String.class, index);
    }

    private String columnType(String column) {
        return jdbc.queryForObject("SELECT CONCAT(COLUMN_TYPE, ' ', IFNULL(COLLATION_NAME, '-'), ' ', IS_NULLABLE) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'social_account' AND COLUMN_NAME = ?", String.class, column);
    }

    @Test
    void 마이그레이션은_재실행해도_안전하고_제약_이름과_컬럼이_스펙대로다() {
        assertThat(jdbc.update(INSERT, 1, "GOOGLE", "sub-1")).isEqualTo(1);

        jdbc.update("DELETE FROM flyway_schema_history WHERE version = '14'");
        assertThat(flyway("14").migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_account", Integer.class)).as("재실행이 데이터를 지우지 않는다").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                .as("임시 프로시저가 남지 않는다").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME <> 'flyway_schema_history'", Integer.class)).as("19 + 1 테이블").isEqualTo(20);

        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'social_account' ORDER BY ORDINAL_POSITION", String.class))
                .containsExactly("id", "user_id", "provider", "provider_user_id", "created_at");
        assertThat(columnType("provider")).isEqualTo("varchar(20) utf8mb4_bin NO");
        assertThat(columnType("provider_user_id")).isEqualTo("varchar(100) utf8mb4_bin NO");
        assertThat(columnType("created_at")).isEqualTo("datetime(6) - NO");
        assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL FROM social_account", Boolean.class))
                .as("created_at 기본값 CURRENT_TIMESTAMP(6)").isTrue();

        assertThat(jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'social_account' AND CONSTRAINT_TYPE IN ('FOREIGN KEY','UNIQUE','CHECK')", String.class))
                .containsExactlyInAnyOrder("uk_social_account_provider_user", "fk_social_account_user",
                        "ck_social_account_provider", "ck_social_account_provider_user_id");
        assertThat(indexColumns("uk_social_account_provider_user")).containsExactly("provider", "provider_user_id");
        assertThat(indexColumns("idx_social_account_user")).as("FK 인덱스는 단일 컬럼").containsExactly("user_id");
        assertThat(jdbc.queryForList("SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME = 'fk_social_account_user'", String.class)).containsExactly("NO ACTION");
    }

    @Test
    void 같은_제공자_식별자는_한_번만_연결된다() {
        assertThat(jdbc.update(INSERT, 1, "KAKAO", "12345")).isEqualTo(1);
        assertRejected("uk_social_account_provider_user", () -> jdbc.update(INSERT, 1, "KAKAO", "12345"));
        assertRejected("uk_social_account_provider_user", () -> jdbc.update(INSERT, 2, "KAKAO", "12345"));
        // 다른 제공자의 같은 식별자는 별개 계정
        assertThat(jdbc.update(INSERT, 2, "NAVER", "12345")).isEqualTo(1);
        // 식별자는 바이트 비교(utf8mb4_bin): 대소문자가 다르면 다른 계정
        assertThat(jdbc.update(INSERT, 1, "NAVER", "AbC")).isEqualTo(1);
        assertThat(jdbc.update(INSERT, 2, "NAVER", "abc")).isEqualTo(1);
        assertRejected("uk_social_account_provider_user", () -> jdbc.update(INSERT, 2, "NAVER", "AbC"));
    }

    @Test
    void provider_CHECK와_FK가_잘못된_행을_거부한다() {
        assertRejected("ck_social_account_provider", () -> jdbc.update(INSERT, 1, "FACEBOOK", "x"));
        assertRejected("ck_social_account_provider", () -> jdbc.update(INSERT, 1, "google", "x"));
        assertRejected("ck_social_account_provider", () -> jdbc.update(INSERT, 1, "", "x"));
        assertRejected("ck_social_account_provider_user_id", () -> jdbc.update(INSERT, 1, "GOOGLE", "   "));
        assertThatThrownBy(() -> jdbc.update(INSERT, 1, null, "x")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT, 1, "GOOGLE", null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT, null, "GOOGLE", "x")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(INSERT, 1, "GOOGLE", "x".repeat(101))).isInstanceOf(DataAccessException.class);
        assertRejected("fk_social_account_user", () -> jdbc.update(INSERT, 999, "GOOGLE", "x"));
        // 연결된 사용자는 지울 수 없다(ON DELETE 동작 없음, 탈퇴는 익명화)
        assertThat(jdbc.update(INSERT, 1, "GOOGLE", "x".repeat(100))).isEqualTo(1);
        assertRejected("fk_social_account_user", () -> jdbc.update("DELETE FROM users WHERE id = 1"));
    }

    @Test
    void 같은_사용자는_여러_제공자를_연결할_수_있다() {
        assertThat(jdbc.update(INSERT, 1, "GOOGLE", "g-1")).isEqualTo(1);
        assertThat(jdbc.update(INSERT, 1, "KAKAO", "k-1")).isEqualTo(1);
        assertThat(jdbc.update(INSERT, 1, "NAVER", "n-1")).isEqualTo(1);
        // (user_id, provider) 유니크는 두지 않는다(설계 3절)
        assertThat(jdbc.update(INSERT, 1, "GOOGLE", "g-2")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM social_account WHERE user_id = 1", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT user_id FROM social_account WHERE provider = 'KAKAO' AND provider_user_id = 'k-1'", Long.class))
                .isEqualTo(1L);
    }
}
