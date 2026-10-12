package com.seatswap.service.social;

import com.seatswap.domain.SocialProvider;
import com.seatswap.domain.User;
import com.seatswap.dto.response.TokenResponse;
import com.seatswap.exception.AccountSuspendedException;
import com.seatswap.repository.UserRepository;
import com.seatswap.security.AccountSuspensionChecker;
import com.seatswap.service.AuthService;
import com.seatswap.service.social.SocialLoginFailure.SocialError;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 소셜 로그인 흐름(백엔드가 처리하는 Authorization Code + PKCE). authorize -> callback -> 일회용 코드 -> exchange.
 * 리다이렉트 목적지는 설정(frontend-base-url)으로 고정되고 요청 파라미터로 받지 않는다(오픈 리다이렉트 불가).
 * 로그·예외에 code/token/secret/이메일을 남기지 않는다(제공자 이름과 오류 종류만).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialAuthService {

    /** 제공자로 보낼 302 Location 과 심을 Set-Cookie 값. */
    public record AuthorizeResult(String location, String setCookie) {
    }

    /** 프런트로 보낼 302 Location 과 (항상) 쿠키 삭제 Set-Cookie 값. */
    public record CallbackResult(String location, String clearCookie) {
    }

    private final SocialProperties properties;
    private final SocialStateCookie stateCookie;
    private final SocialProviderClient providerClient;
    private final SocialLoginService loginService;
    private final SocialLoginCodeStore codeStore;
    private final SocialRateLimiter rateLimiter;
    private final AuthService authService;
    private final UserRepository userRepository;
    private final AccountSuspensionChecker suspensionChecker;
    private final SecureRandom random = new SecureRandom();
    /** 제공자 token·userinfo 호출 동시 실행 상한(초과는 기다리지 않고 SERVER_BUSY). */
    private final Semaphore providerCalls = new Semaphore(8);
    /** 이미 콜백을 통과한 state(쿠키는 무상태라 10분 안에는 재사용될 수 있어 서버가 한 번만 받는다). 만료 키는 가끔 쓸어 낸다. */
    private final ConcurrentHashMap<String, Long> usedStates = new ConcurrentHashMap<>();
    private static final long USED_STATE_TTL_MILLIS = 11 * 60_000L;
    private static final int USED_STATE_MAX = 10_000;

    /** 활성 제공자 경로값(`["kakao","google"]` 순서는 enum 순서). */
    public List<String> enabledProviders() {
        return properties.enabledProviderPaths();
    }

    /** 1단계: state·PKCE verifier 를 만들어 서명 쿠키에 담고 제공자 authorize URL 을 돌려준다. */
    public AuthorizeResult authorize(String providerPath, String clientIp) {
        rateLimiter.acquire("a:" + clientIp);
        SocialProvider provider = enabledProvider(providerPath);
        SocialProperties.Provider cfg = properties.of(provider);

        String state = randomToken();
        String verifier = randomToken();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", cfg.getClientId());
        params.put("redirect_uri", properties.redirectUri(provider));
        String scope = switch (provider) {
            case GOOGLE -> "openid email profile";
            case KAKAO -> "profile_nickname account_email";
            case NAVER -> null; // 네이버는 scope 파라미터 없이(앱 설정으로 결정)
        };
        if (scope != null) {
            params.put("scope", scope);
        }
        params.put("state", state);
        if (provider.supportsPkce()) {
            params.put("code_challenge", SocialPkce.challenge(verifier));
            params.put("code_challenge_method", "S256");
        }
        String base = cfg.getAuthorizeUri();
        String location = base + (base.contains("?") ? "&" : "?") + encode(params);
        return new AuthorizeResult(location, stateCookie.setCookieHeader(stateCookie.sign(provider, state, verifier)));
    }

    /**
     * 콜백. 항상 프런트로 302(성공은 /auth/callback#code=..., 실패는 /login?socialError=..) 하고 state 쿠키를 지운다.
     * 비활성/알 수 없는 제공자만 SocialAuthException(404). 쿠키 검증이 끝나기 전에는 제공자에게 아무것도 요청하지 않는다.
     */
    /** 콜백 IP 속도 제한을 넘었는가(authorize·exchange 와 버킷을 나눈다). 넘으면 제공자에게 아무것도 요청하지 않고 {@link #busyResult()} 로 돌려보낸다. */
    public boolean callbackRateLimited(String clientIp) {
        try {
            rateLimiter.acquire("c:" + clientIp);
            return false;
        } catch (SocialAuthException e) {
            return true;
        }
    }

    public CallbackResult busyResult() {
        return new CallbackResult(failureLocation(SocialError.SERVER_BUSY, null), stateCookie.clearCookieHeader());
    }

    public CallbackResult callback(String providerPath, String code, String state, String error, String cookieValue) {
        SocialProvider provider = enabledProvider(providerPath);
        String clear = stateCookie.clearCookieHeader();
        try {
            // 1) 쿠키 검증(서명·만료·provider)과 state 상수시간 비교. 실패하면 코드 교환을 시도하지 않는다
            SocialStateCookie.Verified verified = stateCookie.verify(cookieValue, provider)
                    .orElseThrow(() -> new SocialLoginFailure(SocialError.INVALID_STATE));
            if (!SocialStateCookie.constantTimeEquals(verified.state(), state)) {
                throw new SocialLoginFailure(SocialError.INVALID_STATE);
            }
            // 같은 state 는 한 번만(쿠키를 훔쳐 코드 교환을 반복 유발하는 것을 막는다)
            markStateUsed(verified.state());
            // 2) 사용자가 동의를 거부하는 등 제공자가 error 를 돌려준 경우
            if (error != null && !error.isBlank()) {
                throw new SocialLoginFailure(SocialError.ACCESS_DENIED);
            }
            if (code == null || code.isBlank()) {
                throw new SocialLoginFailure(SocialError.PROVIDER_ERROR);
            }
            // 3) code -> access_token -> userinfo
            SocialProfile profile;
            if (!providerCalls.tryAcquire()) {
                throw new SocialLoginFailure(SocialError.SERVER_BUSY);
            }
            try {
                String accessToken = providerClient.exchangeCode(provider, code, verified.verifier(), verified.state());
                profile = providerClient.fetchProfile(provider, accessToken);
            } catch (SocialProviderClient.ProviderException e) {
                log.warn("Social provider call failed (provider: {}, kind: {})", provider, e.getMessage());
                throw new SocialLoginFailure(SocialError.PROVIDER_ERROR);
            } finally {
                providerCalls.release();
            }
            // 4) 사용자 결정 + 일회용 로그인 코드
            Long userId = loginService.loginOrSignup(provider, profile);
            String loginCode = codeStore.issue(userId);
            return new CallbackResult(properties.frontendBase() + "/auth/callback#code=" + loginCode, clear);
        } catch (SocialLoginFailure f) {
            log.info("Social login failed (provider: {}, error: {})", provider, f.getError());
            return new CallbackResult(failureLocation(f.getError(), null), clear);
        } catch (AccountSuspendedException e) {
            Object until = e.toBody().get("until");
            return new CallbackResult(failureLocation(SocialError.ACCOUNT_SUSPENDED, until == null ? "" : until.toString()), clear);
        } catch (RuntimeException e) {
            // 분류하지 못한 오류(DB 장애·무결성 위반 등)도 JSON 이 아니라 로그인 화면으로 돌려보내고 쿠키를 지운다. 값은 로그에 남기지 않는다
            log.warn("Social login callback failed (provider: {}, type: {})", provider, e.getClass().getSimpleName());
            return new CallbackResult(failureLocation(SocialError.SERVER_BUSY, null), clear);
        }
    }

    private void markStateUsed(String state) {
        long now = System.currentTimeMillis();
        if (usedStates.size() >= USED_STATE_MAX) {
            usedStates.entrySet().removeIf(e -> e.getValue() <= now);
            if (usedStates.size() >= USED_STATE_MAX) {
                throw new SocialLoginFailure(SocialError.SERVER_BUSY);
            }
        }
        if (usedStates.putIfAbsent(state, now + USED_STATE_TTL_MILLIS) != null) {
            throw new SocialLoginFailure(SocialError.INVALID_STATE);
        }
    }

    /** 3단계: 일회용 코드를 토큰으로 바꾼다. 틀리거나 만료·사용된 코드는 400 INVALID_LOGIN_CODE, 정지는 403. */
    public TokenResponse exchange(String loginCode, String clientIp) {
        rateLimiter.acquire("x:" + clientIp);
        Long userId = codeStore.consume(loginCode).orElseThrow(SocialAuthException::invalidLoginCode);
        suspensionChecker.assertNotSuspended(userId); // 코드 발급 뒤에 정지됐을 수 있어 다시 검사한다
        User user = userRepository.findById(userId).orElseThrow(SocialAuthException::invalidLoginCode);
        return authService.issueTokens(user);
    }

    private SocialProvider enabledProvider(String providerPath) {
        SocialProvider provider = SocialProvider.fromPath(providerPath).orElseThrow(SocialAuthException::providerDisabled);
        if (!properties.isEnabled(provider)) {
            throw SocialAuthException.providerDisabled();
        }
        return provider;
    }

    private String failureLocation(SocialError error, String until) {
        String location = properties.frontendBase() + "/login?socialError=" + error.name();
        if (until != null) {
            location += "&until=" + URLEncoder.encode(until, StandardCharsets.UTF_8);
        }
        return location;
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
