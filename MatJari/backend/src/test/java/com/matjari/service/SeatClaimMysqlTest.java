package com.matjari.service;

import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.SeatClaimDtos.AdminSeatClaimResponse;
import com.matjari.dto.response.SeatClaimDtos.SeatClaimResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.NotFoundException;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 좌석 소유 인증 요청(V12 seat_claim)의 실제 MySQL 통합 테스트. 환경변수가 없으면 건너뛴다:
 *   MATJARI_IT_JDBC_URL=jdbc:mysql://localhost:13322/matjari_adminclaim_it  MATJARI_IT_USER=root  MATJARI_IT_PASSWORD=...
 * 이미지는 임시 디렉터리에 저장한다. 경쟁은 CountDownLatch 로 동시에 출발시키고 라운드를 반복한다(교착·DB 예외는 실패).
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "ticket.max-active-per-user=1000")
class SeatClaimMysqlTest {

    private static final int ROUNDS = 10;
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
    private static final Path UPLOAD_DIR;

    static {
        try {
            UPLOAD_DIR = Files.createTempDirectory("seat-claims-it");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("matjari.upload.dir", UPLOAD_DIR::toString);
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.cleanDatabase();
        registry.add("spring.datasource.url", RealMysqlSupport::url);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private SeatClaimService claimService;
    @Autowired
    private ExchangeMatchService matchService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SeatClaimImageCleanupService cleanupService;

    private long seq;
    private long performanceId;
    private long session;
    private long admin;
    private long claimant;

    @BeforeEach
    void reset() throws IOException {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("seat_claim", "performance_edit_proposal", "chat_message", "chat_read_state", "notification", "exchange_history",
                "exchange_ticket_lock", "exchange_match", "exchange_want_seat", "exchange_want_session", "exchange_want_range",
                "exchange_request", "ticket", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        try (Stream<Path> files = Files.list(UPLOAD_DIR)) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }
        seq = 0;
        long registrant = user("registrant");
        admin = user("관리자");
        claimant = user("요청자");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', '인증 공연', '올림픽홀', ?)", registrant);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)",
                LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0), performanceId);
        session = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    // ------------------------------------------------------------------ 생성

    @Test
    void createStoresFileAndRowAndRejectsDuplicatesAndLimit() throws Exception {
        Seat seat = holder("A", 1);

        SeatClaimResponse r = claim(claimant, "A", "1", "1");
        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(files()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT image_content_type FROM seat_claim WHERE id = ?", String.class, r.id())).isEqualTo("image/jpeg");
        assertThat(claimService.image(claimant, false, r.id()).bytes()).isEqualTo(JPEG);
        assertThatThrownBy(() -> claimService.image(seat.userId, false, r.id())).isInstanceOf(NotFoundException.class);
        assertThat(claimService.image(admin, true, r.id()).bytes()).isEqualTo(JPEG);

        assertThatThrownBy(() -> claim(claimant, "A", "1", "1"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "SEAT_CLAIM_ALREADY_PENDING"));
        assertThatThrownBy(() -> claim(seat.userId, "A", "1", "1"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "MY_TICKET_ALREADY_REGISTERED"));
        assertThatThrownBy(() -> claim(claimant, "A", "1", "99"))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SEAT_NOT_REGISTERED"));
        assertThat(files()).as("실패한 요청은 파일을 남기지 않는다").hasSize(1);

        for (int col = 2; col <= 5; col++) {
            holder("A", col);
            claim(claimant, "A", "1", String.valueOf(col));
        }
        holder("A", 6);
        assertThatThrownBy(() -> claim(claimant, "A", "1", "6"))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SEAT_CLAIM_LIMIT_REACHED"));
        assertThat(files()).hasSize(5);
    }

    @Test
    void concurrentCreateOfTheSameSeatKeepsExactlyOneRowAndFile() throws Exception {
        holder("A", 1);
        for (int round = 0; round < ROUNDS; round++) {
            jdbc.update("DELETE FROM seat_claim");
            clearFiles();
            List<Object> results = runConcurrently(List.of(
                    () -> claim(claimant, "A", "1", "1"), () -> claim(claimant, "A", "1", "1"), () -> claim(claimant, "A", "1", "1")));
            assertThat(results.stream().filter(o -> o instanceof SeatClaimResponse)).as("round " + round).hasSize(1);
            assertThat(results.stream().filter(o -> o instanceof ConflictException)).hasSize(2);
            assertThat(count("SELECT COUNT(*) FROM seat_claim")).isEqualTo(1);
            assertThat(files()).as("진 요청의 파일은 지워진다").hasSize(1);
        }
    }

    // ------------------------------------------------------------------ 승인

    @Test
    void approveTakesDownTicketClosesRequestCancelsChattingMatchAndDeletesImageAfterCommit() throws Exception {
        Seat owner = holder("A", 1);
        Seat partner = holder("A", 2);
        wantEachOther(owner, partner);
        long matchId = matchService.propose(partner.userId, partner.requestId, owner.requestId).id();
        long claimId = claim(claimant, "A", "1", "1").id();

        AdminSeatClaimResponse r = claimService.approve(admin, claimId);

        assertThat(r.status()).isEqualTo("APPROVED");
        assertThat(r.reviewerNickname()).isEqualTo("관리자");
        assertThat(r.imageAvailable()).isFalse();
        assertThat(r.holder().ticketStatus()).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, owner.ticketId)).isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_request WHERE id = ?", String.class, owner.requestId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, matchId)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT canceled_by_id FROM exchange_match WHERE id = ?", Long.class, matchId)).isNull();
        assertThat(jdbc.queryForObject("SELECT resolved_ticket_id FROM seat_claim WHERE id = ?", Long.class, claimId)).isEqualTo(owner.ticketId);
        assertThat(jdbc.queryForObject("SELECT reviewed_by_id FROM seat_claim WHERE id = ?", Long.class, claimId)).isEqualTo(admin);
        assertThat(files()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT image_deleted_at IS NOT NULL FROM seat_claim WHERE id = ?", Boolean.class, claimId)).isTrue();
        assertThatThrownBy(() -> claimService.image(claimant, false, claimId)).isInstanceOf(SeatClaimService.ImageDeletedException.class);
        assertThatThrownBy(() -> claimService.approve(admin, claimId))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "CLAIM_ALREADY_REVIEWED"));
        // 파트너 티켓은 그대로
        assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, partner.ticketId)).isEqualTo("ACTIVE");
        // 요청자는 이제 그 좌석을 직접 등록할 수 있다
        assertThat(count("SELECT COUNT(*) FROM ticket WHERE status = 'ACTIVE' AND col_key = '1'")).isZero();
    }

    @Test
    void reservedTicketCannotBeApprovedButCanBeRejectedAndTheNextTryWorksAfterUnreserve() throws Exception {
        Seat owner = holder("A", 1);
        Seat partner = holder("A", 2);
        wantEachOther(owner, partner);
        long matchId = matchService.propose(partner.userId, partner.requestId, owner.requestId).id();
        reserveFlow(partner.userId, matchId);
        long claimId = claim(claimant, "A", "1", "1").id();

        assertThatThrownBy(() -> claimService.approve(admin, claimId))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_RESERVED"));
        assertThat(jdbc.queryForObject("SELECT status FROM seat_claim WHERE id = ?", String.class, claimId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, owner.ticketId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, matchId)).isEqualTo("RESERVED");
        assertThat(files()).as("승인 실패(롤백)면 파일은 그대로").hasSize(1);
        PageResponse<AdminSeatClaimResponse> list = claimService.adminList("PENDING", 0, 20);
        assertThat(list.content()).hasSize(1);
        assertThat(list.content().get(0).holder().ticketStatus()).isEqualTo("RESERVED_LOCKED");
        assertThat(list.content().get(0).holder().holderNickname()).isEqualTo("user1");

        matchService.unreserve(partner.userId, matchId);
        assertThat(claimService.approve(admin, claimId).status()).isEqualTo("APPROVED");
        assertThat(files()).isEmpty();
    }

    @Test
    void rejectDeletesImageAndKeepsTheTicket() throws Exception {
        Seat owner = holder("A", 1);
        long claimId = claim(claimant, "A", "1", "1").id();

        AdminSeatClaimResponse r = claimService.reject(admin, claimId, "  사진이 흐려요 ");

        assertThat(r.status()).isEqualTo("REJECTED");
        assertThat(r.reviewNote()).isEqualTo("사진이 흐려요");
        assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, owner.ticketId)).isEqualTo("ACTIVE");
        assertThat(files()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT image_deleted_at IS NOT NULL FROM seat_claim WHERE id = ?", Boolean.class, claimId)).isTrue();
        // 처리됐으니 같은 좌석에 다시 요청할 수 있다
        assertThat(claim(claimant, "A", "1", "1").status()).isEqualTo("PENDING");
    }

    @Test
    void approveSucceedsWithNullResolvedWhenSeatIsAlreadyEmpty() throws Exception {
        Seat owner = holder("A", 1);
        long claimId = claim(claimant, "A", "1", "1").id();
        jdbc.update("UPDATE ticket SET status = 'INACTIVE' WHERE id = ?", owner.ticketId);

        AdminSeatClaimResponse r = claimService.approve(admin, claimId);

        assertThat(r.status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT resolved_ticket_id FROM seat_claim WHERE id = ?", Long.class, claimId)).isNull();
    }

    @Test
    void approveDoesNotTakeDownTheClaimantsOwnTicketRegisteredAfterTheRequest() throws Exception {
        Seat owner = holder("A", 1);
        long claimId = claim(claimant, "A", "1", "1").id();
        // 소유자가 티켓을 내리고, 요청자가 그 좌석을 직접 등록한 뒤 관리자가 승인한다
        jdbc.update("UPDATE ticket SET status = 'INACTIVE' WHERE id = ?", owner.ticketId);
        jdbc.update("INSERT INTO ticket (performance_session_id, user_id, zone_label, zone_key, row_label, row_key, "
                + "col_label, col_key, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',NOW(6),NOW(6))",
                session, claimant, "A", "A", "1", "1", "1", "1");
        long mine = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        AdminSeatClaimResponse r = claimService.approve(admin, claimId);

        assertThat(r.status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, mine)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT resolved_ticket_id FROM seat_claim WHERE id = ?", Long.class, claimId)).isNull();
    }

    @Test
    void adminCannotApproveTheirOwnClaim() throws Exception {
        holder("A", 1);
        long claimId = claim(admin, "A", "1", "1").id();

        assertThatThrownBy(() -> claimService.approve(admin, claimId))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getDetails()).containsEntry("code", "SELF_REVIEW"));
        assertThat(jdbc.queryForObject("SELECT status FROM seat_claim WHERE id = ?", String.class, claimId)).isEqualTo("PENDING");
    }

    @Test
    void imageDeleteFailureDoesNotUndoTheReview() throws Exception {
        holder("A", 1);
        long claimId = claim(claimant, "A", "1", "1").id();
        // 파일을 디렉터리로 바꿔 deleteIfExists 가 실패하게 만든다(비어 있지 않은 디렉터리)
        String key = jdbc.queryForObject("SELECT image_key FROM seat_claim WHERE id = ?", String.class, claimId);
        Path file = UPLOAD_DIR.resolve(key);
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("x"), "x");

        assertThat(claimService.reject(admin, claimId, null).status()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT status FROM seat_claim WHERE id = ?", String.class, claimId)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT image_deleted_at IS NULL FROM seat_claim WHERE id = ?", Boolean.class, claimId))
                .as("삭제 실패면 삭제 시각을 기록하지 않는다").isTrue();
        Files.delete(file.resolve("x"));
        Files.delete(file);
    }

    @Test
    void cleanupDeletesLeftoverImageOfProcessedClaimAndOrphansButKeepsPendingImages() throws Exception {
        holder("A", 1);
        holder("A", 2);
        long processed = claim(claimant, "A", "1", "1").id();
        long pending = claim(claimant, "A", "1", "2").id();
        String processedKey = jdbc.queryForObject("SELECT image_key FROM seat_claim WHERE id = ?", String.class, processed);
        String pendingKey = jdbc.queryForObject("SELECT image_key FROM seat_claim WHERE id = ?", String.class, pending);
        claimService.reject(admin, processed, null);   // 파일 삭제와 기록까지 끝난다
        // 삭제가 실패했던 상황을 만든다: 파일이 남고 기록이 없다
        Path leftover = UPLOAD_DIR.resolve(processedKey);
        Files.write(leftover, JPEG);
        jdbc.update("UPDATE seat_claim SET image_deleted_at = NULL, reviewed_at = ? WHERE id = ?", LocalDateTime.now().minusHours(3), processed);
        // 요청 행이 없는 오래된 고아 파일, 방금 만든 고아 파일(저장 직후일 수 있어 지우지 않는다)
        String orphanKey = java.util.UUID.randomUUID().toString();
        Files.write(UPLOAD_DIR.resolve(orphanKey), JPEG);
        Files.setLastModifiedTime(UPLOAD_DIR.resolve(orphanKey),
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3 * 3600)));
        String freshKey = java.util.UUID.randomUUID().toString();
        Files.write(UPLOAD_DIR.resolve(freshKey), JPEG);
        // 처리 중 요청의 사진도 오래돼 보이게 한다(그래도 지우면 안 된다)
        Files.setLastModifiedTime(UPLOAD_DIR.resolve(pendingKey),
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3 * 3600)));

        SeatClaimImageCleanupService.Result result = cleanupService.cleanup();

        assertThat(result.processedFilesDeleted()).isEqualTo(1);
        assertThat(result.orphanFilesDeleted()).isEqualTo(1);
        assertThat(leftover).doesNotExist();
        assertThat(jdbc.queryForObject("SELECT image_deleted_at IS NOT NULL FROM seat_claim WHERE id = ?", Boolean.class, processed)).isTrue();
        assertThat(UPLOAD_DIR.resolve(orphanKey)).doesNotExist();
        assertThat(files()).containsExactlyInAnyOrder(pendingKey, freshKey);
        assertThat(jdbc.queryForObject("SELECT image_deleted_at IS NULL FROM seat_claim WHERE id = ?", Boolean.class, pending)).isTrue();
        assertThat(cleanupService.cleanup()).as("다시 실행해도 더 지울 것이 없다").isEqualTo(new SeatClaimImageCleanupService.Result(0, 0));
    }

    // ------------------------------------------------------------------ 경쟁

    @Test
    void concurrentReviewsOfTheSameClaimHaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            reset();
            Seat owner = holder("A", 1);
            long claimId = claim(claimant, "A", "1", "1").id();
            List<Object> results = runConcurrently(List.of(
                    () -> claimService.approve(admin, claimId), () -> claimService.approve(admin, claimId),
                    () -> claimService.reject(admin, claimId, "no"), () -> claimService.approve(admin, claimId)));

            assertThat(results.stream().filter(o -> o instanceof AdminSeatClaimResponse)).as("round " + round).hasSize(1);
            assertThat(results.stream().filter(o -> o instanceof ConflictException)).hasSize(3);
            for (Object o : results) {
                if (o instanceof ConflictException e) {
                    assertThat(e.getDetails()).containsEntry("code", "CLAIM_ALREADY_REVIEWED");
                }
            }
            String status = jdbc.queryForObject("SELECT status FROM seat_claim WHERE id = ?", String.class, claimId);
            assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, owner.ticketId))
                    .isEqualTo("APPROVED".equals(status) ? "INACTIVE" : "ACTIVE");
            assertThat(files()).isEmpty();
        }
    }

    @Test
    void approveRacingOwnerTakedownAndProposalNeitherDeadlocksNorBreaksInvariants() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            reset();
            Seat owner = holder("A", 1);
            Seat partner = holder("A", 2);
            Seat third = holder("A", 3);
            wantEachOther(owner, partner);
            wantEachOther(owner, third);
            long matchId = matchService.propose(partner.userId, partner.requestId, owner.requestId).id();
            long claimId = claim(claimant, "A", "1", "1").id();

            List<Object> results = runConcurrently(List.of(
                    () -> claimService.approve(admin, claimId),
                    () -> { ticketService.deactivate(owner.userId, owner.ticketId); return "down"; },
                    () -> matchService.propose(third.userId, third.requestId, owner.requestId),
                    () -> reserveFlow(partner.userId, matchId)));

            for (Object o : results) {
                if (o instanceof Exception e) {
                    assertThat(e).as("round " + round + ": " + e).isInstanceOfAny(ConflictException.class, BusinessRuleException.class);
                }
            }
            assertThat(jdbc.queryForObject("SELECT status FROM seat_claim WHERE id = ?", String.class, claimId))
                    .as("승인이 409(예약 잠금)면 PENDING 으로 남고, 아니면 APPROVED").isIn("PENDING", "APPROVED");
            // 불변식: 예약 잠금이 걸린 티켓은 ACTIVE, 내려간 티켓의 요청은 CLOSED, 활성 좌석 중복 없음
            assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN ticket t ON t.id = l.ticket_id WHERE t.status <> 'ACTIVE'")).isZero();
            assertThat(count("SELECT COUNT(*) FROM exchange_request r JOIN ticket t ON t.id = r.ticket_id WHERE t.status = 'INACTIVE' AND r.status = 'OPEN'")).isZero();
            assertThat(count("SELECT COUNT(*) FROM exchange_match m JOIN ticket t ON t.id IN (m.ticket_a_id, m.ticket_b_id) "
                    + "WHERE t.status = 'INACTIVE' AND m.status IN ('CHATTING')")).isZero();
            assertThat(files()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ 도우미

    private record Seat(long userId, long ticketId, long requestId, String col) {}

    private SeatClaimResponse claim(long userId, String zone, String row, String col) {
        return claimService.create(userId, session, zone, row, col, "메모",
                new MockMultipartFile("image", "x.png", "image/png", JPEG));
    }

    private long user(String nickname) {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, 'x', ?)",
                "u" + seq + "@t.com", nickname, "관리자".equals(nickname) ? "ADMIN" : "USER");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 새 사용자 한 명이 (zone, 열 1, 번 col) 티켓과 OPEN 교환 요청(희망 회차 = 이 회차)을 가진다. */
    private Seat holder(String zone, int col) {
        long userId = user("user" + col);
        jdbc.update("INSERT INTO ticket (performance_session_id, user_id, zone_label, zone_key, row_label, row_key, "
                + "col_label, col_key, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',NOW(6),NOW(6))",
                session, userId, zone, zone, "1", "1", String.valueOf(col), String.valueOf(col));
        long ticketId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_request (ticket_id, status, created_at, updated_at) VALUES (?, 'OPEN', NOW(6), NOW(6))", ticketId);
        long requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,1)", requestId, session);
        return new Seat(userId, ticketId, requestId, String.valueOf(col));
    }

    private void wantEachOther(Seat a, Seat b) {
        for (Seat[] pair : new Seat[][]{{a, b}, {b, a}}) {
            jdbc.update("INSERT IGNORE INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount) "
                    + "VALUES (?,?,?,?,'ANY',NULL)", pair[0].requestId, "A", "1", pair[1].col);
        }
    }

    private List<String> files() throws IOException {
        try (Stream<Path> s = Files.list(UPLOAD_DIR)) {
            return s.map(p -> p.getFileName().toString()).toList();
        }
    }

    private void clearFiles() throws IOException {
        for (String f : files()) {
            Files.delete(UPLOAD_DIR.resolve(f));
        }
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    /** 모든 호출을 같은 순간에 출발시키고 결과(응답 또는 던진 예외)를 모은다. 교착·락 대기 초과·DB 예외는 실패로 본다. */
    private List<Object> runConcurrently(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return call.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            for (Object r : results) {
                if (r instanceof org.springframework.dao.DataAccessException || r instanceof IllegalStateException) {
                    throw new AssertionError("교착/락 대기 초과/DB 예외/불변식 위반이 났다: " + r);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 예약 제안 -> 상대 수락으로 RESERVED 를 만든다(V15). */
    private com.matjari.dto.response.ExchangeMatchResponse reserveFlow(long proposerId, long matchId) {
        return com.matjari.testsupport.ReserveFlow.reserve(matchService, jdbc, proposerId, matchId);
    }
}
