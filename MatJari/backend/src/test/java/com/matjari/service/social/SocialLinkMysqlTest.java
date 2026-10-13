package com.matjari.service.social;

import com.matjari.domain.SocialProvider;
import com.matjari.exception.AccountSuspendedException;
import com.matjari.service.social.SocialStubServer.Reply;
import com.matjari.testsupport.RealMysqlSupport;
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
 * 소셜 계정 연결·해제 실제 MySQL 통합 테스트(제공자는 JDK HttpServer 스텁). MATJARI_IT_* 환경변수가 있을 때만 실행한다.
 * 연결은 2단계다: 콜백이 연결 확정 코드를 발급하고(DB 저장 없음), 인증된 본인이 confirm 으로 확정할 때만 저장한다.
 * 동시 해제 vs 해제(마지막 로그인 수단 보호), 동시 확정 중복(SOCIAL_ALREADY_LINKED), 서로 다른 사용자의 같은 제공자 계정 동시 확정(SOCIAL_LINKED_OTHER),
 * 확정 후 그 소셜 계정으로 로그인이 같은 사용자로 되는지, 콜백만으로는 저장되지 않는지, 남의 코드로 확정할 수 없는지를 검증한다.
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
        registry.add("matjari.social.google.client-id", () -> "g-client");
        registry.add("matjari.social.google.client-secret", () -> "google-secret");
        registry.add("matjari.social.kakao.client-id", () -> "k-client");
        registry.add("matjari.social.naver.client-id", () -> "n-client");
        for (String p : List.of("google", "kakao", "naver")) {
            registry.add("matjari.social." + p + ".authorize-uri", () -> STUB.baseUrl() + "/authorize");
            registry.add("matjari.social." + p + ".token-uri", () -> STUB.baseUrl() + "/token");
            registry.add("matjari.social." + p + ".userinfo-uri", () -> STUB.baseUrl() + "/userinfo");
        }
        registry.add("matjari.social.ip-per-minute", () -> "100000");
        registry.add("matjari.social.link-per-minute", () -> "100000");
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

    private static final String LINK_CODE = "#linkCode=";

    /** 콜백 결과 location 에서 연결 확정 코드를 꺼낸다. */
    private static String linkCode(String location) {
        assertThat(location).startsWith("http://localhost:5173/me#linkCode=");
        return location.substring(location.indexOf(LINK_CODE) + LINK_CODE.length());
    }

    /** 연결 모드 authorize·콜백까지 진행해 연결 확정 코드를 돌려준다(저장은 하지 않는다). */
    private String callbackForCode(long userId, SocialProvider provider) {
        String[] c = prepareLink(userId, provider);
        return linkCode(socialAuthService.callback(provider.getPath(), "code", c[0], null, c[1]).location());
    }

    private String confirm(long userId, String code) {
        try {
            return socialAccountService.confirm(userId, code).provider();
        } catch (SocialAuthException e) {
            return e.getCode();
        }
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
    void 콜백은_저장하지_않고_확정해야_연결되며_그_소셜_계정으로_로그인하면_같은_사용자다() {
        long userId = insertUser("me@example.com", true);
        STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "sub-link", "different@example.com", true, "구글닉")));
        String code = callbackForCode(userId, SocialProvider.GOOGLE);
        assertThat(count("social_account")).isZero(); // 콜백만으로는 저장되지 않는다
        assertThat(socialAccountService.confirm(userId, code).provider()).isEqualTo("google");
        assertThat(count("social_account")).isEqualTo(1);
        assertThat(count("users")).isEqualTo(1);

        // 이제 이 소셜 계정으로 로그인하면 같은 사용자
        SocialAuthService.AuthorizeResult a = socialAuthService.authorize("google", "10.3.0.1");
        String loc = socialAuthService.callback("google", "code", param(a.location(), "state"), null,
                a.setCookie().substring("ss_oauth=".length(), a.setCookie().indexOf(';'))).location();
        assertThat(loc).contains("/auth/callback#code=");
        assertThat(count("users")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_id FROM social_account", Long.class)).isEqualTo(userId);
    }

    @Test
    void 남의_코드로_확정하면_400이고_코드는_소진되며_저장되지_않는다_피해자_계정에_붙지_않는다() {
        long attacker = insertUser("attacker@example.com", true);
        long victim = insertUser("victim@example.com", true);
        STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "victim-sub", null, true, "피해자")));
        String code = callbackForCode(attacker, SocialProvider.GOOGLE); // 공격자 티켓으로 시작한 연결(제공자 계정은 피해자 것)
        assertThat(confirm(victim, code)).isEqualTo("INVALID_LINK_CODE"); // 피해자 세션에서 확정 시도
        assertThat(confirm(attacker, code)).isEqualTo("INVALID_LINK_CODE"); // 이미 소진
        assertThat(count("social_account")).isZero();
    }

    @Test
    void 동시_해제_vs_해제에서_마지막_로그인_수단이_0개가_되지_않는다() throws Exception {
        for (int round = 0; round < 10; round++) {
            jdbc.update("DELETE FROM social_account");
            jdbc.update("DELETE FROM users");
            long userId = insertUser("s" + round + "@social.matjari.invalid", false);
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

        long socialOnly = insertUser("only@social.matjari.invalid", false);
        insertAccount(socialOnly, SocialProvider.NAVER, "n-2");
        assertThatThrownBy(() -> socialAccountService.unlink(socialOnly, "naver"))
                .isInstanceOfSatisfying(SocialAuthException.class, e -> assertThat(e.getCode()).isEqualTo("LAST_LOGIN_METHOD"));
        assertThat(count("social_account")).isEqualTo(1);
    }

    @Test
    void 같은_사용자가_같은_소셜_계정을_동시에_확정하면_하나만_성공하고_나머지는_SOCIAL_ALREADY_LINKED() throws Exception {
        int threads = 6;
        for (int round = 0; round < 5; round++) {
            jdbc.update("DELETE FROM social_account");
            jdbc.update("DELETE FROM users");
            long userId = insertUser("dup" + round + "@example.com", true);
            STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "dup-" + round, null, true, "구글닉")));
            List<String> codes = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                codes.add(callbackForCode(userId, SocialProvider.GOOGLE));
            }
            List<Callable<String>> tasks = new ArrayList<>();
            for (String code : codes) {
                tasks.add(() -> confirm(userId, code));
            }
            List<String> results = runConcurrently(tasks);
            assertThat(results.stream().filter(r -> r.equals("google")).count()).isEqualTo(1);
            assertThat(results.stream().filter(r -> r.equals("SOCIAL_ALREADY_LINKED")).count()).isEqualTo(threads - 1);
            assertThat(count("social_account")).isEqualTo(1);
        }
    }

    @Test
    void 서로_다른_사용자가_같은_소셜_계정을_동시에_확정하면_하나만_성공하고_나머지는_SOCIAL_LINKED_OTHER() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.update("DELETE FROM social_account");
            jdbc.update("DELETE FROM users");
            long u1 = insertUser("a" + round + "@example.com", true);
            long u2 = insertUser("b" + round + "@example.com", true);
            // 카카오 userinfo 의 id 는 JSON 숫자여야 한다(스텁이 따옴표 없이 넣으므로 숫자 문자열을 쓴다)
            STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.KAKAO, String.valueOf(9000 + round), null, true, "카카오닉")));
            String c1 = callbackForCode(u1, SocialProvider.KAKAO);
            String c2 = callbackForCode(u2, SocialProvider.KAKAO);
            List<String> results = runConcurrently(List.of(() -> confirm(u1, c1), () -> confirm(u2, c2)));
            assertThat(results).containsExactlyInAnyOrder("kakao", "SOCIAL_LINKED_OTHER");
            assertThat(count("social_account")).isEqualTo(1);
        }
    }

    @Test
    void 확정은_이미_다른_사용자에_연결된_제공자_계정이면_SOCIAL_LINKED_OTHER_내가_이미_가진_제공자면_SOCIAL_ALREADY_LINKED() {
        long u1 = insertUser("x@example.com", true);
        long u2 = insertUser("y@example.com", true);
        insertAccount(u1, SocialProvider.GOOGLE, "taken");
        STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "taken", null, true, "닉")));
        assertThat(confirm(u2, callbackForCode(u2, SocialProvider.GOOGLE))).isEqualTo("SOCIAL_LINKED_OTHER");
        STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "another", null, true, "닉")));
        String code = callbackForCode(u2, SocialProvider.GOOGLE); // 이 시점에는 u2 가 구글을 갖고 있지 않다
        insertAccount(u2, SocialProvider.GOOGLE, "already-mine");
        assertThat(confirm(u2, code)).isEqualTo("SOCIAL_ALREADY_LINKED");
        assertThat(count("social_account")).isEqualTo(2);
    }

    @Test
    void 정지된_사용자의_연결은_거부되고_행이_늘지_않는다() {
        long userId = insertUser("sus@example.com", true);
        long adminId = insertUser("admin@example.com", true);
        jdbc.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", adminId);
        STUB.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "sus-1", null, true, "닉")));
        String code = callbackForCode(userId, SocialProvider.GOOGLE); // 정지 전에 받은 확정 코드
        jdbc.update("INSERT INTO user_sanction (user_id, type, note, starts_at, ends_at, created_by_id, created_at) VALUES (?, 'SUSPEND', '테스트', NOW(6) - INTERVAL 1 HOUR, NULL, ?, NOW(6))", userId, adminId);
        assertThatThrownBy(() -> socialAccountService.confirm(userId, code)).isInstanceOf(AccountSuspendedException.class);
        assertThat(count("social_account")).isZero();
    }
}
