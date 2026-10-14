package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.AuthService;
import com.matjari.service.EmailVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 가입 이메일 인증 사용 여부 공개 조회(GET /api/auth/signup/email-verification). 로그인 없이 호출된다. */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class AuthControllerEmailVerificationStatusSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;
    @MockBean
    private EmailVerificationService emailVerificationService;
    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;

    @Test
    void 인증이_꺼져_있으면_enabled_false를_인증_없이_돌려준다() throws Exception {
        when(emailVerificationService.isEnabled()).thenReturn(false);
        mockMvc.perform(get("/api/auth/signup/email-verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void 인증이_켜져_있으면_enabled_true() throws Exception {
        when(emailVerificationService.isEnabled()).thenReturn(true);
        mockMvc.perform(get("/api/auth/signup/email-verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }
}
