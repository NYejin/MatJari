package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.domain.UserRole;
import com.matjari.dto.response.ModerationDtos.AdminReport;
import com.matjari.dto.response.ModerationDtos.ReportCreatedResponse;
import com.matjari.dto.response.ModerationDtos.ReportedUser;
import com.matjari.dto.response.ModerationDtos.UserRef;
import com.matjari.dto.response.PageResponse;
import com.matjari.exception.AccountSuspendedException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.AbuseReportService;
import com.matjari.service.AdminModerationService;
import com.matjari.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 신고·관리자 처리 API 와 이용 정지 응답 형식 — 실제 SecurityConfig(JwtAuthenticationFilter 포함) + GlobalExceptionHandler, 서비스는 mock.
 * 권한: 미인증 401, 일반 사용자의 /api/admin/** 403, 정지 사용자는 403 ACCOUNT_SUSPENDED({message, code, until}) — 401 과 구분되고 로그아웃만 통과.
 */
@WebMvcTest(controllers = {ReportController.class, AdminModerationController.class, AuthController.class})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class ModerationControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private AbuseReportService reportService;
    @MockBean
    private AdminModerationService adminService;
    @MockBean
    private AuthService authService;
    @MockBean
    private com.matjari.service.EmailVerificationService emailVerificationService;

    @BeforeEach
    void setUp() {
        token("user", 1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "u@b.com", UserRole.USER));
        token("admin", 2L);
        when(userDetailsService.loadUserById(2L)).thenReturn(AuthUserPrincipal.of(2L, "a@b.com", UserRole.ADMIN));
        token("suspended", 3L);
        when(userDetailsService.loadUserById(3L)).thenThrow(new AccountSuspendedException(LocalDateTime.of(2026, 11, 1, 9, 30, 5)));
        token("forever", 4L);
        when(userDetailsService.loadUserById(4L)).thenThrow(new AccountSuspendedException(null));
    }

    private void token(String token, long userId) {
        when(tokenProvider.validateToken(token)).thenReturn(true);
        when(tokenProvider.isRefreshToken(token)).thenReturn(false);
        when(tokenProvider.getUserId(token)).thenReturn(userId);
    }

    private static AdminReport report() {
        return new AdminReport(9L, 50L, "ABUSE", null, "OPEN", LocalDateTime.of(2026, 10, 12, 10, 0, 0), null, null, null,
                new UserRef(1L, "신고자"), new ReportedUser(7L, "대상", 3, 1, null));
    }

    // ------------------------------------------------------------------ 권한

    @Test
    void 신고_API는_로그인이_필요하다() throws Exception {
        mockMvc.perform(post("/api/exchange/matches/50/report").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ABUSE\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reports/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reportService);
    }

    @Test
    void 관리자_API는_미인증_401_일반_사용자_403_관리자만_통과한다() throws Exception {
        String[] gets = {"/api/admin/reports", "/api/admin/reports/9", "/api/admin/reports/9/messages", "/api/admin/sanctions", "/api/admin/blocks"};
        String[] posts = {"/api/admin/reports/9/resolve", "/api/admin/reports/9/dismiss", "/api/admin/sanctions/4/revoke"};
        for (String url : gets) {
            mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(url).header("Authorization", "Bearer user")).andExpect(status().isForbidden());
        }
        for (String url : posts) {
            mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
            mockMvc.perform(post(url).header("Authorization", "Bearer user").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(adminService);

        when(adminService.getReport(9L)).thenReturn(report());
        mockMvc.perform(get("/api/admin/reports/9").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.reported.totalReports").value(3))
                .andExpect(jsonPath("$.reported.activeSanction").doesNotExist())
                .andExpect(jsonPath("$.createdAt").value("2026-10-12T10:00:00"))
                .andExpect(jsonPath("$.handledAt").value((Object) null));
    }

    // ------------------------------------------------------------------ 정지 응답 형식

    @Test
    void 정지_사용자의_인증_API는_403_ACCOUNT_SUSPENDED이고_until이_초_단위_문자열이다() throws Exception {
        mockMvc.perform(get("/api/reports/me").header("Authorization", "Bearer suspended"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("이용이 정지된 계정입니다."))
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
                .andExpect(jsonPath("$.until").value("2026-11-01T09:30:05"));
        mockMvc.perform(get("/api/reports/me").header("Authorization", "Bearer forever"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
                .andExpect(jsonPath("$.until").value((Object) null));
        mockMvc.perform(get("/api/admin/reports").header("Authorization", "Bearer suspended")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        verifyNoInteractions(reportService, adminService);
    }

    @Test
    void 정지_사용자도_로그아웃은_할_수_있다() throws Exception {
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer suspended")).andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void 로그인에서_던진_정지_예외도_같은_본문이다() throws Exception {
        when(authService.login(any())).thenThrow(new AccountSuspendedException(LocalDateTime.of(2026, 12, 1, 0, 0, 0)));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("이용이 정지된 계정입니다."))
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
                .andExpect(jsonPath("$.until").value("2026-12-01T00:00:00"));
    }

    // ------------------------------------------------------------------ 신고 / 처리 매핑

    @Test
    void 신고는_201과_본문_계약을_따르고_오류는_상태코드로_변환된다() throws Exception {
        when(reportService.create(eq(1L), eq(50L), any()))
                .thenReturn(new ReportCreatedResponse(11L, "OPEN", LocalDateTime.of(2026, 10, 12, 10, 0, 1)));
        mockMvc.perform(post("/api/exchange/matches/50/report").header("Authorization", "Bearer user")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ABUSE\",\"detail\":\"욕설\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(11))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-12T10:00:01"));

        when(reportService.create(eq(1L), eq(51L), any())).thenThrow(new ConflictException("dup", Map.of("code", "REPORT_ALREADY_OPEN")));
        mockMvc.perform(post("/api/exchange/matches/51/report").header("Authorization", "Bearer user")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ABUSE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_ALREADY_OPEN"));

        when(reportService.create(eq(1L), eq(52L), any())).thenThrow(new FieldValidationException("reason", "신고 사유를 선택해주세요."));
        mockMvc.perform(post("/api/exchange/matches/52/report").header("Authorization", "Bearer user")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("신고 사유를 선택해주세요."));
    }

    @Test
    void 처리_API는_본문_없이도_호출되고_관리자_id를_넘긴다() throws Exception {
        when(adminService.dismiss(2L, 9L, null)).thenReturn(report());
        mockMvc.perform(post("/api/admin/reports/9/dismiss").header("Authorization", "Bearer admin")).andExpect(status().isOk());
        when(adminService.resolve(eq(2L), eq(9L), any())).thenReturn(report());
        mockMvc.perform(post("/api/admin/reports/9/resolve").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"조치\",\"sanction\":{\"days\":7,\"note\":\"욕설\"}}"))
                .andExpect(status().isOk());
        verify(adminService).resolve(eq(2L), eq(9L), any());

        when(adminService.listReports(null, null, 0, 20)).thenReturn(new PageResponse<>(List.of(report()), 0, 20, 1, 1));
        mockMvc.perform(get("/api/admin/reports").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }
}
