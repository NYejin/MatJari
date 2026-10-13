package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.domain.UserRole;
import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.SeatClaimDtos.AdminSeatClaimResponse;
import com.matjari.dto.response.SeatClaimDtos.Holder;
import com.matjari.dto.response.SeatClaimDtos.SeatClaimResponse;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.SeatClaimService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/seat-claims, /api/admin/seat-claims - 실제 SecurityConfig + 필터 + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = {SeatClaimController.class, AdminSeatClaimController.class})
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class SeatClaimControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private SeatClaimService service;

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

    private static SeatClaimResponse claim() {
        return new SeatClaimResponse(900L, "PENDING", 10L, "두아 리파 내한", 7L, LocalDateTime.of(2026, 11, 1, 19, 0),
                "1층 A", "3", "5", null, true, null, LocalDateTime.of(2026, 10, 6, 14, 3, 21), null);
    }

    private static final MockMultipartFile IMAGE =
            new MockMultipartFile("image", "evil.html", "text/html", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});

    @Test
    void anonymousIs401AndUserIs403OnAdminApi() throws Exception {
        mockMvc.perform(multipart("/api/seat-claims").file(IMAGE)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/seat-claims/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/seat-claims/900/image")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/seat-claims")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/seat-claims").header("Authorization", "Bearer user")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/seat-claims/900/approve").header("Authorization", "Bearer user")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/seat-claims/900/reject").header("Authorization", "Bearer user")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verify(service, never()).approve(any(), any());
    }

    @Test
    void multipartCreateReturns201AndPassesFormFieldsOnly() throws Exception {
        when(service.create(eq(1L), eq(7L), eq("1층 A"), eq("3"), eq("5"), eq("메모"), any())).thenReturn(claim());

        mockMvc.perform(multipart("/api/seat-claims").file(IMAGE).header("Authorization", "Bearer user")
                        .param("sessionId", "7").param("zone", "1층 A").param("row", "3").param("col", "5").param("memo", "메모"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(900))
                .andExpect(jsonPath("$.sessionStartsAt").value("2026-11-01T19:00"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-06T14:03:21"))
                .andExpect(jsonPath("$.imageAvailable").value(true))
                .andExpect(jsonPath("$.imageKey").doesNotExist())
                .andExpect(jsonPath("$.imageContentType").doesNotExist());
    }

    @Test
    void missingFieldsGoToServiceAsNullSoItCanAnswerWithFieldMap() throws Exception {
        when(service.create(eq(1L), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenThrow(FieldValidationException.ofAll(Map.of("zone", "구역을 입력해주세요.")));

        mockMvc.perform(multipart("/api/seat-claims").header("Authorization", "Bearer user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.zone").value("구역을 입력해주세요."));
    }

    @Test
    void tooLargeUploadIs413WithCode() throws Exception {
        when(service.create(any(), any(), any(), any(), any(), any(), any())).thenThrow(new MaxUploadSizeExceededException(5L * 1024 * 1024));

        mockMvc.perform(multipart("/api/seat-claims").file(IMAGE).header("Authorization", "Bearer user")
                        .param("sessionId", "7").param("zone", "A").param("row", "1").param("col", "1"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
    }

    @Test
    void conflictCodeIsPassedThrough() throws Exception {
        when(service.create(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("dup", Map.of("code", "SEAT_CLAIM_ALREADY_PENDING")));

        mockMvc.perform(multipart("/api/seat-claims").file(IMAGE).header("Authorization", "Bearer user")
                        .param("sessionId", "7").param("zone", "A").param("row", "1").param("col", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_CLAIM_ALREADY_PENDING"));
    }

    @Test
    void imageResponseCarriesSecurityHeaders() throws Exception {
        when(service.image(1L, false, 900L)).thenReturn(new SeatClaimService.ImageData("image/jpeg", new byte[]{1, 2, 3}));

        mockMvc.perform(get("/api/seat-claims/900/image").header("Authorization", "Bearer user"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    @Test
    void adminFlagIsPassedToImageAndDeletedImageIs410() throws Exception {
        when(service.image(2L, true, 900L)).thenThrow(new SeatClaimService.ImageDeletedException());

        mockMvc.perform(get("/api/seat-claims/900/image").header("Authorization", "Bearer admin"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("IMAGE_DELETED"));
    }

    @Test
    void adminListApproveAndReject() throws Exception {
        AdminSeatClaimResponse r = AdminSeatClaimResponse.of(null_safe(), "요청자", null, new Holder("RESERVED_LOCKED", "보유자"));
        when(service.adminList("PENDING", 0, 20)).thenReturn(new PageResponse<>(List.of(r), 0, 20, 1, 1));
        when(service.approve(2L, 900L)).thenReturn(r);
        when(service.reject(2L, 900L, "사진 불명")).thenReturn(r);

        mockMvc.perform(get("/api/admin/seat-claims?status=PENDING").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].holder.ticketStatus").value("RESERVED_LOCKED"))
                .andExpect(jsonPath("$.content[0].claimantNickname").value("요청자"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(post("/api/admin/seat-claims/900/approve").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(900));
        mockMvc.perform(post("/api/admin/seat-claims/900/reject").header("Authorization", "Bearer admin")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"사진 불명\"}"))
                .andExpect(status().isOk());
    }

    private static com.matjari.domain.SeatClaim null_safe() {
        var performance = com.matjari.domain.Performance.create("KSPO DOME", "공연", "https://tickets.interpark.com/goods/1", "interpark:1", com.matjari.domain.User.create("r@b.com", "enc", "등록자"));
        org.springframework.test.util.ReflectionTestUtils.setField(performance, "id", 10L);
        var session = com.matjari.domain.PerformanceSession.create(performance, LocalDateTime.of(2026, 11, 1, 19, 0));
        org.springframework.test.util.ReflectionTestUtils.setField(session, "id", 7L);
        var c = com.matjari.domain.SeatClaim.create(5L, session, "1층 A", "1층A", "3", "3", "5", "5", null,
                "123e4567-e89b-12d3-a456-426614174000", "image/jpeg", 4);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", 900L);
        return c;
    }
}
