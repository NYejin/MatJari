package com.seatswap.controller;

import com.seatswap.config.SecurityConfig;
import com.seatswap.dto.response.TokenResponse;
import com.seatswap.security.CustomUserDetailsService;
import com.seatswap.security.JwtAccessDeniedHandler;
import com.seatswap.security.JwtAuthenticationEntryPoint;
import com.seatswap.security.JwtTokenProvider;
import com.seatswap.security.SecurityErrorResponseWriter;
import com.seatswap.service.AuthService;
import com.seatswap.service.social.SocialAuthException;
import com.seatswap.service.social.SocialAuthService;
import com.seatswap.exception.AccountSuspendedException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SocialAuthController + 실제 SecurityConfig/GlobalExceptionHandler. 서비스는 mock. */
@WebMvcTest(controllers = SocialAuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class SocialAuthControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SocialAuthService socialAuthService;
    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private AuthService authService;

    @Test
    void providers는_인증_없이_활성_제공자만_돌려주고_no_store() throws Exception {
        when(socialAuthService.enabledProviders()).thenReturn(List.of("google", "kakao"));
        mockMvc.perform(get("/api/auth/social/providers"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.providers[0]").value("google"))
                .andExpect(jsonPath("$.providers[1]").value("kakao"));
    }

    @Test
    void authorize는_302와_Set_Cookie와_no_store_인증_불필요() throws Exception {
        when(socialAuthService.authorize(eq("google"), any()))
                .thenReturn(new SocialAuthService.AuthorizeResult("https://accounts.example/auth?x=1",
                        "ss_oauth=V; Max-Age=600; Path=/api/auth/social; HttpOnly; SameSite=Lax"));
        mockMvc.perform(get("/api/auth/social/google/authorize"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://accounts.example/auth?x=1"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.startsWith("ss_oauth=V;"),
                        org.hamcrest.Matchers.containsString("HttpOnly"), org.hamcrest.Matchers.containsString("SameSite=Lax"))))
                .andExpect(header().string("Cache-Control", "no-store"));
        // IP 는 remoteAddr(MockMvc 기본 127.0.0.1)이고 X-Forwarded-For 는 쓰지 않는다
        mockMvc.perform(get("/api/auth/social/google/authorize").header("X-Forwarded-For", "6.6.6.6"));
        verify(socialAuthService, org.mockito.Mockito.times(2)).authorize("google", "127.0.0.1");
    }

    @Test
    void 비활성_제공자는_404_JSON() throws Exception {
        when(socialAuthService.authorize(eq("kakao"), any())).thenThrow(SocialAuthException.providerDisabled());
        mockMvc.perform(get("/api/auth/social/kakao/authorize"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SOCIAL_PROVIDER_DISABLED"));
    }

    @Test
    void 속도_제한은_429와_Retry_After() throws Exception {
        when(socialAuthService.authorize(any(), any())).thenThrow(SocialAuthException.rateLimited(17));
        mockMvc.perform(get("/api/auth/social/google/authorize"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "17"))
                .andExpect(jsonPath("$.code").value("SOCIAL_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(17));
    }

    @Test
    void callback은_쿠키_값을_서비스에_넘기고_302_쿠키삭제_no_store() throws Exception {
        when(socialAuthService.callback(eq("google"), eq("c"), eq("s"), eq(null), eq("COOKIEVAL")))
                .thenReturn(new SocialAuthService.CallbackResult("http://localhost:5173/auth/callback#code=XYZ",
                        "ss_oauth=; Max-Age=0; Path=/api/auth/social; HttpOnly; SameSite=Lax"));
        mockMvc.perform(get("/api/auth/social/google/callback").param("code", "c").param("state", "s")
                        .cookie(new Cookie("ss_oauth", "COOKIEVAL")))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/auth/callback#code=XYZ"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void callback에_쿠키가_없으면_null을_넘긴다() throws Exception {
        when(socialAuthService.callback(any(), any(), any(), any(), eq(null)))
                .thenReturn(new SocialAuthService.CallbackResult("http://localhost:5173/login?socialError=INVALID_STATE", "ss_oauth=; Max-Age=0"));
        mockMvc.perform(get("/api/auth/social/google/callback").param("code", "c").param("state", "s"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/login?socialError=INVALID_STATE"));
    }

    @Test
    void exchange는_토큰을_돌려주고_no_store() throws Exception {
        when(socialAuthService.exchange(eq("CODE"), any())).thenReturn(TokenResponse.of("A", "R"));
        mockMvc.perform(post("/api/auth/social/exchange").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"CODE\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accessToken").value("A"))
                .andExpect(jsonPath("$.refreshToken").value("R"));
    }

    @Test
    void exchange_잘못된_코드는_400_INVALID_LOGIN_CODE_빈_코드는_400() throws Exception {
        when(socialAuthService.exchange(eq("BAD"), any())).thenThrow(SocialAuthException.invalidLoginCode());
        mockMvc.perform(post("/api/auth/social/exchange").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"BAD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LOGIN_CODE"));
        mockMvc.perform(post("/api/auth/social/exchange").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/social/exchange").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exchange_정지_계정은_403_ACCOUNT_SUSPENDED() throws Exception {
        when(socialAuthService.exchange(eq("SUS"), any())).thenThrow(new AccountSuspendedException(null));
        mockMvc.perform(post("/api/auth/social/exchange").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"SUS\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }
}
