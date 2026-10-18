package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.domain.UserRole;
import com.matjari.dto.response.PageResponse;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.AdminUserService;
import com.matjari.service.AdminUserService.AdminActionLogItem;
import com.matjari.service.AdminUserService.AdminUserItem;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 이용자 관리·조치 기록 API: 권한(401/403), 204 응답, 오류 코드 매핑. 서비스는 mock. */
@WebMvcTest(controllers = AdminUserController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class, SecurityErrorResponseWriter.class})
class AdminUserControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        token("user", 1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(AuthUserPrincipal.of(1L, "u@b.com", UserRole.USER));
        token("admin", 2L);
        when(userDetailsService.loadUserById(2L)).thenReturn(AuthUserPrincipal.of(2L, "a@b.com", UserRole.ADMIN));
    }

    private void token(String token, long userId) {
        when(tokenProvider.validateToken(token)).thenReturn(true);
        when(tokenProvider.isRefreshToken(token)).thenReturn(false);
        when(tokenProvider.getUserId(token)).thenReturn(userId);
    }

    @Test
    void 모든_경로는_미인증_401_일반_사용자_403이다() throws Exception {
        String[] gets = {"/api/admin/users", "/api/admin/users/5", "/api/admin/action-logs"};
        for (String url : gets) {
            mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(url).header("Authorization", "Bearer user")).andExpect(status().isForbidden());
        }
        for (String url : new String[] {"/api/admin/users/5/suspend", "/api/admin/users/5/unsuspend", "/api/admin/users/5/force-withdraw"}) {
            mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
            mockMvc.perform(post(url).header("Authorization", "Bearer user").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(put("/api/admin/users/5/role").header("Authorization", "Bearer user").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void 목록은_계약_필드와_시각_형식을_따른다() throws Exception {
        when(service.list(eq("kim"), eq("ACTIVE"), eq("ALL"), eq(0), eq(20))).thenReturn(new PageResponse<>(List.of(
                new AdminUserItem(5L, null, true, "닉", "USER", false, null, false, LocalDateTime.of(2026, 10, 1, 9, 0, 0))), 0, 20, 1, 1));
        mockMvc.perform(get("/api/admin/users?query=kim&status=ACTIVE&role=ALL").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].emailPlaceholder").value(true))
                .andExpect(jsonPath("$.content[0].suspendedUntil").value((Object) null))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-10-01T09:00:00"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void 조치는_204이고_처리자는_로그인한_관리자다() throws Exception {
        mockMvc.perform(post("/api/admin/users/5/suspend").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":7,\"reason\":\"사유\"}"))
                .andExpect(status().isNoContent());
        verify(service).suspend(eq(2L), eq(5L), eq(new AdminUserService.SuspendRequest(7, "사유")));
        mockMvc.perform(post("/api/admin/users/5/unsuspend").header("Authorization", "Bearer admin")).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/admin/users/5/role").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\",\"reason\":\"사유\"}"))
                .andExpect(status().isNoContent());
        verify(service).changeRole(eq(2L), eq(5L), eq(new AdminUserService.RoleRequest("ADMIN", "사유")));
        mockMvc.perform(post("/api/admin/users/5/force-withdraw").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmText\":\"강제 탈퇴\",\"reason\":\"사유\"}"))
                .andExpect(status().isNoContent());
        verify(service).forceWithdraw(eq(2L), eq(5L), eq(new AdminUserService.ForceWithdrawRequest("강제 탈퇴", "사유")));
    }

    @Test
    void 오류_코드와_검증_오류가_본문으로_변환된다() throws Exception {
        doThrow(new ConflictException("마지막", Map.of("code", "LAST_ADMIN"))).when(service).changeRole(eq(2L), eq(5L), any());
        mockMvc.perform(put("/api/admin/users/5/role").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\",\"reason\":\"사유\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        doThrow(new FieldValidationException("reason", "사유를 입력해주세요.")).when(service).suspend(eq(2L), eq(6L), any());
        mockMvc.perform(post("/api/admin/users/6/suspend").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"days\":7}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 조치_기록_조회는_필터를_넘기고_항목_계약을_따른다() throws Exception {
        when(service.actionLogs(eq("USER_SUSPEND"), eq("USER"), eq(5L), eq(0), eq(20))).thenReturn(new PageResponse<>(List.of(
                new AdminActionLogItem(1L, 2L, "관리자", "USER_SUSPEND", "USER", 5L, "사유", "{\"days\":7}", LocalDateTime.of(2026, 10, 19, 1, 2, 3))),
                0, 20, 1, 1));
        mockMvc.perform(get("/api/admin/action-logs?action=USER_SUSPEND&targetType=USER&targetId=5").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].adminNickname").value("관리자"))
                .andExpect(jsonPath("$.content[0].detail").value("{\"days\":7}"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-10-19T01:02:03"));
    }
}
