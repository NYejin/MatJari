package com.matjari.service;

import com.matjari.dto.request.WithdrawRequest;
import com.matjari.dto.response.ExchangeCandidateResponse;
import com.matjari.dto.response.ExchangeMatchResponse;
import com.matjari.dto.response.ExchangeRequestResponse;
import com.matjari.dto.response.ListingImageRef;
import com.matjari.dto.response.TicketResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 티켓·교환 희망 조건 사진(V33)의 실제 MySQL 통합 테스트. 환경변수가 없으면 건너뛴다(MATJARI_IT_JDBC_URL 등, DB 이름은 _it 로 끝나야 한다).
 * 올리기 규칙, 열람 권한 네 가지(소유자·관리자·매칭 상대·지금 후보)와 거부, 후보·매칭 응답의 사진 목록, 정리 작업(보관 기간·신고 보류·고아 파일),
 * 요청 삭제·회원 탈퇴 정리를 본다. 속도 제한은 여기서 높여 두고 {@code ListingImageRateLimiterTest} 가 따로 본다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "ticket.max-active-per-user=100000",
        "matjari.listing-image.process-per-minute=100000",
        "matjari.listing-image.daily-limit=100000",
        "matjari.listing-image.cleanup.enabled=false"})
class ListingImageMysqlTest {

    private static final String PASSWORD = "password123";
    private static final Path UPLOAD_DIR;

    static {
        try {
            UPLOAD_DIR = Files.createTempDirectory("listing-image-it");
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
    private ListingImageService imageService;
    @Autowired
    private ListingImageAccessService accessService;
    @Autowired
    private ListingImageCleanupService cleanupService;
    @Autowired
    private ExchangeCandidateService candidateService;
    @Autowired
    private ExchangeMatchQueryService queryService;
    @Autowired
    private ExchangeMatchService matchService;
    @Autowired
    private ExchangeRequestService requestService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private WithdrawService withdrawService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    private long seq;
    private long performanceId;
    private long session1;
    private final Map<Integer, Long> users = new HashMap<>();

    @BeforeEach
    void reset() throws IOException {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("listing_image", "abuse_report", "user_sanction", "user_block", "social_account", "chat_message", "chat_read_state",
                "notification", "exchange_history", "exchange_ticket_lock", "exchange_match", "exchange_want_seat", "exchange_want_session",
                "exchange_want_range", "exchange_request", "ticket", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        withdrawService.clearRateLimits();
        seq = 0;
        users.clear();
        long reg = user("registrant", "USER");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', '사진 공연', '홀', ?)", reg);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)",
                LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0), performanceId);
        session1 = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Path dir = UPLOAD_DIR.resolve("listing");
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                files.forEach(f -> f.toFile().delete());
            }
        }
    }

    // ------------------------------------------------------------------ 올리기

    @Test
    void 티켓_사진은_본인의_활성_티켓에만_3장까지_올릴_수_있다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");

        ListingImageRef a = imageService.uploadForTicket(p.userId, p.ticketId, png());
        imageService.uploadForTicket(p.userId, p.ticketId, png());
        imageService.uploadForTicket(p.userId, p.ticketId, jpeg());
        assertThat(count("SELECT COUNT(*) FROM listing_image WHERE ticket_id = " + p.ticketId)).isEqualTo(3);
        assertThat(count("SELECT COUNT(DISTINCT sort_order) FROM listing_image WHERE ticket_id = " + p.ticketId)).isEqualTo(3);
        assertThat(filesInStore()).isEqualTo(3);

        assertThatThrownBy(() -> imageService.uploadForTicket(p.userId, p.ticketId, png()))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_LIMIT_REACHED"));
        assertThat(filesInStore()).as("상한 초과는 파일을 만들지 않는다").isEqualTo(3);
        // 남의 티켓은 404, 없는 티켓도 404
        assertThatThrownBy(() -> imageService.uploadForTicket(q.userId, p.ticketId, png())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> imageService.uploadForTicket(p.userId, 999_999L, png())).isInstanceOf(NotFoundException.class);
        // 지우면 빈 칸이 다시 생긴다(멱등 삭제)
        imageService.deleteForTicket(p.userId, p.ticketId, a.id());
        imageService.deleteForTicket(p.userId, p.ticketId, a.id());
        assertThat(filesInStore()).isEqualTo(2);
        imageService.uploadForTicket(p.userId, p.ticketId, png());
        assertThat(count("SELECT COUNT(*) FROM listing_image WHERE ticket_id = " + p.ticketId)).isEqualTo(3);
        // 남의 티켓의 사진 지우기는 404, 다른 티켓 id 로 지우기는 아무 일도 없다(204)
        assertThatThrownBy(() -> imageService.deleteForTicket(q.userId, p.ticketId, a.id())).isInstanceOf(NotFoundException.class);
        long other = jdbc.queryForObject("SELECT id FROM listing_image WHERE ticket_id = ? LIMIT 1", Long.class, p.ticketId);
        imageService.deleteForTicket(q.userId, q.ticketId, other);
        assertThat(count("SELECT COUNT(*) FROM listing_image WHERE id = " + other)).isEqualTo(1);
    }

    @Test
    void 내린_티켓과_잘못된_파일은_거절하고_파일을_남기지_않는다() {
        Party p = party(1, "Z1");
        jdbc.update("UPDATE ticket SET status = 'INACTIVE' WHERE id = ?", p.ticketId);
        assertThatThrownBy(() -> imageService.uploadForTicket(p.userId, p.ticketId, png()))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_NOT_ACTIVE"));

        Party q = party(2, "Z1");
        MockMultipartFile text = new MockMultipartFile("image", "a.png", "image/png", "not an image".getBytes());
        assertThatThrownBy(() -> imageService.uploadForTicket(q.userId, q.ticketId, text)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> imageService.uploadForTicket(q.userId, q.ticketId, null)).isInstanceOf(FieldValidationException.class);
        assertThat(count("SELECT COUNT(*) FROM listing_image")).isZero();
        assertThat(filesInStore()).isZero();
    }

    @Test
    void 요청_사진은_본인의_열린_요청에만_올리고_삭제된_요청은_409다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        imageService.uploadForRequest(p.userId, p.requestId, png());
        assertThatThrownBy(() -> imageService.uploadForRequest(q.userId, p.requestId, png())).isInstanceOf(NotFoundException.class);
        imageService.uploadForRequest(p.userId, p.requestId, png());
        imageService.uploadForRequest(p.userId, p.requestId, png());
        assertThatThrownBy(() -> imageService.uploadForRequest(p.userId, p.requestId, png()))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_LIMIT_REACHED"));

        jdbc.update("UPDATE exchange_request SET status = 'DELETED', deleted_at = NOW(6) WHERE id = ?", q.requestId);
        assertThatThrownBy(() -> imageService.uploadForRequest(q.userId, q.requestId, png()))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "REQUEST_DELETED"));
        jdbc.update("UPDATE exchange_request SET status = 'CLOSED' WHERE id = ?", p.requestId);
        long img = jdbc.queryForObject("SELECT id FROM listing_image WHERE request_id = ? LIMIT 1", Long.class, p.requestId);
        imageService.deleteForRequest(p.userId, p.requestId, img);   // 닫힌 요청의 사진도 본인은 지울 수 있다
        assertThat(count("SELECT COUNT(*) FROM listing_image WHERE request_id = " + p.requestId)).isEqualTo(2);
    }

    @Test
    void 요청을_삭제하면_그_요청_사진은_곧바로_지워진다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        imageService.uploadForRequest(p.userId, p.requestId, png());
        imageService.uploadForRequest(p.userId, p.requestId, png());
        imageService.uploadForTicket(p.userId, p.ticketId, png());   // 티켓 사진은 요청 삭제와 무관
        imageService.uploadForRequest(q.userId, q.requestId, png());
        assertThat(filesInStore()).isEqualTo(4);

        requestService.delete(p.userId, p.requestId);

        assertThat(count("SELECT COUNT(*) FROM listing_image WHERE request_id = " + p.requestId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM listing_image WHERE ticket_id = " + p.ticketId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM listing_image WHERE request_id = " + q.requestId)).isEqualTo(1);
        assertThat(filesInStore()).as("커밋 뒤 파일 삭제").isEqualTo(2);
        requestService.delete(p.userId, p.requestId);   // 멱등
    }

    // ------------------------------------------------------------------ 열람 권한

    @Test
    void 소유자와_관리자는_볼_수_있고_관계없는_사용자는_404다() {
        Party p = party(1, "Z1");
        Party stranger = party(9, "Z9");
        long admin = user("admin", "ADMIN");
        long ticketImg = imageService.uploadForTicket(p.userId, p.ticketId, png()).id();
        long requestImg = imageService.uploadForRequest(p.userId, p.requestId, jpeg()).id();

        assertThat(accessService.view(p.userId, false, ticketImg).contentType()).isEqualTo("image/png");
        assertThat(accessService.view(p.userId, false, requestImg).contentType()).isEqualTo("image/jpeg");
        assertThat(accessService.view(admin, true, ticketImg).bytes()).isNotEmpty();
        // 희망 조건이 서로 맞지 않는 사용자: 404(없는 사진과 같다)
        assertThatThrownBy(() -> accessService.view(stranger.userId, false, ticketImg)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> accessService.view(stranger.userId, false, requestImg)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> accessService.view(stranger.userId, false, 999_999L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 지금_후보_관계인_사용자는_볼_수_있고_차단_정지_잠금이면_볼_수_없다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long ticketImg = imageService.uploadForTicket(p.userId, p.ticketId, png()).id();
        long requestImg = imageService.uploadForRequest(p.userId, p.requestId, png()).id();

        assertThat(accessService.view(q.userId, false, ticketImg).bytes()).isNotEmpty();
        assertThat(accessService.view(q.userId, false, requestImg).bytes()).isNotEmpty();
        // 후보 관계는 양방향이다
        long qImg = imageService.uploadForTicket(q.userId, q.ticketId, png()).id();
        assertThat(accessService.view(p.userId, false, qImg).bytes()).isNotEmpty();

        // 한쪽이 상대 좌석을 더는 원하지 않으면 후보가 아니다
        jdbc.update("DELETE FROM exchange_want_seat WHERE request_id = ?", q.requestId);
        assertThatThrownBy(() -> accessService.view(q.userId, false, ticketImg)).isInstanceOf(NotFoundException.class);
        wants(q, p);
        assertThat(accessService.view(q.userId, false, ticketImg).bytes()).isNotEmpty();

        // 차단(어느 방향이든)
        jdbc.update("INSERT INTO user_block (blocker_id, blocked_id, created_at) VALUES (?, ?, NOW(6))", p.userId, q.userId);
        assertThatThrownBy(() -> accessService.view(q.userId, false, ticketImg)).isInstanceOf(NotFoundException.class);
        jdbc.update("DELETE FROM user_block");
        assertThat(accessService.view(q.userId, false, ticketImg).bytes()).isNotEmpty();

        // 사진 주인이 이용 정지 중이면 후보에서 빠진다
        long admin = user("admin", "ADMIN");
        jdbc.update("INSERT INTO user_sanction (user_id, type, note, starts_at, ends_at, created_by_id, created_at) "
                + "VALUES (?, 'SUSPEND', 'n', NOW(6), NULL, ?, NOW(6))", p.userId, admin);
        assertThatThrownBy(() -> accessService.view(q.userId, false, ticketImg)).isInstanceOf(NotFoundException.class);
        jdbc.update("DELETE FROM user_sanction");

        // 상대 요청이 닫히면(티켓 내림) 후보가 아니다
        jdbc.update("UPDATE exchange_request SET status = 'CLOSED' WHERE id = ?", p.requestId);
        assertThatThrownBy(() -> accessService.view(q.userId, false, ticketImg)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 매칭_상대는_후보가_아니게_돼도_매칭이_닫힌_뒤에도_볼_수_있다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long pTicketImg = imageService.uploadForTicket(p.userId, p.ticketId, png()).id();
        long pRequestImg = imageService.uploadForRequest(p.userId, p.requestId, png()).id();
        long matchId = matchService.propose(q.userId, q.requestId, p.requestId).id();

        // 후보 관계를 끊어도(서로 좌석 희망 삭제) 매칭 참여자는 열람할 수 있다
        jdbc.update("DELETE FROM exchange_want_seat");
        assertThat(accessService.view(q.userId, false, pTicketImg).bytes()).isNotEmpty();
        assertThat(accessService.view(q.userId, false, pRequestImg).bytes()).isNotEmpty();
        // 매칭에 없는 제3자는 여전히 404
        Party other = party(3, "Z3");
        assertThatThrownBy(() -> accessService.view(other.userId, false, pTicketImg)).isInstanceOf(NotFoundException.class);

        matchService.cancel(q.userId, matchId);   // 매칭 종료
        assertThat(accessService.view(q.userId, false, pTicketImg).bytes()).as("닫힌 매칭 뒤에도 대화 상대는 열람 가능").isNotEmpty();
        assertThatThrownBy(() -> accessService.view(other.userId, false, pTicketImg)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 정리된_사진은_권한이_있는_사람에게만_410이고_없는_사람에게는_404다() {
        Party p = party(1, "Z1");
        Party stranger = party(9, "Z9");
        long img = imageService.uploadForTicket(p.userId, p.ticketId, png()).id();
        jdbc.update("UPDATE listing_image SET deleted_at = NOW(6) WHERE id = ?", img);
        assertThatThrownBy(() -> accessService.view(p.userId, false, img))
                .isInstanceOfSatisfying(ChatImageStore.ImageExpiredException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_EXPIRED"));
        assertThatThrownBy(() -> accessService.view(stranger.userId, false, img)).isInstanceOf(NotFoundException.class);
        // 파일이 사라진 경우도 410
        long img2 = imageService.uploadForTicket(p.userId, p.ticketId, png()).id();
        String key = jdbc.queryForObject("SELECT image_key FROM listing_image WHERE id = ?", String.class, img2);
        new ListingImageStore(UPLOAD_DIR.toString()).delete(key);
        assertThatThrownBy(() -> accessService.view(p.userId, false, img2)).isInstanceOf(ChatImageStore.ImageExpiredException.class);
    }

    // ------------------------------------------------------------------ 응답의 사진 목록

    @Test
    void 내_목록_후보_매칭_응답에_사진_참조가_들어간다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party r = party(3, "Z3");
        mutual(p, q);
        mutual(p, r);
        long p1 = imageService.uploadForTicket(p.userId, p.ticketId, png()).id();
        long p2 = imageService.uploadForTicket(p.userId, p.ticketId, png()).id();
        long pr = imageService.uploadForRequest(p.userId, p.requestId, png()).id();
        long qt = imageService.uploadForTicket(q.userId, q.ticketId, png()).id();
        long qr = imageService.uploadForRequest(q.userId, q.requestId, png()).id();

        List<TicketResponse> mine = ticketService.listMine(p.userId);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).images()).extracting(ListingImageRef::id).containsExactly(p1, p2);
        List<ExchangeRequestResponse> myRequests = requestService.listMine(p.userId, null);
        assertThat(myRequests.get(0).images()).extracting(ListingImageRef::id).containsExactly(pr);

        // q 의 후보 목록: p 의 사진만(r 은 사진 없음, 같은 쌍은 p 만 후보)
        List<ExchangeCandidateResponse> candidates = candidateService.findCandidateSlice(q.userId, q.requestId, null, 20).content();
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).ticketImages()).extracting(ListingImageRef::id).containsExactly(p1, p2);
        assertThat(candidates.get(0).requestImages()).extracting(ListingImageRef::id).containsExactly(pr);
        List<ExchangeCandidateResponse> pCandidates = candidateService.findCandidates(p.userId, p.requestId, 0, 20).content();
        assertThat(pCandidates).extracting(ExchangeCandidateResponse::ticketId).containsExactlyInAnyOrder(q.ticketId, r.ticketId);
        ExchangeCandidateResponse qc = pCandidates.stream().filter(c -> c.ticketId() == q.ticketId).findFirst().orElseThrow();
        assertThat(qc.ticketImages()).extracting(ListingImageRef::id).containsExactly(qt);
        assertThat(qc.requestImages()).extracting(ListingImageRef::id).containsExactly(qr);
        ExchangeCandidateResponse rc = pCandidates.stream().filter(c -> c.ticketId() == r.ticketId).findFirst().orElseThrow();
        assertThat(rc.ticketImages()).isEmpty();
        assertThat(rc.requestImages()).isEmpty();

        // 내 매칭: 상대 쪽 / 내 쪽
        long matchId = matchService.propose(q.userId, q.requestId, p.requestId).id();
        ExchangeMatchResponse qm = queryService.findOne(q.userId, matchId);
        assertThat(qm.myTicketImages()).extracting(ListingImageRef::id).containsExactly(qt);
        assertThat(qm.partnerTicketImages()).extracting(ListingImageRef::id).containsExactly(p1, p2);
        assertThat(qm.partnerRequestImages()).extracting(ListingImageRef::id).containsExactly(pr);
        ExchangeMatchResponse pm = queryService.findMine(p.userId, null, null, 0, 20).content().get(0);
        assertThat(pm.myTicketImages()).extracting(ListingImageRef::id).containsExactly(p1, p2);
        assertThat(pm.partnerTicketImages()).extracting(ListingImageRef::id).containsExactly(qt);
        assertThat(pm.partnerRequestImages()).extracting(ListingImageRef::id).containsExactly(qr);

        // 상대 요청이 삭제되면 상대 요청 사진은 빈 목록(티켓 사진은 남는다)
        requestService.delete(p.userId, p.requestId);
        ExchangeMatchResponse after = queryService.findOne(q.userId, matchId);
        assertThat(after.partnerRequestImages()).isEmpty();
        assertThat(after.partnerTicketImages()).extracting(ListingImageRef::id).containsExactly(p1, p2);
    }

    // ------------------------------------------------------------------ 정리 작업

    @Test
    void 정리_작업은_보관_기간이_지난_사진만_지우고_처리_전_신고가_있으면_남긴다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party r = party(3, "Z3");
        Party s = party(4, "Z4");
        long oldTicketImg = imageService.uploadForTicket(p.userId, p.ticketId, png()).id();
        long recentTicketImg = imageService.uploadForTicket(q.userId, q.ticketId, png()).id();
        long activeImg = imageService.uploadForTicket(r.userId, r.ticketId, png()).id();
        long oldRequestImg = imageService.uploadForRequest(s.userId, s.requestId, png()).id();
        long reportedImg = imageService.uploadForRequest(r.userId, r.requestId, png()).id();

        // p 티켓: 내린 지 31일 / q 티켓: 1일 / r 티켓: 활성 / s 요청: 31일 전 닫힘 / r 요청: 31일 전 닫힘이지만 처리 전 신고가 걸린 매칭(r-u)
        jdbc.update("UPDATE ticket SET status = 'INACTIVE', updated_at = ? WHERE id = ?", LocalDateTime.now().minusDays(31), p.ticketId);
        jdbc.update("UPDATE ticket SET status = 'EXCHANGED', updated_at = ? WHERE id = ?", LocalDateTime.now().minusDays(1), q.ticketId);
        jdbc.update("UPDATE exchange_request SET status = 'CLOSED', updated_at = ? WHERE id = ?", LocalDateTime.now().minusDays(31), s.requestId);
        jdbc.update("UPDATE exchange_request SET status = 'CLOSED', updated_at = ? WHERE id = ?", LocalDateTime.now().minusDays(31), r.requestId);
        Party u = party(5, "Z5");
        long matchId = insertMatch(r, u);
        jdbc.update("INSERT INTO abuse_report (reporter_id, reported_id, match_id, reason, detail, status, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'ABUSE', NULL, 'OPEN', NOW(6), NOW(6))", u.userId, r.userId, matchId);

        ListingImageCleanupService.Result first = cleanupService.cleanup();

        assertThat(first.expiredDeleted()).isEqualTo(2);   // p 의 티켓 사진, s 의 요청 사진
        assertThat(deletedAt(oldTicketImg)).isNotNull();
        assertThat(deletedAt(oldRequestImg)).isNotNull();
        assertThat(deletedAt(recentTicketImg)).isNull();
        assertThat(deletedAt(activeImg)).isNull();
        assertThat(deletedAt(reportedImg)).as("처리 전 신고가 있으면 남긴다").isNull();
        assertThat(filesInStore()).isEqualTo(3);

        // 신고가 처리되면 다음 주기에 지운다
        jdbc.update("UPDATE abuse_report SET status = 'DISMISSED', handled_by_id = ?, handled_at = NOW(6)", user("admin", "ADMIN"));
        assertThat(cleanupService.cleanup().expiredDeleted()).isEqualTo(1);
        assertThat(deletedAt(reportedImg)).isNotNull();
        assertThat(cleanupService.cleanup().expiredDeleted()).isZero();   // 멱등
        // 활성 티켓의 사진은 응답에 그대로 나온다
        assertThat(ticketService.listMine(r.userId).get(0).images()).hasSize(1);
    }

    @Test
    void 정리_작업은_오래된_고아_파일도_지운다() throws IOException {
        Party p = party(1, "Z1");
        long img = imageService.uploadForTicket(p.userId, p.ticketId, png()).id();
        Path dir = UPLOAD_DIR.resolve("listing");
        Path orphan = dir.resolve(UUID.randomUUID().toString());
        Path freshOrphan = dir.resolve(UUID.randomUUID().toString());
        Files.write(orphan, new byte[]{1, 2, 3});
        Files.write(freshOrphan, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(orphan, FileTime.from(java.time.Instant.now().minusSeconds(3 * 3600)));
        String liveKey = jdbc.queryForObject("SELECT image_key FROM listing_image WHERE id = ?", String.class, img);
        Files.setLastModifiedTime(dir.resolve(liveKey), FileTime.from(java.time.Instant.now().minusSeconds(3 * 3600)));

        ListingImageCleanupService.Result result = cleanupService.cleanup();

        assertThat(result.orphanDeleted()).isEqualTo(1);
        assertThat(Files.exists(orphan)).isFalse();
        assertThat(Files.exists(freshOrphan)).as("grace 안의 새 파일은 남긴다").isTrue();
        assertThat(Files.exists(dir.resolve(liveKey))).as("살아 있는 행의 파일은 남긴다").isTrue();
    }

    // ------------------------------------------------------------------ 탈퇴

    @Test
    void 회원_탈퇴는_그_사용자의_사진_파일을_지우고_행에_정리_시각을_남긴다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        imageService.uploadForTicket(p.userId, p.ticketId, png());
        imageService.uploadForRequest(p.userId, p.requestId, png());
        long qImg = imageService.uploadForTicket(q.userId, q.ticketId, png()).id();
        assertThat(filesInStore()).isEqualTo(3);

        withdrawService.withdraw(p.userId, new WithdrawRequest(PASSWORD, null));

        assertThat(count("SELECT COUNT(*) FROM listing_image WHERE owner_id = " + p.userId + " AND deleted_at IS NOT NULL")).isEqualTo(2);
        assertThat(filesInStore()).isEqualTo(1);
        assertThat(deletedAt(qImg)).as("상대 사진은 그대로").isNull();
        assertThat(ticketService.listMine(q.userId).get(0).images()).hasSize(1);
    }

    // ------------------------------------------------------------------ 도우미

    private record Party(long userId, long ticketId, long requestId, String zone, String row, String col) {}

    private long user(String nickname, String role) {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, ?, ?)",
                "u" + seq + "@t.com", nickname, passwordEncoder.encode(PASSWORD), role);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 사용자 n 번째(고정 사용자 풀)의 티켓·요청. 열은 "1", 번은 사용자 번호로 둔다. */
    private Party party(int n, String zone) {
        long userId = users.computeIfAbsent(n, k -> user("user" + k, "USER"));
        String col = String.valueOf(n);
        jdbc.update("INSERT INTO ticket (performance_session_id, user_id, zone_label, zone_key, row_label, row_key, "
                + "col_label, col_key, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',NOW(6),NOW(6))",
                session1, userId, zone, zone, "1", "1", col, col);
        long ticketId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_request (ticket_id, status, created_at, updated_at) VALUES (?, 'OPEN', NOW(6), NOW(6))", ticketId);
        long requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,1)", requestId, session1);
        return new Party(userId, ticketId, requestId, zone, "1", col);
    }

    private void wants(Party a, Party... others) {
        for (Party o : others) {
            jdbc.update("INSERT IGNORE INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount) "
                    + "VALUES (?,?,?,?,'ANY',NULL)", a.requestId, o.zone, o.row, o.col);
        }
    }

    private void mutual(Party a, Party b) {
        wants(a, b);
        wants(b, a);
    }

    /** 정리 작업 시험용으로 매칭 행을 직접 넣는다(CANCELED, 신고 근거). */
    private long insertMatch(Party a, Party b) {
        jdbc.update("INSERT INTO exchange_match (request_a_id, request_b_id, ticket_a_id, ticket_b_id, user_a_id, user_b_id, status, "
                + "a_extra_type, b_extra_type, canceled_at, created_at, updated_at) VALUES (?,?,?,?,?,?, 'CANCELED', 'ANY', 'ANY', NOW(6), NOW(6), NOW(6))",
                a.requestId, b.requestId, a.ticketId, b.ticketId, a.userId, b.userId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private Object deletedAt(long imageId) {
        return jdbc.queryForObject("SELECT deleted_at FROM listing_image WHERE id = ?", Object.class, imageId);
    }

    private long filesInStore() {
        Path dir = UPLOAD_DIR.resolve("listing");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (var files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockMultipartFile png() {
        return new MockMultipartFile("image", "a.png", "image/png", encode("png"));
    }

    private static MockMultipartFile jpeg() {
        return new MockMultipartFile("image", "a.jpg", "image/jpeg", encode("jpg"));
    }

    private static byte[] encode(String format) {
        BufferedImage image = new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 24; x++) {
            for (int y = 0; y < 16; y++) {
                image.setRGB(x, y, (x * 10) << 16 | (y * 15) << 8 | 0x40);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
