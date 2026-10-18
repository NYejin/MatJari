package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.aiextract.DetailExtractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PerformanceAiExtractController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class PerformanceAiExtractControllerSliceTest {

    private static final String URL = "https://ticket.melon.com/performance/index.htm?prodId=1";
    private static final String BODY = "{\"sourceUrl\":\"" + URL + "\"}";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private DetailExtractionService service;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/api/performances/ai-extract").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsContract() throws Exception {
        when(service.extract(1L, URL)).thenReturn(
                new DetailExtractionService.Response(List.of(new DetailExtractionService.SessionDto("2026-11-01", "19:00")), "AI",
                        List.of(new DetailExtractionService.ZoneDto("A구역", List.of(List.of(List.of(0, 0), List.of(10, 0), List.of(0, 10)))),
                                new DetailExtractionService.ZoneDto("B구역", null)),
                        1.5, true, false, false, false));
        mockMvc.perform(post("/api/performances/ai-extract").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].date").value("2026-11-01"))
                .andExpect(jsonPath("$.sessionsSource").value("AI"))
                .andExpect(jsonPath("$.zones[0].shapePolygons[0][1][0]").value(10))
                .andExpect(jsonPath("$.zones[1].shapePolygons").isEmpty())
                .andExpect(jsonPath("$.seatmapAspect").value(1.5))
                .andExpect(jsonPath("$.aiEnabled").value(true))
                .andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.truncatedSlices").value(false));
    }

    @Test
    void blankUrlIs400() throws Exception {
        mockMvc.perform(post("/api/performances/ai-extract").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sourceUrl\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rateLimitMapsTo429WithRetryAfter() throws Exception {
        when(service.extract(1L, URL))
                .thenThrow(new DetailExtractionService.AiExtractException(429, "AI_RATE_LIMITED", "많아요", 120));
        mockMvc.perform(post("/api/performances/ai-extract").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(jsonPath("$.code").value("AI_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(120));
    }

    @Test
    void unavailableIs503() throws Exception {
        when(service.extract(1L, URL))
                .thenThrow(new DetailExtractionService.AiExtractException(503, "AI_UNAVAILABLE", "실패", 0));
        mockMvc.perform(post("/api/performances/ai-extract").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
    }
}
