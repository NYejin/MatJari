package com.seatswap.security;

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
 * JWT access/refresh 토큰 발급 및 검증.
 * access/refresh는 "type" 클레임으로 구분한다 (별도 테이블로 refresh token을 관리하지 않는
 * stateless 방식 — 포트폴리오 범위에서는 충분하며, 추후 로그아웃 시 즉시 폐기가 필요해지면
 * refresh token을 DB/Redis에 저장하는 방식으로 교체 가능).
 */
@Component
public class JwtTokenProvider {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessTokenValidityMs;
    private final long refreshTokenValidityMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity-ms}") long accessTokenValidityMs,
            @Value("${jwt.refresh-token-validity-ms}") long refreshTokenValidityMs
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidityMs = accessTokenValidityMs;
        this.refreshTokenValidityMs = refreshTokenValidityMs;
    }

    public String generateAccessToken(Long userId, String email) {
        return buildToken(userId, email, TYPE_ACCESS, accessTokenValidityMs);
    }

    public String generateRefreshToken(Long userId, String email) {
        return buildToken(userId, email, TYPE_REFRESH, refreshTokenValidityMs);
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
