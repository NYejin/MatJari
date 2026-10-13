package com.matjari.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matjari.exception.AccountSuspendedException;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Security 필터 단계(컨트롤러 도달 전)의 401/403 응답을 GlobalExceptionHandler와 같은
 * {"message": ...} JSON 포맷으로 작성한다.
 * response.reset()을 호출하지 않으므로 앞단 CorsFilter가 붙인 CORS 헤더가 그대로 유지된다.
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {

    static final String CONTENT_TYPE = "application/json;charset=UTF-8";

    private final ObjectMapper objectMapper;

    public void write(HttpServletResponse response, int status, String message) throws IOException {
        writeBody(response, status, Map.of("message", message));
    }

    /** 이용 정지: 403 {"message","code":"ACCOUNT_SUSPENDED","until"} — GlobalExceptionHandler 와 같은 본문. */
    public void writeSuspended(HttpServletResponse response, AccountSuspendedException e) throws IOException {
        writeBody(response, HttpServletResponse.SC_FORBIDDEN, e.toBody());
    }

    private void writeBody(HttpServletResponse response, int status, Map<String, ?> body) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(CONTENT_TYPE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
