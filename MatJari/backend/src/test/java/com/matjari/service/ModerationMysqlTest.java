package com.matjari.service;

import com.matjari.dto.response.ModerationDtos.AdminReport;
import com.matjari.dto.response.ModerationDtos.ReportCreateRequest;
import com.matjari.dto.response.ModerationDtos.ResolveRequest;
import com.matjari.dto.response.ModerationDtos.SanctionInput;
import com.matjari.exception.AccountSuspendedException;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.security.JwtTokenProvider;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신고·관리자 처리·이용 정지 강제의 실제 MySQL 통합 테스트(서비스 + 필터 체인 + 트랜잭션 + 잠금 + 제약). 환경변수가 없으면 건너뛴다:
 *   MATJARI_IT_JDBC_URL=jdbc:mysql://localhost:13412/matjari_reportbe_it  MATJARI_IT_USER=root  MATJARI_IT_PASSWORD=...
 * OpenMatchCanceller 구현체는 이 작업의 범위가 아니라서 호출만 기록하는 스텁을 쓴다(실제 취소 동작은 구현체 쪽 테스트가 본다).
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(properties = "ticket.max-active-per-user=100000")
@AutoConfigureMockMvc
class ModerationMysqlTest {

    /** 호출 기록 스텁: 활성 트랜잭션 안에서 불렸는지, 그 시점의 사용자 id. */
    static final class RecordingCanceller implements OpenMatchCanceller {
        final ConcurrentLinkedQueue<Long> users = new ConcurrentLinkedQueue<>();
        final AtomicInteger outsideTransaction = new AtomicInteger();

        @Override
        public int cancelBetween(long userA, long userB) {
            return 0;
        }

        @Override
        public int cancelAllOf(long userId) {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                outsideTransaction.incrementAndGet();
            }
            users.add(userId);
            return 0;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        RecordingCanceller recordingCanceller() {
            return new RecordingCanceller();
        }
    }

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
    private AbuseReportService reportService;
    @Autowired
    private AdminModerationService adminService;
    @Autowired
    private RecordingCanceller canceller;
    @Autowired
    private JwtTokenProvider jwt;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private long seq;
    private long performanceId;
    private long sessionId;
    private long admin;
    private long alice;
    private long bob;

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("abuse_report", "user_sanction", "user_block", "chat_message", "chat_read_state", "notification", "exchange_history",
                "exchange_ticket_lock", "exchange_match", "exchange_want_seat", "exchange_want_session", "exchange_want_range", "exchange_request",
                "ticket", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        seq = 0;
        canceller.users.clear();
        canceller.outsideTransaction.set(0);
        admin = user("관리자", "ADMIN");
        alice = user("앨리스", "USER");
        bob = user("밥", "USER");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', '신고 공연', '올림픽홀', ?)", alice);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), DATE_ADD(NOW(), INTERVAL 30 DAY), ?)", performanceId);
        sessionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    // ------------------------------------------------------------------ 픽스처

    private long user(String nickname, String role) {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, ?, ?)",
                "u" + seq + "@t.com", nickname, passwordEncoder.encode("password123"), role);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String email(long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private String bearer(long userId) {
        return "Bearer " + jwt.generateAccessToken(userId, email(userId));
    }

    private long ticketAndRequest(long userId) {
        seq++;
        jdbc.update("INSERT INTO ticket (performance_session_id, user_id, zone_label, zone_key, row_label, row_key, col_label, col_key, status, "
                + "created_at, updated_at) VALUES (?,?,'A','A','1','1',?,?,'ACTIVE',NOW(6),NOW(6))", sessionId, userId, String.valueOf(seq), String.valueOf(seq));
        long ticketId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_request (ticket_id, status, created_at, updated_at) VALUES (?, 'OPEN', NOW(6), NOW(6))", ticketId);
        return ticketId;
    }

    /** a 와 b 사이의 매칭 한 건(서로 다른 새 티켓·요청). 상태는 CHATTING. */
    private long match(long a, long b) {
        long ta = ticketAndRequest(a);
        long tb = ticketAndRequest(b);
        long ra = jdbc.queryForObject("SELECT id FROM exchange_request WHERE ticket_id = ?", Long.class, ta);
        long rb = jdbc.queryForObject("SELECT id FROM exchange_request WHERE ticket_id = ?", Long.class, tb);
        jdbc.update("INSERT INTO exchange_match(request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,created_at,updated_at,"
                + "a_extra_type,b_extra_type) VALUES (?,?,?,?,?,?,'CHATTING',NOW(6),NOW(6),'ANY','ANY')", ra, rb, ta, tb, a, b);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long report(long reporter, long matchId) {
        return reportService.create(reporter, matchId, new ReportCreateRequest("ABUSE", "욕설")).id();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static ResolveRequest suspend(Integer days) {
        return new ResolveRequest("조치", new SanctionInput(days, "욕설 반복"));
    }

    // ------------------------------------------------------------------ 신고

    @Test
    void 신고는_OPEN_중복이_409이고_처리_뒤에는_다시_신고할_수_있다() {
        long m = match(alice, bob);
        long first = report(alice, m);
        assertThat(jdbc.queryForObject("SELECT reported_id FROM abuse_report WHERE id = ?", Long.class, first)).isEqualTo(bob);
        assertThatThrownBy(() -> report(alice, m)).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getDetails()).containsEntry("code", "REPORT_ALREADY_OPEN"));
        report(bob, m);   // 상대가 같은 매칭을 신고하는 것은 별개

        adminService.dismiss(admin, first, "문제 없음");
        report(alice, m);   // 처리됐으니 OPEN 이 아님 -> 새 신고 가능
        assertThat(count("SELECT COUNT(*) FROM abuse_report WHERE reporter_id = " + alice)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM notification")).as("신고는 알림 행을 만들지 않는다").isZero();
    }

    @Test
    void 같은_매칭을_동시에_신고하면_하나만_접수되고_나머지는_409() throws Exception {
        long m = match(alice, bob);
        List<Object> results = runConcurrently(6, () -> report(alice, m));
        assertThat(results.stream().filter(r -> r instanceof Long)).hasSize(1);
        assertThat(results.stream().filter(r -> r instanceof ConflictException)).hasSize(5);
        assertThat(count("SELECT COUNT(*) FROM abuse_report")).isEqualTo(1);
    }

    @Test
    void 사용자당_OPEN_신고는_10건까지이고_11번째는_422_처리되면_다시_늘어난다() {
        long firstReport = 0;
        for (int i = 0; i < 10; i++) {
            long id = report(alice, match(alice, bob));
            if (i == 0) {
                firstReport = id;
            }
        }
        long eleventh = match(alice, bob);
        assertThatThrownBy(() -> report(alice, eleventh)).isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> assertThat(((BusinessRuleException) e).getCode()).isEqualTo("REPORT_LIMIT_REACHED"));
        assertThat(count("SELECT COUNT(*) FROM abuse_report")).isEqualTo(10);

        adminService.dismiss(admin, firstReport, null);
        report(alice, eleventh);
        assertThat(count("SELECT COUNT(*) FROM abuse_report WHERE status = 'OPEN'")).isEqualTo(10);
    }

    @Test
    void 비참여자와_없는_매칭은_404_그리고_닫힌_매칭은_신고할_수_있다() throws Exception {
        long m = match(alice, bob);
        long carol = user("캐럴", "USER");
        mockMvc.perform(post("/api/exchange/matches/" + m + "/report").header("Authorization", bearer(carol))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ABUSE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/exchange/matches/999999/report").header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ABUSE\"}"))
                .andExpect(status().isNotFound());

        jdbc.update("UPDATE exchange_match SET status = 'CANCELED', canceled_at = NOW(6) WHERE id = ?", m);
        mockMvc.perform(post("/api/exchange/matches/" + m + "/report").header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"OTHER\",\"detail\":\"연락 안 됨\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(post("/api/exchange/matches/" + m + "/report").header("Authorization", bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"OTHER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").exists());
        mockMvc.perform(get("/api/reports/me").header("Authorization", bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].reportedNickname").value("밥"))
                .andExpect(jsonPath("$.content[0].status").value("OPEN"))
                .andExpect(jsonPath("$.content[0].handlerNote").doesNotExist());
    }

    // ------------------------------------------------------------------ 처리·동시성

    @Test
    void 같은_신고를_동시에_resolve와_dismiss하면_한_건만_성공하고_나머지는_409_REPORT_ALREADY_HANDLED() throws Exception {
        for (int round = 0; round < 6; round++) {
            long m = match(alice, bob);
            long reportId = report(alice, m);
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> adminService.resolve(admin, reportId, null));
                tasks.add(() -> adminService.dismiss(admin, reportId, null));
            }
            List<Object> results = runAll(tasks);
            assertThat(results.stream().filter(r -> r instanceof AdminReport)).as("round " + round).hasSize(1);
            assertThat(results.stream().filter(r -> r instanceof ConflictException)).hasSize(7);
            assertThat(count("SELECT COUNT(*) FROM abuse_report WHERE id = " + reportId + " AND status <> 'OPEN' AND handled_by_id IS NOT NULL")).isEqualTo(1);
        }
    }

    @Test
    void 제재를_함께_걸어_같은_신고를_동시에_처리해도_제재는_한_건이고_canceller는_한_번만_불린다() throws Exception {
        long m = match(alice, bob);
        long reportId = report(alice, m);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> adminService.resolve(admin, reportId, suspend(7)));
        }
        List<Object> results = runAll(tasks);
        assertThat(results.stream().filter(r -> r instanceof AdminReport)).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM user_sanction WHERE user_id = " + bob)).isEqualTo(1);
        assertThat(canceller.users).containsExactly(bob);
        assertThat(canceller.outsideTransaction.get()).as("canceller 는 활성 트랜잭션 안에서 호출").isZero();
        assertThat(jdbc.queryForObject("SELECT sanction_id FROM abuse_report WHERE id = ?", Long.class, reportId))
                .isEqualTo(jdbc.queryForObject("SELECT id FROM user_sanction WHERE user_id = ?", Long.class, bob));
    }

    @Test
    void 같은_대상의_서로_다른_신고를_동시에_제재해도_활성_제재는_한_건이다() throws Exception {
        long carol = user("캐럴", "USER");
        long r1 = report(alice, match(alice, bob));
        long r2 = report(carol, match(carol, bob));
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Object> results = runAll(List.of(() -> adminService.resolve(admin, r1, suspend(30)), () -> adminService.resolve(admin, r2, suspend(7))));
            long ok = results.stream().filter(r -> r instanceof AdminReport).count();
            assertThat(ok).as("정확히 한 건만 제재 성공: " + results).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM user_sanction WHERE user_id = " + bob + " AND revoked_at IS NULL")).isEqualTo(1);
            // 다음 시도를 위해 초기화
            jdbc.update("UPDATE user_sanction SET revoked_at = NOW(6), revoked_by_id = ? WHERE user_id = ?", admin, bob);
            jdbc.update("UPDATE abuse_report SET status = 'OPEN', handled_by_id = NULL, handled_at = NULL, handler_note = NULL, sanction_id = NULL");
            jdbc.update("DELETE FROM user_sanction");
        }
    }

    @Test
    void ADMIN_대상_제재는_409이고_신고는_OPEN_그대로_제재_행도_취소_호출도_없다() {
        long m = match(alice, admin);
        long reportId = report(alice, m);
        assertThatThrownBy(() -> adminService.resolve(admin, reportId, suspend(7))).isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getDetails()).containsEntry("code", "CANNOT_SANCTION_ADMIN"));
        assertThat(count("SELECT COUNT(*) FROM user_sanction")).isZero();
        assertThat(canceller.users).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM abuse_report WHERE id = ?", String.class, reportId)).isEqualTo("OPEN");
        adminService.resolve(admin, reportId, new ResolveRequest("기록만", null));   // 제재 없는 조치는 가능
        assertThat(jdbc.queryForObject("SELECT status FROM abuse_report WHERE id = ?", String.class, reportId)).isEqualTo("RESOLVED");
    }

    @Test
    void 채팅_근거는_신고된_매칭의_메시지만_최신부터_보여_준다() throws Exception {
        long m = match(alice, bob);
        long other = match(alice, user("캐럴", "USER"));
        for (int i = 1; i <= 5; i++) {
            jdbc.update("INSERT INTO chat_message (match_id, sender_id, content, client_message_id, created_at) VALUES (?,?,?,?,NOW(6))",
                    m, i % 2 == 0 ? bob : alice, "m" + i, "client-id-m" + i);
        }
        jdbc.update("INSERT INTO chat_message (match_id, sender_id, content, client_message_id, created_at) VALUES (?,?,?,?,NOW(6))",
                other, alice, "다른 매칭", "client-id-other");
        long reportId = report(alice, m);

        mockMvc.perform(get("/api/admin/reports/" + reportId + "/messages?size=2").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].content").value("m5"))
                .andExpect(jsonPath("$.messages[0].senderNickname").value("앨리스"))
                .andExpect(jsonPath("$.messages[1].content").value("m4"))
                .andExpect(jsonPath("$.messages[1].senderNickname").value("밥"))
                .andExpect(jsonPath("$.hasMore").value(true));
        long oldestShown = jdbc.queryForObject("SELECT id FROM chat_message WHERE match_id = ? AND content = 'm4'", Long.class, m);
        mockMvc.perform(get("/api/admin/reports/" + reportId + "/messages?size=10&before=" + oldestShown).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.messages.length()").value(3))
                .andExpect(jsonPath("$.messages[2].content").value("m1"))
                .andExpect(jsonPath("$.hasMore").value(false));
        mockMvc.perform(get("/api/admin/reports/" + reportId + "/messages").header("Authorization", bearer(alice))).andExpect(status().isForbidden());
    }

    @Test
    void 목록_필터와_대상_누적_집계와_차단_현황() throws Exception {
        long carol = user("캐럴", "USER");
        long r1 = report(alice, match(alice, bob));
        report(carol, match(carol, bob));
        long r3 = report(alice, match(alice, carol));
        adminService.dismiss(admin, r3, "기각");
        jdbc.update("INSERT INTO user_block (blocker_id, blocked_id, created_at) VALUES (?,?,NOW(6))", alice, bob);

        mockMvc.perform(get("/api/admin/reports?status=OPEN&reason=ABUSE").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].reported.nickname").value("밥"))
                .andExpect(jsonPath("$.content[0].reported.totalReports").value(2))
                .andExpect(jsonPath("$.content[0].reported.openReports").value(2))
                .andExpect(jsonPath("$.content[0].reported.activeSanction").doesNotExist());
        mockMvc.perform(get("/api/admin/reports?status=DISMISSED").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].handlerNickname").value("관리자"))
                .andExpect(jsonPath("$.content[0].handlerNote").value("기각"));
        mockMvc.perform(get("/api/admin/reports?reason=FRAUD").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/admin/reports?status=BAD").header("Authorization", bearer(admin))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/blocks").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].blocker.nickname").value("앨리스"))
                .andExpect(jsonPath("$.content[0].blocked.nickname").value("밥"));

        adminService.resolve(admin, r1, suspend(7));
        mockMvc.perform(get("/api/admin/reports/" + r1).header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.reported.activeSanction.id").isNumber())
                .andExpect(jsonPath("$.reported.activeSanction.until").isString())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    // ------------------------------------------------------------------ 정지 강제

    @Test
    void 정지를_걸면_로그인_인증_API_재발급이_403이고_로그아웃만_된다_해제하면_바로_복귀한다() throws Exception {
        long m = match(alice, bob);
        long reportId = report(alice, m);
        String bobToken = bearer(bob);
        String bobRefresh = "Bearer " + jwt.generateRefreshToken(bob, email(bob));
        mockMvc.perform(get("/api/reports/me").header("Authorization", bobToken)).andExpect(status().isOk());

        adminService.resolve(admin, reportId, suspend(7));
        assertThat(canceller.users).containsExactly(bob);

        // 인증 API: 403 + 형식, 401 과 구분
        mockMvc.perform(get("/api/reports/me").header("Authorization", bobToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("이용이 정지된 계정입니다."))
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
                .andExpect(jsonPath("$.until").isString());
        mockMvc.perform(get("/api/notifications").header("Authorization", bobToken)).andExpect(status().isForbidden());
        // 로그인(비밀번호 맞음): 403 같은 형식, 토큰 없음. 틀린 비밀번호는 정지 여부를 드러내지 않는다
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email(bob) + "\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
                .andExpect(jsonPath("$.until").isString())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email(bob) + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest());
        // 토큰 재발급
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + bobRefresh.substring("Bearer ".length()) + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        // 정지 중에도 로그아웃은 가능
        mockMvc.perform(post("/api/auth/logout").header("Authorization", bobToken)).andExpect(status().isNoContent());
        // 다른 사용자는 영향 없음
        mockMvc.perform(get("/api/reports/me").header("Authorization", bearer(alice))).andExpect(status().isOk());

        // 해제 -> 즉시 복귀
        long sanctionId = jdbc.queryForObject("SELECT id FROM user_sanction WHERE user_id = ?", Long.class, bob);
        mockMvc.perform(post("/api/admin/sanctions/" + sanctionId + "/revoke").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.nickname").value("밥"))
                .andExpect(jsonPath("$.createdByNickname").value("관리자"))
                .andExpect(jsonPath("$.reportId").value(reportId));
        mockMvc.perform(post("/api/admin/sanctions/" + sanctionId + "/revoke").header("Authorization", bearer(admin))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SANCTION_NOT_ACTIVE"));
        mockMvc.perform(get("/api/reports/me").header("Authorization", bobToken)).andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email(bob) + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString());
    }

    @Test
    void 기간이_지나면_스케줄러_없이_자동으로_풀리고_무기한은_until이_null이다() throws Exception {
        long reportId = report(alice, match(alice, bob));
        adminService.resolve(admin, reportId, suspend(null));
        mockMvc.perform(get("/api/reports/me").header("Authorization", bearer(bob)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.until").value((Object) null));
        mockMvc.perform(get("/api/admin/sanctions?active=true").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].active").value(true))
                .andExpect(jsonPath("$.content[0].endsAt").value((Object) null));

        // 기간제로 바꾸고 만료 시각을 과거로 -> 복귀, 활성 목록에서 빠지고 비활성 목록에 나온다
        jdbc.update("UPDATE user_sanction SET starts_at = DATE_SUB(NOW(6), INTERVAL 10 DAY), ends_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE user_id = ?", bob);
        mockMvc.perform(get("/api/reports/me").header("Authorization", bearer(bob))).andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/sanctions?active=true").header("Authorization", bearer(admin))).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/admin/sanctions?active=false").header("Authorization", bearer(admin)))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].active").value(false));
        mockMvc.perform(get("/api/admin/sanctions").header("Authorization", bearer(admin))).andExpect(jsonPath("$.totalElements").value(1));
        assertThatThrownBy(() -> adminService.revoke(admin, jdbc.queryForObject("SELECT id FROM user_sanction", Long.class)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 정지된_사용자의_loadUserById와_서비스_로그인_경로가_AccountSuspendedException을_던진다() {
        long reportId = report(alice, match(alice, bob));
        adminService.resolve(admin, reportId, suspend(30));
        assertThatThrownBy(() -> mockLoad(bob)).isInstanceOf(AccountSuspendedException.class);
        assertThat(mockLoad(alice)).isEqualTo(alice);
    }

    @Autowired
    private com.matjari.security.CustomUserDetailsService userDetailsService;

    private long mockLoad(long userId) {
        return userDetailsService.loadUserById(userId).userId();
    }

    // ------------------------------------------------------------------ 동시 실행 도우미

    private List<Object> runConcurrently(int n, Callable<Object> task) throws Exception {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tasks.add(task);
        }
        return runAll(tasks);
    }

    /** 모든 작업을 출발선에 세웠다가 동시에 시작한다. 결과 또는 던진 예외를 목록에 담는다. */
    private List<Object> runAll(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }
}
