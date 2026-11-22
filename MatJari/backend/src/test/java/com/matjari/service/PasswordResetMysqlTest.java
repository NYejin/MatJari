package com.matjari.service;

import com.matjari.service.mail.EmailSender;
import com.matjari.testsupport.RealMysqlSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 비밀번호 재설정(메일 링크)·변경의 실제 MySQL 통합 테스트(V28): 요청 → 메일 캡처 → 확정, 1회용, 대체, 만료, 리프레시 토큰 폐기,
 * 변경 시 현재 계열 유지, 탈퇴 시 삭제, 동시 확정. MATJARI_IT_* 환경변수가 없으면 건너뛴다.
 * 메일 발송기는 목으로 바꿔 링크를 가로챈다(메일은 커밋 뒤 별도 스레드에서 나가므로 timeout 으로 기다린다).
 * 속도 제한은 메모리라 클래스 전체에서 공유되므로 테스트마다 다른 이메일·접속자 IP(X-Real-IP)를 쓴다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(properties = {"matjari.email-verification.enabled=false", "ticket.max-active-per-user=100000"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetMysqlTest {

    private static final String PASSWORD = "password123";
    private static final String NEW_PASSWORD = "brandNewPw456";
    private static final String AJAX = "XMLHttpRequest";
    private static final Pattern LINK = Pattern.compile("/reset-password#token=([A-Za-z0-9_-]{43})");
    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final AtomicInteger IPS = new AtomicInteger();

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
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @MockBean
    private EmailSender emailSender;

    private String encodedPassword;

    /** 앞 테스트가 남긴 비동기 메일 발송이 이번 테스트의 목 호출 기록에 섞이지 않게 기다린다. */
    private void awaitMailIdle() throws Exception {
        java.util.concurrent.ThreadPoolExecutor ex = (java.util.concurrent.ThreadPoolExecutor)
                org.springframework.test.util.ReflectionTestUtils.getField(passwordResetService, "mailExecutor");
        long deadline = System.currentTimeMillis() + 10_000;
        while ((ex.getActiveCount() > 0 || !ex.getQueue().isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @BeforeEach
    void cleanTables() throws Exception {
        awaitMailIdle();
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("password_reset_token", "refresh_token", "user_sanction", "admin_action_log", "social_account",
                "notification", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        reset(emailSender);
        encodedPassword = passwordEncoder.encode(PASSWORD);
    }

    private long user() {
        int n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, ?, 'USER')",
                "pw" + n + "@t.com", "user" + n, encodedPassword);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String email(long id) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
    }

    private String freshIp() {
        return "198.51.100." + (IPS.incrementAndGet() % 250 + 1) + "";
    }

    private MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder b) {
        return b.header("X-Real-IP", freshIp());
    }

    private int requestReset(String email) throws Exception {
        return mockMvc.perform(withIp(post("/api/auth/password-reset/request")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}")).andReturn().getResponse().getStatus();
    }

    private MvcResult confirmRaw(String token, String newPassword) throws Exception {
        return mockMvc.perform(withIp(post("/api/auth/password-reset/confirm")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}")).andReturn();
    }

    /** n 번째로 보낸 메일(1부터)에서 링크 토큰 원문을 읽는다. */
    private String tokenFromMail(int n) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailSender, timeout(5000).times(n)).send(anyString(), anyString(), text.capture());
        Matcher m = LINK.matcher(text.getAllValues().get(n - 1));
        assertThat(m.find()).as("메일 본문에 링크가 있어야 한다").isTrue();
        return m.group(1);
    }

    private int loginStatus(String email, String password) throws Exception {
        return mockMvc.perform(withIp(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn().getResponse().getStatus();
    }

    /** 로그인해 {액세스 토큰, 리프레시 쿠키 원문}을 돌려준다. */
    private String[] login(long id) throws Exception {
        MvcResult r = mockMvc.perform(withIp(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email(id) + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(body);
        assertThat(m.find()).isTrue();
        return new String[]{m.group(1), r.getResponse().getCookie("mj_rt").getValue()};
    }

    private int refreshStatus(String cookie) throws Exception {
        return mockMvc.perform(withIp(post("/api/auth/refresh")).header("X-Requested-With", AJAX).cookie(new Cookie("mj_rt", cookie)))
                .andReturn().getResponse().getStatus();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ------------------------------------------------------------------ 요청·확정

    @Test
    void 요청하면_메일의_링크로_확정할_수_있고_해시만_저장되며_새_비밀번호로_로그인된다() throws Exception {
        long u = user();
        assertThat(requestReset(email(u).toUpperCase())).isEqualTo(204);
        String token = tokenFromMail(1);
        verify(emailSender).send(eq(email(u)), eq("[맞자리] 비밀번호 재설정"), org.mockito.ArgumentMatchers.contains("http://localhost:5173/reset-password#token="));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM password_reset_token WHERE user_id = ?", u);
        assertThat(row.get("token_hash")).isEqualTo(RefreshTokenService.hash(token)).isNotEqualTo(token);
        assertThat(row.get("used_at")).isNull();
        assertThat(row.get("invalidated_at")).isNull();
        assertThat(row.get("request_ip").toString()).startsWith("198.51.100.");
        long minutes = jdbc.queryForObject("SELECT TIMESTAMPDIFF(MINUTE, created_at, expires_at) FROM password_reset_token", Long.class);
        assertThat(minutes).isBetween(29L, 30L);

        assertThat(confirmRaw(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT used_at FROM password_reset_token WHERE user_id = ?", Object.class, u)).isNotNull();
        assertThat(loginStatus(email(u), NEW_PASSWORD)).isEqualTo(200);
        assertThat(loginStatus(email(u), PASSWORD)).isEqualTo(400);
    }

    @Test
    void 같은_링크를_두_번째_쓰면_400_INVALID_RESET_TOKEN() throws Exception {
        long u = user();
        requestReset(email(u));
        String token = tokenFromMail(1);
        assertThat(confirmRaw(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);

        MvcResult second = confirmRaw(token, "anotherPw789");
        assertThat(second.getResponse().getStatus()).isEqualTo(400);
        assertThat(second.getResponse().getContentAsString()).contains("\"code\":\"INVALID_RESET_TOKEN\"");
        assertThat(loginStatus(email(u), NEW_PASSWORD)).isEqualTo(200);
        assertThat(loginStatus(email(u), "anotherPw789")).isEqualTo(400);
    }

    @Test
    void 없는_링크_형식이_이상한_링크는_같은_400이고_새_비밀번호_규칙_위반은_링크를_쓰지_않는다() throws Exception {
        long u = user();
        requestReset(email(u));
        String token = tokenFromMail(1);
        MvcResult none = confirmRaw("A".repeat(43), NEW_PASSWORD);
        assertThat(none.getResponse().getStatus()).isEqualTo(400);
        assertThat(none.getResponse().getContentAsString()).contains("INVALID_RESET_TOKEN");
        assertThat(confirmRaw("x".repeat(250), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
        // 비밀번호가 짧으면 400 newPassword 이고 링크는 그대로 살아 있다
        MvcResult shortPw = confirmRaw(token, "short");
        assertThat(shortPw.getResponse().getStatus()).isEqualTo(400);
        assertThat(shortPw.getResponse().getContentAsString()).contains("newPassword");
        // 72바이트 초과(한글 25자 = 75바이트)는 400 newPassword
        MvcResult longPw = confirmRaw(token, "가".repeat(25));
        assertThat(longPw.getResponse().getStatus()).isEqualTo(400);
        assertThat(longPw.getResponse().getContentAsString()).contains("newPassword");
        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE used_at IS NULL AND invalidated_at IS NULL")).isEqualTo(1);
        assertThat(confirmRaw(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void 없는_이메일_소셜_전용_탈퇴_계정도_204이고_메일도_행도_없다() throws Exception {
        long social = user();
        jdbc.update("UPDATE users SET password_login_enabled = 0 WHERE id = ?", social);
        long withdrawn = user();
        jdbc.update("UPDATE users SET withdrawn_at = NOW(6) WHERE id = ?", withdrawn);

        assertThat(requestReset("nobody-" + SEQ.incrementAndGet() + "@t.com")).isEqualTo(204);
        assertThat(requestReset(email(social))).isEqualTo(204);
        assertThat(requestReset(email(withdrawn))).isEqualTo(204);

        verify(emailSender, after(700).never()).send(anyString(), anyString(), anyString());
        assertThat(count("SELECT COUNT(*) FROM password_reset_token")).isZero();
    }

    @Test
    void 정지_계정에도_보내고_재설정해도_로그인은_403_그대로() throws Exception {
        long admin = user();
        long u = user();
        jdbc.update("INSERT INTO user_sanction (user_id, type, note, starts_at, ends_at, created_by_id, created_at) "
                + "VALUES (?, 'SUSPEND', '직접 삽입', NOW(6), NULL, ?, NOW(6))", u, admin);
        assertThat(requestReset(email(u))).isEqualTo(204);
        String token = tokenFromMail(1);
        assertThat(confirmRaw(token, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
        assertThat(loginStatus(email(u), NEW_PASSWORD)).isEqualTo(403);
    }

    @Test
    void 새_요청은_이전_링크를_무효화하고_사용자당_살아_있는_링크는_1개다() throws Exception {
        long u = user();
        requestReset(email(u));
        String first = tokenFromMail(1);
        requestReset(email(u));
        String second = tokenFromMail(2);
        assertThat(second).isNotEqualTo(first);

        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", u)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ? AND invalidated_at IS NOT NULL", u)).isEqualTo(1);
        assertThat(confirmRaw(first, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(400);
        assertThat(confirmRaw(second, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void 만료된_링크는_400() throws Exception {
        long u = user();
        requestReset(email(u));
        String token = tokenFromMail(1);
        jdbc.update("UPDATE password_reset_token SET created_at = DATE_SUB(NOW(6), INTERVAL 2 HOUR), "
                + "expires_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE user_id = ?", u);
        MvcResult r = confirmRaw(token, NEW_PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("INVALID_RESET_TOKEN");
        assertThat(loginStatus(email(u), PASSWORD)).isEqualTo(200);
        // 만료된 링크는 새 요청 때 무효화되고(살아 있는 것으로 센다) 새 링크가 발급된다
        requestReset(email(u));
        assertThat(confirmRaw(tokenFromMail(2), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void 확정하면_그_사용자의_리프레시_토큰이_모두_PASSWORD_RESET으로_폐기된다() throws Exception {
        long u = user();
        long other = user();
        String[] a = login(u);
        String[] b = login(u);
        String[] o = login(other);
        requestReset(email(u));
        assertThat(confirmRaw(tokenFromMail(1), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);

        assertThat(count("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'PASSWORD_RESET'", u)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", u)).isZero();
        assertThat(refreshStatus(a[1])).isEqualTo(401);
        assertThat(refreshStatus(b[1])).isEqualTo(401);
        assertThat(refreshStatus(o[1])).as("다른 사용자는 그대로").isEqualTo(200);
    }

    @Test
    void 확정은_로그인_실패_잠금도_푼다() throws Exception {
        long u = user();
        for (int i = 0; i < 5; i++) {
            loginStatus(email(u), "wrong-password");
        }
        assertThat(loginStatus(email(u), PASSWORD)).as("잠김").isEqualTo(429);
        requestReset(email(u));
        assertThat(confirmRaw(tokenFromMail(1), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);
        assertThat(loginStatus(email(u), NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    void 요청_속도_제한은_IP당_5회_초과가_429이고_Retry_After가_있다() throws Exception {
        String ip = "203.0.113.77";
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/password-reset/request").header("X-Real-IP", ip)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody" + SEQ.incrementAndGet() + "@t.com\"}"))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(post("/api/auth/password-reset/request").header("X-Real-IP", ip)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"nobody" + SEQ.incrementAndGet() + "@t.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PASSWORD_RESET_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void 이메일당_1시간_3회_초과도_429() throws Exception {
        long u = user();
        for (int i = 0; i < 3; i++) {
            assertThat(requestReset(email(u))).isEqualTo(204);
        }
        assertThat(requestReset(email(u))).isEqualTo(429);
    }

    @Test
    void 동시에_같은_링크를_확정하면_하나만_성공한다() throws Exception {
        long u = user();
        requestReset(email(u));
        String token = tokenFromMail(1);
        int n = 4;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                String pw = "concurrentPw" + i + "x";
                futures.add(pool.submit(() -> {
                    go.await();
                    return confirmRaw(token, pw).getResponse().getStatus();
                }));
            }
            go.countDown();
            int ok = 0;
            int bad = 0;
            for (Future<Integer> f : futures) {
                int s = f.get(30, TimeUnit.SECONDS);
                if (s == 204) {
                    ok++;
                } else if (s == 400) {
                    bad++;
                }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(bad).isEqualTo(n - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE used_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void 동시에_새_요청을_보내도_살아_있는_링크는_1개다() throws Exception {
        long u = user();
        int n = 3;   // 이메일당 3회 한도 안
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return requestReset(email(u));
                }));
            }
            go.countDown();
            for (Future<Integer> f : futures) {
                assertThat(f.get(30, TimeUnit.SECONDS)).isEqualTo(204);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", u)).isEqualTo(n);
        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ? AND used_at IS NULL AND invalidated_at IS NULL", u)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 변경(로그인 상태)

    private MvcResult change(String path, String access, String cookie, String current, String next) throws Exception {
        MockHttpServletRequestBuilder b = withIp(patch(path)).header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}");
        if (cookie != null) {
            b = b.cookie(new Cookie("mj_rt", cookie));
        }
        return mockMvc.perform(b).andReturn();
    }

    @Test
    void 변경하면_현재_쿠키_계열만_남고_다른_기기_계열은_폐기된다() throws Exception {
        long u = user();
        String[] a = login(u);   // 현재 기기
        String[] b = login(u);   // 다른 기기
        String[] c = login(u);   // 또 다른 기기

        assertThat(change("/api/auth/password", a[0], a[1], PASSWORD, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);

        assertThat(count("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'PASSWORD_RESET'", u)).isEqualTo(2);
        assertThat(refreshStatus(a[1])).as("현재 기기는 유지").isEqualTo(200);
        assertThat(refreshStatus(b[1])).isEqualTo(401);
        assertThat(refreshStatus(c[1])).isEqualTo(401);
        assertThat(loginStatus(email(u), NEW_PASSWORD)).isEqualTo(200);
        assertThat(loginStatus(email(u), PASSWORD)).isEqualTo(400);
    }

    @Test
    void users_경로는_쿠키를_몰라_모든_계열을_폐기한다() throws Exception {
        long u = user();
        String[] a = login(u);
        String[] b = login(u);
        assertThat(change("/api/users/me/password", a[0], null, PASSWORD, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(refreshStatus(a[1])).isEqualTo(401);
        assertThat(refreshStatus(b[1])).isEqualTo(401);
    }

    @Test
    void 다른_사용자의_쿠키를_보내도_내_계열은_모두_폐기된다() throws Exception {
        long u = user();
        long other = user();
        String[] a = login(u);
        String[] o = login(other);
        assertThat(change("/api/auth/password", a[0], o[1], PASSWORD, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(refreshStatus(a[1])).isEqualTo(401);
        assertThat(refreshStatus(o[1])).as("남의 계열은 건드리지 않는다").isEqualTo(200);
    }

    @Test
    void 변경은_이미_보낸_재설정_링크를_무효화한다() throws Exception {
        long u = user();
        String[] a = login(u);
        requestReset(email(u));
        String token = tokenFromMail(1);
        assertThat(change("/api/users/me/password", a[0], null, PASSWORD, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);
        assertThat(confirmRaw(token, "linkPassword99").getResponse().getStatus()).isEqualTo(400);
        assertThat(loginStatus(email(u), NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    void 현재_비밀번호가_틀리면_400_currentPassword이고_5번_틀리면_잠겨_429_LOGIN_RATE_LIMITED() throws Exception {
        long u = user();
        String[] a = login(u);
        for (int i = 0; i < 5; i++) {
            MvcResult r = change("/api/users/me/password", a[0], null, "wrong-password", NEW_PASSWORD);
            assertThat(r.getResponse().getStatus()).isEqualTo(400);
            assertThat(r.getResponse().getContentAsString()).contains("\"currentPassword\"");
        }
        MvcResult locked = change("/api/users/me/password", a[0], null, PASSWORD, NEW_PASSWORD);
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(locked.getResponse().getContentAsString()).contains("LOGIN_RATE_LIMITED");
        assertThat(loginStatus(email(u), PASSWORD)).as("로그인도 같은 계정 잠금").isEqualTo(429);
        assertThat(refreshStatus(a[1])).as("실패한 변경은 아무것도 폐기하지 않는다").isEqualTo(200);
    }

    @Test
    void 새_비밀번호가_현재와_같으면_400_newPassword_소셜_전용은_409() throws Exception {
        long u = user();
        String[] a = login(u);
        MvcResult same = change("/api/users/me/password", a[0], null, PASSWORD, PASSWORD);
        assertThat(same.getResponse().getStatus()).isEqualTo(400);
        assertThat(same.getResponse().getContentAsString()).contains("\"newPassword\"");
        assertThat(change("/api/users/me/password", a[0], null, PASSWORD, "short").getResponse().getStatus()).isEqualTo(400);
        assertThat(refreshStatus(a[1])).isEqualTo(200);

        jdbc.update("UPDATE users SET password_login_enabled = 0 WHERE id = ?", u);
        MvcResult social = change("/api/users/me/password", a[0], null, PASSWORD, NEW_PASSWORD);
        assertThat(social.getResponse().getStatus()).isEqualTo(409);
        assertThat(social.getResponse().getContentAsString()).contains("PASSWORD_LOGIN_DISABLED");
    }

    @Test
    void 변경_경로는_로그인이_필요하다() throws Exception {
        mockMvc.perform(patch("/api/users/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ 탈퇴

    @Test
    void 탈퇴하면_그_사용자의_재설정_링크_행이_모두_삭제된다() throws Exception {
        long u = user();
        long other = user();
        String[] a = login(u);
        requestReset(email(u));
        requestReset(email(other));
        verify(emailSender, timeout(5000).times(2)).send(anyString(), anyString(), anyString());
        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", u)).isEqualTo(1);

        mockMvc.perform(withIp(delete("/api/users/me")).header("Authorization", "Bearer " + a[0])
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", u)).isZero();
        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", other)).as("다른 사용자 행은 그대로").isEqualTo(1);
    }

    @Test
    void 관리자_강제_탈퇴도_재설정_링크_행을_삭제한다() throws Exception {
        long admin = user();
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin);
        long u = user();
        requestReset(email(u));
        tokenFromMail(1);
        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", u)).isEqualTo(1);

        forceWithdraw(admin, u);

        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", u)).isZero();
    }

    @Autowired
    private AdminUserService adminUserService;

    private void forceWithdraw(long admin, long target) {
        adminUserService.forceWithdraw(admin, target, new AdminUserService.ForceWithdrawRequest("강제 탈퇴", "테스트"));
    }

    // ------------------------------------------------------------------ 정리

    @Test
    void 요청_때_마지막_정리_1시간_뒤_만료_행을_지운다() throws Exception {
        long u = user();
        long old = user();
        jdbc.update("INSERT INTO password_reset_token (user_id, token_hash, created_at, expires_at, used_at) "
                        + "VALUES (?, ?, DATE_SUB(NOW(6), INTERVAL 3 DAY), DATE_SUB(NOW(6), INTERVAL 2 DAY), DATE_SUB(NOW(6), INTERVAL 2 DAY))",
                old, "a".repeat(64));
        // 서비스는 처음 요청에서 한 번 정리한다(마지막 정리 시각이 없으므로)
        org.springframework.test.util.ReflectionTestUtils.setField(passwordResetService, "lastCleanup",
                new java.util.concurrent.atomic.AtomicReference<java.time.LocalDateTime>());
        requestReset(email(u));
        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", old)).isZero();
        assertThat(count("SELECT COUNT(*) FROM password_reset_token WHERE user_id = ?", u)).isEqualTo(1);
    }

    @Autowired
    private PasswordResetService passwordResetService;

    // ------------------------------------------------------------------ 옛 액세스 토큰 거부 (credentials_changed_at)

    private int meStatus(String access) throws Exception {
        return mockMvc.perform(withIp(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/users/me"))
                .header("Authorization", "Bearer " + access)).andReturn().getResponse().getStatus();
    }

    /** 토큰 iat(초)와 변경 시각(초)이 같은 초가 되지 않도록 다음 초까지 기다린다. */
    private void nextSecond() throws Exception {
        Thread.sleep(1100);
    }

    @Test
    void 재설정_확정_뒤_이전에_받은_액세스_토큰은_401이고_새로_로그인한_토큰은_200() throws Exception {
        long u = user();
        String[] old = login(u);
        assertThat(meStatus(old[0])).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT credentials_changed_at FROM users WHERE id = ?", Object.class, u)).isNull();
        nextSecond();
        requestReset(email(u));
        assertThat(confirmRaw(tokenFromMail(1), NEW_PASSWORD).getResponse().getStatus()).isEqualTo(204);

        assertThat(jdbc.queryForObject("SELECT credentials_changed_at FROM users WHERE id = ?", Object.class, u)).isNotNull();
        assertThat(meStatus(old[0])).isEqualTo(401);
        MvcResult fresh = mockMvc.perform(withIp(post("/api/auth/login")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email(u) + "\",\"password\":\"" + NEW_PASSWORD + "\"}")).andReturn();
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(fresh.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();
        assertThat(meStatus(m.group(1))).isEqualTo(200);
    }

    @Test
    void 변경_뒤_이전_액세스_토큰은_401이고_응답으로_받은_새_토큰은_200이며_쿠키로_재발급도_된다() throws Exception {
        long u = user();
        long other = user();
        String[] a = login(u);
        String[] o = login(other);
        nextSecond();
        MvcResult r = change("/api/auth/password", a[0], a[1], PASSWORD, NEW_PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(r.getResponse().getContentAsString());
        assertThat(m.find()).isTrue();

        assertThat(meStatus(a[0])).as("옛 토큰").isEqualTo(401);
        assertThat(meStatus(m.group(1))).as("응답으로 받은 새 토큰").isEqualTo(200);
        assertThat(refreshStatus(a[1])).as("현재 기기는 쿠키로 재발급").isEqualTo(200);
        assertThat(meStatus(o[0])).as("다른 사용자는 영향 없음").isEqualTo(200);
    }

    @Test
    void 변경_때_쿠키_토큰이_방금_회전돼_유예_안이면_그_계열을_유지한다() throws Exception {
        long u = user();
        String[] a = login(u);
        String[] other = login(u);
        MvcResult rotated = mockMvc.perform(withIp(post("/api/auth/refresh")).header("X-Requested-With", AJAX)
                .cookie(new Cookie("mj_rt", a[1]))).andReturn();
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String newCookie = rotated.getResponse().getCookie("mj_rt").getValue();

        // 브라우저가 아직 옛 쿠키(a[1], ROTATED)를 들고 변경을 요청한 경우
        assertThat(change("/api/auth/password", a[0], a[1], PASSWORD, NEW_PASSWORD).getResponse().getStatus()).isEqualTo(200);

        assertThat(refreshStatus(newCookie)).as("현재 기기 계열 유지").isEqualTo(200);
        assertThat(refreshStatus(other[1])).as("다른 기기는 폐기").isEqualTo(401);
    }
}
