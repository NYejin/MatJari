package com.matjari.service.social;

import com.matjari.domain.SocialProvider;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 소셜 로그인 설정(matjari.social.*). client-id 가 비어 있는 제공자는 비활성이다.
 * 엔드포인트 기본값은 실제 제공자 값이고, 설정(주로 테스트의 스텁 서버)으로만 덮어쓸 수 있다. 사용자 입력 URL 은 절대 요청하지 않는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "matjari.social")
public class SocialProperties {

    /** 제공자가 돌려보낼 백엔드 주소(리다이렉트 URI = 이 값 + /api/auth/social/{provider}/callback). */
    private String backendBaseUrl = "http://localhost:8080";
    /** 로그인 결과를 돌려보낼 프런트 주소(목적지 고정 — 오픈 리다이렉트 방지). allowed-origins 중 하나여야 한다. */
    private String frontendBaseUrl = "http://localhost:5173";
    /** state 쿠키 HMAC 키. 운영에서는 반드시 환경변수로 덮어쓴다. */
    static final String DEV_STATE_SECRET = "dev-only-insecure-social-state-secret-please-override-0123456789";
    private String stateSecret = DEV_STATE_SECRET;
    private boolean cookieSecure = false;
    private long connectTimeoutMs = 3_000;
    private long readTimeoutMs = 5_000;
    private int maxBodyBytes = 64 * 1024;
    private int ipPerMinute = 30;
    private long loginCodeTtlSeconds = 60;
    private int loginCodeMax = 10_000;
    /** 계정 연결 시작(POST /api/users/me/social/{provider}/link)의 사용자당 분당 한도. */
    private int linkPerMinute = 10;

    private Provider google = new Provider("https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token", "https://openidconnect.googleapis.com/v1/userinfo");
    private Provider kakao = new Provider("https://kauth.kakao.com/oauth/authorize",
            "https://kauth.kakao.com/oauth/token", "https://kapi.kakao.com/v2/user/me");
    private Provider naver = new Provider("https://nid.naver.com/oauth2.0/authorize",
            "https://nid.naver.com/oauth2.0/token", "https://openapi.naver.com/v1/nid/me");

    @Getter
    @Setter
    public static class Provider {
        private String clientId = "";
        private String clientSecret = "";
        private String authorizeUri;
        private String tokenUri;
        private String userinfoUri;

        public Provider() {
        }

        public Provider(String authorizeUri, String tokenUri, String userinfoUri) {
            this.authorizeUri = authorizeUri;
            this.tokenUri = tokenUri;
            this.userinfoUri = userinfoUri;
        }

        public boolean isEnabled() {
            return clientId != null && !clientId.isBlank();
        }

        // toString 에 secret 이 실리지 않도록 직접 정의(Lombok 은 toString 을 만들지 않는다)
        @Override
        public String toString() {
            return "Provider[enabled=" + isEnabled() + "]";
        }
    }

    public Provider of(SocialProvider provider) {
        return switch (provider) {
            case GOOGLE -> google;
            case KAKAO -> kakao;
            case NAVER -> naver;
        };
    }

    public boolean isEnabled(SocialProvider provider) {
        return of(provider).isEnabled();
    }

    /** 활성 제공자 경로값 목록(authorize 버튼 표시용). */
    public List<String> enabledProviderPaths() {
        List<String> result = new ArrayList<>();
        for (SocialProvider p : SocialProvider.values()) {
            if (isEnabled(p)) {
                result.add(p.getPath());
            }
        }
        return result;
    }

    public boolean anyEnabled() {
        return !enabledProviderPaths().isEmpty();
    }

    /** 끝의 '/' 를 뗀 백엔드 주소. */
    public String backendBase() {
        return stripTrailingSlash(backendBaseUrl);
    }

    /** 끝의 '/' 를 뗀 프런트 주소. */
    public String frontendBase() {
        return stripTrailingSlash(frontendBaseUrl);
    }

    public String redirectUri(SocialProvider provider) {
        return backendBase() + "/api/auth/social/" + provider.getPath() + "/callback";
    }

    private static String stripTrailingSlash(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private static boolean isLoopbackHost(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host != null && LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 시작 시 검증. 활성 제공자가 하나도 없으면 건너뛴다(기존 환경을 깨지 않는다).
     * 위반하면 IllegalStateException 으로 기동을 막는다. 메시지에는 secret 값을 싣지 않는다.
     *
     * @param allowedOrigins matjari.websocket.allowed-origins(이미 WebSocketConfig.validatedOrigins 로 검증된 값)
     */
    public void validate(String[] allowedOrigins) {
        if (!anyEnabled()) {
            return;
        }
        if (stateSecret == null || stateSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("matjari.social.state-secret 은 32바이트 이상이어야 합니다.");
        }
        if ((cookieSecure || !isLoopbackHost(backendBase())) && DEV_STATE_SECRET.equals(stateSecret)) {
            throw new IllegalStateException("운영(cookie-secure=true)에서는 matjari.social.state-secret 의 개발용 기본값을 쓸 수 없습니다.");
        }
        URI backend = requireHttpOrigin("matjari.social.backend-base-url", backendBase());
        requireHttpOrigin("matjari.social.frontend-base-url", frontendBase());
        boolean allowed = false;
        for (String origin : allowedOrigins) {
            if (stripTrailingSlash(origin).equals(frontendBase())) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new IllegalStateException(
                    "matjari.social.frontend-base-url 은 matjari.websocket.allowed-origins 중 하나여야 합니다: " + frontendBase());
        }
        if ("https".equals(backend.getScheme()) && !cookieSecure) {
            throw new IllegalStateException("backend-base-url 이 https 이면 matjari.social.cookie-secure=true 여야 합니다.");
        }
        for (SocialProvider p : SocialProvider.values()) {
            Provider cfg = of(p);
            if (!cfg.isEnabled()) {
                continue;
            }
            requireEndpoint(p, "authorize-uri", cfg.getAuthorizeUri());
            requireEndpoint(p, "token-uri", cfg.getTokenUri());
            requireEndpoint(p, "userinfo-uri", cfg.getUserinfoUri());
            if (p == SocialProvider.GOOGLE && (cfg.getClientSecret() == null || cfg.getClientSecret().isBlank())) {
                throw new IllegalStateException("matjari.social.google.client-secret 이 필요합니다.");
            }
        }
    }

    /** http(s)://host[:port] 형태(경로·쿼리·프래그먼트·userinfo 없음)만 허용한다. */
    private static URI requireHttpOrigin(String key, String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(key + " 형식이 올바르지 않습니다.");
        }
        String scheme = uri.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            throw new IllegalStateException(key + " 는 http(s)://host[:port] 형태여야 합니다.");
        }
        return uri;
    }

    /** 엔드포인트는 https 만(개발 스텁을 위해 루프백 호스트는 http 허용). */
    private static void requireEndpoint(SocialProvider p, String key, String value) {
        URI uri;
        try {
            uri = URI.create(value == null ? "" : value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("matjari.social." + p.getPath() + "." + key + " 형식이 올바르지 않습니다.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean ok = uri.getUserInfo() == null && uri.getRawFragment() == null && !host.isEmpty()
                && ("https".equals(uri.getScheme())
                || ("http".equals(uri.getScheme()) && LOOPBACK.contains(host)));
        if (!ok) {
            throw new IllegalStateException("matjari.social." + p.getPath() + "." + key + " 는 https URL 이어야 합니다.");
        }
    }
}
