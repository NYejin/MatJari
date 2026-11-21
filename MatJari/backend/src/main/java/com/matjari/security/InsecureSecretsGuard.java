package com.matjari.security;

import com.matjari.service.EmailVerificationProperties;
import com.matjari.service.social.SocialProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 개발용 기본 비밀값(JWT 서명 키, 이메일 인증번호 해시 키)으로 운영 기동하는 것을 막는다. 백엔드 주소
 * (matjari.social.backend-base-url)가 루프백이 아니면 운영으로 보고 개발용 기본값·예시값이 그대로일 때 기동을 거부한다.
 * 이메일 인증번호 키는 인증이 켜져 있을 때만 검사한다(꺼져 있으면 쓰이지 않는다). 메시지에 비밀값은 싣지 않는다.
 */
@Component
public class InsecureSecretsGuard {

    static final Set<String> REJECTED_JWT = Set.of(
            "dev-only-insecure-default-secret-please-override-with-JWT-SECRET-env",
            "changeme-dev-secret");
    static final Set<String> REJECTED_EMAIL_CODE = Set.of(
            "dev-only-insecure-email-code-secret-please-override");
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    private final String jwtSecret;
    private final EmailVerificationProperties emailProperties;
    private final SocialProperties socialProperties;

    public InsecureSecretsGuard(@Value("${jwt.secret}") String jwtSecret,
                                EmailVerificationProperties emailProperties,
                                SocialProperties socialProperties) {
        this.jwtSecret = jwtSecret;
        this.emailProperties = emailProperties;
        this.socialProperties = socialProperties;
    }

    @PostConstruct
    void validate() {
        check(jwtSecret, emailProperties.getSecret(), emailProperties.isEnabled(), socialProperties.backendBase());
    }

    /** 위반이면 IllegalStateException. 단위 테스트가 직접 부른다. */
    static void check(String jwtSecret, String emailCodeSecret, boolean emailVerificationEnabled, String backendBaseUrl) {
        if (isLoopback(backendBaseUrl)) {
            return;
        }
        if (jwtSecret == null || REJECTED_JWT.contains(jwtSecret.trim())) {
            throw new IllegalStateException("운영(루프백이 아닌 백엔드 주소)에서는 jwt.secret(JWT_SECRET)의 개발용 기본값·예시값을 쓸 수 없습니다.");
        }
        if (emailVerificationEnabled && (emailCodeSecret == null || REJECTED_EMAIL_CODE.contains(emailCodeSecret.trim()))) {
            throw new IllegalStateException("운영(루프백이 아닌 백엔드 주소)에서는 이메일 인증번호 키(MATJARI_EMAIL_CODE_SECRET)의 개발용 기본값을 쓸 수 없습니다.");
        }
    }

    private static boolean isLoopback(String baseUrl) {
        try {
            String host = URI.create(baseUrl == null ? "" : baseUrl).getHost();
            return host != null && LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
