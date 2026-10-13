package com.matjari.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 토큰 종류(access·refresh·withdraw)는 type 클레임으로 구분되고 서로 바꿔 쓸 수 없다. */
class JwtTokenProviderTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-unit-test-secret-0123456789";

    private final JwtTokenProvider provider = new JwtTokenProvider(SECRET, 3_600_000L, 1_209_600_000L, 600_000L);

    @Test
    void 탈퇴_전용_토큰은_액세스도_리프레시도_아니다() {
        String withdraw = provider.generateWithdrawToken(7L, "a@b.com");

        assertThat(provider.validateToken(withdraw)).isTrue();
        assertThat(provider.isWithdrawToken(withdraw)).isTrue();
        assertThat(provider.isRefreshToken(withdraw)).isFalse();
        assertThat(provider.getUserId(withdraw)).isEqualTo(7L);
    }

    @Test
    void 액세스와_리프레시_토큰은_탈퇴_전용이_아니다() {
        assertThat(provider.isWithdrawToken(provider.generateAccessToken(7L, "a@b.com"))).isFalse();
        assertThat(provider.isWithdrawToken(provider.generateRefreshToken(7L, "a@b.com"))).isFalse();
    }

    @Test
    void 탈퇴_전용_토큰은_설정한_짧은_시간_뒤에_만료된다() {
        Instant expiresAt = provider.getExpiration(provider.generateWithdrawToken(7L, "a@b.com"));

        assertThat(Duration.between(Instant.now(), expiresAt)).isBetween(Duration.ofMinutes(9), Duration.ofMinutes(11));
    }
}
