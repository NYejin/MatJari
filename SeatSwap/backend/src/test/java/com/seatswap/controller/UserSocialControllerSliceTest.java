package com.seatswap.controller;

import com.seatswap.config.SecurityConfig;
import com.seatswap.dto.response.SocialAccountsResponse;
import com.seatswap.dto.response.SocialConfirmResponse;
import com.seatswap.dto.response.SocialLinkResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.security.CustomUserDetailsService;
import com.seatswap.security.JwtAccessDeniedHandler;
import com.seatswap.security.JwtAuthenticationEntryPoint;
import com.seatswap.security.JwtTokenProvider;
import com.seatswap.security.SecurityErrorResponseWriter;
import com.seatswap.exception.AccountSuspendedException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.service.UserService;
import com.seatswap.service.social.SocialAccountService;
import com.seatswap.service.social.SocialAuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.seatswap.dto.response.UserResponse;
import com.seatswap.domain.UserRole;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 닉네임 수정(PATCH /api/users/me)과 소셜 계정 연결 API(/api/users/me/social/**). 실제 SecurityConfig/GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = {UserController.class, UserSocialController.class})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class UserSocialControllerSliceTest {

    private static final String AUTH = "Bearer good";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private UserService userService;
    @MockBean
    private SocialAccountService socialAccountService;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
        when(tokenProvider.validateToken("sus")).thenReturn(true);
        when(tokenProvider.isRefreshToken("sus")).thenReturn(false);
        when(tokenProvider.getUserId("sus")).thenReturn(2L);
        when(userDetailsService.loadUserById(2L)).thenThrow(new AccountSuspendedException(null));
    }

    // ---------------- PATCH /api/users/me ----------------

    @Test
    void 닉네임_수정은_200과_UserResponse() throws Exception {
        when(userService.updateNickname(1L, "새닉네임"))
                .thenReturn(new UserResponse(1L, null, "새닉네임", 0.0, UserRole.USER, true, false));
        mockMvc.perform(patch("/api/users/me").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"  새닉네임 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("새닉네임"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.emailPlaceholder").value(true))
                .andExpect(jsonPath("$.passwordLoginEnabled").value(false));
    }

    @Test
    void 닉네임_길이_빈값_누락은_400_필드_오류() throws Exception {
        for (String body : new String[]{"{\"nickname\":\"가\"}", "{\"nickname\":\"" + "가".repeat(21) + "\"}",
                "{\"nickname\":\"   \"}", "{}"}) {
            mockMvc.perform(patch("/api/users/me").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.nickname").exists());
        }
        verify(userService, never()).updateNickname(any(), any());
    }

    @Test
    void 보이지_않는_문자는_서비스가_400_필드_오류로_거부한다() throws Exception {
        when(userService.updateNickname(1L, "닉​네임")).thenThrow(new FieldValidationException("nickname", "닉네임에 사용할 수 없는 문자가 포함되어 있습니다."));
        mockMvc.perform(patch("/api/users/me").header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"닉\\u200b네임\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.nickname").exists());
    }

    @Test
    void 인증이_없거나_정지_사용자면_닉네임_수정_불가() throws Exception {
        mockMvc.perform(patch("/api/users/me").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"닉네임\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/users/me").header("Authorization", "Bearer sus").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"닉네임\"}"))
                .andExpect(status().isForbidden());
        verify(userService, never()).updateNickname(any(), any());
    }

    // ---------------- GET /api/users/me/social ----------------

    @Test
    void 소셜_목록은_accounts_providers_canUnlink와_no_store() throws Exception {
        when(socialAccountService.list(1L)).thenReturn(new SocialAccountsResponse(
                List.of(new SocialAccountsResponse.Item("kakao", LocalDateTime.of(2026, 10, 13, 9, 0))),
                List.of("google", "kakao"), false));
        mockMvc.perform(get("/api/users/me/social").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accounts[0].provider").value("kakao"))
                .andExpect(jsonPath("$.accounts[0].linkedAt").exists())
                .andExpect(jsonPath("$.providers[1]").value("kakao"))
                .andExpect(jsonPath("$.canUnlink").value(false));
    }

    // ---------------- POST /api/users/me/social/{provider}/link ----------------

    @Test
    void 연결_시작은_상대경로_authorizeUrl과_no_store() throws Exception {
        when(socialAccountService.startLink(1L, "google"))
                .thenReturn(new SocialLinkResponse("/api/auth/social/google/authorize?link=TICKET"));
        mockMvc.perform(post("/api/users/me/social/google/link").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.authorizeUrl").value("/api/auth/social/google/authorize?link=TICKET"));
    }

    @Test
    void 연결_시작의_오류_409_404_429() throws Exception {
        when(socialAccountService.startLink(1L, "google"))
                .thenThrow(new SocialAuthException(HttpStatus.CONFLICT, "SOCIAL_ALREADY_LINKED", "이미 연결된 소셜 계정이에요."));
        mockMvc.perform(post("/api/users/me/social/google/link").header("Authorization", AUTH))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOCIAL_ALREADY_LINKED"));
        when(socialAccountService.startLink(1L, "kakao")).thenThrow(SocialAuthException.providerDisabled());
        mockMvc.perform(post("/api/users/me/social/kakao/link").header("Authorization", AUTH))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SOCIAL_PROVIDER_DISABLED"));
        when(socialAccountService.startLink(1L, "naver")).thenThrow(SocialAuthException.rateLimited(9));
        mockMvc.perform(post("/api/users/me/social/naver/link").header("Authorization", AUTH))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "9"));
    }

    // ---------------- POST /api/users/me/social/confirm ----------------

    @Test
    void 연결_확정은_인증_주체의_userId로_서비스를_부르고_provider와_no_store를_돌려준다() throws Exception {
        when(socialAccountService.confirm(1L, "CODE")).thenReturn(new SocialConfirmResponse("google"));
        mockMvc.perform(post("/api/users/me/social/confirm").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"CODE\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.provider").value("google"));
    }

    @Test
    void 연결_확정의_오류는_400_409_429이고_코드가_비었으면_400() throws Exception {
        when(socialAccountService.confirm(1L, "BAD")).thenThrow(SocialAuthException.invalidLinkCode());
        mockMvc.perform(post("/api/users/me/social/confirm").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"BAD\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_LINK_CODE"));
        when(socialAccountService.confirm(1L, "DUP")).thenThrow(SocialAuthException.linkedOther());
        mockMvc.perform(post("/api/users/me/social/confirm").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"DUP\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOCIAL_LINKED_OTHER"));
        when(socialAccountService.confirm(1L, "MINE")).thenThrow(SocialAuthException.alreadyLinked());
        mockMvc.perform(post("/api/users/me/social/confirm").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"MINE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOCIAL_ALREADY_LINKED"));
        when(socialAccountService.confirm(1L, "RATE")).thenThrow(SocialAuthException.rateLimited(7));
        mockMvc.perform(post("/api/users/me/social/confirm").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"RATE\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "7"));
        for (String body : new String[]{"{}", "{\"code\":\"  \"}"}) {
            mockMvc.perform(post("/api/users/me/social/confirm").header("Authorization", AUTH)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void 연결_확정은_인증_없이는_401_정지_사용자는_403() throws Exception {
        mockMvc.perform(post("/api/users/me/social/confirm").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"C\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/me/social/confirm").header("Authorization", "Bearer sus")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"C\"}"))
                .andExpect(status().isForbidden());
        verify(socialAccountService, never()).confirm(any(), any());
    }

    // ---------------- DELETE /api/users/me/social/{provider} ----------------

    @Test
    void 해제는_204_no_store() throws Exception {
        mockMvc.perform(delete("/api/users/me/social/google").header("Authorization", AUTH))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(socialAccountService).unlink(1L, "google");
    }

    @Test
    void 마지막_로그인_수단이면_409_LAST_LOGIN_METHOD() throws Exception {
        doThrow(new SocialAuthException(HttpStatus.CONFLICT, "LAST_LOGIN_METHOD", "로그인 수단이 하나뿐이라 해제할 수 없어요."))
                .when(socialAccountService).unlink(1L, "google");
        mockMvc.perform(delete("/api/users/me/social/google").header("Authorization", AUTH))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_LOGIN_METHOD"));
    }

    @Test
    void 소셜_API는_인증_없이는_401_정지_사용자는_403() throws Exception {
        mockMvc.perform(get("/api/users/me/social")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/me/social/google/link")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/users/me/social/google")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me/social").header("Authorization", "Bearer sus")).andExpect(status().isForbidden());
        verify(socialAccountService, never()).list(any());
    }
}
