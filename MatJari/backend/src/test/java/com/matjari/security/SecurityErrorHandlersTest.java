package com.matjari.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityErrorHandlersTest {

    private final SecurityErrorResponseWriter writer = new SecurityErrorResponseWriter(new ObjectMapper());

    @Test
    void entryPointWrites401Json() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new JwtAuthenticationEntryPoint(writer).commence(
                new MockHttpServletRequest(), response, new InsufficientAuthenticationException("x"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"로그인이 필요합니다.\"}");
    }

    @Test
    void accessDeniedHandlerWrites403Json() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new JwtAccessDeniedHandler(writer).handle(
                new MockHttpServletRequest(), response, new AccessDeniedException("x"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"접근 권한이 없습니다.\"}");
    }

    @Test
    void writerKeepsHeadersAlreadySetByCorsFilter() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader("Access-Control-Allow-Origin", "http://localhost:5173");

        writer.write(response, 401, "로그인이 필요합니다.");

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
    }
}
