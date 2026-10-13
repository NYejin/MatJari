package com.matjari.service;

import com.matjari.domain.SocialProvider;
import com.matjari.dto.request.TicketCreateRequest;
import com.matjari.dto.request.WithdrawRequest;
import com.matjari.security.JwtTokenProvider;
import com.matjari.service.social.SocialAccountLinker;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 회원 탈퇴(V17)의 실제 MySQL 통합 테스트(서비스 + 필터 체인 + 트랜잭션 + 잠금 + 제약). MATJARI_IT_* 환경변수가 없으면 건너뛴다.
 * 동시성 시나리오는 CountDownLatch 로 동시에 출발시켜 여러 라운드 반복하며 교착·락 대기 초과(DataAccessException)를 허용하지 않고,
 * 매 라운드 탈퇴 불변식을 확인한다: 탈퇴 사용자의 활성 티켓 0, 열린 매칭 0, OPEN 요청 0, 잠금 행 = 2 x RESERVED, social_account 0, 알림 0, 이메일 형식.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(properties = {"ticket.max-active-per-user=100000", "matjari.email-verification.enabled=false"})
@AutoConfigureMockMvc
class WithdrawMysqlTest {

    private static final int ROUNDS = 15;
    private static final String PASSWORD = "password123";

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
    private WithdrawService withdrawService;
    @Autowired
    private ExchangeMatchService matchService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private ChatService chatService;
    @Autowired
    private SocialAccountLinker accountLinker;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwt;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.matjari.service.social.SocialLinkConfirmStore reauthStore;
    @Autowired
    private com.matjari.service.social.SocialProperties socialProperties;

    private long seq;
    private long colSeq;
    private long performanceId;
    private long session1;
    private String encodedPassword;
    private final Map<Integer, Long> users = new HashMap<>();

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("seat_claim", "performance_edit_proposal", "social_account", "user_sanction", "abuse_report", "user_block", "exchange_history",
                "chat_message", "chat_read_state", "notification", "exchange_ticket_lock", "exchange_match", "exchange_want_seat", "exchange_want_session",
                "exchange_want_range", "exchange_request", "ticket", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        withdrawService.clearRateLimits();
        seq = 0;
        colSeq = 0;
        users.clear();
        encodedPassword = passwordEncoder.encode(PASSWORD);
        long reg = user("registrant", "USER");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', '탈퇴 공연', '홀', ?)", reg);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)",
                LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0), performanceId);
        session1 = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    // ------------------------------------------------------------------ 단일 흐름

    @Test
    void 탈퇴는_열린_매칭을_모두_취소하고_티켓_요청_인증_소셜_알림을_정리하며_상대_데이터와_이력은_남긴다() throws Exception {
        Party p1 = party(1, "Z1");          // q 와 RESERVED
        Party p2 = party(1, "Z2");          // r 와 CHATTING(예약 제안 대기)
        Party p3 = party(1, "Z3");          // 이미 삭제된 요청
        Party q = party(2, "Z1");
        Party r = party(3, "Z2");
        mutual(p1, q);
        mutual(p2, r);
        jdbc.update("UPDATE exchange_request SET status = 'DELETED', deleted_at = NOW(6) WHERE id = ?", p3.requestId);
        long m1 = matchService.propose(q.userId, q.requestId, p1.requestId).id();
        matchService.reserve(q.userId, m1);
        matchService.reserveAccept(users.get(1), m1);
        long m2 = matchService.propose(r.userId, r.requestId, p2.requestId).id();
        matchService.reserve(r.userId, m2);
        chatService.send(users.get(1), m2, "안녕하세요", "11111111-wdrw");
        long p = users.get(1);
        jdbc.update("INSERT INTO social_account (user_id, provider, provider_user_id) VALUES (?, 'KAKAO', 'k-p')", p);
        jdbc.update("INSERT INTO social_account (user_id, provider, provider_user_id) VALUES (?, 'GOOGLE', 'g-q')", q.userId);
        jdbc.update("INSERT INTO seat_claim (claimant_id, performance_session_id, zone_label, zone_key, row_label, row_key, col_label, col_key, "
                + "image_key, image_content_type, image_size, status, created_at, updated_at) "
                + "VALUES (?, ?, 'Q', 'Q', '1', '1', '9', '9', '2f1b1b6e-7b34-4d6c-9b2e-0a1b2c3d4e5f', 'image/png', 100, 'PENDING', NOW(6), NOW(6))", p, session1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = " + p)).isPositive();
        long chatBefore = count("SELECT COUNT(*) FROM chat_message");

        withdrawService.withdraw(p, new WithdrawRequest(PASSWORD, null));

        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE id IN (" + m1 + "," + m2 + ") AND status = 'CANCELED' AND canceled_by_id IS NULL")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE id = " + m2 + " AND reserve_proposed_by_id IS NULL AND reserved_by_id IS NULL")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_request WHERE id = ?", String.class, p1.requestId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_request WHERE id = ?", String.class, p2.requestId)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_request WHERE id = ?", String.class, p3.requestId)).as("삭제된 요청은 그대로").isEqualTo("DELETED");
        assertThat(count("SELECT COUNT(*) FROM ticket WHERE user_id = " + p + " AND status = 'INACTIVE'")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, q.ticketId)).as("상대 티켓은 그대로").isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM exchange_request WHERE id = ?", String.class, q.requestId)).isEqualTo("OPEN");
        assertThat(jdbc.queryForMap("SELECT status, review_note, reviewed_by_id FROM seat_claim WHERE claimant_id = " + p))
                .containsEntry("status", "REJECTED").containsEntry("reviewed_by_id", p).containsEntry("review_note", SeatClaimService.WITHDRAWAL_REJECT_NOTE);
        assertThat(count("SELECT COUNT(*) FROM social_account WHERE user_id = " + p)).isZero();
        assertThat(count("SELECT COUNT(*) FROM social_account WHERE user_id = " + q.userId)).as("남의 소셜 연결은 그대로").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_message")).as("채팅은 지우지 않는다").isEqualTo(chatBefore);
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, r.userId)).isEqualTo("user3");
        Map<String, Object> row = jdbc.queryForMap("SELECT email, nickname, role, password_login_enabled, withdrawn_at, password FROM users WHERE id = " + p);
        assertThat(row).containsEntry("email", "withdrawn." + p + "@withdrawn.matjari.invalid").containsEntry("nickname", "탈퇴한 회원").containsEntry("role", "USER");
        assertThat(row.get("withdrawn_at")).isNotNull();
        assertThat(passwordEncoder.matches(PASSWORD, (String) row.get("password"))).isFalse();
        // 채팅 근거를 보는 쪽(상대·관리자)의 닉네임 조회는 users.nickname 이라 '탈퇴한 회원'이 된다
        assertThat(jdbc.queryForObject("SELECT u.nickname FROM chat_message c JOIN users u ON u.id = c.sender_id WHERE c.client_message_id = '11111111-wdrw'", String.class))
                .isEqualTo("탈퇴한 회원");
        invariants(p);
        invariants(-1);   // 전체 잠금 불변식
    }

    @Test
    void 탈퇴_뒤_기존_토큰은_401이고_로그인_재발급은_실패하며_같은_이메일로_다시_가입할_수_있다() throws Exception {
        long p = user("탈퇴자", "USER");
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, p);
        String access = jwt.generateAccessToken(p, email);
        String refresh = jwt.generateRefreshToken(p, email);
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access)).andExpect(status().isOk());

        // 본인 확인 실패: 400, 아무것도 바뀌지 않는다
        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("비밀번호가 올바르지 않습니다."));
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM users WHERE id = ?", Object.class, p)).isNull();

        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"withdrawn." + p + "@withdrawn.matjari.invalid\",\"password\":\"anything1\"}"))
                .andExpect(status().isBadRequest());
        // 원래 이메일로 재가입 가능
        mockMvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"nickname\":\"새회원\"}"))
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM users WHERE email = '" + email + "'")).isEqualTo(1);
    }

    @Test
    void 관리자는_409이고_소셜_전용_계정은_확인_문구로_탈퇴하며_연속_시도는_429() throws Exception {
        long admin = user("관리자", "ADMIN");
        String adminToken = "Bearer " + jwt.generateAccessToken(admin, "a@t.com");
        mockMvc.perform(delete("/api/users/me").header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ADMIN_CANNOT_WITHDRAW"));
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM users WHERE id = ?", Object.class, admin)).isNull();

        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, password_login_enabled, role) VALUES (NOW(6), ?, '소셜', ?, 0, 'USER')",
                "s" + seq + "@social.matjari.invalid", encodedPassword);
        long s = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String token = "Bearer " + jwt.generateAccessToken(s, "s@x.com");
        mockMvc.perform(delete("/api/users/me").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"confirmText\":\"탈퇴\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.confirmText").exists());
        mockMvc.perform(delete("/api/users/me").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"confirmText\":\"탈퇴합니다\"}"))
                .andExpect(status().isNoContent());
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = " + s + " AND withdrawn_at IS NOT NULL")).isEqualTo(1);

        long t = user("연타", "USER");
        String tToken = "Bearer " + jwt.generateAccessToken(t, "t@x.com");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(delete("/api/users/me").header("Authorization", tToken).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"bad\"}"))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(delete("/api/users/me").header("Authorization", tToken).contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("WITHDRAW_RATE_LIMITED"));
        assertThat(jdbc.queryForObject("SELECT withdrawn_at FROM users WHERE id = ?", Object.class, t)).isNull();
    }

    @Test
    void 연결된_제공자가_켜져_있으면_소셜_전용_계정은_재인증_코드로만_탈퇴한다() throws Exception {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, password_login_enabled, role) VALUES (NOW(6), ?, '소셜', ?, 0, 'USER')",
                "r" + seq + "@social.matjari.invalid", encodedPassword);
        long s = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO social_account (user_id, provider, provider_user_id) VALUES (?, 'GOOGLE', 'g-reauth')", s);
        String token = "Bearer " + jwt.generateAccessToken(s, "r@x.com");
        socialProperties.getGoogle().setClientId("g-client");
        try {
            // 확인 문구만으로는 안 된다(액세스 토큰만 훔친 경우)
            mockMvc.perform(delete("/api/users/me").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"confirmText\":\"탈퇴합니다\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reauthCode").exists());
            assertThat(count("SELECT COUNT(*) FROM users WHERE id = " + s + " AND withdrawn_at IS NOT NULL")).isZero();
            // 다른 계정(제공자 사용자 id 불일치)으로 인증한 코드도 안 된다
            String wrong = reauthStore.issue(s, com.matjari.domain.SocialProvider.GOOGLE, "someone-else");
            mockMvc.perform(delete("/api/users/me").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"confirmText\":\"탈퇴합니다\",\"reauthCode\":\"" + wrong + "\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reauthCode").exists());
            // 연결된 계정으로 방금 인증한 코드면 된다
            String code = reauthStore.issue(s, com.matjari.domain.SocialProvider.GOOGLE, "g-reauth");
            mockMvc.perform(delete("/api/users/me").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"confirmText\":\"탈퇴합니다\",\"reauthCode\":\"" + code + "\"}"))
                    .andExpect(status().isNoContent());
            assertThat(count("SELECT COUNT(*) FROM users WHERE id = " + s + " AND withdrawn_at IS NOT NULL")).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM social_account WHERE user_id = " + s)).isZero();
        } finally {
            socialProperties.getGoogle().setClientId("");
        }
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    void 탈퇴와_새_제안이_동시에_와도_교착이_없고_불변식이_유지된다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(100 + round, "Z" + round);
            Party q = party(400 + round, "Z" + round);
            mutual(p, q);
            long p1 = p.userId;
            List<Object> results = runConcurrently(shuffled(
                    () -> withdraw(p1),
                    () -> matchService.propose(q.userId, q.requestId, p.requestId)));
            assertThat(results).as("round " + round + " " + results).noneMatch(x -> x instanceof Exception && isUnexpectedForWithdraw((Exception) x));
            invariants(p1);
        }
    }

    @Test
    void 탈퇴와_예약_수락_예약_취소가_동시에_와도_잠금_행과_상태가_어긋나지_않는다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(100 + round, "Z" + round);
            Party q = party(400 + round, "Z" + round);
            mutual(p, q);
            long p1 = p.userId;
            long m = matchService.propose(q.userId, q.requestId, p.requestId).id();
            matchService.reserve(q.userId, m);
            List<Object> results = runConcurrently(shuffled(
                    () -> withdraw(p1),
                    () -> matchService.reserveAccept(p1, m),
                    () -> matchService.reserveWithdraw(q.userId, m)));
            assertThat(results).as("round " + round + " " + results).noneMatch(x -> x instanceof Exception && isUnexpectedForWithdraw((Exception) x));
            invariants(p1);
        }
    }

    @Test
    void 탈퇴와_교환_수락이_동시에_와도_새_티켓까지_정리되고_불변식이_유지된다() throws Exception {
        int completed = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(100 + round, "Z" + round);
            Party q = party(400 + round, "Z" + round);
            mutual(p, q);
            long p1 = p.userId;
            long m = matchService.propose(q.userId, q.requestId, p.requestId).id();
            matchService.reserve(q.userId, m);
            matchService.reserveAccept(p1, m);
            matchService.complete(q.userId, m);   // q 가 먼저 수락한 상태에서 p 의 수락과 p 의 탈퇴가 겹친다
            List<Object> results = runConcurrently(shuffled(
                    () -> withdraw(p1),
                    () -> matchService.complete(p1, m)));
            assertThat(results).as("round " + round + " " + results).noneMatch(x -> x instanceof Exception && isUnexpectedForWithdraw((Exception) x));
            invariants(p1);   // 교환이 먼저 끝났다면 p 가 받은 새 티켓도 내려가 있어야 한다
            String state = jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m);
            assertThat(state).isIn("COMPLETED", "CANCELED");
            if (state.equals("COMPLETED")) {
                completed++;
            }
            assertThat(count("SELECT COUNT(*) FROM exchange_history")).isEqualTo(2L * completed);   // 교환 한 건당 사용자별 이력 2행
        }
    }

    @Test
    void 탈퇴_두_번과_탈퇴와_소셜_연결_확정과_티켓_등록이_동시에_와도_일관되다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party a = party(100 + round, "Z" + round);
            long a1 = a.userId;
            List<Object> twice = runConcurrently(shuffled(
                    () -> withdraw(a1),
                    () -> withdraw(a1)));
            assertThat(twice.stream().filter(x -> !(x instanceof Exception)).count()).as("round " + round + " " + twice).isEqualTo(1);
            assertThat(twice.stream().filter(x -> x instanceof Exception))
                    .allMatch(x -> x instanceof InsufficientAuthenticationException);
            invariants(a1);

            Party b = party(400 + round, "Y" + round);
            long b1 = b.userId;
            String providerUserId = "kakao-" + round;
            String zone = "N" + round;
            List<Object> mixed = runConcurrently(shuffled(
                    () -> withdraw(b1),
                    () -> {
                        accountLinker.link(b1, SocialProvider.KAKAO, providerUserId);
                        return "linked";
                    },
                    () -> ticketService.create(b1, new TicketCreateRequest(session1, zone, "1", "7"))));
            assertThat(mixed).as("round " + round + " " + mixed).noneMatch(x -> x instanceof Exception && isUnexpectedForWithdraw((Exception) x));
            invariants(b1);   // 어느 순서든 연결·새 티켓이 남아 있으면 안 된다
        }
    }

    // ------------------------------------------------------------------ 도우미

    /** 탈퇴와 겹친 쪽에서 나올 수 있는 정상 거절(409/422/404/400/401)이 아닌 예외인가. 탈퇴 호출 자체의 실패는 항상 비정상이다. */
    private static boolean isUnexpectedForWithdraw(Exception e) {
        if (e instanceof com.matjari.exception.MatJariException || e instanceof InsufficientAuthenticationException
                || e instanceof com.matjari.service.social.SocialAuthException) {
            return false;
        }
        return true;
    }

    private void invariants(long withdrawnUser) {
        if (withdrawnUser > 0) {
            assertThat(count("SELECT COUNT(*) FROM users WHERE id = " + withdrawnUser + " AND withdrawn_at IS NOT NULL")).as("탈퇴 처리됨").isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, withdrawnUser))
                    .isEqualTo("withdrawn." + withdrawnUser + "@withdrawn.matjari.invalid");
            assertThat(count("SELECT COUNT(*) FROM ticket WHERE user_id = " + withdrawnUser + " AND status = 'ACTIVE'")).as("활성 티켓 0").isZero();
            assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE (user_a_id = " + withdrawnUser + " OR user_b_id = " + withdrawnUser
                    + ") AND status IN ('CHATTING','RESERVED')")).as("열린 매칭 0").isZero();
            assertThat(count("SELECT COUNT(*) FROM exchange_request r JOIN ticket t ON t.id = r.ticket_id WHERE t.user_id = " + withdrawnUser
                    + " AND r.status = 'OPEN'")).as("OPEN 요청 0").isZero();
            assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN ticket t ON t.id = l.ticket_id WHERE t.user_id = " + withdrawnUser)).as("잠금 0").isZero();
            assertThat(count("SELECT COUNT(*) FROM social_account WHERE user_id = " + withdrawnUser)).as("social_account 0").isZero();
            assertThat(count("SELECT COUNT(*) FROM notification WHERE user_id = " + withdrawnUser)).as("알림 0").isZero();
            assertThat(count("SELECT COUNT(*) FROM seat_claim WHERE claimant_id = " + withdrawnUser + " AND status = 'PENDING'")).as("PENDING 인증 요청 0").isZero();
        }
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock")).as("잠금 행 수 = 2 x RESERVED")
                .isEqualTo(2 * count("SELECT COUNT(*) FROM exchange_match WHERE status = 'RESERVED'"));
        assertThat(count("SELECT COUNT(*) FROM exchange_match WHERE status <> 'CHATTING' AND (reserve_proposed_by_id IS NOT NULL OR reserve_proposed_at IS NOT NULL)")).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN ticket t ON t.id = l.ticket_id WHERE t.status <> 'ACTIVE'")).isZero();
    }

    private Object withdraw(long userId) {
        withdrawService.withdraw(userId, new WithdrawRequest(PASSWORD, null));
        return "withdrawn";
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    @SafeVarargs
    private static List<Callable<Object>> shuffled(Callable<Object>... calls) {
        List<Callable<Object>> list = new ArrayList<>(List.of(calls));
        Collections.shuffle(list);
        return list;
    }

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
                if (r instanceof org.springframework.dao.DataAccessException) {
                    throw new AssertionError("교착/락 대기 초과/DB 예외가 났다: " + r);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Party(long userId, long ticketId, long requestId, String zone, String row, String col) {}

    private long user(String nickname, String role) {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, ?, ?)",
                "u" + seq + "@t.com", nickname, encodedPassword, role);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Party party(int n, String zone) {
        long userId = users.computeIfAbsent(n, k -> user("user" + k, "USER"));
        String col = String.valueOf(++colSeq);
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
}
