package com.matjari.dto.response;

/**
 * 로그인·재발급 응답. 리프레시 토큰은 HttpOnly 쿠키(mj_rt)로만 전달하므로 {@code refreshToken} 은 항상 null 이다.
 * 필드는 호환을 위해 남겼으며 폐기 예정이다(다음 정리 때 제거).
 */
public record TokenResponse(String accessToken, String refreshToken, String tokenType) {
    public static TokenResponse of(String accessToken) {
        return new TokenResponse(accessToken, null, "Bearer");
    }
}
