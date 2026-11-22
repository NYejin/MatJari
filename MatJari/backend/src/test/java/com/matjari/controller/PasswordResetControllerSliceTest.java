package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.security.AuthRateLimiter;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.ClientIpResolver;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.RefreshTokenCookie;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.AuthService;
import com.matjari.service.EmailVerificationService;
import com.matjari.service.PasswordResetService;
import com.matjari.service.UserService;
import com.matjari.service.WithdrawService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 비밀번호 재설정 요청·확정(공개)과 비밀번호 변경(인증 필요) 슬라이스 테스트: 응답 규약·검증 오류·권한. */
@WebMvcTest(controllers = {AuthController.class, UserController.class})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class PasswordResetControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private AuthService authService;
    @MockBean
    private EmailVerificationService emailVerificationService;
    @MockBean
    private AuthRateLimiter rateLimiter;
    @MockBean
    private ClientIpResolver clientIpResolver;
    @MockBean
    private RefreshTokenCookie refreshCookie;
    @MockBean
    private PasswordResetService passwordResetService;
    @MockBean
    private UserService userService;
    @MockBean
    private WithdrawService withdrawService;

    @BeforeEach
    void setUp() {
        when(clientIpResolver.resolve(any())).thenReturn("9.9.9.9");
        when(passwordResetService.changePassword(any(), any(), any(), any(), any(), any()))
                .thenReturn(com.matjari.dto.response.TokenResponse.of("new-access"));
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
    }

    private static String json(String s) {
        return s;
    }

    // ---- 요청

    @Test
    void 재설정_요청은_로그인_없이_204이고_이메일은_정규화해서_넘긴다() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"email\":\"  Some.User@Example.COM \"}")))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(passwordResetService).requestReset("some.user@example.com", "9.9.9.9");
    }

    @Test
    void 재설정_요청은_형식이_틀린_이메일을_400으로_거절한다() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"email\":\"not-an-email\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.email").exists());
        mockMvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{}")))
                .andExpect(status().isBadRequest());
        verify(passwordResetService, never()).requestReset(anyString(), anyString());
    }

    @Test
    void 재설정_요청이_너무_잦으면_429_코드와_Retry_After를_준다() throws Exception {
        doThrow(new AuthRateLimiter.AuthRateLimitedException(AuthRateLimiter.PASSWORD_RESET_CODE, "많아요", 42))
                .when(passwordResetService).requestReset(anyString(), anyString());
        mockMvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"email\":\"a@b.com\"}")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PASSWORD_RESET_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(42))
                .andExpect(header().string("Retry-After", "42"));
    }

    // ---- 확정

    @Test
    void 확정은_204이고_IP_제한을_세며_계정_잠금을_푼다() throws Exception {
        when(passwordResetService.confirmReset("tok", "newpassword1")).thenReturn("a@b.com");
        mockMvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"token\":\"tok\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isNoContent());
        verify(rateLimiter).acquirePasswordResetConfirm("9.9.9.9");
        verify(rateLimiter).recordLoginSuccess("a@b.com");
    }

    @Test
    void 확정은_새_비밀번호_규칙을_가입과_같게_검사한다() throws Exception {
        mockMvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"token\":\"tok\",\"newPassword\":\"short\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.newPassword").value("비밀번호는 8~64자로 입력해주세요."));
        mockMvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"token\":\"\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.token").exists());
        verify(passwordResetService, never()).confirmReset(anyString(), anyString());
    }

    @Test
    void 확정에서_링크가_틀리면_400_INVALID_RESET_TOKEN() throws Exception {
        when(passwordResetService.confirmReset(anyString(), anyString()))
                .thenThrow(new PasswordResetService.InvalidResetTokenException());
        mockMvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"token\":\"tok\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
        verify(rateLimiter, never()).recordLoginSuccess(anyString());
    }

    @Test
    void 확정이_IP당_한도를_넘으면_429() throws Exception {
        doThrow(new AuthRateLimiter.AuthRateLimitedException(AuthRateLimiter.PASSWORD_RESET_CODE, "많아요", 5))
                .when(rateLimiter).acquirePasswordResetConfirm(anyString());
        mockMvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"token\":\"tok\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("PASSWORD_RESET_RATE_LIMITED"));
        verify(passwordResetService, never()).confirmReset(anyString(), anyString());
    }

    // ---- 변경(로그인 상태)

    @Test
    void 비밀번호_변경은_로그인이_필요하다() throws Exception {
        mockMvc.perform(patch("/api/users/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"currentPassword\":\"oldpassword1\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"currentPassword\":\"oldpassword1\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isUnauthorized());
        verify(passwordResetService, never()).changePassword(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 비밀번호_변경_users_경로는_쿠키를_모르므로_null을_넘기고_auth_경로는_쿠키를_넘긴다() throws Exception {
        mockMvc.perform(patch("/api/users/me/password").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"currentPassword\":\"oldpassword1\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(passwordResetService).changePassword(1L, "a@b.com", "9.9.9.9", "oldpassword1", "newpassword1", null);

        when(refreshCookie.read(any())).thenReturn("cookie-token");
        mockMvc.perform(patch("/api/auth/password").header("Authorization", "Bearer good").cookie(new Cookie("mj_rt", "cookie-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"currentPassword\":\"oldpassword1\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("new-access"));
        verify(passwordResetService).changePassword(1L, "a@b.com", "9.9.9.9", "oldpassword1", "newpassword1", "cookie-token");
    }

    @Test
    void 비밀번호_변경_검증_오류와_업무_오류는_계약대로_응답한다() throws Exception {
        mockMvc.perform(patch("/api/users/me/password").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"currentPassword\":\"oldpassword1\",\"newPassword\":\"short\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.newPassword").exists());
        mockMvc.perform(patch("/api/users/me/password").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"currentPassword\":\"\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.currentPassword").exists());

        doThrow(new FieldValidationException("currentPassword", "현재 비밀번호가 올바르지 않아요."))
                .when(passwordResetService).changePassword(any(), any(), any(), eq("wrongpassword"), any(), isNull());
        mockMvc.perform(patch("/api/users/me/password").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"currentPassword\":\"wrongpassword\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.currentPassword").value("현재 비밀번호가 올바르지 않아요."));

        doThrow(new ConflictException("소셜", java.util.Map.of("code", "PASSWORD_LOGIN_DISABLED")))
                .when(passwordResetService).changePassword(any(), any(), any(), eq("socialpassword"), any(), isNull());
        mockMvc.perform(patch("/api/users/me/password").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"currentPassword\":\"socialpassword\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PASSWORD_LOGIN_DISABLED"));

        doThrow(new AuthRateLimiter.AuthRateLimitedException(AuthRateLimiter.LOGIN_CODE, "잠김", 600))
                .when(passwordResetService).changePassword(any(), any(), any(), eq("lockedpassword"), any(), isNull());
        mockMvc.perform(patch("/api/users/me/password").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"currentPassword\":\"lockedpassword\",\"newPassword\":\"newpassword1\"}")))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("LOGIN_RATE_LIMITED"));
    }

    @Test
    void 메일을_보낼_수_없는_설정이면_재설정_요청은_503_PASSWORD_RESET_UNAVAILABLE() throws Exception {
        doThrow(new PasswordResetService.PasswordResetUnavailableException())
                .when(passwordResetService).requestReset(anyString(), anyString());
        mockMvc.perform(post("/api/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{\"email\":\"a@b.com\"}")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PASSWORD_RESET_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("지금은 비밀번호 재설정 메일을 보낼 수 없어요. 잠시 후 다시 시도하거나 문의해 주세요."));
    }
}
