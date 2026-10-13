package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.dto.response.BlockDtos.BlockItem;
import com.matjari.dto.response.BlockDtos.BlockResult;
import com.matjari.dto.response.PageResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.NotFoundException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.BlockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 차단 API 계약 — 실제 SecurityConfig + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = BlockController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class BlockControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private BlockService blockService;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("good")).thenReturn(true);
        when(tokenProvider.isRefreshToken("good")).thenReturn(false);
        when(tokenProvider.getUserId("good")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "a@b.com"));
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/api/exchange/matches/5/block")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/blocks")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/blocks/3")).andExpect(status().isUnauthorized());
        verifyNoInteractions(blockService);
    }

    @Test
    void blockReturns200WithContractFields() throws Exception {
        when(blockService.blockFromMatch(1L, 5L)).thenReturn(new BlockResult(9L, "상대", 2, false));
        mockMvc.perform(post("/api/exchange/matches/5/block").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"blockId\":9,\"blockedNickname\":\"상대\",\"canceledMatches\":2,\"alreadyBlocked\":false}", true));
    }

    @Test
    void blockMapsNotFoundAndLimit() throws Exception {
        when(blockService.blockFromMatch(1L, 6L)).thenThrow(new NotFoundException("매칭을 찾을 수 없습니다."));
        mockMvc.perform(post("/api/exchange/matches/6/block").header("Authorization", "Bearer good"))
                .andExpect(status().isNotFound());
        when(blockService.blockFromMatch(1L, 7L)).thenThrow(new BusinessRuleException("BLOCK_LIMIT_REACHED", "상한"));
        mockMvc.perform(post("/api/exchange/matches/7/block").header("Authorization", "Bearer good"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("{\"code\":\"BLOCK_LIMIT_REACHED\"}"));
    }

    @Test
    void listAndDelete() throws Exception {
        when(blockService.listMine(1L, 0, 20)).thenReturn(new PageResponse<>(
                List.of(new BlockItem(3L, "닉", LocalDateTime.of(2026, 10, 12, 9, 30, 5))), 0, 20, 1, 1));
        mockMvc.perform(get("/api/blocks").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[{\"id\":3,\"blockedNickname\":\"닉\",\"createdAt\":\"2026-10-12T09:30:05\"}],"
                        + "\"page\":0,\"size\":20,\"totalElements\":1,\"totalPages\":1}", true));

        mockMvc.perform(delete("/api/blocks/3").header("Authorization", "Bearer good")).andExpect(status().isNoContent());
        verify(blockService).unblock(1L, 3L);

        doThrow(new NotFoundException("차단 내역을 찾을 수 없습니다.")).when(blockService).unblock(1L, 4L);
        mockMvc.perform(delete("/api/blocks/4").header("Authorization", "Bearer good")).andExpect(status().isNotFound());
    }
}
