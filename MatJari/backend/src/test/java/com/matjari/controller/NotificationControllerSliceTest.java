package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.dto.response.NotificationResponse;
import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.UnreadCountResponse;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/notifications/... — 실제 SecurityConfig + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = NotificationController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class NotificationControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer good");
    }

    private static final String LOGIN_REQUIRED = "{\"message\":\"로그인이 필요합니다.\"}";

    @Test
    void allEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized())
                .andExpect(content().json(LOGIN_REQUIRED, true));
        mockMvc.perform(get("/api/notifications/unread-count")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/5/read")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/notifications/read-all")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/notifications/read")).andExpect(status().isUnauthorized())
                .andExpect(content().json(LOGIN_REQUIRED, true));
        verifyNoInteractions(notificationService);
    }

    @Test
    void listReturnsPageWithoutPersonalDataAndWithSecondPrecisionTimes() throws Exception {
        NotificationResponse n = new NotificationResponse(7L, "PROPOSAL_RECEIVED", 50L, false, null,
                LocalDateTime.of(2026, 10, 11, 9, 30, 5), "CANCELED", true, "RECEIVED", "상대", "공연",
                new NotificationResponse.Seat("A구역", "3", "5"), new NotificationResponse.Seat("B구역", "4", "6"));
        when(notificationService.list(1L, 0, 20, false)).thenReturn(new PageResponse<>(List.of(n), 0, 20, 1, 1));

        mockMvc.perform(auth(get("/api/notifications"))).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"content":[{"id":7,"type":"PROPOSAL_RECEIVED","matchId":50,"read":false,"readAt":null,
                          "createdAt":"2026-10-11T09:30:05","matchStatus":"CANCELED","matchClosed":true,"role":"RECEIVED",
                          "counterpartNickname":"상대","performanceTitle":"공연",
                          "mySeat":{"zone":"A구역","row":"3","col":"5"},"counterpartSeat":{"zone":"B구역","row":"4","col":"6"}}],
                         "page":0,"size":20,"totalElements":1,"totalPages":1}
                        """, true));
    }

    @Test
    void listPassesParamsAndRejectsBadValues() throws Exception {
        when(notificationService.list(1L, 2, 50, true)).thenReturn(new PageResponse<>(List.of(), 2, 50, 0, 0));
        mockMvc.perform(auth(get("/api/notifications?page=2&size=50&unreadOnly=true"))).andExpect(status().isOk());
        verify(notificationService).list(1L, 2, 50, true);

        when(notificationService.list(1L, -1, 20, false)).thenThrow(new FieldValidationException("page", "page는 0 이상이어야 합니다."));
        mockMvc.perform(auth(get("/api/notifications?page=-1"))).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"page\":\"page는 0 이상이어야 합니다.\"}", true));
        when(notificationService.list(1L, 0, 0, false)).thenThrow(new FieldValidationException("size", "size는 1 이상이어야 합니다."));
        mockMvc.perform(auth(get("/api/notifications?size=0"))).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"size\":\"size는 1 이상이어야 합니다.\"}", true));
        mockMvc.perform(auth(get("/api/notifications?size=abc"))).andExpect(status().isBadRequest());
        mockMvc.perform(auth(get("/api/notifications?unreadOnly=maybe"))).andExpect(status().isBadRequest());
    }

    @Test
    void unreadCountAlwaysHasAllThreeKeys() throws Exception {
        Map<String, Long> byType = new LinkedHashMap<>();
        byType.put("PROPOSAL_RECEIVED", 2L);
        byType.put("MATCH_RESERVED", 0L);
        byType.put("RESERVATION_CANCELED", 0L);
        when(notificationService.unreadCount(1L)).thenReturn(new UnreadCountResponse(2, byType));
        mockMvc.perform(auth(get("/api/notifications/unread-count"))).andExpect(status().isOk())
                .andExpect(content().json("""
                        {"total":2,"byType":{"PROPOSAL_RECEIVED":2,"MATCH_RESERVED":0,"RESERVATION_CANCELED":0}}
                        """, true));
    }

    @Test
    void readReturns204AndHidesOthersNotifications() throws Exception {
        mockMvc.perform(auth(post("/api/notifications/5/read"))).andExpect(status().isNoContent());
        verify(notificationService).markRead(1L, 5L);

        doThrow(new NotFoundException("알림을 찾을 수 없습니다.")).when(notificationService).markRead(1L, 99L);
        mockMvc.perform(auth(post("/api/notifications/99/read"))).andExpect(status().isNotFound())
                .andExpect(content().json("{\"message\":\"알림을 찾을 수 없습니다.\"}", true));
        mockMvc.perform(auth(post("/api/notifications/abc/read"))).andExpect(status().isBadRequest());
    }

    @Test
    void readAllReturns204AndValidatesType() throws Exception {
        mockMvc.perform(auth(post("/api/notifications/read-all"))).andExpect(status().isNoContent());
        verify(notificationService).markAllRead(1L, null);
        mockMvc.perform(auth(post("/api/notifications/read-all?type=MATCH_RESERVED"))).andExpect(status().isNoContent());
        verify(notificationService).markAllRead(1L, "MATCH_RESERVED");

        doThrow(new FieldValidationException("type", "type은 허용된 값이 아닙니다.")).when(notificationService).markAllRead(1L, "BAD");
        mockMvc.perform(auth(post("/api/notifications/read-all?type=BAD"))).andExpect(status().isBadRequest())
                .andExpect(content().json("{\"type\":\"type은 허용된 값이 아닙니다.\"}", true));
    }

    @Test
    void deleteReadReturnsDeletedCount() throws Exception {
        when(notificationService.deleteRead(1L)).thenReturn(3);
        mockMvc.perform(auth(delete("/api/notifications/read"))).andExpect(status().isOk())
                .andExpect(content().json("{\"deleted\":3}", true));
    }
}
