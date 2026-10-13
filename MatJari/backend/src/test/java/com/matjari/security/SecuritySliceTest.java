package com.matjari.security;

import com.matjari.config.SecurityConfig;
import com.matjari.controller.AuthController;
import com.matjari.dto.response.TokenResponse;
import com.matjari.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 SecurityConfig + JwtAuthenticationFilter + GlobalExceptionHandler를 올린 슬라이스 테스트.
 * JwtTokenProvider/CustomUserDetailsService는 mock — 토큰 문자열별로 상황을 흉내낸다.
 */
@WebMvcTest(controllers = {AuthController.class, SecuritySliceTest.ProbeController.class})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, SecuritySliceTest.ProbeController.class})
class SecuritySliceTest {

    private static final String ORIGIN = "http://localhost:5173";
    private static final String UNAUTHORIZED_JSON = "{\"message\":\"로그인이 필요합니다.\"}";

    @RestController
    static class ProbeController {
        @GetMapping("/api/probe")
        String probe() {
            return "ok";
        }

        // 인증된 사용자가 권한 부족(AccessDeniedException)을 만나는 상황:
        // GlobalExceptionHandler가 rethrow → ExceptionTranslationFilter → AccessDeniedHandler(403)
        @GetMapping("/api/probe/denied")
        String denied() {
            throw new AccessDeniedException("no");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private AuthService authService;
    @MockBean
    private com.matjari.service.EmailVerificationService emailVerificationService;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));

        when(tokenProvider.validateToken("refresh")).thenReturn(true);
        when(tokenProvider.isRefreshToken("refresh")).thenReturn(true);

        when(tokenProvider.validateToken("ghost")).thenReturn(true);
        when(tokenProvider.isRefreshToken("ghost")).thenReturn(false);
        when(tokenProvider.getUserId("ghost")).thenReturn(99L);
        when(userDetailsService.loadUserById(99L))
                .thenThrow(new UsernameNotFoundException("not found"));

        when(tokenProvider.validateToken("expired")).thenReturn(false);
    }

    @Test
    void noTokenIs401Json() throws Exception {
        mockMvc.perform(get("/api/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(content().json(UNAUTHORIZED_JSON, true));
    }

    @Test
    void invalidRefreshAndDeletedUserTokensAre401() throws Exception {
        for (String token : List.of("expired", "refresh", "ghost", "garbage")) {
            mockMvc.perform(get("/api/probe").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json(UNAUTHORIZED_JSON, true));
        }
    }

    @Test
    void validTokenPasses() throws Exception {
        mockMvc.perform(get("/api/probe").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    void authenticatedButDeniedIs403Json() throws Exception {
        mockMvc.perform(get("/api/probe/denied").header("Authorization", "Bearer good"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Content-Type", "application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."));
    }

    @Test
    void unauthorizedResponseCarriesCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/probe").header("Origin", ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void refreshEndpointIgnoresBadAuthorizationHeader() throws Exception {
        when(authService.refresh(any())).thenReturn(TokenResponse.of("new-access", "new-refresh"));

        for (String token : List.of("expired", "ghost", "garbage")) {
            mockMvc.perform(post("/api/auth/refresh")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"r\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").value("new-access"));
        }
    }

    @Test
    void jwtFilterServletAutoRegistrationIsDisabledButChainStillAuthenticates() throws Exception {
        assertThat(jwtAuthenticationFilterRegistration.isEnabled()).isFalse();

        clearInvocations(tokenProvider);
        mockMvc.perform(get("/api/probe").header("Authorization", "Bearer good"))
                .andExpect(status().isOk());
        // 시큐리티 체인 안에서 요청당 정확히 한 번 실행된다
        verify(tokenProvider, times(1)).validateToken("good");
        verify(tokenProvider, atLeastOnce()).getUserId("good");
    }

    @Test
    void blankRefreshTokenHasKoreanMessage() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"refreshToken\":\"refresh token이 필요합니다.\"}", true));
    }
}
