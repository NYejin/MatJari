package com.matjari.service.social;

import com.matjari.domain.SocialProvider;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;

/**
 * 인증 시작 때 심는 HttpOnly 쿠키 `ss_oauth`. 값 = base64url(provider|state|verifier|linkUserId(로그인 모드는 빈 칸)|만료epochMs) + "." + base64url(HMAC-SHA256).
 * 4칸짜리 구 포맷은 무효로 처리한다.
 * 서명(상수시간 비교)·만료·provider 일치를 모두 통과해야 {@link Verified} 를 돌려준다. 서버에 세션을 두지 않으려는 방식이다.
 * state/verifier 는 URL-safe Base64 라 '|' 를 포함하지 않는다.
 */
@Component
public class SocialStateCookie {

    public static final String NAME = "ss_oauth";
    static final String PATH = "/api/auth/social";
    static final int MAX_AGE_SECONDS = 600;
    private static final int MAX_VALUE_LENGTH = 1024;

    /** 검증을 통과한 쿠키 내용. */
    public record Verified(String state, String verifier, Long linkUserId) {
        public Verified(String state, String verifier) {
            this(state, verifier, null);
        }
    }

    private final SocialProperties properties;
    private final Clock clock;

    public SocialStateCookie(SocialProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 서명된 쿠키 값. */
    public String sign(SocialProvider provider, String state, String verifier) {
        return sign(provider, state, verifier, null);
    }

    /** linkUserId 가 있으면 '계정 연결' 모드 쿠키(서명 대상에 포함되어 위조할 수 없다). 로그인 모드는 null(빈 칸). */
    public String sign(SocialProvider provider, String state, String verifier, Long linkUserId) {
        long expiresAt = clock.millis() + MAX_AGE_SECONDS * 1000L;
        String payload = provider.name() + "|" + state + "|" + verifier + "|"
                + (linkUserId == null ? "" : linkUserId.toString()) + "|" + expiresAt;
        String encoded = b64(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + b64(hmac(encoded));
    }

    /** 서명·만료·provider 를 검사한다. 하나라도 어긋나면 빈 값(이유는 구분하지 않는다). */
    public Optional<Verified> verify(String cookieValue, SocialProvider provider) {
        if (cookieValue == null || cookieValue.isEmpty() || cookieValue.length() > MAX_VALUE_LENGTH) {
            return Optional.empty();
        }
        int dot = cookieValue.indexOf('.');
        if (dot <= 0 || dot != cookieValue.lastIndexOf('.')) {
            return Optional.empty();
        }
        String encoded = cookieValue.substring(0, dot);
        byte[] givenSig;
        byte[] payloadBytes;
        try {
            givenSig = Base64.getUrlDecoder().decode(cookieValue.substring(dot + 1));
            payloadBytes = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(givenSig, hmac(encoded))) {
            return Optional.empty();
        }
        String[] parts = new String(payloadBytes, StandardCharsets.UTF_8).split("\\|", -1);
        if (parts.length != 5) { // 4칸짜리 구 포맷은 무효
            return Optional.empty();
        }
        long expiresAt;
        Long linkUserId = null;
        try {
            expiresAt = Long.parseLong(parts[4]);
            if (!parts[3].isEmpty()) {
                linkUserId = Long.parseLong(parts[3]);
                if (linkUserId <= 0) {
                    return Optional.empty();
                }
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (clock.millis() >= expiresAt) {
            return Optional.empty();
        }
        if (!provider.name().equals(parts[0]) || parts[1].isEmpty() || parts[2].isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Verified(parts[1], parts[2], linkUserId));
    }

    /**
     * 서명 검증 없이 쿠키 값의 'linkUserId 칸(5칸 중 4번째)이 양의 정수인가'만 본다. 실패 리다이렉트 목적지(/me 또는 /login)를
     * 고르는 용도일 뿐 보안 판단에 쓰지 않는다(목적지는 둘 다 설정으로 고정된 경로).
     */
    public static boolean looksLikeLinkMode(String cookieValue) {
        if (cookieValue == null || cookieValue.isEmpty() || cookieValue.length() > MAX_VALUE_LENGTH) {
            return false;
        }
        try {
            int dot = cookieValue.indexOf('.');
            String encoded = dot < 0 ? cookieValue : cookieValue.substring(0, dot);
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
            return parts.length == 5 && !parts[3].isEmpty() && Long.parseLong(parts[3]) > 0;
        } catch (IllegalArgumentException e) { // Base64 오류·NumberFormatException
            return false;
        }
    }

    /** 두 문자열을 상수시간으로 비교한다(null 이면 false). */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public String setCookieHeader(String value) {
        return header(value, MAX_AGE_SECONDS);
    }

    public String clearCookieHeader() {
        return header("", 0);
    }

    private String header(String value, int maxAge) {
        return NAME + "=" + value + "; Max-Age=" + maxAge + "; Path=" + PATH + "; HttpOnly; SameSite=Lax"
                + (properties.isCookieSecure() ? "; Secure" : "");
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getStateSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 를 사용할 수 없습니다.", e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
