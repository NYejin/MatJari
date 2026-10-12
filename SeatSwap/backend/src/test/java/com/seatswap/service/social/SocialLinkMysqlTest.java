package com.seatswap.service.social;

import com.seatswap.domain.SocialProvider;
import com.seatswap.service.social.SocialStubServer.Reply;
import com.seatswap.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 소셜 계정 연결·해제 실제 MySQL 통합 테스트(제공자는 JDK HttpServer 스텁). SEATSWAP_IT_* 환경변수가 있을 때만 실행한다.
 * 동시 해제 vs 해제(마지막 로그인 수단 보호), 동시 연결 중복(ALREADY_LINKED), 서로 다른 사용자의 같은 제공자 계정 동시 연결(ALREADY_LINKED_OTHER),
 * 연결 후 그 소셜 계정으로 로그인이 같은 사용자로 되는지를 검증한다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SocialLinkMysqlTest {

    private static final SocialStubServer STUB = new SocialStubServer();

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("seatswap.social.google.client-id", () -> "g-client");
        registry.add("seatswap.social.google.client-secret", () -> "google-secret");
        registry.add("seatswap.social.kakao.client-id", () -> "k-client");
        registry.add("seatswap.social.naver.client-id", () -> "n-client");
        for (String p : List.of("google", "kakao", "naver")) {
            registry.add("seatswap.social." + p + ".authorize-uri", () -> STUB.baseUrl() + "/authorize");
            registry.add("seatswap.social." + p + ".token-uri", () -> STUB.baseUrl() + "/token");
            registry.add("seatswap.social." + p + ".userinfo-uri", () -> STUB.baseUrl() + "/userinfo");
        }
        registry.add("seatswap.social.ip-per-minute", () -> "100000");
        registry.add("seatswap.social.link-per-minute", () -> "100000");
        registry.add("seatswap.social.read-timeout-ms", () -> "3000");
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
    private SocialAccountService socialAccountService;
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

    private long insertUser(String email, boolean passwordLogin) {
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, password_login_enabled, role) VALUES (NOW(6), ?, ?, 'x', ?, 'USER')",
                email, "사용자", passwordLogin ? 1 : 0);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertAccount(long userId, SocialProvider provider, String providerUserId) {
        jdbc.update("INSERT INTO social_account (created_at, user_id, provider, provider_user_id) VALUES (NOW(6), ?, ?, ?)",
                userId, provider.name(), providerUserId);
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

    /** 연결 모드 authorize 까지 진행해 {state, 쿠키값} 을 돌려준다. */
    private String[] prepareLink(long userId, SocialProvider provider) {
        String url = socialAccountService.startLink(userId, provider.getPath()).authorizeUrl();
        String ticket = url.substring(url.indexOf("link=") + 5);
        SocialAuthService.AuthorizeResult a = socialAuthService.authorize(provider.getPath(), "10.2.0." + (seq++ % 200), ticket);
        return new String[]{param(a.location(), "state"), a.setCookie().substring("ss_oauth=".length(), a.setCookie().indexOf(';'))};
    }

    private List<String> runConcurrently(List<Callable<String>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> t : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return t.call();
                }));
            }
            go.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------

    @Test
    void 연결_후_그_소셜_계정으로_로그인하면_같은_사용자이고_새_계정을_만들지_않는다() {
        long userId = insertUser("me@example.com", true);
        STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-link", "different@example.com", true, "구글닉")));
        String[] c = prepareLink(userId, SocialProvider.GOOGLE);
        String location = socialAuthService.callback("google", "code", c[0], null, c[1]).location();
        assertThat(location).isEqualTo("http://localhost:5173/me?socialLinked=google");
        assertThat(count("social_account")).isEqualTo(1);
        assertThat(count("users")).isEqualTo(1);

        // 이제 이 소셜 계정으로 로그인하면 같은 사용자
        SocialAuthService.AuthorizeResult a = socialAuthService.authorize("google", "10.3.0.1");
        String loc = socialAuthService.callback("google", "code", param(a.location(), "state"), null,
                a.setCookie().substring("ss_oauth=".length(), a.setCookie().indexOf(';'))).location();
        assertThat(loc).contains("/auth/callback#code=");
        assertThat(count("users")).isEqualTo(1);
    }

    @Test
    void 동시_해제_vs_해제에서_마지막_로그인_수단이_0개가_되지_않는다() throws Exception {
        for (int round = 0; round < 10; round++) {
            jdbc.update("DELETE FROM social_account");
            jdbc.update("DELETE FROM users");
            long userId = insertUser("s" + round + "@social.seatswap.invalid", false);
            insertAccount(userId, SocialProvider.GOOGLE, "g-" + round);
            insertAccount(userId, SocialProvider.KAKAO, "k-" + round);
            List<Callable<String>> tasks = List.of(
                    () -> unlink(userId, "google"),
                    () -> unlink(userId, "kakao"));
            List<String> results = runConcurrently(tasks);
            assertThat(results).containsExactlyInAnyOrder("OK", "LAST_LOGIN_METHOD");
            assertThat(count("social_account")).isEqualTo(1);
        }
    }

    private String unlink(long userId, String provider) {
        try {
            socialAccountService.unlink(userId, provider);
            return "OK";
        } catch (SocialAuthException e) {
            return e.getCode();
        }
    }

    @Test
    void 비밀번호_로그인이_가능하면_하나뿐이어도_해제할_수_있고_연결이_없으면_멱등이다() {
        long userId = insertUser("pw@example.com", true);
        insertAccount(userId, SocialProvider.NAVER, "n-1");
        socialAccountService.unlink(userId, "naver");
        assertThat(count("social_account")).isZero();
        socialAccountService.unlink(userId, "naver"); // 멱등

        long socialOnly = insertUser("only@social.seatswap.invalid", false);
        insertAccount(socialOnly, SocialProvider.NAVER, "n-2");
        assertThatThrownBy(() -> socialAccountService.unlink(socialOnly, "naver"))
                .isInstanceOfSatisfying(SocialAuthException.class, e -> assertThat(e.getCode()).isEqualTo("LAST_LOGIN_METHOD"));
        assertThat(count("social_account")).isEqualTo(1);
    }

    @Test
    void 같은_사용자가_같은_소셜_계정을_동시에_연결하면_하나만_성공하고_나머지는_ALREADY_LINKED() throws Exception {
        int threads = 6;
        for (int round = 0; round < 5; round++) {
            jdbc.update("DELETE FROM social_account");
            jdbc.update("DELETE FROM users");
            long userId = insertUser("dup" + round + "@example.com", true);
            STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "dup-" + round, null, true, "구글닉")));
            List<String[]> prepared = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                prepared.add(prepareLink(userId, SocialProvider.GOOGLE));
            }
            List<Callable<String>> tasks = new ArrayList<>();
            for (String[] p : prepared) {
                tasks.add(() -> socialAuthService.callback("google", "c", p[0], null, p[1]).location());
            }
            List<String> results = runConcurrently(tasks);
            assertThat(results.stream().filter(l -> l.endsWith("/me?socialLinked=google")).count()).isEqualTo(1);
            assertThat(results.stream().filter(l -> l.endsWith("/me?socialLinkError=ALREADY_LINKED")).count()).isEqualTo(threads - 1);
            assertThat(count("social_account")).isEqualTo(1);
        }
    }

    @Test
    void 서로_다른_사용자가_같은_소셜_계정을_동시에_연결하면_하나만_성공하고_나머지는_ALREADY_LINKED_OTHER() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.update("DELETE FROM social_account");
            jdbc.update("DELETE FROM users");
            long u1 = insertUser("a" + round + "@example.com", true);
            long u2 = insertUser("b" + round + "@example.com", true);
            STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.KAKAO, "shared-" + round, null, true, "카카오닉")));
            String[] c1 = prepareLink(u1, SocialProvider.KAKAO);
            String[] c2 = prepareLink(u2, SocialProvider.KAKAO);
            List<String> results = runConcurrently(List.of(
                    () -> socialAuthService.callback("kakao", "c", c1[0], null, c1[1]).location(),
                    () -> socialAuthService.callback("kakao", "c", c2[0], null, c2[1]).location()));
            assertThat(results).anyMatch(l -> l.endsWith("/me?socialLinked=kakao"));
            assertThat(results).anyMatch(l -> l.endsWith("/me?socialLinkError=ALREADY_LINKED_OTHER"));
            assertThat(count("social_account")).isEqualTo(1);
        }
    }

    @Test
    void 정지된_사용자의_연결은_거부되고_행이_늘지_않는다() {
        long userId = insertUser("sus@example.com", true);
        jdbc.update("INSERT INTO user_sanction (user_id, type, note, starts_at, ends_at, created_by_id, created_at) VALUES (?, 'SUSPEND', '테스트', NOW(6) - INTERVAL 1 HOUR, NULL, ?, NOW(6))", userId, userId);
        STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "sus-1", null, true, "닉")));
        String[] c = prepareLink(userId, SocialProvider.GOOGLE);
        String location = socialAuthService.callback("google", "c", c[0], null, c[1]).location();
        assertThat(location).startsWith("http://localhost:5173/me?socialLinkError=ACCOUNT_SUSPENDED");
        assertThat(count("social_account")).isZero();
    }
}
