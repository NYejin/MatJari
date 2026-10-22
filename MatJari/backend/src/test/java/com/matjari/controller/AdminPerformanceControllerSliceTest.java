package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.domain.UserRole;
import com.matjari.dto.response.PageResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.AdminPerformanceService;
import com.matjari.service.AdminPerformanceService.Detail;
import com.matjari.service.AdminPerformanceService.ListItem;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리자 공연 관리 컨트롤러 — 실제 SecurityConfig(/api/admin/** 는 ADMIN) + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = AdminPerformanceController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class AdminPerformanceControllerSliceTest {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 19, 9, 30, 5);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private AdminPerformanceService service;
    @MockBean
    private com.matjari.service.aiextract.DetailExtractionService extractionService;

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
        return b.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    private static Detail detail() {
        return new Detail(100L, "제목", "공연장", "https://x", true, AT, "사유", "관리자",
                new AdminPerformanceService.Registrant(1L, "등록자"),
                List.of(new AdminPerformanceService.SessionItem(7L, LocalDateTime.of(2026, 11, 1, 19, 0), true)),
                List.of(new AdminPerformanceService.ZoneItem("A구역", null)), null, AT);
    }

    @Test
    void 일반_사용자와_비로그인은_모든_엔드포인트가_막힌다() throws Exception {
        mockMvc.perform(as("user", get("/api/admin/performances"))).andExpect(status().isForbidden());
        mockMvc.perform(as("user", get("/api/admin/performances/1"))).andExpect(status().isForbidden());
        mockMvc.perform(as("user", post("/api/admin/performances/1/hide")).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as("user", post("/api/admin/performances/1/unhide")).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as("user", patch("/api/admin/performances/1")).content("{}")).andExpect(status().isForbidden());
        mockMvc.perform(as("user", post("/api/admin/performances/1/session-changes")).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as("user", put("/api/admin/performances/1/zones")).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/performances")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void 목록은_파라미터를_서비스에_넘긴다() throws Exception {
        ListItem item = new ListItem(100L, "제목", "공연장", "https://x", "등록자", 2, 1, true, AT, AT);
        when(service.list("두아", "HIDDEN", 1, 10)).thenReturn(new PageResponse<>(List.of(item), 1, 10, 11, 2));
        mockMvc.perform(as("admin", get("/api/admin/performances?query=두아&hidden=HIDDEN&page=1&size=10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].hidden").value(true))
                .andExpect(jsonPath("$.content[0].registrantNickname").value("등록자"))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void 상세는_회차_사용중_표시를_담는다() throws Exception {
        when(service.get(100L)).thenReturn(detail());
        mockMvc.perform(as("admin", get("/api/admin/performances/100")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions[0].inUse").value(true))
                .andExpect(jsonPath("$.sessions[0].startsAt").value("2026-11-01T19:00"))
                .andExpect(jsonPath("$.hiddenByNickname").value("관리자"));
    }

    @Test
    void 숨기기와_올리기는_204이고_사유_오류는_400() throws Exception {
        mockMvc.perform(as("admin", post("/api/admin/performances/100/hide")).content("{\"reason\":\"저작권\"}"))
                .andExpect(status().isNoContent());
        verify(service).hide(2L, 100L, "저작권");
        doThrow(new FieldValidationException("reason", "사유를 입력해주세요.")).when(service).hide(eq(2L), eq(100L), any());
        mockMvc.perform(as("admin", post("/api/admin/performances/100/hide")).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").exists());
        mockMvc.perform(as("admin", post("/api/admin/performances/100/unhide")))
                .andExpect(status().isNoContent());
        verify(service).unhide(2L, 100L, null);
    }

    @Test
    void 수정_회차변경_구역교체는_200_상세를_돌려준다() throws Exception {
        when(service.edit(eq(2L), eq(100L), any())).thenReturn(detail());
        when(service.changeSessions(eq(2L), eq(100L), any())).thenReturn(detail());
        when(service.replaceZones(eq(2L), eq(100L), any())).thenReturn(detail());
        mockMvc.perform(as("admin", patch("/api/admin/performances/100")).content("{\"title\":\"새\",\"reason\":\"오타\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(100));
        mockMvc.perform(as("admin", post("/api/admin/performances/100/session-changes"))
                        .content("{\"changes\":[{\"type\":\"ADD\",\"startsAt\":\"2026-12-01T19:00\"}],\"reason\":\"추가\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(as("admin", put("/api/admin/performances/100/zones"))
                        .content("{\"zones\":[{\"name\":\"A구역\",\"shapePolygons\":null}],\"seatmapAspect\":1.5,\"reason\":\"교체\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 업무_오류는_코드와_함께_돌려준다() throws Exception {
        when(service.changeSessions(eq(2L), eq(100L), any()))
                .thenThrow(new BusinessRuleException("SESSION_IN_USE", "사용 중"))
                .thenThrow(new ConflictException("충돌", Map.of("code", "SESSION_EDIT_CONFLICT")));
        mockMvc.perform(as("admin", post("/api/admin/performances/100/session-changes")).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SESSION_IN_USE"));
        mockMvc.perform(as("admin", post("/api/admin/performances/100/session-changes")).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SESSION_EDIT_CONFLICT"));
    }
}
