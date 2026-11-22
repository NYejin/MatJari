package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.dto.NotificationSettingDtos;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.NotificationSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/users/me/notification-settings — 실제 SecurityConfig + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = NotificationSettingController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class NotificationSettingControllerSliceTest {

    private static final String URL = "/api/users/me/notification-settings";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private NotificationSettingService service;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
    }

    @Test
    void 로그인이_필요하다() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("{\"emailEnabled\":false}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 조회는_emailEnabled와_emailAvailable을_돌려준다() throws Exception {
        when(service.get(1L)).thenReturn(new NotificationSettingDtos.Response(true, false));
        mockMvc.perform(get(URL).header("Authorization", "Bearer good")).andExpect(status().isOk())
                .andExpect(content().json("{\"emailEnabled\":true,\"emailAvailable\":false}", true));
    }

    @Test
    void 수정은_본인_설정을_바꾸고_같은_응답을_돌려준다() throws Exception {
        when(service.update(1L, false)).thenReturn(new NotificationSettingDtos.Response(false, true));
        mockMvc.perform(patch(URL).header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"emailEnabled\":false,\"emailAvailable\":true}", true));
        verify(service).update(1L, false);
    }

    @Test
    void emailEnabled가_없거나_null이면_400이다() throws Exception {
        mockMvc.perform(patch(URL).header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.emailEnabled").exists());
        mockMvc.perform(patch(URL).header("Authorization", "Bearer good").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emailEnabled\":null}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.emailEnabled").exists());
        verifyNoInteractions(service);
    }
}
