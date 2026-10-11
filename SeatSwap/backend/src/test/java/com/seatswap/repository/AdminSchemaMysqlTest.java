package com.seatswap.repository;

import com.seatswap.domain.AdminReviewStatus;
import com.seatswap.domain.Performance;
import com.seatswap.domain.PerformanceEditProposal;
import com.seatswap.domain.PerformanceSession;
import com.seatswap.domain.SeatClaim;
import com.seatswap.testsupport.RealMysqlSupport;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V12(performance_edit_proposal, seat_claim) 스키마 검증 (실제 MySQL, SEATSWAP_IT_* 환경변수가 있을 때만).
 * 다른 IT 와 데이터가 섞이지 않게 `X_adminschema_it` 별도 DB 를 쓴다. 확인 항목: V1~V12 전체 적용과 V12 재실행 안전,
 * 제약·인덱스 이름, 좌석 키 collation 이 ticket 과 같음, FK 인덱스 단일 컬럼, CHECK 거부, 'PENDING 만 1건' 유니크,
 * 엔티티 매핑(ddl-auto=validate 로 컨텍스트가 뜨는 것 + 저장·처리 왕복).
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AdminSchemaMysqlTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 12, 0);
    private static final String UUID1 = "0f8fad5b-d9cb-469f-a165-70867728950e";
    private static final String UUID2 = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
    private static final String UUID3 = "1b4e28ba-2fa1-11d2-883f-0016d3cca427";

    private static String adminUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("SEATSWAP_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "").replaceAll("_adminschema$", "") + "_adminschema_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.assertSafe();
        registry.add("spring.datasource.url", AdminSchemaMysqlTest::adminUrl);
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
        return Flyway.configure().dataSource(adminUrl(), RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    @BeforeEach
    void setUp() {
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(adminUrl().replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_adminschema_it");
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_adminschema_it");
        flyway("12").clean();
        assertThat(flyway("12").migrate().migrationsExecuted).as("V1~V12 전체 적용").isEqualTo(12);
        seed();
    }

    /** 사용자 1~3(3 = 관리자), 공연 1, 회차 1~2. */
    private void seed() {
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at,trust_score) VALUES (?,?,?,?,?,NOW(6),0)",
                    i, "u" + i + "@t", "x", "U" + i, i == 3 ? "ADMIN" : "USER");
        }
        jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) VALUES (1,1,'k','http://x','T','V',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO performance_session(id,performance_id,starts_at,created_at) VALUES (1,1,'2030-01-01 19:00:00',NOW(6))");
        jdbc.update("INSERT INTO performance_session(id,performance_id,starts_at,created_at) VALUES (2,1,'2030-01-02 19:00:00',NOW(6))");
    }

    private static final String INSERT_PROPOSAL = "INSERT INTO performance_edit_proposal (performance_id, proposer_id, new_title, new_venue_name, reason, "
            + "status, old_title, old_venue_name, reviewed_by_id, reviewed_at, review_note, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,NOW(6),NOW(6))";

    private int proposal(Object perf, Object proposer, Object newTitle, Object newVenue, Object reason, Object status,
                         Object oldTitle, Object oldVenue, Object reviewer, Object reviewedAt, Object note) {
        return jdbc.update(INSERT_PROPOSAL, perf, proposer, newTitle, newVenue, reason, status, oldTitle, oldVenue, reviewer, reviewedAt, note);
    }

    private int pendingProposal(Object proposer, Object newTitle, Object newVenue) {
        return proposal(1, proposer, newTitle, newVenue, null, "PENDING", null, null, null, null, null);
    }

    private static final String INSERT_CLAIM = "INSERT INTO seat_claim (claimant_id, performance_session_id, zone_label, zone_key, row_label, row_key, "
            + "col_label, col_key, memo, image_key, image_content_type, image_size, image_deleted_at, status, reviewed_by_id, reviewed_at, "
            + "review_note, resolved_ticket_id, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW(6),NOW(6))";

    private int claim(Object claimant, Object session, String zone, String row, String col, Object memo, Object imageKey, Object type,
                      Object size, Object imageDeletedAt, Object status, Object reviewer, Object reviewedAt, Object note, Object resolved) {
        return jdbc.update(INSERT_CLAIM, claimant, session, zone, zone, row, row, col, col, memo, imageKey, type, size, imageDeletedAt,
                status, reviewer, reviewedAt, note, resolved);
    }

    private int pendingClaim(Object claimant, Object session, String zone, String row, String col, String imageKey) {
        return claim(claimant, session, zone, row, col, null, imageKey, "image/png", 1000, null, "PENDING", null, null, null, null);
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
        assertThat(pendingProposal(1, "새 제목", null)).isEqualTo(1);
        assertThat(pendingClaim(1, 1, "A", "1", "1", UUID1)).isEqualTo(1);

        jdbc.update("DELETE FROM flyway_schema_history WHERE version = '12'");
        assertThat(flyway("12").migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_edit_proposal", Integer.class)).as("재실행이 데이터를 지우지 않는다").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_claim", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                .as("임시 프로시저가 남지 않는다").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME <> 'flyway_schema_history'", Integer.class)).as("14 + 2 테이블").isEqualTo(16);

        assertThat(columns("performance_edit_proposal")).containsExactly("id", "performance_id", "proposer_id", "new_title", "new_venue_name",
                "reason", "status", "old_title", "old_venue_name", "reviewed_by_id", "reviewed_at", "review_note", "created_at", "updated_at",
                "pending_proposer_id");
        assertThat(columns("seat_claim")).containsExactly("id", "claimant_id", "performance_session_id", "zone_label", "zone_key",
                "row_label", "row_key", "col_label", "col_key", "memo", "image_key", "image_content_type", "image_size", "image_deleted_at",
                "status", "reviewed_by_id", "reviewed_at", "review_note", "resolved_ticket_id", "created_at", "updated_at", "pending_claimant_id");

        // 좌석 label·key 컬럼은 ticket 과 같은 타입·길이·collation·NULL 여부
        for (String col : List.of("zone_label", "zone_key", "row_label", "row_key", "col_label", "col_key")) {
            assertThat(columnType("seat_claim", col)).as(col).isEqualTo(columnType("ticket", col));
        }
        assertThat(columnType("seat_claim", "zone_key")).isEqualTo("varchar(50) utf8mb4_bin NO");
        assertThat(columnType("seat_claim", "image_key")).isEqualTo("varchar(36) ascii_bin NO");
        assertThat(columnType("seat_claim", "status")).isEqualTo("varchar(20) utf8mb4_bin NO");
        assertThat(columnType("performance_edit_proposal", "status")).isEqualTo("varchar(20) utf8mb4_bin NO");
        assertThat(columnType("performance_edit_proposal", "new_title")).isEqualTo(columnType("performance", "title").replace(" NO", " YES"));
        assertThat(columnType("performance_edit_proposal", "new_venue_name")).isEqualTo(columnType("performance", "venue_name").replace(" NO", " YES"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND COLUMN_NAME IN ('pending_proposer_id','pending_claimant_id') AND EXTRA = 'STORED GENERATED'", Integer.class)).isEqualTo(2);

        assertThat(jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME IN ('performance_edit_proposal','seat_claim') AND CONSTRAINT_TYPE IN ('FOREIGN KEY','UNIQUE')", String.class))
                .containsExactlyInAnyOrder("uk_performance_edit_proposal_pending", "fk_performance_edit_proposal_performance",
                        "fk_performance_edit_proposal_proposer", "fk_performance_edit_proposal_reviewed_by",
                        "uk_seat_claim_pending_seat", "uk_seat_claim_image_key", "fk_seat_claim_claimant", "fk_seat_claim_session",
                        "fk_seat_claim_reviewed_by");
        assertThat(jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND (CONSTRAINT_NAME LIKE 'ck_performance_edit_proposal%' OR CONSTRAINT_NAME LIKE 'ck_seat_claim%')", String.class))
                .containsExactlyInAnyOrder("ck_performance_edit_proposal_status", "ck_performance_edit_proposal_change",
                        "ck_performance_edit_proposal_text", "ck_performance_edit_proposal_review", "ck_performance_edit_proposal_snapshot",
                        "ck_seat_claim_status", "ck_seat_claim_seat", "ck_seat_claim_text", "ck_seat_claim_image_key", "ck_seat_claim_image_type",
                        "ck_seat_claim_image_size", "ck_seat_claim_image_deleted", "ck_seat_claim_review", "ck_seat_claim_resolved");

        // FK 인덱스는 단일 컬럼(갱신 컬럼이 섞이지 않는다)
        for (String[] idx : new String[][]{
                {"performance_edit_proposal", "idx_performance_edit_proposal_performance", "performance_id"},
                {"performance_edit_proposal", "idx_performance_edit_proposal_proposer", "proposer_id"},
                {"performance_edit_proposal", "idx_performance_edit_proposal_reviewed_by", "reviewed_by_id"},
                {"seat_claim", "idx_seat_claim_claimant", "claimant_id"},
                {"seat_claim", "idx_seat_claim_session", "performance_session_id"},
                {"seat_claim", "idx_seat_claim_reviewed_by", "reviewed_by_id"}}) {
            assertThat(indexColumns(idx[0], idx[1])).as(idx[1]).containsExactly(idx[2]);
        }
        // 'PENDING 만 1건' 유니크는 FK 컬럼이 아닌 생성 컬럼이 맨 앞(FK 인덱스로 쓰일 수 없다)
        assertThat(indexColumns("performance_edit_proposal", "uk_performance_edit_proposal_pending"))
                .containsExactly("pending_proposer_id", "performance_id");
        assertThat(indexColumns("seat_claim", "uk_seat_claim_pending_seat"))
                .containsExactly("pending_claimant_id", "performance_session_id", "zone_key", "row_key", "col_key");
        assertThat(indexColumns("seat_claim", "idx_seat_claim_status")).containsExactly("status");
        assertThat(indexColumns("performance_edit_proposal", "idx_performance_edit_proposal_status")).containsExactly("status");
    }

    @Test
    void 공연_수정_제안_CHECK와_FK가_잘못된_행을_거부한다() {
        assertRejected("ck_performance_edit_proposal_change", () -> pendingProposal(1, null, null));
        assertRejected("ck_performance_edit_proposal_change", () -> pendingProposal(1, "   ", null));
        assertRejected("ck_performance_edit_proposal_change", () -> pendingProposal(1, "제목", ""));
        assertRejected("ck_performance_edit_proposal_text", () -> proposal(1, 1, "제목", null, " ", "PENDING", null, null, null, null, null));
        assertRejected("Check constraint", () -> proposal(1, 1, "제목", null, null, "pending", null, null, null, null, null));
        assertRejected("Check constraint", () -> proposal(1, 1, "제목", null, null, "DONE", null, null, null, null, null));
        // PENDING 인데 처리 정보가 있다 / 처리됐는데 처리자·시각이 없다
        assertRejected("ck_performance_edit_proposal_review", () -> proposal(1, 1, "제목", null, null, "PENDING", null, null, 3, null, null));
        assertRejected("ck_performance_edit_proposal_review", () -> proposal(1, 1, "제목", null, null, "PENDING", null, null, null, null, "메모"));
        assertRejected("ck_performance_edit_proposal_review", () -> proposal(1, 1, "제목", null, null, "REJECTED", null, null, null, NOW, null));
        assertRejected("ck_performance_edit_proposal_review", () -> proposal(1, 1, "제목", null, null, "REJECTED", null, null, 3, null, null));
        // 스냅샷은 APPROVED 일 때만, 그리고 둘 다
        assertRejected("ck_performance_edit_proposal_snapshot", () -> proposal(1, 1, "제목", null, null, "APPROVED", "T", null, 3, NOW, null));
        assertRejected("ck_performance_edit_proposal_snapshot", () -> proposal(1, 1, "제목", null, null, "APPROVED", null, null, 3, NOW, null));
        assertRejected("ck_performance_edit_proposal_snapshot", () -> proposal(1, 1, "제목", null, null, "REJECTED", "T", "V", 3, NOW, null));
        assertRejected("ck_performance_edit_proposal_snapshot", () -> proposal(1, 1, "제목", null, null, "PENDING", "T", "V", null, null, null));
        // 길이 초과(strict mode)
        assertThatThrownBy(() -> pendingProposal(1, "가".repeat(201), null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> pendingProposal(1, null, "가".repeat(101))).isInstanceOf(DataAccessException.class);
        // FK
        assertRejected("fk_performance_edit_proposal_performance", () -> proposal(999, 1, "제목", null, null, "PENDING", null, null, null, null, null));
        assertRejected("fk_performance_edit_proposal_proposer", () -> pendingProposal(999, "제목", null));
        assertRejected("fk_performance_edit_proposal_reviewed_by", () -> proposal(1, 1, "제목", null, null, "REJECTED", null, null, 999, NOW, null));

        // 경계값·정상 행은 통과
        assertThat(pendingProposal(1, "가".repeat(200), null)).isEqualTo(1);
        assertThat(proposal(1, 2, null, "가".repeat(100), "사유", "APPROVED", "T", "V", 3, NOW, "확인")).isEqualTo(1);
        assertThat(proposal(1, 2, "제목", null, null, "REJECTED", null, null, 3, NOW, null)).isEqualTo(1);
    }

    @Test
    void 공연_수정_제안은_사용자_공연별_PENDING_1건이고_처리되면_다시_제안할_수_있다() {
        assertThat(pendingProposal(1, "A", null)).isEqualTo(1);
        assertRejected("uk_performance_edit_proposal_pending", () -> pendingProposal(1, null, "B"));
        assertThat(pendingProposal(2, "A", null)).as("다른 사용자는 허용").isEqualTo(1);
        jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) VALUES (2,1,'k2','http://y','T2','V2',NOW(6),NOW(6))");
        assertThat(proposal(2, 1, "A", null, null, "PENDING", null, null, null, null, null)).as("다른 공연은 허용").isEqualTo(1);

        // 거절하면 생성 컬럼이 NULL 이 되어 유니크에서 빠진다 -> 같은 사용자·공연에 새 PENDING 가능
        assertThat(jdbc.update("UPDATE performance_edit_proposal SET status='REJECTED', reviewed_by_id=3, reviewed_at=NOW(6) "
                + "WHERE proposer_id=1 AND performance_id=1")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pending_proposer_id FROM performance_edit_proposal WHERE proposer_id=1 AND performance_id=1",
                Long.class)).isNull();
        assertThat(pendingProposal(1, "C", null)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_edit_proposal WHERE pending_proposer_id = 1", Integer.class))
                .as("사용자당 PENDING 개수(서비스 상한 판정용)").isEqualTo(2);
        // 처리된 행끼리는 몇 건이든 허용
        assertThat(jdbc.update("UPDATE performance_edit_proposal SET status='REJECTED', reviewed_by_id=3, reviewed_at=NOW(6) "
                + "WHERE proposer_id=1 AND performance_id=1 AND status='PENDING'")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_edit_proposal WHERE proposer_id=1 AND performance_id=1", Integer.class)).isEqualTo(2);
    }

    @Test
    void 좌석_인증_요청_CHECK와_FK가_잘못된_행을_거부한다() {
        assertRejected("ck_seat_claim_image_key", () -> pendingClaim(1, 1, "A", "1", "1", "../../etc/passwd"));
        assertRejected("ck_seat_claim_image_key", () -> pendingClaim(1, 1, "A", "1", "1", UUID1.toUpperCase()));
        assertRejected("ck_seat_claim_image_key", () -> pendingClaim(1, 1, "A", "1", "1", UUID1.replace("-", "")));
        assertRejected("ck_seat_claim_image_type", () -> claim(1, 1, "A", "1", "1", null, UUID1, "image/gif", 10, null, "PENDING", null, null, null, null));
        assertRejected("ck_seat_claim_image_type", () -> claim(1, 1, "A", "1", "1", null, UUID1, "IMAGE/PNG", 10, null, "PENDING", null, null, null, null));
        assertRejected("ck_seat_claim_image_size", () -> claim(1, 1, "A", "1", "1", null, UUID1, "image/png", 0, null, "PENDING", null, null, null, null));
        assertRejected("ck_seat_claim_image_size", () -> claim(1, 1, "A", "1", "1", null, UUID1, "image/png", 5242881, null, "PENDING", null, null, null, null));
        assertRejected("ck_seat_claim_seat", () -> pendingClaim(1, 1, "", "1", "1", UUID1));
        assertRejected("ck_seat_claim_text", () -> claim(1, 1, "A", "1", "1", "  ", UUID1, "image/png", 10, null, "PENDING", null, null, null, null));
        assertRejected("Check constraint", () -> claim(1, 1, "A", "1", "1", null, UUID1, "image/png", 10, null, "approved", 3, NOW, null, null));
        assertRejected("ck_seat_claim_image_deleted", () -> claim(1, 1, "A", "1", "1", null, UUID1, "image/png", 10, NOW, "PENDING", null, null, null, null));
        assertRejected("ck_seat_claim_review", () -> claim(1, 1, "A", "1", "1", null, UUID1, "image/png", 10, null, "PENDING", 3, NOW, null, null));
        assertRejected("ck_seat_claim_review", () -> claim(1, 1, "A", "1", "1", null, UUID1, "image/png", 10, null, "APPROVED", null, NOW, null, null));
        assertRejected("ck_seat_claim_resolved", () -> claim(1, 1, "A", "1", "1", null, UUID1, "image/png", 10, null, "REJECTED", 3, NOW, null, 42));
        assertRejected("ck_seat_claim_resolved", () -> claim(1, 1, "A", "1", "1", null, UUID1, "image/png", 10, null, "PENDING", null, null, null, 42));
        assertThatThrownBy(() -> pendingClaim(1, 1, "가".repeat(51), "1", "1", UUID1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> pendingClaim(1, 1, "A", "1".repeat(21), "1", UUID1)).isInstanceOf(DataAccessException.class);
        assertRejected("fk_seat_claim_claimant", () -> pendingClaim(999, 1, "A", "1", "1", UUID1));
        assertRejected("fk_seat_claim_session", () -> pendingClaim(1, 999, "A", "1", "1", UUID1));
        assertRejected("fk_seat_claim_reviewed_by", () -> claim(1, 1, "A", "1", "1", null, UUID1, "image/png", 10, null, "REJECTED", 999, NOW, null, null));

        // 정상: 경계값 크기, 승인(resolved 있음/없음), 거절 후 사진 삭제 기록. resolved_ticket_id 는 FK 가 아니다(없는 id 도 저장)
        assertThat(claim(1, 1, "가".repeat(50), "1", "1", "메모", UUID1, "image/webp", 5242880, null, "PENDING", null, null, null, null)).isEqualTo(1);
        assertThat(claim(1, 1, "A", "1", "2", null, UUID2, "image/jpeg", 1, NOW, "APPROVED", 3, NOW, "확인", 424242)).isEqualTo(1);
        assertThat(claim(1, 1, "A", "1", "3", null, UUID3, "image/png", 10, NOW, "REJECTED", 3, NOW, null, null)).isEqualTo(1);
        // 같은 image_key 재사용 거부
        assertRejected("uk_seat_claim_image_key", () -> pendingClaim(2, 1, "B", "1", "1", UUID1));
    }

    @Test
    void 좌석_인증_요청은_사용자_좌석별_PENDING_1건이고_처리되면_다시_요청할_수_있다() {
        assertThat(pendingClaim(1, 1, "A", "1", "1", UUID1)).isEqualTo(1);
        assertRejected("uk_seat_claim_pending_seat", () -> pendingClaim(1, 1, "A", "1", "1", UUID2));
        assertThat(pendingClaim(2, 1, "A", "1", "1", UUID2)).as("다른 사용자는 같은 좌석도 허용").isEqualTo(1);
        assertThat(pendingClaim(1, 2, "A", "1", "1", UUID3)).as("다른 회차는 허용").isEqualTo(1);
        assertThat(pendingClaim(1, 1, "a", "1", "1", "2c1d0b4a-0000-4000-8000-000000000001")).as("키는 utf8mb4_bin(정규화는 서비스)").isEqualTo(1);

        assertThat(jdbc.update("UPDATE seat_claim SET status='REJECTED', reviewed_by_id=3, reviewed_at=NOW(6), image_deleted_at=NOW(6) "
                + "WHERE claimant_id=1 AND performance_session_id=1 AND zone_key='A'")).isEqualTo(1);
        assertThat(pendingClaim(1, 1, "A", "1", "1", "2c1d0b4a-0000-4000-8000-000000000002")).as("거절 후 재요청").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_claim WHERE pending_claimant_id = 1", Integer.class)).isEqualTo(3);
        // 처리된 행에서 다시 PENDING 으로 되돌리려 하면(같은 좌석 PENDING 이 이미 있으니) 유니크가 막는다
        assertRejected("uk_seat_claim_pending_seat", () -> jdbc.update("UPDATE seat_claim SET status='PENDING', reviewed_by_id=NULL, reviewed_at=NULL, "
                + "image_deleted_at=NULL WHERE claimant_id=1 AND status='REJECTED'"));
    }

    @Test
    void 엔티티로_저장하고_처리하면_감사_시각과_상태가_반영된다() {
        Long[] ids = tx.execute(s -> {
            Performance perf = em.find(Performance.class, 1L);
            PerformanceSession session = em.find(PerformanceSession.class, 1L);
            PerformanceEditProposal p = PerformanceEditProposal.create(perf, 1L, "  새   제목 ", " ", "  오타 ");
            SeatClaim c = SeatClaim.create(1L, session, "A구역", "A구역", "1열", "1", "2번", "2", null, UUID1, "image/png", 1234);
            em.persist(p);
            em.persist(c);
            em.flush();
            return new Long[]{p.getId(), c.getId()};
        });
        tx.executeWithoutResult(s -> {
            PerformanceEditProposal p = em.find(PerformanceEditProposal.class, ids[0]);
            assertThat(p.getNewTitle()).isEqualTo("새 제목");
            assertThat(p.getNewVenueName()).as("빈 값은 NULL").isNull();
            assertThat(p.getReason()).isEqualTo("오타");
            assertThat(p.getStatus()).isEqualTo(AdminReviewStatus.PENDING);
            assertThat(p.getCreatedAt()).isNotNull();
            p.approve(3L, "T", "V", null, NOW);
            SeatClaim c = em.find(SeatClaim.class, ids[1]);
            assertThat(c.getCreatedAt()).isNotNull();
            assertThatThrownBy(() -> c.markImageDeleted(NOW)).isInstanceOf(IllegalStateException.class);
            c.reject(3L, " 사진이 흐려요 ", NOW);
            c.markImageDeleted(NOW);
        });
        assertThat(jdbc.queryForMap("SELECT status, old_title, old_venue_name, reviewed_by_id, pending_proposer_id FROM performance_edit_proposal WHERE id = ?", ids[0]))
                .containsEntry("status", "APPROVED").containsEntry("old_title", "T").containsEntry("old_venue_name", "V")
                .containsEntry("reviewed_by_id", 3L).containsEntry("pending_proposer_id", null);
        assertThat(jdbc.queryForMap("SELECT status, review_note, image_deleted_at IS NOT NULL AS deleted, resolved_ticket_id FROM seat_claim WHERE id = ?", ids[1]))
                .containsEntry("status", "REJECTED").containsEntry("review_note", "사진이 흐려요").containsEntry("resolved_ticket_id", null);
        assertThat(jdbc.queryForObject("SELECT image_deleted_at IS NOT NULL FROM seat_claim WHERE id = ?", Boolean.class, ids[1])).isTrue();

        // 처리된 행은 다시 처리할 수 없다(엔티티 방어선)
        tx.executeWithoutResult(s -> {
            PerformanceEditProposal p = em.find(PerformanceEditProposal.class, ids[0]);
            assertThatThrownBy(() -> p.reject(3L, null, NOW)).isInstanceOf(IllegalStateException.class);
        });
        assertThatThrownBy(() -> SeatClaim.create(1L, null, "A", "A", "1", "1", "1", "1", null, UUID2, "image/png", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PerformanceEditProposal.create(new Performance(), 1L, " ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
