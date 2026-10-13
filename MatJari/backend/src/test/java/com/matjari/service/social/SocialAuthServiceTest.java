package com.matjari.service.social;

import com.matjari.domain.SocialProvider;
import com.matjari.domain.User;
import com.matjari.dto.response.TokenResponse;
import com.matjari.exception.AccountSuspendedException;
import com.matjari.repository.UserRepository;
import com.matjari.security.AccountSuspensionChecker;
import com.matjari.service.AuthService;
import com.matjari.service.social.SocialLoginFailure.SocialError;
import com.matjari.service.social.SocialTestSupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 콜백/교환 흐름의 보안 동작. 제공자 호출·사용자 결정은 mock 으로 두고, 쿠키·코드 저장소·속도 제한은 실제 구현을 쓴다. */
class SocialAuthServiceTest {

    private static final String IP = "9.9.9.9";

    private final MutableClock clock = new MutableClock();
    private SocialProperties props;
    private SocialStateCookie stateCookie;
    private SocialProviderClient providerClient;
    private SocialLoginService loginService;
    private SocialLoginCodeStore codeStore;
    private AuthService authService;
    private UserRepository userRepository;
    private AccountSuspensionChecker suspensionChecker;
    private SocialLinkTicketStore linkTicketStore;
    private SocialLinkConfirmStore confirmStore;
    private SocialAuthService service;

    @BeforeEach
    void setUp() {
        props = new SocialProperties();
        props.setStateSecret(SocialTestSupport.SECRET);
        props.getGoogle().setClientId("g-client");
        props.getGoogle().setClientSecret("g-secret-VALUE");
        props.getKakao().setClientId("k-client");
        props.getNaver().setClientId("n-client");
        stateCookie = new SocialStateCookie(props, clock);
        providerClient = mock(SocialProviderClient.class);
        loginService = mock(SocialLoginService.class);
        codeStore = new SocialLoginCodeStore(clock, props);
        linkTicketStore = new SocialLinkTicketStore(clock, props);
        confirmStore = new SocialLinkConfirmStore(clock, props);
        authService = mock(AuthService.class);
        userRepository = mock(UserRepository.class);
        suspensionChecker = mock(AccountSuspensionChecker.class);
        service = new SocialAuthService(props, stateCookie, providerClient, loginService, codeStore,
                new SocialRateLimiter(clock, props), authService, userRepository, suspensionChecker,
                linkTicketStore, confirmStore);
    }

    private static Map<String, String> query(String location) {
        Map<String, String> m = new LinkedHashMap<>();
        String q = URI.create(location).getRawQuery();
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String cookieValueOf(String setCookie) {
        return setCookie.substring("ss_oauth=".length(), setCookie.indexOf(';'));
    }

    // ---------------- authorize ----------------

    @Test
    void 구글_authorize는_state_PKCE_scope를_담고_쿠키와_짝이_맞는다() {
        SocialAuthService.AuthorizeResult r = service.authorize("google", IP);
        assertThat(r.location()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        Map<String, String> q = query(r.location());
        assertThat(q).containsEntry("response_type", "code").containsEntry("client_id", "g-client")
                .containsEntry("redirect_uri", "http://localhost:8080/api/auth/social/google/callback")
                .containsEntry("scope", "openid email profile").containsEntry("code_challenge_method", "S256");
        SocialStateCookie.Verified v = stateCookie.verify(cookieValueOf(r.setCookie()), SocialProvider.GOOGLE).orElseThrow();
        assertThat(q.get("state")).isEqualTo(v.state());
        assertThat(q.get("code_challenge")).isEqualTo(SocialPkce.challenge(v.verifier()));
        assertThat(v.state()).hasSize(43);
        assertThat(v.verifier()).hasSize(43);
        assertThat(r.setCookie()).contains("HttpOnly", "SameSite=Lax", "Path=/api/auth/social", "Max-Age=600");
        assertThat(r.location()).doesNotContain("g-secret-VALUE").doesNotContain(v.verifier());
    }

    @Test
    void 카카오는_kakao_scope와_PKCE_네이버는_scope와_PKCE_없음() {
        Map<String, String> k = query(service.authorize("kakao", IP).location());
        assertThat(k).containsEntry("scope", "profile_nickname account_email").containsKey("code_challenge");
        Map<String, String> n = query(service.authorize("naver", IP).location());
        assertThat(n).doesNotContainKeys("scope", "code_challenge", "code_challenge_method").containsKey("state");
    }

    @Test
    void 매번_새_state와_verifier를_만든다() {
        Map<String, String> a = query(service.authorize("google", IP).location());
        Map<String, String> b = query(service.authorize("google", IP).location());
        assertThat(a.get("state")).isNotEqualTo(b.get("state"));
        assertThat(a.get("code_challenge")).isNotEqualTo(b.get("code_challenge"));
    }

    @Test
    void 비활성_또는_알_수_없는_제공자는_404_SOCIAL_PROVIDER_DISABLED() {
        props.getKakao().setClientId("");
        for (String p : new String[]{"kakao", "facebook", "GOOGLE", "", "../x"}) {
            assertThatThrownBy(() -> service.authorize(p, IP)).isInstanceOf(SocialAuthException.class)
                    .satisfies(e -> {
                        assertThat(((SocialAuthException) e).getStatus().value()).isEqualTo(404);
                        assertThat(((SocialAuthException) e).getCode()).isEqualTo("SOCIAL_PROVIDER_DISABLED");
                    });
        }
        assertThatThrownBy(() -> service.callback("kakao", "c", "s", null, "x")).isInstanceOf(SocialAuthException.class);
        assertThat(service.enabledProviders()).containsExactly("google", "naver");
    }

    @Test
    void authorize는_IP당_분당_30회로_제한된다() {
        for (int i = 0; i < 30; i++) {
            service.authorize("google", IP);
        }
        assertThatThrownBy(() -> service.authorize("google", IP)).isInstanceOf(SocialAuthException.class)
                .satisfies(e -> assertThat(((SocialAuthException) e).getCode()).isEqualTo("SOCIAL_RATE_LIMITED"));
        service.authorize("google", "8.8.8.8");
    }

    // ---------------- callback: state 검증 ----------------

    private String validCookie(SocialProvider p, String state) {
        return stateCookie.sign(p, state, "verifier-1");
    }

    private void assertFailure(SocialAuthService.CallbackResult r, SocialError error) {
        assertThat(r.location()).isEqualTo("http://localhost:5173/login?socialError=" + error.name());
        assertThat(r.clearCookie()).contains("ss_oauth=;").contains("Max-Age=0");
    }

    @Test
    void 쿠키가_없으면_INVALID_STATE이고_제공자를_호출하지_않는다() {
        assertFailure(service.callback("google", "code", "st", null, null), SocialError.INVALID_STATE);
        verifyNoInteractions(providerClient, loginService);
    }

    @Test
    void 변조_만료_다른provider_쿠키와_state_불일치는_모두_INVALID_STATE() {
        String good = validCookie(SocialProvider.GOOGLE, "st");
        String tampered = good.substring(0, good.length() - 2) + (good.endsWith("AA") ? "BB" : "AA");
        assertFailure(service.callback("google", "code", "st", null, tampered), SocialError.INVALID_STATE);
        assertFailure(service.callback("kakao", "code", "st", null, good), SocialError.INVALID_STATE); // provider 불일치
        assertFailure(service.callback("google", "code", "other", null, good), SocialError.INVALID_STATE);
        assertFailure(service.callback("google", "code", null, null, good), SocialError.INVALID_STATE);
        assertFailure(service.callback("google", "code", "", null, good), SocialError.INVALID_STATE);
        clock.advance(601_000);
        assertFailure(service.callback("google", "code", "st", null, good), SocialError.INVALID_STATE);
        verifyNoInteractions(providerClient, loginService);
    }

    @Test
    void 제공자_error_파라미터는_ACCESS_DENIED이고_코드_교환을_하지_않는다() {
        String cookie = validCookie(SocialProvider.GOOGLE, "st");
        assertFailure(service.callback("google", null, "st", "access_denied", cookie), SocialError.ACCESS_DENIED);
        verifyNoInteractions(providerClient, loginService);
    }

    @Test
    void error_파라미터라도_state가_틀리면_INVALID_STATE가_우선() {
        String cookie = validCookie(SocialProvider.GOOGLE, "st");
        assertFailure(service.callback("google", null, "zzz", "access_denied", cookie), SocialError.INVALID_STATE);
    }

    // ---------------- callback: 교환·사용자 결정 ----------------

    @Test
    void 제공자_호출_실패는_PROVIDER_ERROR() {
        String cookie = validCookie(SocialProvider.GOOGLE, "st");
        when(providerClient.exchangeCode(any(), anyString(), anyString(), anyString()))
                .thenThrow(new SocialProviderClient.ProviderException("status-400"));
        assertFailure(service.callback("google", "code", "st", null, cookie), SocialError.PROVIDER_ERROR);
        verifyNoInteractions(loginService);
        // state 는 한 번만 받는다 — 같은 쿠키·state 를 다시 쓰면 INVALID_STATE
        assertFailure(service.callback("google", null, "st", null, cookie), SocialError.INVALID_STATE);
        assertFailure(service.callback("google", null, "st2", null, validCookie(SocialProvider.GOOGLE, "st2")), SocialError.PROVIDER_ERROR); // code 없음
    }

    @Test
    void 같은_state_쿠키는_두_번_통과하지_못하고_두_번째는_제공자를_호출하지_않는다() {
        String cookie = validCookie(SocialProvider.GOOGLE, "st");
        when(providerClient.exchangeCode(any(), anyString(), anyString(), anyString())).thenReturn("AT");
        when(providerClient.fetchProfile(any(), anyString())).thenReturn(new SocialProfile("sub-1", null, null));
        when(loginService.loginOrSignup(any(), any())).thenReturn(1L);
        assertThat(service.callback("google", "c", "st", null, cookie).location()).contains("/auth/callback#code=");
        assertFailure(service.callback("google", "c", "st", null, cookie), SocialError.INVALID_STATE);
        verify(providerClient, times(1)).exchangeCode(any(), anyString(), anyString(), anyString());
    }

    @Test
    void 분류하지_못한_런타임_예외도_로그인_화면으로_돌려보내고_쿠키를_지운다() {
        String cookie = validCookie(SocialProvider.GOOGLE, "st");
        when(providerClient.exchangeCode(any(), anyString(), anyString(), anyString())).thenReturn("AT");
        when(providerClient.fetchProfile(any(), anyString())).thenReturn(new SocialProfile("sub-1", null, null));
        when(loginService.loginOrSignup(any(), any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate entry 'secret@x'"));
        SocialAuthService.CallbackResult r = service.callback("google", "c", "st", null, cookie);
        assertThat(r.location()).isEqualTo("http://localhost:5173/login?socialError=SERVER_BUSY");
        assertThat(r.clearCookie()).contains("Max-Age=0");
        assertThat(codeStore.size()).isZero();
    }

    @Test
    void 성공하면_프래그먼트로_일회용_코드를_보내고_쿠키를_지우며_토큰을_싣지_않는다() {
        String cookie = validCookie(SocialProvider.GOOGLE, "st");
        when(providerClient.exchangeCode(SocialProvider.GOOGLE, "the-code", "verifier-1", "st")).thenReturn("access-token-SECRET");
        SocialProfile profile = new SocialProfile("sub-1", "a@b.com", "닉");
        when(providerClient.fetchProfile(SocialProvider.GOOGLE, "access-token-SECRET")).thenReturn(profile);
        when(loginService.loginOrSignup(SocialProvider.GOOGLE, profile)).thenReturn(42L);

        SocialAuthService.CallbackResult r = service.callback("google", "the-code", "st", null, cookie);

        assertThat(r.location()).startsWith("http://localhost:5173/auth/callback#code=");
        assertThat(r.location()).doesNotContain("the-code").doesNotContain("access-token-SECRET").doesNotContain("?");
        assertThat(r.clearCookie()).contains("Max-Age=0");
        String loginCode = r.location().substring(r.location().indexOf("#code=") + 6);
        assertThat(codeStore.consume(loginCode)).contains(42L);
    }

    @Test
    void 같은_이메일_기존_계정은_EMAIL_EXISTS() {
        String cookie = validCookie(SocialProvider.KAKAO, "st");
        when(providerClient.exchangeCode(any(), anyString(), anyString(), anyString())).thenReturn("AT");
        when(providerClient.fetchProfile(any(), anyString())).thenReturn(new SocialProfile("1", "a@b.com", null));
        when(loginService.loginOrSignup(any(), any())).thenThrow(new SocialLoginFailure(SocialError.EMAIL_EXISTS));
        assertFailure(service.callback("kakao", "c", "st", null, cookie), SocialError.EMAIL_EXISTS);
    }

    @Test
    void 정지_계정은_콜백에서_차단되고_until이_붙으며_코드는_발급되지_않는다() {
        String cookie = validCookie(SocialProvider.NAVER, "st");
        when(providerClient.exchangeCode(any(), anyString(), any(), anyString())).thenReturn("AT");
        when(providerClient.fetchProfile(any(), anyString())).thenReturn(new SocialProfile("1", null, null));
        when(loginService.loginOrSignup(any(), any())).thenThrow(new AccountSuspendedException(LocalDateTime.of(2026, 12, 25, 10, 30, 5)));
        SocialAuthService.CallbackResult r = service.callback("naver", "c", "st", null, cookie);
        assertThat(r.location()).isEqualTo("http://localhost:5173/login?socialError=ACCOUNT_SUSPENDED&until=2026-12-25T10%3A30%3A05");

        doThrow(new AccountSuspendedException(null)).when(loginService).loginOrSignup(any(), any());
        assertThat(service.callback("naver", "c", "st2", null, validCookie(SocialProvider.NAVER, "st2")).location()).endsWith("socialError=ACCOUNT_SUSPENDED&until=");
        assertThat(codeStore.size()).isZero();
    }

    @Test
    void 로그인_코드_보관이_가득이면_SERVER_BUSY() {
        props.setLoginCodeMax(0);
        String cookie = validCookie(SocialProvider.GOOGLE, "st");
        when(providerClient.exchangeCode(any(), anyString(), any(), anyString())).thenReturn("AT");
        when(providerClient.fetchProfile(any(), anyString())).thenReturn(new SocialProfile("1", null, null));
        when(loginService.loginOrSignup(any(), any())).thenReturn(1L);
        assertFailure(service.callback("google", "c", "st", null, cookie), SocialError.SERVER_BUSY);
    }

    @Test
    void 콜백_결과의_목적지는_항상_설정된_프런트_주소다() {
        props.setFrontendBaseUrl("https://app.example.com/");
        for (String cookie : new String[]{null, "garbage", validCookie(SocialProvider.GOOGLE, "st")}) {
            for (String state : new String[]{"st", "https://evil.example.com", "//evil.example.com", null}) {
                SocialAuthService.CallbackResult r = service.callback("google", "https://evil.example.com", state, "https://evil.example.com", cookie);
                assertThat(r.location()).startsWith("https://app.example.com/login?socialError=");
                assertThat(r.location()).doesNotContain("evil");
            }
        }
    }

    // ---------------- 계정 연결(link) 모드 ----------------

    private void assertLinkFailure(SocialAuthService.CallbackResult r, SocialError error) {
        assertThat(r.location()).isEqualTo("http://localhost:5173/me?socialLinkError=" + error.name());
        assertThat(r.clearCookie()).contains("ss_oauth=;").contains("Max-Age=0");
    }

    private String linkCookie(SocialProvider p, String state, long userId) {
        return stateCookie.sign(p, state, "verifier-1", userId);
    }

    private void stubProfile(String providerUserId) {
        when(providerClient.exchangeCode(any(), anyString(), anyString(), anyString())).thenReturn("AT");
        when(providerClient.fetchProfile(any(), anyString())).thenReturn(new SocialProfile(providerUserId, "other@example.com", "닉"));
    }

    @Test
    void link_authorize는_티켓을_소비하고_쿠키에_linkUserId를_서명해_담는다() {
        String ticket = linkTicketStore.issue(42L, SocialProvider.GOOGLE);
        SocialAuthService.AuthorizeResult r = service.authorize("google", IP, ticket);
        assertThat(r.location()).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(r.location()).doesNotContain(ticket);
        SocialStateCookie.Verified v = stateCookie.verify(cookieValueOf(r.setCookie()), SocialProvider.GOOGLE).orElseThrow();
        assertThat(v.linkUserId()).isEqualTo(42L);
        assertThat(query(r.location()).get("state")).isEqualTo(v.state());
        assertThat(linkTicketStore.consume(ticket, SocialProvider.GOOGLE)).isEmpty(); // 1회용
        // 같은 티켓을 다시 쓰면 제공자로 보내지 않고 /me 로 돌려보낸다
        SocialAuthService.AuthorizeResult again = service.authorize("google", IP, ticket);
        assertThat(again.location()).isEqualTo("http://localhost:5173/me?socialLinkError=INVALID_LINK");
        assertThat(again.setCookie()).isNull();
    }

    @Test
    void link_authorize는_없거나_빈_또는_만료된_티켓이면_INVALID_LINK() {
        for (String bad : new String[]{"", "never-issued", "  "}) {
            SocialAuthService.AuthorizeResult r = service.authorize("google", IP, bad);
            assertThat(r.location()).isEqualTo("http://localhost:5173/me?socialLinkError=INVALID_LINK");
            assertThat(r.setCookie()).isNull();
        }
        String ticket = linkTicketStore.issue(1L, SocialProvider.GOOGLE);
        clock.advance(61_000);
        assertThat(service.authorize("google", IP, ticket).location()).endsWith("socialLinkError=INVALID_LINK");
    }

    @Test
    void link_티켓은_발급된_제공자에서만_쓸_수_있고_다른_제공자면_INVALID_LINK이며_소진된다() {
        String ticket = linkTicketStore.issue(42L, SocialProvider.GOOGLE);
        SocialAuthService.AuthorizeResult r = service.authorize("kakao", IP, ticket);
        assertThat(r.location()).isEqualTo("http://localhost:5173/me?socialLinkError=INVALID_LINK");
        assertThat(r.setCookie()).isNull();
        assertThat(linkTicketStore.consume(ticket, SocialProvider.GOOGLE)).isEmpty();
    }

    @Test
    void link_authorize는_비활성_제공자면_JSON이_아니라_me로_PROVIDER_DISABLED_302이고_티켓을_태우지_않는다() {
        props.getKakao().setClientId("");
        String ticket = linkTicketStore.issue(1L, SocialProvider.KAKAO);
        for (String p : new String[]{"kakao", "facebook"}) {
            SocialAuthService.AuthorizeResult r = service.authorize(p, IP, ticket);
            assertThat(r.location()).isEqualTo("http://localhost:5173/me?socialLinkError=PROVIDER_DISABLED");
            assertThat(r.setCookie()).isNull();
        }
        assertThat(linkTicketStore.consume(ticket, SocialProvider.KAKAO)).contains(1L);
        // 로그인 모드는 기존 그대로 404
        assertThatThrownBy(() -> service.authorize("kakao", IP)).isInstanceOf(SocialAuthException.class);
    }

    @Test
    void link_authorize의_IP_속도_제한_초과는_me로_SERVER_BUSY_302이고_로그인_모드는_429() {
        for (int i = 0; i < 30; i++) {
            service.authorize("google", IP);
        }
        String ticket = linkTicketStore.issue(1L, SocialProvider.GOOGLE);
        SocialAuthService.AuthorizeResult r = service.authorize("google", IP, ticket);
        assertThat(r.location()).isEqualTo("http://localhost:5173/me?socialLinkError=SERVER_BUSY");
        assertThat(r.setCookie()).isNull();
        assertThat(linkTicketStore.consume(ticket, SocialProvider.GOOGLE)).contains(1L);
        assertThatThrownBy(() -> service.authorize("google", IP)).isInstanceOf(SocialAuthException.class)
                .satisfies(e -> assertThat(((SocialAuthException) e).getCode()).isEqualTo("SOCIAL_RATE_LIMITED"));
    }

    @Test
    void link_콜백_성공은_저장하지_않고_연결_확정_코드를_프래그먼트로_보낸다() {
        stubProfile("sub-77");
        SocialAuthService.CallbackResult r = service.callback("google", "the-code", "st", null, linkCookie(SocialProvider.GOOGLE, "st", 42L));
        assertThat(r.location()).startsWith("http://localhost:5173/me#linkCode=");
        assertThat(r.location()).doesNotContain("?").doesNotContain("sub-77").doesNotContain("the-code");
        assertThat(r.clearCookie()).contains("Max-Age=0");
        verifyNoInteractions(loginService); // 로그인·가입 경로를 타지 않는다(이메일 일치 여부도 보지 않는다)
        assertThat(codeStore.size()).isZero();
        String code = r.location().substring(r.location().indexOf("#linkCode=") + "#linkCode=".length());
        assertThat(code).hasSize(43);
        SocialLinkConfirmStore.Payload payload = confirmStore.consume(code).orElseThrow();
        assertThat(payload.userId()).isEqualTo(42L);
        assertThat(payload.provider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(payload.providerUserId()).isEqualTo("sub-77");
        assertThat(confirmStore.consume(code)).isEmpty();
    }

    @Test
    void link_콜백의_확정_코드_보관이_가득이면_me로_SERVER_BUSY() {
        stubProfile("sub-77");
        props.setLoginCodeMax(0);
        assertLinkFailure(service.callback("google", "c", "st", null, linkCookie(SocialProvider.GOOGLE, "st", 42L)), SocialError.SERVER_BUSY);
    }

    @Test
    void link_콜백의_분류_못한_오류는_me로_SERVER_BUSY() {
        when(providerClient.exchangeCode(any(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("boom"));
        assertLinkFailure(service.callback("google", "c", "st", null, linkCookie(SocialProvider.GOOGLE, "st", 42L)), SocialError.SERVER_BUSY);
    }

    @Test
    void link_콜백_동의거부_제공자오류는_me로_돌려보낸다() {
        String cookie = linkCookie(SocialProvider.GOOGLE, "st", 42L);
        assertLinkFailure(service.callback("google", null, "st", "access_denied", cookie), SocialError.ACCESS_DENIED);
        when(providerClient.exchangeCode(any(), anyString(), anyString(), anyString()))
                .thenThrow(new SocialProviderClient.ProviderException("status-400"));
        assertLinkFailure(service.callback("google", "c", "st2", null, linkCookie(SocialProvider.GOOGLE, "st2", 42L)), SocialError.PROVIDER_ERROR);
        assertThat(confirmStore.size()).isZero();
    }

    @Test
    void 쿠키_검증_전_실패도_쿠키가_link_모드처럼_보이면_me로_보낸다_서명_검증은_하지_않는다() {
        String good = linkCookie(SocialProvider.GOOGLE, "st", 42L);
        // state 불일치 / 쿠키 만료 / provider 불일치 / 변조(서명 틀림, 본문은 link 모양)
        assertLinkFailure(service.callback("google", "c", "other", null, good), SocialError.INVALID_STATE);
        assertLinkFailure(service.callback("kakao", "c", "st", null, good), SocialError.INVALID_STATE);
        String tampered = good.substring(0, good.length() - 2) + (good.endsWith("AA") ? "BB" : "AA");
        assertLinkFailure(service.callback("google", "c", "st", null, tampered), SocialError.INVALID_STATE);
        clock.advance(601_000);
        assertLinkFailure(service.callback("google", "c", "st", null, good), SocialError.INVALID_STATE);
        // 쿠키 없음·쓰레기·로그인 모드 쿠키는 /login
        for (String c : new String[]{null, "garbage", stateCookie.sign(SocialProvider.GOOGLE, "st", "v")}) {
            assertThat(service.callback("google", "c", "other", null, c).location()).isEqualTo("http://localhost:5173/login?socialError=INVALID_STATE");
        }
        // 속도 제한 busyResult 도 같은 규칙
        assertThat(service.busyResult(good).location()).isEqualTo("http://localhost:5173/me?socialLinkError=SERVER_BUSY");
        assertThat(service.busyResult(null).location()).isEqualTo("http://localhost:5173/login?socialError=SERVER_BUSY");
        assertThat(service.busyResult(good).clearCookie()).contains("Max-Age=0");
        verifyNoInteractions(providerClient);
    }

    @Test
    void link_콜백도_state는_한_번만_받는다() {
        stubProfile("sub-77");
        String cookie = linkCookie(SocialProvider.GOOGLE, "st", 42L);
        assertThat(service.callback("google", "c", "st", null, cookie).location()).contains("/me#linkCode=");
        assertThat(service.callback("google", "c", "st", null, cookie).location()).endsWith("/me?socialLinkError=INVALID_STATE");
        verify(providerClient, times(1)).exchangeCode(any(), anyString(), anyString(), anyString());
        assertThat(confirmStore.size()).isEqualTo(1);
    }

    @Test
    void 로그인_모드_쿠키는_연결_코드를_발급하지_않는다() {
        stubProfile("sub-1");
        when(loginService.loginOrSignup(any(), any())).thenReturn(5L);
        String cookie = stateCookie.sign(SocialProvider.GOOGLE, "st", "verifier-1"); // linkUserId 없음
        assertThat(service.callback("google", "c", "st", null, cookie).location()).contains("/auth/callback#code=");
        assertThat(confirmStore.size()).isZero();
    }

    // ---------------- exchange ----------------

    @Test
    void exchange는_코드를_한_번만_토큰으로_바꾼다() {
        User user = User.create("a@b.com", "pw", "닉네임");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        TokenResponse tokens = TokenResponse.of("A", "R");
        when(authService.issueTokens(user)).thenReturn(tokens);
        String code = codeStore.issue(42L);

        assertThat(service.exchange(code, IP)).isSameAs(tokens);
        verify(suspensionChecker).assertNotSuspended(42L);
        assertThatThrownBy(() -> service.exchange(code, IP)).isInstanceOf(SocialAuthException.class)
                .satisfies(e -> {
                    assertThat(((SocialAuthException) e).getCode()).isEqualTo("INVALID_LOGIN_CODE");
                    assertThat(((SocialAuthException) e).getStatus().value()).isEqualTo(400);
                });
    }

    @Test
    void 만료되었거나_모르는_코드는_INVALID_LOGIN_CODE() {
        String code = codeStore.issue(1L);
        clock.advance(61_000);
        assertThatThrownBy(() -> service.exchange(code, IP)).isInstanceOf(SocialAuthException.class);
        assertThatThrownBy(() -> service.exchange("never-issued", IP)).isInstanceOf(SocialAuthException.class);
        verify(authService, never()).issueTokens(any());
    }

    @Test
    void 코드_발급_뒤_정지되면_exchange가_403이고_코드는_소진된다() {
        String code = codeStore.issue(42L);
        doThrow(new AccountSuspendedException(null)).when(suspensionChecker).assertNotSuspended(eq(42L));
        assertThatThrownBy(() -> service.exchange(code, IP)).isInstanceOf(AccountSuspendedException.class);
        verify(authService, never()).issueTokens(any());
        assertThat(codeStore.consume(code)).isEmpty();
    }

    @Test
    void exchange도_IP당_분당_30회로_제한된다() {
        for (int i = 0; i < 30; i++) {
            assertThatThrownBy(() -> service.exchange("bad", IP)).hasMessageNotContaining("30회");
        }
        assertThatThrownBy(() -> service.exchange("bad", IP)).isInstanceOf(SocialAuthException.class)
                .satisfies(e -> assertThat(((SocialAuthException) e).getCode()).isEqualTo("SOCIAL_RATE_LIMITED"));
    }
}
