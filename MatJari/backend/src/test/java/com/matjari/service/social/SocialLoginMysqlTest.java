package com.matjari.service.social;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.matjari.domain.SocialProvider;
import com.matjari.dto.request.LoginRequest;
import com.matjari.dto.response.TokenResponse;
import com.matjari.exception.AccountSuspendedException;
import com.matjari.exception.MatJariException;
import com.matjari.security.JwtTokenProvider;
import com.matjari.service.AuthService;
import com.matjari.service.social.SocialStubServer.Reply;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 소셜 로그인 실제 MySQL 통합 테스트(제공자는 JDK HttpServer 스텁). MATJARI_IT_* 환경변수가 있을 때만 실행한다.
 * authorize -> callback -> exchange 전체 흐름, 가입 규칙, 정지, 동시 첫 로그인 경합, 로그 비노출을 검증한다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SocialLoginMysqlTest {

    private static final SocialStubServer STUB = new SocialStubServer();
    private static final String CLIENT_SECRET = "google-secret-SHOULD-NOT-LEAK";

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("matjari.social.google.client-id", () -> "g-client");
        registry.add("matjari.social.google.client-secret", () -> CLIENT_SECRET);
        registry.add("matjari.social.kakao.client-id", () -> "k-client");
        registry.add("matjari.social.naver.client-id", () -> "n-client");
        for (String p : List.of("google", "kakao", "naver")) {
            registry.add("matjari.social." + p + ".authorize-uri", () -> STUB.baseUrl() + "/authorize");
            registry.add("matjari.social." + p + ".token-uri", () -> STUB.baseUrl() + "/token");
            registry.add("matjari.social." + p + ".userinfo-uri", () -> STUB.baseUrl() + "/userinfo");
        }
        registry.add("matjari.social.ip-per-minute", () -> "100000");
        registry.add("matjari.social.read-timeout-ms", () -> "3000");
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.cleanDatabase();
        registry.add("spring.datasource.url", RealMysqlSupport::url);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private SocialAuthService socialAuthService;
    @Autowired
    private SocialLoginService loginService;
    @Autowired
    private AuthService authService;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private SocialProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    private long seq;

    @BeforeEach
    void reset() {
        STUB.reset();
        jdbc.update("DELETE FROM user_sanction");
        jdbc.update("DELETE FROM social_account");
        jdbc.update("DELETE FROM users");
        STUB.on("/token", Reply.json("{\"access_token\":\"AT-1\"}"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private static String param(String location, String name) {
        for (String pair : URI.create(location).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) {
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /** authorize 로 state 쿠키를 받고 callback 까지 진행한 결과 Location. */
    private String callback(SocialProvider provider, String userinfoJson) {
        STUB.on("/userinfo", Reply.json(userinfoJson));
        SocialAuthService.AuthorizeResult a = socialAuthService.authorize(provider.getPath(), "10.0.0." + (seq++ % 200));
        String cookie = a.setCookie().substring("ss_oauth=".length(), a.setCookie().indexOf(';'));
        return socialAuthService.callback(provider.getPath(), "provider-code", param(a.location(), "state"), null, cookie).location();
    }

    private static String loginCode(String location) {
        assertThat(location).contains("/auth/callback#code=");
        return location.substring(location.indexOf("#code=") + 6);
    }

    private long insertUser(String email) {
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, 'x', 'USER')", email, "기존사용자");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    // ------------------------------------------------------------------

    @Test
    void 신규_가입_이메일_있음_전체_흐름과_토큰() {
        String location = callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-100", "New@Example.com", true, "새 사용자"));
        TokenResponse tokens = socialAuthService.exchange(loginCode(location), "10.9.9.9");

        assertThat(count("users")).isEqualTo(1);
        assertThat(count("social_account")).isEqualTo(1);
        Map<String, Object> user = jdbc.queryForMap("SELECT id, email, nickname, role FROM users");
        assertThat(user.get("email")).isEqualTo("new@example.com");
        assertThat(user.get("nickname")).isEqualTo("새 사용자");
        assertThat(user.get("role")).isEqualTo("USER");
        Map<String, Object> link = jdbc.queryForMap("SELECT user_id, provider, provider_user_id FROM social_account");
        assertThat(link.get("provider")).isEqualTo("GOOGLE");
        assertThat(link.get("provider_user_id")).isEqualTo("sub-100");
        assertThat(((Number) link.get("user_id")).longValue()).isEqualTo(((Number) user.get("id")).longValue());
        assertThat(jwtTokenProvider.validateToken(tokens.accessToken())).isTrue();
        assertThat(jwtTokenProvider.getUserId(tokens.accessToken())).isEqualTo(((Number) user.get("id")).longValue());
        // 스텁 token 요청에 PKCE verifier 와 client_secret 이 실렸다
        String body = STUB.received().stream().filter(r -> r.path().equals("/token")).findFirst().orElseThrow().body();
        assertThat(body).contains("code_verifier=").contains("client_secret=");
    }

    @Test
    void 신규_가입_이메일_없음_또는_미검증이면_자리표시_이메일() {
        callback(SocialProvider.KAKAO, SocialStubServer.userinfo(SocialProvider.KAKAO, "777", null, false, "카카오닉"));
        callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-u", "unverified@example.com", false, "구글닉"));
        List<String> emails = jdbc.queryForList("SELECT email FROM users ORDER BY id", String.class);
        assertThat(emails).hasSize(2);
        assertThat(emails.get(0)).matches("kakao\\.[0-9a-f]{16}@social\\.matjari\\.invalid");
        assertThat(emails.get(1)).matches("google\\.[0-9a-f]{16}@social\\.matjari\\.invalid");
        assertThat(count("social_account")).isEqualTo(2);
    }

    @Test
    void 재로그인은_같은_사용자이고_이메일이_바뀌어도_새_계정을_만들지_않는다() {
        callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-1", "old@example.com", true, "닉네임이"));
        long id = jdbc.queryForObject("SELECT id FROM users", Long.class);
        String location = callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-1", "changed@example.com", true, "다른닉"));
        TokenResponse tokens = socialAuthService.exchange(loginCode(location), "10.9.9.9");
        assertThat(count("users")).isEqualTo(1);
        assertThat(count("social_account")).isEqualTo(1);
        assertThat(jwtTokenProvider.getUserId(tokens.accessToken())).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT email FROM users", String.class)).isEqualTo("old@example.com");
    }

    @Test
    void 같은_이메일의_기존_계정이_있으면_EMAIL_EXISTS이고_행이_늘지_않는다() {
        insertUser("taken@example.com");
        long users = count("users");
        String location = callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-t", "Taken@Example.com", true, "닉네임이"));
        assertThat(location).isEqualTo("http://localhost:5173/login?socialError=EMAIL_EXISTS");
        assertThat(count("users")).isEqualTo(users);
        assertThat(count("social_account")).isZero();
    }

    @Test
    void 소셜_가입_계정은_비밀번호_로그인이_불가능하다() {
        callback(SocialProvider.KAKAO, SocialStubServer.userinfo(SocialProvider.KAKAO, "888", null, false, "카카오닉"));
        callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-pw", "pw@example.com", true, "비번계정"));
        for (String email : jdbc.queryForList("SELECT email FROM users ORDER BY id", String.class)) {
            for (String guess : List.of("", "password1", "12345678", "kakao.888", email)) {
                assertThatThrownBy(() -> authService.login(new LoginRequest(email, guess))).isInstanceOf(MatJariException.class);
            }
        }
        assertThat(jdbc.queryForObject("SELECT password FROM users LIMIT 1", String.class)).startsWith("$2");
    }

    @Test
    void 정지_계정은_콜백에서_차단되고_코드가_없다() {
        callback(SocialProvider.NAVER, SocialStubServer.userinfo(SocialProvider.NAVER, "n-1", "n1@example.com", true, "네이버닉"));
        long userId = jdbc.queryForObject("SELECT id FROM users", Long.class);
        suspend(userId, "2099-01-01 00:00:00");
        String location = callback(SocialProvider.NAVER, SocialStubServer.userinfo(SocialProvider.NAVER, "n-1", "n1@example.com", true, "네이버닉"));
        assertThat(location).startsWith("http://localhost:5173/login?socialError=ACCOUNT_SUSPENDED&until=2099-01-01T00");
        assertThat(location).doesNotContain("#code=");
    }

    @Test
    void 코드_발급_뒤_정지되면_exchange도_차단되고_무기한_정지는_until이_비어_있다() {
        String location = callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-s", "s@example.com", true, "정지예정자"));
        long userId = jdbc.queryForObject("SELECT id FROM users", Long.class);
        suspend(userId, null);
        String code = loginCode(location);
        assertThatThrownBy(() -> socialAuthService.exchange(code, "10.9.9.9")).isInstanceOf(AccountSuspendedException.class);
        assertThatThrownBy(() -> socialAuthService.exchange(code, "10.9.9.9")).isInstanceOf(SocialAuthException.class); // 코드는 소진됨
        String again = callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-s", "s@example.com", true, "정지예정자"));
        assertThat(again).isEqualTo("http://localhost:5173/login?socialError=ACCOUNT_SUSPENDED&until=");
    }

    private void suspend(long userId, String endsAt) {
        long admin = insertUser("admin" + (seq++) + "@example.com");
        jdbc.update("INSERT INTO user_sanction (user_id, type, note, starts_at, ends_at, created_by_id, created_at) VALUES (?, 'SUSPEND', 'x', '2020-01-01', ?, ?, NOW(6))",
                userId, endsAt, admin);
    }

    @Test
    void 같은_코드를_두번_교환하면_두번째는_실패한다() {
        String code = loginCode(callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-2x", "twice@example.com", true, "두번교환")));
        assertThat(socialAuthService.exchange(code, "10.9.9.9").accessToken()).isNotBlank();
        assertThatThrownBy(() -> socialAuthService.exchange(code, "10.9.9.9")).isInstanceOf(SocialAuthException.class)
                .satisfies(e -> assertThat(((SocialAuthException) e).getCode()).isEqualTo("INVALID_LOGIN_CODE"));
    }

    @Test
    void 동시_첫_로그인_경합에서도_사용자는_1명이고_모두_성공한다() throws Exception {
        int threads = 8;
        for (int round = 0; round < 10; round++) {
            jdbc.update("DELETE FROM social_account");
            jdbc.update("DELETE FROM users");
            String json = SocialStubServer.userinfo(SocialProvider.GOOGLE, "race-" + round, round % 2 == 0 ? "race@example.com" : null, true, "경합사용자");
            STUB.on("/userinfo", Reply.json(json));
            List<String[]> prepared = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                SocialAuthService.AuthorizeResult a = socialAuthService.authorize("google", "10.1.0." + i);
                prepared.add(new String[]{param(a.location(), "state"), a.setCookie().substring("ss_oauth=".length(), a.setCookie().indexOf(';'))});
            }
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (String[] p : prepared) {
                Callable<String> task = () -> {
                    go.await();
                    return socialAuthService.callback("google", "c", p[0], null, p[1]).location();
                };
                results.add(pool.submit(task));
            }
            go.countDown();
            for (Future<String> f : results) {
                assertThat(f.get(30, TimeUnit.SECONDS)).contains("/auth/callback#code=");
            }
            pool.shutdownNow();
            assertThat(count("users")).as("round %d", round).isEqualTo(1);
            assertThat(count("social_account")).as("round %d", round).isEqualTo(1);
        }
    }

    @Test
    void 같은_이메일로_서로_다른_소셜_계정이_동시에_가입하면_하나만_성공하고_나머지는_EMAIL_EXISTS() throws Exception {
        int threads = 6;
        for (int round = 0; round < 10; round++) {
            jdbc.update("DELETE FROM social_account");
            jdbc.update("DELETE FROM users");
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String id = "same-email-" + round + "-" + i;
                results.add(pool.submit(() -> {
                    go.await();
                    try {
                        loginService.loginOrSignup(SocialProvider.GOOGLE, new SocialProfile(id, "same@example.com", "같은이메일"));
                        return "OK";
                    } catch (SocialLoginFailure f) {
                        return f.getError().name();
                    }
                }));
            }
            go.countDown();
            int ok = 0;
            for (Future<String> f : results) {
                String r = f.get(30, TimeUnit.SECONDS);
                if (r.equals("OK")) {
                    ok++;
                } else {
                    assertThat(r).isEqualTo("EMAIL_EXISTS");
                }
            }
            pool.shutdownNow();
            assertThat(ok).as("round %d", round).isEqualTo(1);
            assertThat(count("users")).isEqualTo(1);
            assertThat(count("social_account")).isEqualTo(1);
        }
    }

    @Test
    void 로그와_리다이렉트에_code_token_secret이_남지_않는다() {
        // 애플리케이션(com.matjari) 로그만 본다 — Hibernate/JDBC 의 DEBUG 바인딩 로그는 프레임워크 몫이다
        Logger root = (Logger) LoggerFactory.getLogger("com.matjari");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        Level before = root.getLevel();
        root.setLevel(Level.DEBUG);
        try {
            // 성공 경로
            String ok = callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-log", "log@example.com", true, "로그확인"));
            // 제공자 오류 경로
            STUB.on("/token", Reply.status(400, "{\"error\":\"invalid_grant\",\"secret\":\"" + CLIENT_SECRET + "\"}"));
            String fail = callback(SocialProvider.GOOGLE, SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-log", "log@example.com", true, "로그확인"));
            assertThat(fail).isEqualTo("http://localhost:5173/login?socialError=PROVIDER_ERROR");
            String loginCode = loginCode(ok);
            socialAuthService.exchange(loginCode, "10.9.9.9");

            String logs = appender.list.stream().map(e -> e.getFormattedMessage() + " " + (e.getThrowableProxy() == null ? "" : e.getThrowableProxy().getMessage()))
                    .collect(Collectors.joining("\n"));
            for (String forbidden : List.of(CLIENT_SECRET, "AT-1", "provider-code", loginCode, "log@example.com", properties.getStateSecret())) {
                assertThat(logs).as("log contains %s", forbidden).doesNotContain(forbidden);
            }
            assertThat(ok).doesNotContain("AT-1").doesNotContain("provider-code").doesNotContain(CLIENT_SECRET);
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }
    }
}
