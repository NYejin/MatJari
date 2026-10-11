package com.seatswap.controller;

import com.seatswap.config.SecurityConfig;
import com.seatswap.dto.response.ExchangeHistoryResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.security.CustomUserDetailsService;
import com.seatswap.security.JwtAccessDeniedHandler;
import com.seatswap.security.JwtAuthenticationEntryPoint;
import com.seatswap.security.JwtTokenProvider;
import com.seatswap.security.SecurityErrorResponseWriter;
import com.seatswap.service.ExchangeHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/exchange/history — 실제 SecurityConfig + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = ExchangeHistoryController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class ExchangeHistoryControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private ExchangeHistoryService historyService;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/exchange/history")).andExpect(status().isUnauthorized());
        verifyNoInteractions(historyService);
    }

    @Test
    void returnsMyHistoryWithSecondPrecisionTimesAndNoCounterpartData() throws Exception {
        ExchangeHistoryResponse h = new ExchangeHistoryResponse(9L, 50L, 3L, "공연", "올림픽홀",
                LocalDateTime.of(2026, 11, 1, 19, 0), LocalDateTime.of(2026, 11, 2, 19, 0),
                new ExchangeHistoryResponse.Seat("A구역", "3", "5"), new ExchangeHistoryResponse.Seat("B구역", "4", "6"),
                LocalDateTime.of(2026, 10, 12, 9, 30, 5));
        when(historyService.listMine(1L, 0, 20)).thenReturn(new PageResponse<>(List.of(h), 0, 20, 1, 1));

        mockMvc.perform(get("/api/exchange/history").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"content":[{"id":9,"matchId":50,"performanceId":3,"performanceTitle":"공연","venueName":"올림픽홀",
                          "oldStartsAt":"2026-11-01T19:00:00","newStartsAt":"2026-11-02T19:00:00",
                          "oldSeat":{"zone":"A구역","row":"3","col":"5"},"newSeat":{"zone":"B구역","row":"4","col":"6"},
                          "completedAt":"2026-10-12T09:30:05"}],"page":0,"size":20,"totalElements":1,"totalPages":1}
                        """, true));
    }

    @Test
    void passesParamsAndMapsBadValuesTo400() throws Exception {
        when(historyService.listMine(1L, 2, 50)).thenReturn(new PageResponse<>(List.of(), 2, 50, 0, 0));
        mockMvc.perform(get("/api/exchange/history?page=2&size=50").header("Authorization", "Bearer good"))
                .andExpect(status().isOk());
        verify(historyService).listMine(1L, 2, 50);

        when(historyService.listMine(1L, -1, 20)).thenThrow(new FieldValidationException("page", "page는 0 이상이어야 합니다."));
        mockMvc.perform(get("/api/exchange/history?page=-1").header("Authorization", "Bearer good"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"page\":\"page는 0 이상이어야 합니다.\"}", true));
    }
}
