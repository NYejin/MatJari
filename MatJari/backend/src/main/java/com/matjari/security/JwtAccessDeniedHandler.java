package com.matjari.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 인증은 됐지만 권한이 없는 요청에 403 {"message":"접근 권한이 없습니다."}로 응답한다.
 * (익명 사용자의 AccessDeniedException은 ExceptionTranslationFilter가 EntryPoint(401)로 보낸다.)
 */
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    static final String MESSAGE = "접근 권한이 없습니다.";

    private final SecurityErrorResponseWriter writer;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        writer.write(response, HttpServletResponse.SC_FORBIDDEN, MESSAGE);
    }
}
