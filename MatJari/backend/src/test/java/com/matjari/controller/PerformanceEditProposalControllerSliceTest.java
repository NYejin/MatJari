package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.domain.UserRole;
import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.PerformanceEditProposalDtos.AdminEditProposal;
import com.matjari.dto.response.PerformanceEditProposalDtos.CreateRequest;
import com.matjari.dto.response.PerformanceEditProposalDtos.EditProposal;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.NotFoundException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.PerformanceEditProposalService;
import com.matjari.service.PerformanceService;
import com.matjari.service.linkinfo.PerformancePreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 공연 수정 제안 컨트롤러 2개 — 실제 SecurityConfig(/api/admin/** 는 ADMIN) + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = {PerformanceEditProposalController.class, AdminPerformanceEditProposalController.class,
        PerformanceController.class})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class PerformanceEditProposalControllerSliceTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 12, 9, 30, 5);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private PerformanceEditProposalService service;
    @MockBean
    private PerformanceService performanceService;
    @MockBean
    private PerformancePreviewService previewService;

    @BeforeEach
    void setUp() {
        when(tokenProvider.validateToken("user")).thenReturn(true);
        when(tokenProvider.isRefreshToken("user")).thenReturn(false);
        when(tokenProvider.getUserId("user")).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "u@b.com", UserRole.USER));
        when(tokenProvider.validateToken("admin")).thenReturn(true);
        when(tokenProvider.isRefreshToken("admin")).thenReturn(false);
        when(tokenProvider.getUserId("admin")).thenReturn(2L);
        when(userDetailsService.loadUserById(2L)).thenReturn(AuthUserPrincipal.of(2L, "a@b.com", UserRole.ADMIN));
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder b) {
        return b.header("Authorization", "Bearer " + token);
    }

    private static EditProposal proposal() {
        return new EditProposal(5L, 100L, "옛 제목", "옛 제목", "옛 공연장", "새 제목", null, "오타", "PENDING", null, AT, null);
    }

    private static AdminEditProposal adminProposal() {
        return new AdminEditProposal(5L, 100L, "새 제목", "새 제목", "옛 공연장", "새 제목", null, "오타", "APPROVED", null,
                AT, AT, 1L, "제안자", "옛 제목", "옛 공연장", "관리자");
    }

    @Test
    void 제안_생성은_201과_계약_필드를_돌려준다() throws Exception {
        when(service.create(eq(1L), eq(100L), any(CreateRequest.class))).thenReturn(proposal());

        mockMvc.perform(as("user", post("/api/performances/100/edit-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newTitle\":\"새 제목\",\"reason\":\"오타\"}")))
                .andExpect(status().isCreated())
                .andExpect(content().json("""
                        {"id":5,"performanceId":100,"performanceTitle":"옛 제목","currentTitle":"옛 제목",
                         "currentVenueName":"옛 공연장","newTitle":"새 제목","newVenueName":null,"reason":"오타",
                         "status":"PENDING","reviewNote":null,"createdAt":"2026-10-12T09:30:05","reviewedAt":null}
                        """, true));
    }

    @Test
    void 내_제안_경로는_공연_상세_경로와_충돌하지_않는다() throws Exception {
        when(service.listMine(1L, 0, 20)).thenReturn(new PageResponse<>(List.of(proposal()), 0, 20, 1, 1));

        mockMvc.perform(as("user", get("/api/performances/edit-proposals/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(5))
                .andExpect(jsonPath("$.totalElements").value(1));
        verifyNoInteractions(performanceService);
    }

    @Test
    void 오류_계약_404_409_422() throws Exception {
        when(service.create(eq(1L), eq(404L), any())).thenThrow(new NotFoundException("공연을 찾을 수 없습니다."));
        when(service.create(eq(1L), eq(409L), any()))
                .thenThrow(new ConflictException("대기 중", Map.of("code", "EDIT_PROPOSAL_ALREADY_PENDING")));
        when(service.create(eq(1L), eq(422L), any()))
                .thenThrow(new BusinessRuleException("EDIT_NO_CHANGE", "같아요"));
        String body = "{\"newTitle\":\"x\"}";

        mockMvc.perform(as("user", post("/api/performances/404/edit-proposals").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isNotFound());
        mockMvc.perform(as("user", post("/api/performances/409/edit-proposals").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EDIT_PROPOSAL_ALREADY_PENDING"));
        mockMvc.perform(as("user", post("/api/performances/422/edit-proposals").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EDIT_NO_CHANGE"));
    }

    @Test
    void 로그인하지_않으면_401이다() throws Exception {
        mockMvc.perform(get("/api/performances/edit-proposals/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/performances/1/edit-proposals").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 관리자_API는_ADMIN이_아니면_403이고_서비스를_호출하지_않는다() throws Exception {
        mockMvc.perform(as("user", get("/api/admin/performance-edit-proposals"))).andExpect(status().isForbidden());
        mockMvc.perform(as("user", post("/api/admin/performance-edit-proposals/5/approve"))).andExpect(status().isForbidden());
        mockMvc.perform(as("user", post("/api/admin/performance-edit-proposals/5/reject")
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/performance-edit-proposals")).andExpect(status().isUnauthorized());
        verify(service, never()).approve(any(), any());
        verify(service, never()).reject(any(), any(), any());
        verify(service, never()).listForAdmin(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void 관리자_목록_승인_거절은_계약_필드를_돌려준다() throws Exception {
        when(service.listForAdmin("PENDING", 0, 20)).thenReturn(new PageResponse<>(List.of(adminProposal()), 0, 20, 1, 1));
        when(service.approve(2L, 5L)).thenReturn(adminProposal());
        when(service.reject(2L, 5L, "사유")).thenReturn(adminProposal());
        when(service.approve(2L, 6L)).thenThrow(new ConflictException("처리됨", Map.of("code", "PROPOSAL_ALREADY_REVIEWED")));

        mockMvc.perform(as("admin", get("/api/admin/performance-edit-proposals").param("status", "PENDING")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].proposerNickname").value("제안자"))
                .andExpect(jsonPath("$.content[0].oldTitle").value("옛 제목"))
                .andExpect(jsonPath("$.content[0].reviewerNickname").value("관리자"))
                .andExpect(jsonPath("$.content[0].reviewedAt").value("2026-10-12T09:30:05"));
        mockMvc.perform(as("admin", post("/api/admin/performance-edit-proposals/5/approve")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(as("admin", post("/api/admin/performance-edit-proposals/5/reject")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"사유\"}")))
                .andExpect(status().isOk());
        mockMvc.perform(as("admin", post("/api/admin/performance-edit-proposals/6/approve")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_ALREADY_REVIEWED"));
    }
}
