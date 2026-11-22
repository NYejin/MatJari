package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.domain.UserRole;
import com.matjari.exception.NotFoundException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.AdminModerationService;
import com.matjari.service.ChatImageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 신고 상세의 채팅 사진 경로(V30): ADMIN 만, 보안 헤더, 410·404 매핑. 서비스는 mock. */
@WebMvcTest(controllers = AdminModerationController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class AdminReportImageControllerSliceTest {

    private static final String URL = "/api/admin/reports/9/messages/700/image";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private AdminModerationService service;

    @BeforeEach
    void setUp() {
        for (String[] t : new String[][]{{"user", "1"}, {"admin", "2"}}) {
            when(tokenProvider.validateToken(t[0])).thenReturn(true);
            when(tokenProvider.isRefreshToken(t[0])).thenReturn(false);
            when(tokenProvider.getUserId(t[0])).thenReturn(Long.parseLong(t[1]));
        }
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "u@b.com", UserRole.USER));
        when(userDetailsService.loadUserById(2L)).thenReturn(AuthUserPrincipal.of(2L, "a@b.com", UserRole.ADMIN));
    }

    @Test
    void 미인증은_401_일반_사용자는_403이고_서비스에_닿지_않는다() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL).header("Authorization", "Bearer user")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 관리자는_사진을_보안_헤더와_함께_받는다() throws Exception {
        when(service.messageImage(9L, 700L)).thenReturn(new ChatImageStore.ImageData("image/jpeg", new byte[]{1, 2}));

        mockMvc.perform(get(URL).header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(new byte[]{1, 2}));
    }

    @Test
    void 지워진_사진은_410_다른_신고의_메시지는_404다() throws Exception {
        when(service.messageImage(9L, 701L)).thenThrow(new ChatImageStore.ImageExpiredException());
        when(service.messageImage(9L, 702L)).thenThrow(new NotFoundException("사진을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/admin/reports/9/messages/701/image").header("Authorization", "Bearer admin"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("IMAGE_EXPIRED"));
        mockMvc.perform(get("/api/admin/reports/9/messages/702/image").header("Authorization", "Bearer admin"))
                .andExpect(status().isNotFound());
    }
}
