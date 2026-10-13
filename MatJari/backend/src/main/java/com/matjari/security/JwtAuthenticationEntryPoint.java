package com.matjari.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증되지 않은 요청(토큰 없음/형식·서명 오류/만료/refresh token 사용/사용자 없음)이
 * 보호된 API에 접근하면 401 {"message":"로그인이 필요합니다."}로 응답한다.
 * 토큰 문제의 세부 사유는 응답에 노출하지 않는다 (프론트는 401이면 refresh 시도 → 실패 시 로그인 화면).
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String MESSAGE = "로그인이 필요합니다.";

    private final SecurityErrorResponseWriter writer;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        writer.write(response, HttpServletResponse.SC_UNAUTHORIZED, MESSAGE);
    }
}
