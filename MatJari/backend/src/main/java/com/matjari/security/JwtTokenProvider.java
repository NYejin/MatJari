package com.matjari.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT access/withdraw 토큰 발급 및 검증. (리프레시 토큰은 V27부터 불투명 토큰 + 서버 저장이라 RefreshTokenService 가 맡는다. 아래 generateRefreshToken 은 더 이상 발급에 쓰지 않으며 필터가 옛 refresh 형식 JWT 를 거절하는 테스트용으로만 남아 있다.)
 * access/withdraw 는 "type" 클레임으로 구분하는 무상태 JWT 다(서버에 저장하지 않는다). 즉시 폐기가 필요한 리프레시 토큰은 이 클래스가 아니라
 * 서버에 해시로 저장·회전하는 {@code RefreshTokenService}(V27)가 다룬다.
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";
    private static final String TYPE_WITHDRAW = "withdraw";

    private final SecretKey key;
    private final long accessTokenValidityMs;
    private final long refreshTokenValidityMs;
    private final long withdrawTokenValidityMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity-ms}") long accessTokenValidityMs,
            @Value("${jwt.refresh-token-validity-ms}") long refreshTokenValidityMs,
            @Value("${jwt.withdraw-token-validity-ms:600000}") long withdrawTokenValidityMs
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidityMs = accessTokenValidityMs;
        this.refreshTokenValidityMs = refreshTokenValidityMs;
        this.withdrawTokenValidityMs = withdrawTokenValidityMs;
    }

    public String generateAccessToken(Long userId, String email) {
        return buildToken(userId, email, TYPE_ACCESS, accessTokenValidityMs);
    }

    public String generateRefreshToken(Long userId, String email) {
        return buildToken(userId, email, TYPE_REFRESH, refreshTokenValidityMs);
    }

    /**
     * 이용 정지 중인 계정이 비밀번호를 확인한 뒤 받는 탈퇴 전용 토큰(기본 10분). 회원탈퇴(DELETE /api/users/me) 외의 어떤 API·STOMP 에도 쓸 수 없다.
     * JwtAuthenticationFilter 가 이 요청에서만 인증으로 인정하고, 재발급(refresh)·STOMP 는 type 이 달라 거부한다.
     */
    public String generateWithdrawToken(Long userId, String email) {
        return buildToken(userId, email, TYPE_WITHDRAW, withdrawTokenValidityMs);
    }

    private String buildToken(Long userId, String email, String type, long validityMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + validityMs);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_TYPE, type)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    /** 서명/만료만 검증한다. 유효하지 않으면 false (예외를 밖으로 던지지 않음). */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isWithdrawToken(String token) {
        return TYPE_WITHDRAW.equals(parseClaims(token).get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(String token) {
        return TYPE_REFRESH.equals(parseClaims(token).get(CLAIM_TYPE, String.class));
    }

    public Long getUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    /** 토큰 만료 시각(STOMP 세션이 토큰보다 오래 살지 않게 하는 데 쓴다). 검증된 토큰에만 호출한다. */
    public java.time.Instant getExpiration(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    public String getEmail(String token) {
        return parseClaims(token).get(CLAIM_EMAIL, String.class);
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
