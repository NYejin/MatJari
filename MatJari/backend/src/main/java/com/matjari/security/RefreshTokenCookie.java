package com.matjari.security;

import com.matjari.exception.ForbiddenException;
import com.matjari.service.social.SocialProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 쿠키 {@code mj_rt}: HttpOnly, Path=/api/auth, SameSite=Strict, Max-Age=토큰 수명. Secure 는 소셜 로그인과 같은 설정
 * ({@code matjari.social.cookie-secure}, 운영에서 true 강제)을 따른다.
 * CSRF 보조 방어: 재발급·로그아웃은 {@code X-Requested-With: XMLHttpRequest} 헤더가 있을 때만 처리한다(없으면 403).
 */
@Component
public class RefreshTokenCookie {

    public static final String NAME = "mj_rt";
    public static final String PATH = "/api/auth";
    public static final String REQUIRED_HEADER = "X-Requested-With";
    public static final String REQUIRED_HEADER_VALUE = "XMLHttpRequest";

    private final SocialProperties socialProperties;
    private final long maxAgeSeconds;

    public RefreshTokenCookie(SocialProperties socialProperties,
                              @Value("${jwt.refresh-token-validity-ms}") long validityMs) {
        this.socialProperties = socialProperties;
        this.maxAgeSeconds = validityMs / 1000;
    }

    /** Set-Cookie 헤더 값(발급·회전). */
    public String create(String token) {
        return build(token, maxAgeSeconds);
    }

    /** Set-Cookie 헤더 값(삭제). */
    public String clear() {
        return build("", 0);
    }

    private String build(String value, long maxAge) {
        return NAME + "=" + value + "; Path=" + PATH + "; Max-Age=" + maxAge + "; HttpOnly; SameSite=Strict"
                + (socialProperties.isCookieSecure() ? "; Secure" : "");
    }

    /** 요청 쿠키의 토큰 원문. 없으면 null. */
    public String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }

    /** 재발급·로그아웃 CSRF 보조 검사. 헤더가 없거나 값이 다르면 403. */
    public void requireAjaxHeader(HttpServletRequest request) {
        if (!REQUIRED_HEADER_VALUE.equals(request.getHeader(REQUIRED_HEADER))) {
            throw new ForbiddenException("허용되지 않은 요청입니다.");
        }
    }
}
