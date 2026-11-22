package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.dto.NotificationSettingDtos;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.ClientIpResolver;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.EmailVerificationService.EmailCodeRateLimitedException;
import com.matjari.service.NotificationSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/users/me/email-verification/** — 실제 SecurityConfig + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = AccountEmailVerificationController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class AccountEmailVerificationControllerSliceTest {

    private static final String CODE_URL = "/api/users/me/email-verification/code";
    private static final String CONFIRM_URL = "/api/users/me/email-verification/confirm";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private NotificationSettingService service;
    @MockBean
    private ClientIpResolver clientIpResolver;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
        when(clientIpResolver.resolve(any())).thenReturn("9.9.9.9");
    }

    @Test
    void 로그인이_필요하다() throws Exception {
        mockMvc.perform(post(CODE_URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(CONFIRM_URL).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 코드_발송은_204이고_접속자_IP를_넘긴다() throws Exception {
        mockMvc.perform(post(CODE_URL).header("Authorization", "Bearer good")).andExpect(status().isNoContent());
        verify(service).sendVerificationCode(1L, "9.9.9.9");
    }

    @Test
    void 이미_확인됨은_409_EMAIL_ALREADY_VERIFIED() throws Exception {
        doThrow(new ConflictException("이미 확인된 이메일이에요.", Map.of("code", "EMAIL_ALREADY_VERIFIED")))
                .when(service).sendVerificationCode(eq(1L), any());
        mockMvc.perform(post(CODE_URL).header("Authorization", "Bearer good"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_VERIFIED"));
    }

    @Test
    void 대기와_제한은_429이고_Retry_After가_붙고_발송_불가는_503() throws Exception {
        doThrow(new EmailCodeRateLimitedException("EMAIL_CODE_COOLDOWN", "방금 보냈어요", 42))
                .when(service).sendVerificationCode(eq(1L), any());
        mockMvc.perform(post(CODE_URL).header("Authorization", "Bearer good"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("EMAIL_CODE_COOLDOWN")).andExpect(jsonPath("$.retryAfterSeconds").value(42));
        Mockito.reset(service);
        doThrow(new EmailCodeRateLimitedException("EMAIL_UNAVAILABLE", "보낼 수 없어요", 0))
                .when(service).sendVerificationCode(eq(1L), any());
        mockMvc.perform(post(CODE_URL).header("Authorization", "Bearer good"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EMAIL_UNAVAILABLE"));
    }

    @Test
    void 확인은_200과_설정_응답을_돌려준다() throws Exception {
        when(service.confirmVerification(1L, "123456")).thenReturn(new NotificationSettingDtos.Response(true, true, true));
        mockMvc.perform(post(CONFIRM_URL).header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"emailEnabled\":true,\"emailAvailable\":true,\"emailVerified\":true}", true));
    }

    @Test
    void 틀린_코드는_400_code() throws Exception {
        when(service.confirmVerification(1L, "000000")).thenThrow(new FieldValidationException("code", "인증번호가 일치하지 않아요."));
        mockMvc.perform(post(CONFIRM_URL).header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").exists());
    }
}
