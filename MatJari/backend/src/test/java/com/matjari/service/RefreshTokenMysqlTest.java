package com.matjari.service;

import com.matjari.testsupport.RealMysqlSupport;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 리프레시 토큰 회전·재사용 탐지·폐기(V27)의 실제 MySQL 통합 테스트(서비스 + 필터 체인 + 쿠키 + 잠금).
 * MATJARI_IT_* 환경변수가 없으면 건너뛴다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(properties = {"matjari.email-verification.enabled=false"})
@AutoConfigureMockMvc
class RefreshTokenMysqlTest {

    private static final String PASSWORD = "password123";
    private static final String AJAX = "XMLHttpRequest";

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
    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private RefreshTokenService refreshTokenService;

    private String encodedPassword;
    private long seq;

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("refresh_token", "user_sanction", "admin_action_log", "social_account", "notification", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        encodedPassword = passwordEncoder.encode(PASSWORD);
        seq = 0;
    }

    private long user(String role) {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, ?, ?)",
                "rt" + seq + "@t.com", "user" + seq, encodedPassword, role);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String email(long id) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
    }

    /** 로그인해 mj_rt 쿠키 원문을 돌려준다. */
    private String login(long id) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email(id) + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        return r.getResponse().getCookie("mj_rt").getValue();
    }

    private MvcResult refresh(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", AJAX).cookie(new Cookie("mj_rt", token))).andReturn();
    }

    private List<Map<String, Object>> rows(long userId) {
        return jdbc.queryForList("SELECT * FROM refresh_token WHERE user_id = ? ORDER BY id", userId);
    }

    @Test
    void 로그인은_새_계열을_만들고_해시만_저장하며_쿠키_속성이_맞다() throws Exception {
        long u = user("USER");
        MvcResult r = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email(u) + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        String setCookie = r.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("mj_rt=").contains("Path=/api/auth", "Max-Age=1209600", "HttpOnly", "SameSite=Strict");
        String raw = r.getResponse().getCookie("mj_rt").getValue();
        assertThat(raw).matches("[A-Za-z0-9_-]{43}");
        List<Map<String, Object>> rows = rows(u);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("token_hash")).isEqualTo(RefreshTokenService.hash(raw)).isNotEqualTo(raw);
        assertThat(rows.get(0).get("revoked_at")).isNull();
    }

    @Test
    void 재발급은_새_토큰으로_회전하고_옛_토큰을_ROTATED로_폐기한다() throws Exception {
        long u = user("USER");
        String rt1 = login(u);

        MvcResult r = refresh(rt1);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String rt2 = r.getResponse().getCookie("mj_rt").getValue();
        assertThat(rt2).isNotEqualTo(rt1);
        assertThat(r.getResponse().getContentAsString()).contains("\"accessToken\"").contains("\"refreshToken\":null");

        List<Map<String, Object>> rows = rows(u);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("revoke_reason")).isEqualTo("ROTATED");
        assertThat(rows.get(0).get("replaced_by_id")).isEqualTo(rows.get(1).get("id"));
        assertThat(rows.get(1).get("revoked_at")).isNull();
        assertThat(rows.get(1).get("family_id")).isEqualTo(rows.get(0).get("family_id"));
        assertThat(rows.get(1).get("family_started_at")).as("계열 시작 시각은 회전해도 그대로").isEqualTo(rows.get(0).get("family_started_at"));
        // 새 토큰으로 계속 회전된다
        assertThat(refresh(rt2).getResponse().getStatus()).isEqualTo(200);
    }

    /** 회전된 행의 폐기 시각을 유예 시간(20초) 밖으로 민다(created_at·family_started_at 도 함께 앞당겨 CHECK 를 지킨다). */
    private void agePastGrace(long userId) {
        jdbc.update("UPDATE refresh_token SET family_started_at = DATE_SUB(NOW(6), INTERVAL 10 MINUTE), created_at = DATE_SUB(NOW(6), INTERVAL 10 MINUTE), "
                + "revoked_at = DATE_SUB(NOW(6), INTERVAL 5 MINUTE) WHERE user_id = ? AND revoke_reason = 'ROTATED'", userId);
    }

    @Test
    void 유예_시간_안의_회전된_토큰_재요청은_401만_주고_계열은_유지된다() throws Exception {
        long u = user("USER");
        String rt1 = login(u);
        String rt2 = refresh(rt1).getResponse().getCookie("mj_rt").getValue();

        assertThat(refresh(rt1).getResponse().getStatus()).as("유예 안 재요청").isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'REUSE'", Long.class, u)).isZero();
        assertThat(refresh(rt2).getResponse().getStatus()).as("새 토큰으로 재발급 성공").isEqualTo(200);
    }

    @Test
    void 세션_최대_수명을_넘으면_재발급_401이고_만료는_계열_시작_기준으로_잘린다() throws Exception {
        long u = user("USER");
        String rt = login(u);
        jdbc.update("UPDATE refresh_token SET family_started_at = DATE_SUB(NOW(6), INTERVAL 29 DAY), created_at = DATE_SUB(NOW(6), INTERVAL 29 DAY) WHERE user_id = ?", u);
        MvcResult r = refresh(rt);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String rt2 = r.getResponse().getCookie("mj_rt").getValue();
        Long daysLeft = jdbc.queryForObject("SELECT TIMESTAMPDIFF(HOUR, NOW(6), expires_at) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", Long.class, u);
        assertThat(daysLeft).as("14일이 아니라 계열 시작 + 30일까지(약 1일)").isBetween(20L, 25L);

        jdbc.update("UPDATE refresh_token SET family_started_at = DATE_SUB(NOW(6), INTERVAL 31 DAY), created_at = DATE_SUB(NOW(6), INTERVAL 31 DAY) WHERE user_id = ?", u);
        assertThat(refresh(rt2).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 이미_회전된_토큰의_재사용은_계열_전체를_REUSE로_폐기하고_401() throws Exception {
        long u = user("USER");
        String other = login(u);   // 다른 계열(다른 기기)은 영향을 받지 않는다
        String rt1 = login(u);
        String rt2 = refresh(rt1).getResponse().getCookie("mj_rt").getValue();
        agePastGrace(u);           // 유예 시간(20초) 밖의 재사용

        assertThat(refresh(rt1).getResponse().getStatus()).as("재사용").isEqualTo(401);
        assertThat(refresh(rt2).getResponse().getStatus()).as("계열의 최신 토큰도 폐기").isEqualTo(401);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'REUSE'", Long.class, u)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", Long.class, u))
                .as("다른 계열만 살아 있다").isEqualTo(1);
        assertThat(refresh(other).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 없는_토큰_쿠키_없음_만료된_토큰은_401() throws Exception {
        long u = user("USER");
        assertThat(refresh("not-a-token").getResponse().getStatus()).isEqualTo(401);
        assertThat(mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", AJAX)).andReturn().getResponse().getStatus()).isEqualTo(401);

        String rt = login(u);
        jdbc.update("UPDATE refresh_token SET family_started_at = DATE_SUB(NOW(6), INTERVAL 20 DAY), created_at = DATE_SUB(NOW(6), INTERVAL 20 DAY), expires_at = DATE_SUB(NOW(6), INTERVAL 1 DAY) WHERE user_id = ?", u);
        assertThat(refresh(rt).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 로그아웃은_계열을_LOGOUT으로_폐기하고_쿠키를_지우며_이후_재발급은_401이고_토큰이_없어도_204() throws Exception {
        long u = user("USER");
        String rt1 = login(u);
        String rt2 = refresh(rt1).getResponse().getCookie("mj_rt").getValue();

        MvcResult out = mockMvc.perform(post("/api/auth/logout").header("X-Requested-With", AJAX).cookie(new Cookie("mj_rt", rt2)))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(out.getResponse().getHeader("Set-Cookie")).contains("mj_rt=;", "Max-Age=0");
        assertThat(refresh(rt2).getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'LOGOUT'", Long.class, u)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", Long.class, u)).isZero();

        mockMvc.perform(post("/api/auth/logout").header("X-Requested-With", AJAX)).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout").header("X-Requested-With", AJAX).cookie(new Cookie("mj_rt", "unknown"))).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isForbidden());
    }

    @Test
    void 탈퇴하면_모든_토큰이_WITHDRAW로_폐기되고_재발급은_401() throws Exception {
        long u = user("USER");
        String rt1 = login(u);
        String rt2 = login(u);
        String loginBody = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email(u) + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(loginBody);
        assertThat(m.find()).isTrue();
        String access = m.group(1);

        mockMvc.perform(delete("/api/users/me").header("Authorization", "Bearer " + access).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", Long.class, u)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'WITHDRAW'", Long.class, u)).isEqualTo(3);
        assertThat(refresh(rt1).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(rt2).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 관리자_강제_탈퇴도_모든_토큰을_WITHDRAW로_폐기한다() throws Exception {
        long admin = user("ADMIN");
        long u = user("USER");
        String rt = login(u);

        adminUserService.forceWithdraw(admin, u, new AdminUserService.ForceWithdrawRequest("강제 탈퇴", "테스트"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'WITHDRAW'", Long.class, u)).isEqualTo(1);
        assertThat(refresh(rt).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 관리자_이용_정지는_모든_토큰을_ADMIN으로_폐기하고_재발급은_401() throws Exception {
        long admin = user("ADMIN");
        long u = user("USER");
        String rt1 = login(u);
        String rt2 = login(u);

        adminUserService.suspend(admin, u, new AdminUserService.SuspendRequest(7, "테스트 정지"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'ADMIN'", Long.class, u)).isEqualTo(2);
        assertThat(refresh(rt1).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(rt2).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void 폐기되지_않은_토큰이라도_정지_중이면_재발급은_403_ACCOUNT_SUSPENDED이고_토큰은_그대로() throws Exception {
        long admin = user("ADMIN");
        long u = user("USER");
        String rt = login(u);
        jdbc.update("INSERT INTO user_sanction (user_id, type, note, starts_at, ends_at, created_by_id, created_at) "
                + "VALUES (?, 'SUSPEND', '직접 삽입', NOW(6), NULL, ?, NOW(6))", u, admin);

        MvcResult r = refresh(rt);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(r.getResponse().getContentAsString()).contains("ACCOUNT_SUSPENDED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", Long.class, u)).isEqualTo(1);
    }

    @Test
    void 같은_토큰으로_동시에_재발급하면_한쪽만_성공하고_다른쪽은_재사용으로_401이며_교착이_없다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                long u = user("USER");
                String rt = login(u);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Integer>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    futures.add(pool.submit(() -> {
                        go.await();
                        return refresh(rt).getResponse().getStatus();
                    }));
                }
                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                for (Future<Integer> f : futures) {
                    statuses.add(f.get(30, TimeUnit.SECONDS));
                }
                assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 401);
                // 진 쪽은 유예 시간 안의 회전된 토큰이라 401 만 받고 계열은 유지된다 — 이긴 쪽의 새 토큰 하나만 살아 있다
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL", Long.class, u))
                        .as("round " + round).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refresh_token WHERE user_id = ? AND revoke_reason = 'REUSE'", Long.class, u))
                        .as("round " + round).isZero();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 정리는_만료된_행만_지우고_짧은_간격으로_다시_돌지_않는다() throws Exception {
        long u = user("USER");
        String rt = login(u);
        for (int i = 0; i < 3; i++) {
            jdbc.update("INSERT INTO refresh_token (user_id, token_hash, family_id, family_started_at, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                    u, String.format("%064x", i + 1), "00000000-0000-4000-8000-00000000000" + i,
                    LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(15));
        }
        ((java.util.concurrent.atomic.AtomicReference<?>) ReflectionTestUtils.getField(refreshTokenService, "lastCleanup")).set(null);
        refreshTokenService.maybeCleanup();

        assertThat(rows(u)).as("만료 3개는 지워지고 살아 있는 1개만 남는다").hasSize(1);
        jdbc.update("INSERT INTO refresh_token (user_id, token_hash, family_id, family_started_at, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                u, String.format("%064x", 99), "00000000-0000-4000-8000-000000000099", LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(30), LocalDateTime.now().minusDays(15));
        refreshTokenService.maybeCleanup();
        assertThat(rows(u)).as("1시간 안에는 다시 정리하지 않는다").hasSize(2);
        assertThat(refresh(rt).getResponse().getStatus()).isEqualTo(200);
    }
}
