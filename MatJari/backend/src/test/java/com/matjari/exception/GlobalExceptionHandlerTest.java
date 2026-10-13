package com.matjari.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsStandardClientErrorsToKoreanMessages() {
        assertResponse(handler.handleException(new HttpRequestMethodNotSupportedException("GET")),
                405, "지원하지 않는 요청 방식입니다.");
        assertResponse(handler.handleException(new NoResourceFoundException(HttpMethod.GET, "x")),
                404, "요청한 리소스를 찾을 수 없습니다.");
        assertResponse(handler.handleException(new HttpMediaTypeNotSupportedException("text/plain")),
                415, "지원하지 않는 형식입니다.");
        assertResponse(handler.handleException(new ResponseStatusException(HttpStatus.CONFLICT, "english detail")),
                409, "잘못된 요청입니다.");
    }

    @Test
    void keepsServerErrorStatusOfErrorResponse() {
        assertResponse(handler.handleException(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)),
                503, "서버 오류가 발생했습니다.");
    }

    @Test
    void unknownExceptionIs500WithoutDetails() {
        assertResponse(handler.handleException(new IllegalStateException("secret internals")),
                500, "서버 오류가 발생했습니다.");
    }

    @Test
    void securityExceptionsAreRethrown() {
        AccessDeniedException denied = new AccessDeniedException("no");
        BadCredentialsException bad = new BadCredentialsException("no");
        assertThatThrownBy(() -> handler.rethrowSecurityException(denied)).isSameAs(denied);
        assertThatThrownBy(() -> handler.rethrowSecurityException(bad)).isSameAs(bad);
    }

    @Test
    void fieldValidationExceptionKeepsFieldFormat() {
        ResponseEntity<Map<String, String>> res =
                handler.handleFieldValidationException(new FieldValidationException("email", "이미 가입된 이메일입니다."));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).containsExactly(Map.entry("email", "이미 가입된 이메일입니다."));
    }

    private static void assertResponse(ResponseEntity<Map<String, String>> res, int status, String message) {
        assertThat(res.getStatusCode().value()).isEqualTo(status);
        assertThat(res.getBody()).containsExactly(Map.entry("message", message));
    }
}
