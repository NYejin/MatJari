package com.matjari.repository;

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
 * V27(refresh_token) 스키마·엔티티·리포지토리 검증 (실제 MySQL, MATJARI_IT_* 환경변수가 있을 때만).
 * <ul>
 *   <li>마이그레이션 검증은 Spring 없이 Flyway + JdbcTemplate 로 별도 DB `X_mig_it`(MATJARI_IT_JDBC_URL 의 DB 이름에서 `_it` 를 떼고 `_mig_it`)
 *       에서 한다: V1~V26 → V27 적용, V27 재실행 안전, 컬럼·제약·인덱스 이름, FK 인덱스 단일 컬럼, CHECK·UNIQUE·FK 위반.</li>
 *   <li>엔티티 매핑(ddl-auto validate)과 리포지토리 쿼리는 MATJARI_IT_JDBC_URL DB 에 Spring 컨텍스트를 띄워 확인한다.</li>
 * </ul>
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefreshTokenSchemaMysqlTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String FAMILY_1 = "11111111-1111-4111-8111-111111111111";
    private static final String FAMILY_2 = "22222222-2222-4222-8222-222222222222";
    private static final String INSERT = "INSERT INTO refresh_token (user_id, token_hash, family_id, family_started_at, created_at, expires_at, revoked_at, revoke_reason, replaced_by_id) "
            + "VALUES (?,?,?,?,?,?,?,?,?)";

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
    private RefreshTokenRepository repository;
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
        jdbc.execute("TRUNCATE TABLE refresh_token");
        jdbc.execute("TRUNCATE TABLE users");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,'USER',NOW(6))",
                    i, "u" + i + "@t", "x", "U" + i);
        }
    }

    private void prepareMigrationDb() {
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(migUrl().replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_mig_it");
        mig = new JdbcTemplate(new DriverManagerDataSource(migUrl(), RealMysqlSupport.user(), RealMysqlSupport.password()));
        assertThat(mig.queryForObject("SELECT DATABASE()", String.class)).endsWith("_mig_it");
        migFlyway("27").clean();
        assertThat(migFlyway("26").migrate().migrationsExecuted).as("V1~V26").isEqualTo(26);
        assertThat(migFlyway("27").migrate().migrationsExecuted).as("V27").isEqualTo(1);
        for (int i = 1; i <= 2; i++) {
            mig.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,'USER',NOW(6))",
                    i, "u" + i + "@t", "x", "U" + i);
        }
    }

    private List<String> indexColumns(String index) {
        return mig.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'refresh_token' AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX", String.class, index);
    }

    private String columnType(String column) {
        return mig.queryForObject("SELECT CONCAT(COLUMN_TYPE, ' ', IFNULL(COLLATION_NAME, '-'), ' ', IS_NULLABLE) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refresh_token' AND COLUMN_NAME = ?", String.class, column);
    }

    private int insertRaw(Object userId, String hash, String family, LocalDateTime created, LocalDateTime expires,
                          LocalDateTime revoked, String reason, Long replacedBy) {
        return mig.update(INSERT, userId, hash, family, created, created, expires, revoked, reason, replacedBy);
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
        assertThat(insertRaw(1, HASH_A, FAMILY_1, now, now.plusDays(14), null, null, null)).isEqualTo(1);

        mig.update("DELETE FROM flyway_schema_history WHERE version = '27'");
        assertThat(migFlyway("27").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(mig.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class)).as("재실행이 데이터를 지우지 않는다").isEqualTo(1);
        assertThat(mig.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                .as("임시 프로시저가 남지 않는다").isZero();
        assertThat(mig.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME <> 'flyway_schema_history'", Integer.class)).as("26 + 1 테이블").isEqualTo(27);

        assertThat(mig.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'refresh_token' ORDER BY ORDINAL_POSITION", String.class))
                .containsExactly("id", "user_id", "token_hash", "family_id", "family_started_at", "created_at", "expires_at", "revoked_at", "revoke_reason", "replaced_by_id");
        assertThat(columnType("token_hash")).isEqualTo("char(64) ascii_bin NO");
        assertThat(columnType("family_id")).isEqualTo("char(36) ascii_bin NO");
        assertThat(columnType("created_at")).isEqualTo("datetime(6) - NO");
        assertThat(columnType("expires_at")).isEqualTo("datetime(6) - NO");
        assertThat(columnType("revoked_at")).isEqualTo("datetime(6) - YES");
        assertThat(columnType("revoke_reason")).isEqualTo("varchar(20) utf8mb4_bin YES");
        assertThat(columnType("replaced_by_id")).isEqualTo("bigint - YES");

        assertThat(mig.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'refresh_token' AND CONSTRAINT_TYPE IN ('FOREIGN KEY','UNIQUE','CHECK')", String.class))
                .containsExactlyInAnyOrder("uk_refresh_token_hash", "fk_refresh_token_user", "ck_refresh_token_hash", "ck_refresh_token_family",
                        "ck_refresh_token_expires", "ck_refresh_token_family_started", "ck_refresh_token_revoked", "ck_refresh_token_reason", "ck_refresh_token_replaced");
        assertThat(indexColumns("uk_refresh_token_hash")).containsExactly("token_hash");
        assertThat(indexColumns("idx_refresh_token_user")).as("FK 인덱스는 단일 컬럼").containsExactly("user_id");
        assertThat(indexColumns("idx_refresh_token_family")).containsExactly("family_id");
        assertThat(indexColumns("idx_refresh_token_expires")).containsExactly("expires_at");
        assertThat(mig.queryForList("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'refresh_token'", String.class))
                .containsExactlyInAnyOrder("PRIMARY", "uk_refresh_token_hash", "idx_refresh_token_user", "idx_refresh_token_family", "idx_refresh_token_expires");
        assertThat(mig.queryForList("SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME = 'fk_refresh_token_user'", String.class)).containsExactly("NO ACTION");
    }

    @Test
    void CHECK_UNIQUE_FK가_잘못된_행을_거부한다() {
        prepareMigrationDb();
        LocalDateTime t = LocalDateTime.of(2026, 11, 22, 12, 0);
        LocalDateTime exp = t.plusDays(14);

        // 형식
        assertRejected("ck_refresh_token_hash", () -> insertRaw(1, "A".repeat(64), FAMILY_1, t, exp, null, null, null));
        assertRejected("ck_refresh_token_hash", () -> insertRaw(1, "g".repeat(64), FAMILY_1, t, exp, null, null, null));
        assertThatThrownBy(() -> insertRaw(1, "a".repeat(63), FAMILY_1, t, exp, null, null, null)).isInstanceOf(DataAccessException.class);
        assertRejected("ck_refresh_token_family", () -> insertRaw(1, HASH_A, FAMILY_1.toUpperCase().replace('1', 'A'), t, exp, null, null, null));
        assertRejected("ck_refresh_token_family", () -> insertRaw(1, HASH_A, "not-a-uuid-not-a-uuid-not-a-uuid-xx", t, exp, null, null, null));
        // 만료
        assertRejected("ck_refresh_token_expires", () -> insertRaw(1, HASH_A, FAMILY_1, t, t, null, null, null));
        assertRejected("ck_refresh_token_expires", () -> insertRaw(1, HASH_A, FAMILY_1, t, t.minusSeconds(1), null, null, null));
        assertThat(mig.update(INSERT, 1, HASH_B, FAMILY_1, t.minusSeconds(1), t, exp, null, null, null)).as("계열 시작이 created_at 보다 이르면 허용").isEqualTo(1);
        mig.update("DELETE FROM refresh_token WHERE token_hash = ?", HASH_B);
        assertRejected("ck_refresh_token_family_started", () -> mig.update(INSERT, 1, HASH_A, FAMILY_1, t.plusSeconds(1), t, exp, null, null, null));
        // 폐기 시각·사유는 함께
        assertRejected("ck_refresh_token_revoked", () -> insertRaw(1, HASH_A, FAMILY_1, t, exp, t, null, null));
        assertRejected("ck_refresh_token_revoked", () -> insertRaw(1, HASH_A, FAMILY_1, t, exp, null, "LOGOUT", null));
        assertRejected("ck_refresh_token_revoked", () -> insertRaw(1, HASH_A, FAMILY_1, t, exp, t.minusSeconds(1), "LOGOUT", null));
        assertRejected("ck_refresh_token_reason", () -> insertRaw(1, HASH_A, FAMILY_1, t, exp, t, "EXPIRED", null));
        assertRejected("ck_refresh_token_reason", () -> insertRaw(1, HASH_A, FAMILY_1, t, exp, t, "logout", null));
        // replaced_by_id 는 ROTATED 일 때만, ROTATED 면 필수
        assertRejected("ck_refresh_token_replaced", () -> insertRaw(1, HASH_A, FAMILY_1, t, exp, t, "ROTATED", null));
        assertRejected("ck_refresh_token_replaced", () -> insertRaw(1, HASH_A, FAMILY_1, t, exp, t, "LOGOUT", 5L));
        assertRejected("ck_refresh_token_replaced", () -> insertRaw(1, HASH_A, FAMILY_1, t, exp, null, null, 5L));
        // FK
        assertRejected("fk_refresh_token_user", () -> insertRaw(999, HASH_A, FAMILY_1, t, exp, null, null, null));
        assertThatThrownBy(() -> insertRaw(null, HASH_A, FAMILY_1, t, exp, null, null, null)).isInstanceOf(DataAccessException.class);

        // 정상 행: 살아 있음 / 각 사유 / ROTATED + replaced
        assertThat(insertRaw(1, HASH_A, FAMILY_1, t, exp, null, null, null)).isEqualTo(1);
        assertThat(insertRaw(1, HASH_B, FAMILY_1, t, exp, t, "ROTATED", 1L)).isEqualTo(1);
        String[] reasons = {"LOGOUT", "REUSE", "WITHDRAW", "ADMIN"};
        for (int i = 0; i < reasons.length; i++) {
            assertThat(insertRaw(2, Integer.toHexString(i + 1).repeat(64), FAMILY_2, t, exp, t, reasons[i], null)).isEqualTo(1);
        }
        // 같은 해시는 한 번만(사용자가 달라도)
        assertRejected("uk_refresh_token_hash", () -> insertRaw(2, HASH_A, FAMILY_2, t, exp, null, null, null));
        // 토큰이 있는 사용자는 지울 수 없다(ON DELETE 동작 없음, 탈퇴는 익명화)
        assertRejected("fk_refresh_token_user", () -> mig.update("DELETE FROM users WHERE id = 1"));
    }

    @Test
    void 엔티티와_리포지토리가_회전_폐기_정리를_수행한다() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime exp = now.plusDays(14);

        Long firstId = tx.execute(s -> repository.save(RefreshToken.issue(1L, HASH_A, FAMILY_1, now, exp)).getId());
        assertThat(repository.findIdByTokenHash(HASH_A)).contains(firstId);
        assertThat(repository.findIdByTokenHash(HASH_B)).isEmpty();

        // 회전: 새 행 INSERT → 옛 행 PK 잠금 후 ROTATED
        Long secondId = tx.execute(s -> {
            RefreshToken old = repository.findByIdForUpdate(firstId).orElseThrow();
            assertThat(old.isUsableAt(now)).isTrue();
            assertThat(old.getCreatedAt()).as("Auditing 이 created_at 을 채운다").isNotNull();
            Long newId = repository.save(RefreshToken.issue(1L, HASH_B, FAMILY_1, now, exp)).getId();
            old.rotateTo(newId, LocalDateTime.now(clock));
            return newId;
        });
        RefreshToken rotated = repository.findById(firstId).orElseThrow();
        assertThat(rotated.getRevokeReason()).isEqualTo(RevokeReason.ROTATED);
        assertThat(rotated.getReplacedById()).isEqualTo(secondId);
        assertThatThrownBy(() -> rotated.rotateTo(99L, now)).isInstanceOf(IllegalStateException.class);

        // 다른 계열·다른 사용자 행
        String hashC = "c".repeat(64);
        String hashD = "d".repeat(64);
        tx.executeWithoutResult(s -> {
            repository.save(RefreshToken.issue(1L, hashC, FAMILY_2, now, exp));
            repository.save(RefreshToken.issue(2L, hashD, "33333333-3333-4333-8333-333333333333", now, exp));
        });

        // 재사용 탐지: 계열 1 의 살아 있는 행(second)만 REUSE, 이미 ROTATED 인 first 는 그대로
        int reused = inTx(() -> repository.revokeAllActiveByFamilyId(FAMILY_1, RevokeReason.REUSE, LocalDateTime.now(clock)));
        assertThat(reused).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revoke_reason FROM refresh_token WHERE id = ?", String.class, firstId)).isEqualTo("ROTATED");
        assertThat(jdbc.queryForObject("SELECT revoke_reason FROM refresh_token WHERE id = ?", String.class, secondId)).isEqualTo("REUSE");
        assertThat(inTx(() -> repository.revokeAllActiveByFamilyId(FAMILY_1, RevokeReason.REUSE, LocalDateTime.now(clock))))
                .as("멱등").isZero();

        // 일괄 폐기에 ROTATED 를 넘기면 CHECK 가 거부한다
        assertThatThrownBy(() -> inTx(() -> repository.revokeAllActiveByUserId(1L, RevokeReason.ROTATED, LocalDateTime.now(clock))))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_refresh_token_replaced");

        // 탈퇴: 사용자 1 의 살아 있는 행(hashC)만, 사용자 2 는 그대로
        assertThat(inTx(() -> repository.revokeAllActiveByUserId(1L, RevokeReason.WITHDRAW, LocalDateTime.now(clock)))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = 1 AND revoked_at IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = 2 AND revoked_at IS NULL", Integer.class)).isEqualTo(1);

        // 단건 폐기는 멱등
        tx.executeWithoutResult(s -> {
            RefreshToken d = repository.findByIdForUpdate(repository.findIdByTokenHash(hashD).orElseThrow()).orElseThrow();
            d.revoke(RevokeReason.LOGOUT, LocalDateTime.now(clock));
            d.revoke(RevokeReason.ADMIN, LocalDateTime.now(clock));
        });
        assertThat(jdbc.queryForObject("SELECT revoke_reason FROM refresh_token WHERE token_hash = ?", String.class, hashD)).isEqualTo("LOGOUT");

        // 만료 행 배치 삭제: 만료된 것만, limit 개씩, 오래된 것부터
        for (int i = 1; i <= 3; i++) {
            jdbc.update(INSERT, 2, "e".repeat(63) + i, FAMILY_2, now.minusDays(30), now.minusDays(30), now.minusDays(10 - i), null, null, null);
        }
        assertThat(inTx(() -> repository.deleteExpiredBatch(now, 2))).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT token_hash FROM refresh_token WHERE expires_at < ?", String.class, now))
                .as("가장 늦게 만료된 행이 남는다").containsExactly("e".repeat(63) + 3);
        assertThat(inTx(() -> repository.deleteExpiredBatch(now, 100))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token", Integer.class)).as("만료 전 행은(폐기돼도) 남는다").isEqualTo(4);
    }
}
