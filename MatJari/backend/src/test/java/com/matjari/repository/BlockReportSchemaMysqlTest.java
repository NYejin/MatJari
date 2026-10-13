package com.matjari.repository;

import com.matjari.domain.AbuseReport;
import com.matjari.domain.UserBlock;
import com.matjari.domain.UserSanction;
import com.matjari.testsupport.RealMysqlSupport;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V13(user_block, abuse_report, user_sanction) 스키마 검증 (실제 MySQL, MATJARI_IT_* 환경변수가 있을 때만).
 * 다른 IT 와 데이터가 섞이지 않게 `X_blockschema_it` 별도 DB 를 쓴다. 확인 항목: V1~V13 전체 적용과 V13 재실행 안전,
 * 제약·인덱스 이름, FK 인덱스 단일 컬럼, CHECK 거부(자기 차단·자기 신고·OTHER 상세·처리 정보·제재 기간/해제),
 * 'OPEN 만 (신고자, 매칭)당 1건' 유니크, 차단 중복 거부, 후보 SQL 의 양방향 차단 NOT EXISTS 가 uk_user_block_pair 를 쓰는지(EXPLAIN),
 * 엔티티 매핑(ddl-auto=validate 로 컨텍스트가 뜨는 것 + 저장·처리 왕복).
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BlockReportSchemaMysqlTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 12, 0);

    /** 후보 SQL 에 붙일 차단 제외 조각(서비스 담당이 additionalExclusions 에 넣을 형태와 같다). */
    static final String BLOCK_EXCLUSION = """
            AND NOT EXISTS (SELECT 1 FROM user_block bk1 WHERE bk1.blocker_id = ta.user_id AND bk1.blocked_id = tb.user_id)
            AND NOT EXISTS (SELECT 1 FROM user_block bk2 WHERE bk2.blocker_id = tb.user_id AND bk2.blocked_id = ta.user_id)
            """;

    private static String blockUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "").replaceAll("_blockschema$", "") + "_blockschema_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.assertSafe();
        registry.add("spring.datasource.url", BlockReportSchemaMysqlTest::blockUrl);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private EntityManager em;

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(blockUrl(), RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    @BeforeEach
    void setUp() {
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(blockUrl().replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_blockschema_it");
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_blockschema_it");
        flyway("13").clean();
        assertThat(flyway("13").migrate().migrationsExecuted).as("V1~V13 전체 적용").isEqualTo(13);
        seed();
    }

    /**
     * 사용자 1~4(4 = 관리자), 공연·회차 1. 티켓 1(사용자 1, A-1-1)·티켓 2(사용자 2, A-1-2), 서로를 원하는 OPEN 요청 1·2(후보 성립),
     * 매칭 1(요청 1·2, CANCELED — 신고 FK 용이며 후보 판정에 영향 없음).
     */
    private void seed() {
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,?,NOW(6))",
                    i, "u" + i + "@t", "x", "U" + i, i == 4 ? "ADMIN" : "USER");
        }
        jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) VALUES (1,1,'k','http://x','T','V',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO performance_session(id,performance_id,starts_at,created_at) VALUES (1,1,'2030-01-01 19:00:00',NOW(6))");
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO ticket(id,user_id,performance_session_id,zone_label,zone_key,row_label,row_key,col_label,col_key,status,created_at,updated_at) "
                    + "VALUES (?,?,1,'A','A','1','1',?,?,'ACTIVE',NOW(6),NOW(6))", i, i, String.valueOf(i), String.valueOf(i));
            jdbc.update("INSERT INTO exchange_request(id,ticket_id,status,created_at,updated_at) VALUES (?,?,'OPEN',NOW(6),NOW(6))", i, i);
            jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,1,1)", i);
            jdbc.update("INSERT INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount) VALUES (?,'A','1',?,'ANY',NULL)",
                    i, String.valueOf(3 - i));
        }
        jdbc.update("INSERT INTO exchange_match(id,request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,"
                + "created_at,updated_at,canceled_at,a_extra_type,b_extra_type) VALUES (1,1,2,1,2,1,2,'CANCELED',NOW(6),NOW(6),NOW(6),'ANY','ANY')");
    }

    private static final String INSERT_BLOCK = "INSERT INTO user_block (blocker_id, blocked_id, created_at) VALUES (?,?,NOW(6))";

    private static final String INSERT_REPORT = "INSERT INTO abuse_report (reporter_id, reported_id, match_id, reason, detail, status, "
            + "handled_by_id, handled_at, handler_note, sanction_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,NOW(6),NOW(6))";

    private int report(Object reporter, Object reported, Object reason, Object detail, Object status,
                       Object handler, Object handledAt, Object note, Object sanction) {
        return jdbc.update(INSERT_REPORT, reporter, reported, 1, reason, detail, status, handler, handledAt, note, sanction);
    }

    private int openReport(Object reporter, Object reported, Object reason, Object detail) {
        return report(reporter, reported, reason, detail, "OPEN", null, null, null, null);
    }

    private static final String INSERT_SANCTION = "INSERT INTO user_sanction (user_id, report_id, type, note, starts_at, ends_at, "
            + "created_by_id, revoked_at, revoked_by_id, created_at) VALUES (?,?,?,?,?,?,?,?,?,NOW(6))";

    private int sanction(Object user, Object report, Object type, Object note, Object startsAt, Object endsAt,
                         Object createdBy, Object revokedAt, Object revokedBy) {
        return jdbc.update(INSERT_SANCTION, user, report, type, note, startsAt, endsAt, createdBy, revokedAt, revokedBy);
    }

    private void assertRejected(String constraint, Runnable insert) {
        assertThatThrownBy(insert::run).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                + "ORDER BY ORDINAL_POSITION", String.class, table);
    }

    private List<String> indexColumns(String table, String index) {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ? AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX", String.class, table, index);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("SELECT CONCAT(COLUMN_TYPE, ' ', IFNULL(COLLATION_NAME, '-'), ' ', IS_NULLABLE) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?", String.class, table, column);
    }

    @Test
    void 마이그레이션은_재실행해도_안전하고_제약_이름과_컬럼이_스펙대로다() {
        assertThat(jdbc.update(INSERT_BLOCK, 1, 2)).isEqualTo(1);
        assertThat(openReport(1, 2, "ABUSE", null)).isEqualTo(1);
        assertThat(sanction(2, null, "SUSPEND", "메모", NOW, null, 4, null, null)).isEqualTo(1);

        jdbc.update("DELETE FROM flyway_schema_history WHERE version = '13'");
        assertThat(flyway("13").migrate().migrationsExecuted).isEqualTo(1);

        for (String t : List.of("user_block", "abuse_report", "user_sanction")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class)).as("재실행이 데이터를 지우지 않는다: " + t).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                .as("임시 프로시저가 남지 않는다").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME <> 'flyway_schema_history'", Integer.class)).as("16 + 3 테이블").isEqualTo(19);

        assertThat(columns("user_block")).containsExactly("id", "blocker_id", "blocked_id", "created_at");
        assertThat(columns("abuse_report")).containsExactly("id", "reporter_id", "reported_id", "match_id", "reason", "detail", "status",
                "handled_by_id", "handled_at", "handler_note", "sanction_id", "created_at", "updated_at", "open_reporter_id");
        assertThat(columns("user_sanction")).containsExactly("id", "user_id", "report_id", "type", "note", "starts_at", "ends_at",
                "created_by_id", "revoked_at", "revoked_by_id", "created_at");
        assertThat(columnType("abuse_report", "reason")).isEqualTo("varchar(20) utf8mb4_bin NO");
        assertThat(columnType("abuse_report", "status")).isEqualTo("varchar(20) utf8mb4_bin NO");
        assertThat(columnType("abuse_report", "detail")).isEqualTo("varchar(500) utf8mb4_0900_ai_ci YES");
        assertThat(columnType("user_sanction", "type")).isEqualTo("varchar(20) utf8mb4_bin NO");
        assertThat(columnType("user_sanction", "note")).isEqualTo("varchar(300) utf8mb4_0900_ai_ci NO");
        assertThat(columnType("user_sanction", "ends_at")).isEqualTo("datetime(6) - YES");
        assertThat(jdbc.queryForObject("SELECT EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'abuse_report' AND COLUMN_NAME = 'open_reporter_id'", String.class)).isEqualTo("STORED GENERATED");

        assertThat(jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME IN ('user_block','abuse_report','user_sanction') AND CONSTRAINT_TYPE IN ('FOREIGN KEY','UNIQUE')", String.class))
                .containsExactlyInAnyOrder("uk_user_block_pair", "fk_user_block_blocker", "fk_user_block_blocked",
                        "uk_abuse_report_open", "fk_abuse_report_reporter", "fk_abuse_report_reported", "fk_abuse_report_match",
                        "fk_abuse_report_handled_by",
                        "fk_user_sanction_user", "fk_user_sanction_report", "fk_user_sanction_created_by", "fk_user_sanction_revoked_by");
        assertThat(jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND (CONSTRAINT_NAME LIKE 'ck_user_block%' OR CONSTRAINT_NAME LIKE 'ck_abuse_report%' OR CONSTRAINT_NAME LIKE 'ck_user_sanction%')",
                String.class))
                .containsExactlyInAnyOrder("ck_user_block_self",
                        "ck_abuse_report_reason", "ck_abuse_report_status", "ck_abuse_report_self", "ck_abuse_report_detail",
                        "ck_abuse_report_note", "ck_abuse_report_handled", "ck_abuse_report_sanction",
                        "ck_user_sanction_type", "ck_user_sanction_note", "ck_user_sanction_period", "ck_user_sanction_revoke",
                        "ck_user_sanction_self");

        // FK 인덱스는 단일 컬럼(갱신 컬럼이 섞이지 않는다). user_block 의 blocker_id FK 는 uk_user_block_pair 가 겸한다(갱신 컬럼 없음)
        for (String[] idx : new String[][]{
                {"user_block", "idx_user_block_blocked", "blocked_id"},
                {"abuse_report", "idx_abuse_report_reporter", "reporter_id"},
                {"abuse_report", "idx_abuse_report_reported", "reported_id"},
                {"abuse_report", "idx_abuse_report_match", "match_id"},
                {"abuse_report", "idx_abuse_report_handled_by", "handled_by_id"},
                {"abuse_report", "idx_abuse_report_status", "status"},
                {"user_sanction", "idx_user_sanction_user", "user_id"},
                {"user_sanction", "idx_user_sanction_report", "report_id"},
                {"user_sanction", "idx_user_sanction_created_by", "created_by_id"},
                {"user_sanction", "idx_user_sanction_revoked_by", "revoked_by_id"}}) {
            assertThat(indexColumns(idx[0], idx[1])).as(idx[1]).containsExactly(idx[2]);
        }
        assertThat(indexColumns("user_block", "uk_user_block_pair")).containsExactly("blocker_id", "blocked_id");
        // 'OPEN 만 1건' 유니크는 FK 컬럼이 아닌 생성 컬럼이 맨 앞(FK 인덱스로 쓰일 수 없다)
        assertThat(indexColumns("abuse_report", "uk_abuse_report_open")).containsExactly("open_reporter_id", "match_id");
    }

    @Test
    void 차단은_쌍마다_1건이고_자기_차단과_없는_사용자를_거부한다() {
        assertThat(jdbc.update(INSERT_BLOCK, 1, 2)).isEqualTo(1);
        assertRejected("uk_user_block_pair", () -> jdbc.update(INSERT_BLOCK, 1, 2));
        assertThat(jdbc.update(INSERT_BLOCK, 2, 1)).as("반대 방향은 별개 행").isEqualTo(1);
        assertRejected("ck_user_block_self", () -> jdbc.update(INSERT_BLOCK, 3, 3));
        assertRejected("fk_user_block_blocker", () -> jdbc.update(INSERT_BLOCK, 999, 1));
        assertRejected("fk_user_block_blocked", () -> jdbc.update(INSERT_BLOCK, 1, 999));
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_block (blocker_id, blocked_id, created_at) VALUES (1, NULL, NOW(6))"))
                .isInstanceOf(DataAccessException.class);
        // 해제 = 행 삭제 후 다시 차단 가능
        assertThat(jdbc.update("DELETE FROM user_block WHERE blocker_id = 1 AND blocked_id = 2")).isEqualTo(1);
        assertThat(jdbc.update(INSERT_BLOCK, 1, 2)).isEqualTo(1);
    }

    @Test
    void 신고_CHECK와_FK가_잘못된_행을_거부한다() {
        assertRejected("ck_abuse_report_self", () -> openReport(1, 1, "ABUSE", null));
        assertRejected("ck_abuse_report_reason", () -> openReport(1, 2, "SPAM", null));
        assertRejected("ck_abuse_report_reason", () -> openReport(1, 2, "abuse", null));
        assertRejected("Check constraint", () -> report(1, 2, "ABUSE", null, "open", null, null, null, null));
        // 알 수 없는 상태는 ck_abuse_report_status 와 ck_abuse_report_handled 둘 다 어긋나며 어느 쪽이 먼저 보고될지는 정해져 있지 않다
        assertRejected("Check constraint", () -> report(1, 2, "ABUSE", null, "CLOSED", 4, NOW, null, null));
        assertRejected("ck_abuse_report_detail", () -> openReport(1, 2, "OTHER", null));
        assertRejected("ck_abuse_report_detail", () -> openReport(1, 2, "OTHER", "   "));
        assertRejected("ck_abuse_report_detail", () -> openReport(1, 2, "FRAUD", " "));
        assertRejected("ck_abuse_report_note", () -> report(1, 2, "ABUSE", null, "DISMISSED", 4, NOW, "  ", null));
        // OPEN 인데 처리 정보 / 처리됐는데 처리자·시각 없음 / 제재는 RESOLVED 만
        assertRejected("ck_abuse_report_handled", () -> report(1, 2, "ABUSE", null, "OPEN", 4, null, null, null));
        assertRejected("ck_abuse_report_handled", () -> report(1, 2, "ABUSE", null, "OPEN", null, null, "메모", null));
        assertRejected("ck_abuse_report_handled", () -> report(1, 2, "ABUSE", null, "OPEN", null, null, null, 7));
        assertRejected("ck_abuse_report_handled", () -> report(1, 2, "ABUSE", null, "RESOLVED", null, NOW, null, null));
        assertRejected("ck_abuse_report_handled", () -> report(1, 2, "ABUSE", null, "DISMISSED", 4, null, null, null));
        assertRejected("ck_abuse_report_sanction", () -> report(1, 2, "ABUSE", null, "DISMISSED", 4, NOW, null, 7));
        assertThatThrownBy(() -> openReport(1, 2, "OTHER", "가".repeat(501))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> report(1, 2, "ABUSE", null, "DISMISSED", 4, NOW, "가".repeat(301), null)).isInstanceOf(DataAccessException.class);
        assertRejected("fk_abuse_report_reporter", () -> openReport(999, 2, "ABUSE", null));
        assertRejected("fk_abuse_report_reported", () -> openReport(1, 999, "ABUSE", null));
        assertRejected("fk_abuse_report_match", () -> jdbc.update(INSERT_REPORT.replace("?,?,?,?,?,?,?,?,?,?,", "?,?,999,?,?,?,?,?,?,?,"),
                1, 2, "ABUSE", null, "OPEN", null, null, null, null));
        assertRejected("fk_abuse_report_handled_by", () -> report(1, 2, "ABUSE", null, "DISMISSED", 999, NOW, null, null));

        // 경계값·정상 행. sanction_id 는 FK 가 아니다(없는 id 도 저장)
        assertThat(openReport(1, 2, "OTHER", "가".repeat(500))).isEqualTo(1);
        assertThat(report(1, 2, "FAKE_SEAT", null, "RESOLVED", 4, NOW, "가".repeat(300), 424242)).isEqualTo(1);
        assertThat(report(1, 2, "NO_SHOW", null, "DISMISSED", 4, NOW, null, null)).isEqualTo(1);
    }

    @Test
    void 신고는_신고자_매칭별_OPEN_1건이고_처리되면_다시_신고할_수_있다() {
        assertThat(openReport(1, 2, "ABUSE", null)).isEqualTo(1);
        assertRejected("uk_abuse_report_open", () -> openReport(1, 2, "FRAUD", null));
        assertThat(openReport(2, 1, "ABUSE", null)).as("상대도 같은 매칭에 신고 가능").isEqualTo(1);

        assertThat(jdbc.update("UPDATE abuse_report SET status='DISMISSED', handled_by_id=4, handled_at=NOW(6) WHERE reporter_id=1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT open_reporter_id FROM abuse_report WHERE reporter_id=1", Long.class)).isNull();
        assertThat(openReport(1, 2, "FRAUD", null)).as("처리 후 재신고").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM abuse_report WHERE open_reporter_id = 1", Integer.class))
                .as("사용자당 OPEN 개수(서비스 상한 판정용)").isEqualTo(1);
        // 처리된 행을 OPEN 으로 되돌리면 같은 (신고자, 매칭) OPEN 이 있으므로 막힌다
        assertRejected("uk_abuse_report_open", () -> jdbc.update("UPDATE abuse_report SET status='OPEN', handled_by_id=NULL, handled_at=NULL "
                + "WHERE reporter_id=1 AND status='DISMISSED'"));
    }

    @Test
    void 제재_CHECK와_FK가_잘못된_행을_거부한다() {
        assertRejected("ck_user_sanction_type", () -> sanction(2, null, "BAN", "메모", NOW, null, 4, null, null));
        assertRejected("ck_user_sanction_note", () -> sanction(2, null, "SUSPEND", "  ", NOW, null, 4, null, null));
        assertThatThrownBy(() -> sanction(2, null, "SUSPEND", null, NOW, null, 4, null, null)).isInstanceOf(DataAccessException.class);
        assertRejected("ck_user_sanction_period", () -> sanction(2, null, "SUSPEND", "메모", NOW, NOW, 4, null, null));
        assertRejected("ck_user_sanction_period", () -> sanction(2, null, "SUSPEND", "메모", NOW, NOW.minusDays(1), 4, null, null));
        assertRejected("ck_user_sanction_revoke", () -> sanction(2, null, "SUSPEND", "메모", NOW, null, 4, NOW, null));
        assertRejected("ck_user_sanction_revoke", () -> sanction(2, null, "SUSPEND", "메모", NOW, null, 4, null, 4));
        assertRejected("ck_user_sanction_self", () -> sanction(4, null, "SUSPEND", "메모", NOW, null, 4, null, null));
        assertRejected("fk_user_sanction_user", () -> sanction(999, null, "SUSPEND", "메모", NOW, null, 4, null, null));
        assertRejected("fk_user_sanction_report", () -> sanction(2, 999, "SUSPEND", "메모", NOW, null, 4, null, null));
        assertRejected("fk_user_sanction_created_by", () -> sanction(2, null, "SUSPEND", "메모", NOW, null, 999, null, null));
        assertRejected("fk_user_sanction_revoked_by", () -> sanction(2, null, "SUSPEND", "메모", NOW, null, 4, NOW, 999));

        assertThat(openReport(1, 2, "ABUSE", null)).isEqualTo(1);
        long reportId = jdbc.queryForObject("SELECT id FROM abuse_report", Long.class);
        assertThat(sanction(2, reportId, "SUSPEND", "가".repeat(300), NOW, NOW.plusDays(7), 4, null, null)).isEqualTo(1);
        assertThat(sanction(2, null, "SUSPEND", "무기한", NOW, null, 4, NOW.plusHours(1), 4)).isEqualTo(1);
        // 활성 판정 조회(서비스가 쓸 형태): 해제되지 않았고 무기한이거나 끝나지 않음
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_sanction WHERE user_id = 2 AND revoked_at IS NULL "
                + "AND (ends_at IS NULL OR ends_at > ?)", Integer.class, NOW.plusDays(1))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_sanction WHERE user_id = 2 AND revoked_at IS NULL "
                + "AND (ends_at IS NULL OR ends_at > ?)", Integer.class, NOW.plusDays(7))).as("기간 만료").isZero();
    }

    /**
     * 후보 SQL(ExchangeCandidateRepository.candidateSql, STRAIGHT_JOIN)에 양방향 차단 NOT EXISTS 를 붙였을 때
     * 두 서브쿼리 모두 uk_user_block_pair 의 두 컬럼 등호 조회(eq_ref/ref)로 풀리고, 실제로 차단 쌍을 제외하는지 확인한다.
     */
    @Test
    void 후보_SQL의_양방향_차단_NOT_EXISTS는_uk_user_block_pair를_쓴다() {
        for (int i = 0; i < 50; i++) {   // 통계가 비지 않게 다른 사용자 쌍 차단을 넣는다
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?, 'USER',NOW(6))",
                    100 + i, "x" + i + "@t", "x", "X" + i);
        }
        for (int i = 0; i < 50; i++) {
            jdbc.update(INSERT_BLOCK, 100 + i, i % 2 == 0 ? 3 : 100 + ((i + 1) % 50));
        }
        jdbc.execute("ANALYZE TABLE user_block");

        String base = ExchangeCandidateRepository.candidateSql();
        int orderAt = base.indexOf("ORDER BY");
        String sql = base.substring(0, orderAt) + BLOCK_EXCLUSION + base.substring(orderAt);
        Object[] args = {1L, LocalDateTime.of(2029, 12, 31, 0, 0), 20, 0};

        assertThat(jdbc.queryForList(sql, args)).as("차단 없음: 후보 1건").hasSize(1);

        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + sql, args);
        for (String alias : List.of("bk1", "bk2")) {
            Map<String, Object> row = plan.stream().filter(r -> alias.equals(r.get("table"))).findFirst()
                    .orElseThrow(() -> new AssertionError("EXPLAIN 에 " + alias + " 가 없음: " + plan));
            assertThat(row.get("key")).as(alias + " key, plan=" + plan).isEqualTo("uk_user_block_pair");
            assertThat(String.valueOf(row.get("type"))).as(alias + " type").isIn("eq_ref", "ref");
            assertThat(String.valueOf(row.get("ref"))).as(alias + " ref 는 두 컬럼 모두").contains(",");
        }
        System.out.println("[EXPLAIN candidate + block]\n" + plan.stream().map(Object::toString).reduce((x, y) -> x + "\n" + y).orElse(""));

        // 어느 방향이든 차단하면 후보에서 빠지고, 해제하면 복귀한다
        jdbc.update(INSERT_BLOCK, 1, 2);
        assertThat(jdbc.queryForList(sql, args)).as("내가 상대를 차단").isEmpty();
        jdbc.update("DELETE FROM user_block WHERE blocker_id = 1 AND blocked_id = 2");
        jdbc.update(INSERT_BLOCK, 2, 1);
        assertThat(jdbc.queryForList(sql, args)).as("상대가 나를 차단").isEmpty();
        jdbc.update("DELETE FROM user_block WHERE blocker_id = 2 AND blocked_id = 1");
        assertThat(jdbc.queryForList(sql, args)).as("해제 후 복귀").hasSize(1);
    }

    @Test
    void 엔티티로_저장하고_처리하면_상태와_시각이_반영된다() {
        Long[] ids = tx.execute(s -> {
            UserBlock b = UserBlock.of(1L, 2L);
            AbuseReport r = AbuseReport.create(1L, 2L, 1L, AbuseReport.Reason.OTHER, "  줄1\n줄2  ");
            em.persist(b);
            em.persist(r);
            em.flush();
            UserSanction sn = UserSanction.suspend(2L, r.getId(), " 욕설 반복 ", NOW, 7, 4L);
            em.persist(sn);
            em.flush();
            r.resolve(4L, " 확인함 ", sn.getId(), NOW);
            return new Long[]{b.getId(), r.getId(), sn.getId()};
        });
        assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL FROM user_block WHERE id = ?", Boolean.class, ids[0])).isTrue();
        assertThat(jdbc.queryForMap("SELECT reason, detail, status, handled_by_id, handler_note, sanction_id, open_reporter_id FROM abuse_report WHERE id = ?", ids[1]))
                .containsEntry("reason", "OTHER").containsEntry("detail", "줄1\n줄2").containsEntry("status", "RESOLVED")
                .containsEntry("handled_by_id", 4L).containsEntry("handler_note", "확인함").containsEntry("sanction_id", ids[2])
                .containsEntry("open_reporter_id", null);
        assertThat(jdbc.queryForMap("SELECT type, note, ends_at, report_id FROM user_sanction WHERE id = ?", ids[2]))
                .containsEntry("type", "SUSPEND").containsEntry("note", "욕설 반복").containsEntry("report_id", ids[1]);

        tx.executeWithoutResult(s -> {
            UserSanction sn = em.find(UserSanction.class, ids[2]);
            assertThat(sn.getEndsAt()).isEqualTo(NOW.plusDays(7));
            assertThat(sn.isActiveAt(NOW.plusDays(1))).isTrue();
            assertThat(sn.isActiveAt(NOW.plusDays(7))).as("끝 시각에는 풀림").isFalse();
            assertThatThrownBy(() -> sn.revoke(4L, NOW.plusDays(8))).isInstanceOf(IllegalStateException.class);
            sn.revoke(4L, NOW.plusDays(1));
            AbuseReport r = em.find(AbuseReport.class, ids[1]);
            assertThatThrownBy(() -> r.dismiss(4L, null, NOW)).isInstanceOf(IllegalStateException.class);
        });
        assertThat(jdbc.queryForObject("SELECT revoked_by_id FROM user_sanction WHERE id = ?", Long.class, ids[2])).isEqualTo(4L);

        // 엔티티 방어선
        assertThatThrownBy(() -> UserBlock.of(1L, 1L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseReport.create(1L, 1L, 1L, AbuseReport.Reason.ABUSE, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseReport.create(1L, 2L, 1L, AbuseReport.Reason.OTHER, "  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AbuseReport.create(1L, 2L, 1L, AbuseReport.Reason.ABUSE, "가".repeat(501))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserSanction.suspend(2L, null, " ", NOW, null, 4L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserSanction.suspend(4L, null, "메모", NOW, null, 4L)).isInstanceOf(IllegalArgumentException.class);
        UserSanction forever = UserSanction.suspend(2L, null, "메모", NOW, null, 4L);
        assertThat(forever.getEndsAt()).isNull();
        assertThat(forever.isActiveAt(NOW.plusYears(10))).isTrue();
    }
}
