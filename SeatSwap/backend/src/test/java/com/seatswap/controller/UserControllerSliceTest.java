package com.seatswap.controller;

import com.seatswap.config.SecurityConfig;
import com.seatswap.domain.UserRole;
import com.seatswap.dto.response.UserResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.security.CustomUserDetailsService;
import com.seatswap.security.JwtAccessDeniedHandler;
import com.seatswap.security.JwtAuthenticationEntryPoint;
import com.seatswap.security.JwtTokenProvider;
import com.seatswap.security.SecurityErrorResponseWriter;
import com.seatswap.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /api/users/me — 실제 SecurityConfig + JwtAuthenticationFilter + GlobalExceptionHandler 슬라이스 */
@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class UserControllerSliceTest {

    private static final String UNAUTHORIZED_JSON = "{\"message\":\"로그인이 필요합니다.\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private UserService userService;

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
    }

    @Test
    void validTokenReturnsMe() throws Exception {
        when(userService.getMe(1L)).thenReturn(new UserResponse(1L, "a@b.com", "닉네임", 4.5, UserRole.USER, false, true));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"id\":1,\"email\":\"a@b.com\",\"nickname\":\"닉네임\",\"trustScore\":4.5,\"role\":\"USER\",\"emailPlaceholder\":false,\"passwordLoginEnabled\":true}", true));
    }

    @Test
    void noTokenRefreshTokenOrDeletedUserIs401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHORIZED_JSON, true));
        for (String token : List.of("refresh", "ghost", "garbage")) {
            mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json(UNAUTHORIZED_JSON, true));
        }
        verify(userService, never()).getMe(any());
    }

    @Test
    void userDeletedAfterFilterIs401NotServerError() throws Exception {
        // 필터 통과 후 서비스 조회 시점에 사용자가 사라진 경우(레이스)
        when(userService.getMe(1L))
                .thenThrow(new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer good"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json(UNAUTHORIZED_JSON, true));
    }
}
