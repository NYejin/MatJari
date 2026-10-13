package com.matjari.security;

import com.matjari.config.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.matjari.domain.UserRole;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/admin/** 는 ADMIN만 접근: 관리자 200, 일반 사용자 403, 미인증 401. 일반 경로는 USER도 200. */
@WebMvcTest(controllers = AdminAccessSliceTest.ProbeController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class, AdminAccessSliceTest.ProbeController.class})
class AdminAccessSliceTest {

    @RestController
    static class ProbeController {
        @GetMapping({"/api/admin", "/api/admin/probe"})
        String admin() {
            return "admin-ok";
        }

        @GetMapping("/api/probe")
        String user() {
            return "ok";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;

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

    @Test
    void adminCanAccessAdminApi() throws Exception {
        mockMvc.perform(get("/api/admin/probe").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(content().string("admin-ok"));
    }

    @Test
    void normalUserGets403OnAdminApi() throws Exception {
        mockMvc.perform(get("/api/admin/probe").header("Authorization", "Bearer user"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("접근 권한이 없습니다."));
    }

    @Test
    void anonymousGets401OnAdminApi() throws Exception {
        mockMvc.perform(get("/api/admin/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRootPathWithoutSuffixIsProtected() throws Exception {
        mockMvc.perform(get("/api/admin").header("Authorization", "Bearer user"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk());
    }

    @Test
    void adminPathWithTrailingSlashIsProtected() throws Exception {
        mockMvc.perform(get("/api/admin/").header("Authorization", "Bearer user"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void normalUserStillAccessesRegularApi() throws Exception {
        mockMvc.perform(get("/api/probe").header("Authorization", "Bearer user"))
                .andExpect(status().isOk());
    }
}
