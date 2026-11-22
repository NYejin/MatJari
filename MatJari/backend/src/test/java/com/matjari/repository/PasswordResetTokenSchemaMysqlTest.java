package com.matjari.repository;

import com.matjari.domain.PasswordResetToken;
import com.matjari.domain.RefreshToken;
import com.matjari.domain.RefreshToken.RevokeReason;
import com.matjari.testsupport.RealMysqlSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V28(password_reset_token + refresh_token 폐기 사유 PASSWORD_RESET) 스키마·엔티티·리포지토리 검증
 * (실제 MySQL, MATJARI_IT_* 환경변수가 있을 때만).
 * <ul>
 *   <li>마이그레이션 검증은 Spring 없이 Flyway + JdbcTemplate 로 별도 DB `X_mig_it` 에서 한다: V1~V27 → V28 적용, V28 재실행 안전,
 *       컬럼·제약·인덱스 이름, FK 인덱스 단일 컬럼, CHECK·UNIQUE·FK 위반, 기존 refresh_token 행 보존.</li>
 *   <li>엔티티 매핑(ddl-auto validate)과 리포지토리 쿼리는 MATJARI_IT_JDBC_URL DB 에 Spring 컨텍스트를 띄워 확인한다.</li>
 * </ul>
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetTokenSchemaMysqlTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final String INSERT = "INSERT INTO password_reset_token (user_id, token_hash, created_at, expires_at, used_at, invalidated_at, request_ip) "
            + "VALUES (?,?,?,?,?,?,?)";
    private static final String RT_INSERT = "INSERT INTO refresh_token (user_id, token_hash, family_id, family_started_at, created_at, expires_at, revoked_at, revoke_reason) "
            + "VALUES (?,?,?,?,?,?,?,?)";
    private static final String FAMILY = "11111111-1111-4111-8111-111111111111";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.cleanDatabase();
        registry.add("spring.datasource.url", RealMysqlSupport::url);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private PasswordResetTokenRepository repository;
    @Autowired
    private RefreshTokenRepository refreshTokens;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private Clock clock;

    /** 마이그레이션 검증 전용 DB(_mig_it). */
    private JdbcTemplate mig;

    private static String migUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "").replaceAll("_mig$", "") + "_mig_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    private Flyway migFlyway(String target) {
        return Flyway.configure().dataSource(migUrl(), RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbc.execute("TRUNCATE TABLE password_reset_token");
        jdbc.execute("TRUNCATE TABLE refresh_token");
        jdbc.execute("TRUNCATE TABLE users");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,'USER',NOW(6))",
                    i, "u" + i + "@t", "x", "U" + i);
        }
    }

    /** V1~V27 적용 → users·refresh_token 행 → V28 적용. */
    private void prepareMigrationDb() {
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(migUrl().replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_mig_it");
        mig = new JdbcTemplate(new DriverManagerDataSource(migUrl(), RealMysqlSupport.user(), RealMysqlSupport.password()));
        assertThat(mig.queryForObject("SELECT DATABASE()", String.class)).endsWith("_mig_it");
        migFlyway("28").clean();
        assertThat(migFlyway("27").migrate().migrationsExecuted).as("V1~V27").isEqualTo(27);
        for (int i = 1; i <= 2; i++) {
            mig.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,'USER',NOW(6))",
                    i, "u" + i + "@t", "x", "U" + i);
        }
        LocalDateTime t = LocalDateTime.of(2026, 11, 22, 12, 0);
        mig.update(RT_INSERT, 1, "f".repeat(64), FAMILY, t, t, t.plusDays(14), t, "ADMIN");
        assertRejected("ck_refresh_token_reason", () -> mig.update(RT_INSERT, 1, "e".repeat(64), FAMILY, t, t, t.plusDays(14), t, "PASSWORD_RESET"));
        assertThat(migFlyway("28").migrate().migrationsExecuted).as("V28").isEqualTo(1);
        // users.credentials_changed_at: DATETIME(6) NULL, 기존 행은 NULL(제한 없음)
        assertThat(mig.queryForObject("SELECT CONCAT(COLUMN_TYPE, ' ', IS_NULLABLE) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'credentials_changed_at'", String.class))
                .isEqualTo("datetime(6) YES");
        assertThat(mig.queryForObject("SELECT COUNT(*) FROM users WHERE credentials_changed_at IS NOT NULL", Integer.class)).isZero();
    }

    private List<String> indexColumns(String index) {
        return mig.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'password_reset_token' AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX", String.class, index);
    }

    private String columnType(String column) {
        return mig.queryForObject("SELECT CONCAT(COLUMN_TYPE, ' ', IFNULL(COLLATION_NAME, '-'), ' ', IS_NULLABLE) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'password_reset_token' AND COLUMN_NAME = ?", String.class, column);
    }

    private int insertRaw(Object userId, String hash, LocalDateTime created, LocalDateTime expires,
                          LocalDateTime used, LocalDateTime invalidated, String ip) {
        return mig.update(INSERT, userId, hash, created, expires, used, invalidated, ip);
    }

    private int inTx(java.util.function.IntSupplier work) {
        Integer n = tx.execute(s -> work.getAsInt());
        return n == null ? 0 : n;
    }

    private void assertRejected(String constraint, Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    @Test
    void 마이그레이션은_재실행해도_안전하고_컬럼과_제약_이름이_스펙대로다() {
        prepareMigrationDb();
        LocalDateTime now = LocalDateTime.of(2026, 11, 22, 12, 0);
        assertThat(insertRaw(1, HASH_A, now, now.plusMinutes(30), null, null, "127.0.0.1")).isEqualTo(1);
        mig.update(RT_INSERT, 1, "d".repeat(64), FAMILY, now, now, now.plusDays(14), now, "PASSWORD_RESET");

        mig.update("DELETE FROM flyway_schema_history WHERE version = '28'");
        assertThat(migFlyway("28").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(mig.queryForObject("SELECT COUNT(*) FROM password_reset_token", Integer.class)).as("재실행이 데이터를 지우지 않는다").isEqualTo(1);
        assertThat(mig.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class)).as("refresh_token 행 보존").isEqualTo(2);
        assertThat(mig.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                .as("임시 프로시저가 남지 않는다").isZero();
        assertThat(mig.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME <> 'flyway_schema_history'", Integer.class)).as("27 + 1 테이블").isEqualTo(28);

        assertThat(mig.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'password_reset_token' ORDER BY ORDINAL_POSITION", String.class))
                .containsExactly("id", "user_id", "token_hash", "created_at", "expires_at", "used_at", "invalidated_at", "request_ip", "live_user_id");
        assertThat(columnType("token_hash")).isEqualTo("char(64) ascii_bin NO");
        assertThat(columnType("created_at")).isEqualTo("datetime(6) - NO");
        assertThat(columnType("expires_at")).isEqualTo("datetime(6) - NO");
        assertThat(columnType("used_at")).isEqualTo("datetime(6) - YES");
        assertThat(columnType("invalidated_at")).isEqualTo("datetime(6) - YES");
        assertThat(columnType("request_ip")).isEqualTo("varchar(45) ascii_bin YES");
        assertThat(columnType("live_user_id")).isEqualTo("bigint - YES");
        assertThat(mig.queryForObject("SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'password_reset_token' AND COLUMN_NAME = 'live_user_id'", String.class)).isEqualTo("STORED GENERATED");

        assertThat(mig.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'password_reset_token' AND CONSTRAINT_TYPE IN ('FOREIGN KEY','UNIQUE','CHECK')", String.class))
                .containsExactlyInAnyOrder("uk_password_reset_token_hash", "uk_password_reset_token_live", "fk_password_reset_token_user",
                        "ck_password_reset_token_hash", "ck_password_reset_token_expires", "ck_password_reset_token_state", "ck_password_reset_token_ip");
        assertThat(indexColumns("uk_password_reset_token_hash")).containsExactly("token_hash");
        assertThat(indexColumns("uk_password_reset_token_live")).containsExactly("live_user_id");
        assertThat(indexColumns("idx_password_reset_token_user")).as("FK 인덱스는 단일 컬럼").containsExactly("user_id");
        assertThat(indexColumns("idx_password_reset_token_expires")).containsExactly("expires_at");
        assertThat(mig.queryForList("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'password_reset_token'", String.class))
                .containsExactlyInAnyOrder("PRIMARY", "uk_password_reset_token_hash", "uk_password_reset_token_live",
                        "idx_password_reset_token_user", "idx_password_reset_token_expires");
        assertThat(mig.queryForList("SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME = 'fk_password_reset_token_user'", String.class)).containsExactly("NO ACTION");
        assertThat(mig.queryForObject("SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME = 'ck_refresh_token_reason'", Integer.class)).as("CHECK 는 하나만").isEqualTo(1);
    }

    @Test
    void CHECK_UNIQUE_FK가_잘못된_행을_거부한다() {
        prepareMigrationDb();
        LocalDateTime t = LocalDateTime.of(2026, 11, 22, 12, 0);
        LocalDateTime exp = t.plusMinutes(30);

        // 해시 형식
        assertRejected("ck_password_reset_token_hash", () -> insertRaw(1, "A".repeat(64), t, exp, null, null, null));
        assertRejected("ck_password_reset_token_hash", () -> insertRaw(1, "g".repeat(64), t, exp, null, null, null));
        assertThatThrownBy(() -> insertRaw(1, "a".repeat(63), t, exp, null, null, null)).isInstanceOf(DataAccessException.class);
        // 만료
        assertRejected("ck_password_reset_token_expires", () -> insertRaw(1, HASH_A, t, t, null, null, null));
        assertRejected("ck_password_reset_token_expires", () -> insertRaw(1, HASH_A, t, t.minusSeconds(1), null, null, null));
        // 상태: 사용·무효는 동시에 값일 수 없고 각각 created_at 이후
        assertRejected("ck_password_reset_token_state", () -> insertRaw(1, HASH_A, t, exp, t, t, null));
        assertRejected("ck_password_reset_token_state", () -> insertRaw(1, HASH_A, t, exp, t.minusSeconds(1), null, null));
        assertRejected("ck_password_reset_token_state", () -> insertRaw(1, HASH_A, t, exp, null, t.minusSeconds(1), null));
        // IP 형식·길이
        assertRejected("ck_password_reset_token_ip", () -> insertRaw(1, HASH_A, t, exp, null, null, "1"));
        assertRejected("ck_password_reset_token_ip", () -> insertRaw(1, HASH_A, t, exp, null, null, "1.2.3.4; DROP"));
        assertRejected("ck_password_reset_token_ip", () -> insertRaw(1, HASH_A, t, exp, null, null, "fe80::1%eth0"));
        assertRejected("ck_password_reset_token_ip", () -> insertRaw(1, HASH_A, t, exp, null, null, ""));
        assertThatThrownBy(() -> insertRaw(1, HASH_A, t, exp, null, null, "1".repeat(46))).isInstanceOf(DataAccessException.class);
        // FK
        assertRejected("fk_password_reset_token_user", () -> insertRaw(999, HASH_A, t, exp, null, null, null));
        assertThatThrownBy(() -> insertRaw(null, HASH_A, t, exp, null, null, null)).isInstanceOf(DataAccessException.class);

        // 정상 행: 살아 있음(IPv4) / 사용됨(IPv6) / 무효화됨(IP 없음) — 사용자 1 에 살아 있는 행은 1개
        assertThat(insertRaw(1, HASH_A, t, exp, null, null, "203.0.113.7")).isEqualTo(1);
        assertThat(insertRaw(1, HASH_B, t, exp, t, null, "2001:db8::ffff:203.0.113.7")).isEqualTo(1);
        assertThat(insertRaw(1, HASH_C, t, exp, null, t, null)).isEqualTo(1);
        // 사용자당 살아 있는 행 1개
        assertRejected("uk_password_reset_token_live", () -> insertRaw(1, "d".repeat(64), t, exp, null, null, null));
        assertThat(insertRaw(2, "d".repeat(64), t, exp, null, null, "0:0:0:0:0:0:0:1")).as("다른 사용자는 따로").isEqualTo(1);
        // 무효화하면 새 행을 넣을 수 있다(만료된 행도 무효화 전에는 살아 있는 것으로 센다)
        mig.update("UPDATE password_reset_token SET invalidated_at = ? WHERE token_hash = ?", t, HASH_A);
        assertThat(insertRaw(1, "e".repeat(64), t, exp, null, null, null)).isEqualTo(1);
        // 같은 해시는 한 번만(사용자가 달라도)
        assertRejected("uk_password_reset_token_hash", () -> insertRaw(2, HASH_A, t, exp, t, null, null));
        // 토큰이 있는 사용자는 지울 수 없다(ON DELETE 동작 없음, 탈퇴는 익명화 + 토큰 행 삭제)
        assertRejected("fk_password_reset_token_user", () -> mig.update("DELETE FROM users WHERE id = 1"));

        // refresh_token: PASSWORD_RESET 허용, 기존 5종 그대로, 알 수 없는 값 거부
        String[] reasons = {"LOGOUT", "REUSE", "WITHDRAW", "ADMIN", "PASSWORD_RESET"};
        for (int i = 0; i < reasons.length; i++) {
            assertThat(mig.update(RT_INSERT, 2, Integer.toHexString(i + 1).repeat(64), FAMILY, t, t, t.plusDays(14), t, reasons[i])).isEqualTo(1);
        }
        assertRejected("ck_refresh_token_reason", () -> mig.update(RT_INSERT, 2, "9".repeat(64), FAMILY, t, t, t.plusDays(14), t, "password_reset"));
        assertRejected("ck_refresh_token_replaced", () -> mig.update(RT_INSERT, 2, "8".repeat(64), FAMILY, t, t, t.plusDays(14), t, "ROTATED"));
    }

    @Test
    void 엔티티와_리포지토리가_발급_확정_무효화_정리를_수행한다() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime exp = now.plusMinutes(30);

        // 발급
        Long firstId = tx.execute(s -> {
            assertThat(repository.invalidateAllActiveByUserId(1L, LocalDateTime.now(clock))).isZero();
            return repository.save(PasswordResetToken.issue(1L, HASH_A, exp, "127.0.0.1")).getId();
        });
        assertThat(repository.findIdByTokenHash(HASH_A)).contains(firstId);
        assertThat(repository.findIdByTokenHash(HASH_B)).isEmpty();
        assertThat(repository.findUserIdById(firstId)).contains(1L);

        // 재요청: 이전 링크 무효화 → 새 행
        Long secondId = tx.execute(s -> {
            assertThat(repository.invalidateAllActiveByUserId(1L, LocalDateTime.now(clock))).isEqualTo(1);
            return repository.save(PasswordResetToken.issue(1L, HASH_B, exp, null)).getId();
        });
        PasswordResetToken first = repository.findById(firstId).orElseThrow();
        assertThat(first.getInvalidatedAt()).isNotNull();
        assertThat(first.isUsableAt(now)).isFalse();
        assertThatThrownBy(() -> first.markUsed(now)).isInstanceOf(IllegalStateException.class);

        // 무효화를 빠뜨린 두 번째 살아 있는 행은 DB 가 거부한다
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> repository.saveAndFlush(PasswordResetToken.issue(1L, HASH_C, exp, null))))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_password_reset_token_live");

        // 확정: PK 잠금 → 상태 확인 → 사용 기록, 리프레시 토큰 PASSWORD_RESET 일괄 폐기
        tx.executeWithoutResult(s -> refreshTokens.save(RefreshToken.issue(1L, "f".repeat(64), FAMILY, now, now.plusDays(14))));
        tx.executeWithoutResult(s -> {
            PasswordResetToken t = repository.findByIdForUpdate(secondId).orElseThrow();
            assertThat(t.getCreatedAt()).as("Auditing 이 created_at 을 채운다").isNotNull();
            assertThat(t.isUsableAt(LocalDateTime.now(clock))).isTrue();
            assertThat(t.isUsableAt(t.getExpiresAt())).as("만료 시각에는 쓸 수 없다").isFalse();
            t.markUsed(LocalDateTime.now(clock));
            assertThat(refreshTokens.revokeAllActiveByUserId(1L, RevokeReason.PASSWORD_RESET, LocalDateTime.now(clock))).isEqualTo(1);
        });
        assertThat(jdbc.queryForObject("SELECT used_at IS NOT NULL FROM password_reset_token WHERE id = ?", Boolean.class, secondId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT revoke_reason FROM refresh_token WHERE user_id = 1", String.class)).isEqualTo("PASSWORD_RESET");
        assertThatThrownBy(() -> repository.findById(secondId).orElseThrow().markUsed(now)).isInstanceOf(IllegalStateException.class);
        assertThat(inTx(() -> repository.invalidateAllActiveByUserId(1L, LocalDateTime.now(clock)))).as("사용된 행은 무효화 대상이 아니다").isZero();

        // 사용 뒤에는 새 요청을 바로 넣을 수 있다
        tx.executeWithoutResult(s -> repository.save(PasswordResetToken.issue(1L, HASH_C, exp, null)));

        // 탈퇴: 사용자 1 의 행만 모두 삭제
        tx.executeWithoutResult(s -> repository.save(PasswordResetToken.issue(2L, "d".repeat(64), exp, "::1")));
        assertThat(inTx(() -> repository.deleteAllByUserId(1L))).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token WHERE user_id = 1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token WHERE user_id = 2", Integer.class)).isEqualTo(1);

        // 만료 행 배치 삭제: 만료된 것만, limit 개씩, 오래된 것부터 (사용 여부 무관)
        for (int i = 1; i <= 3; i++) {
            jdbc.update(INSERT, 2, "e".repeat(63) + i, now.minusDays(30), now.minusDays(10 - i), now.minusDays(30), null, null);
        }
        assertThat(inTx(() -> repository.deleteExpiredBatch(now, 2))).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT token_hash FROM password_reset_token WHERE expires_at < ?", String.class, now))
                .as("가장 늦게 만료된 행이 남는다").containsExactly("e".repeat(63) + 3);
        assertThat(inTx(() -> repository.deleteExpiredBatch(now, 100))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token", Integer.class)).as("만료 전 행은 남는다").isEqualTo(1);
    }
}
