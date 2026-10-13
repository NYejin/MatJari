package com.matjari.service.social;

import com.matjari.exception.MatJariException;
import org.springframework.http.HttpStatus;

/**
 * 소셜 로그인 REST 오류(리다이렉트가 아니라 JSON 으로 답하는 경우): 404 SOCIAL_PROVIDER_DISABLED, 400 INVALID_LOGIN_CODE,
 * 429 SOCIAL_RATE_LIMITED. 응답 {code, message}(429 는 retryAfterSeconds 와 Retry-After 헤더도) 는 GlobalExceptionHandler 가 만든다.
 */
public class SocialAuthException extends MatJariException {

    private final HttpStatus status;
    private final String code;
    private final long retryAfterSeconds;

    public SocialAuthException(HttpStatus status, String code, String message) {
        this(status, code, message, 0);
    }

    public SocialAuthException(HttpStatus status, String code, String message, long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static SocialAuthException providerDisabled() {
        return new SocialAuthException(HttpStatus.NOT_FOUND, "SOCIAL_PROVIDER_DISABLED", "사용할 수 없는 소셜 로그인입니다.");
    }

    public static SocialAuthException invalidLoginCode() {
        return new SocialAuthException(HttpStatus.BAD_REQUEST, "INVALID_LOGIN_CODE", "로그인 코드가 올바르지 않거나 만료되었어요.");
    }

    public static SocialAuthException invalidLinkCode() {
        return new SocialAuthException(HttpStatus.BAD_REQUEST, "INVALID_LINK_CODE", "연결 확인 코드가 올바르지 않거나 만료되었어요.");
    }

    public static SocialAuthException alreadyLinked() {
        return new SocialAuthException(HttpStatus.CONFLICT, "SOCIAL_ALREADY_LINKED", "이미 연결된 소셜 계정이에요.");
    }

    public static SocialAuthException linkedOther() {
        return new SocialAuthException(HttpStatus.CONFLICT, "SOCIAL_LINKED_OTHER", "이미 다른 계정에 연결된 소셜 계정이에요.");
    }

    public static SocialAuthException rateLimited(long retryAfterSeconds) {
        return new SocialAuthException(HttpStatus.TOO_MANY_REQUESTS, "SOCIAL_RATE_LIMITED",
                "요청이 너무 많아요. 잠시 후 다시 시도해주세요.", retryAfterSeconds);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
