package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.dto.request.TicketCreateRequest;
import com.matjari.dto.response.TicketResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.service.TicketService;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/tickets — 실제 SecurityConfig + JwtAuthenticationFilter + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = TicketController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class TicketControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private TicketService ticketService;

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

    private static TicketResponse ticket() {
        return new TicketResponse(500L, 10L, "두아 리파 내한", "KSPO DOME", 7L,
                LocalDateTime.of(2026, 11, 1, 19, 0), "1층 A", "3", "5", "ACTIVE",
                LocalDateTime.of(2026, 10, 6, 14, 3, 21));
    }

    private static final String TICKET_JSON = """
            {"id":500,"performanceId":10,"performanceTitle":"두아 리파 내한","venueName":"KSPO DOME",
             "sessionId":7,"startsAt":"2026-11-01T19:00","zone":"1층 A","row":"3","col":"5","status":"ACTIVE",
             "createdAt":"2026-10-06T14:03:21"}
            """;

    @Test
    void allEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/tickets/me")).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
        mockMvc.perform(post("/api/tickets").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/tickets/500")).andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns201WithResponseShape() throws Exception {
        when(ticketService.create(eq(1L), any(TicketCreateRequest.class))).thenReturn(ticket());

        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":7,\"zone\":\"1층 A\",\"row\":\"3\",\"col\":\"5\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().json(TICKET_JSON, true));
        verify(ticketService).create(eq(1L), argThat(r ->
                r.sessionId().equals(7L) && r.zone().equals("1층 A") && r.row().equals("3") && r.col().equals("5")));
    }

    @Test
    void createValidationUsesFieldKeys() throws Exception {
        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"zone\":\" \",\"row\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"sessionId":"회차를 선택해주세요.","zone":"구역을 입력해주세요.",
                         "row":"열을 입력해주세요.","col":"번을 입력해주세요."}
                        """, true));
        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"abc\",\"zone\":\"A\",\"row\":\"1\",\"col\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"요청 형식이 올바르지 않습니다.\"}", true));
        verify(ticketService, never()).create(any(), any());
    }

    @Test
    void serviceFieldErrorsKeepSameFormat() throws Exception {
        when(ticketService.create(eq(1L), any(TicketCreateRequest.class)))
                .thenThrow(new FieldValidationException("row", "열은 1 이상이어야 합니다."));

        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":7,\"zone\":\"A\",\"row\":\"0\",\"col\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"row\":\"열은 1 이상이어야 합니다.\"}", true));
    }

    @Test
    void duplicateSeatIs409WithoutOwnerInfo() throws Exception {
        when(ticketService.create(eq(1L), any(TicketCreateRequest.class))).thenThrow(new ConflictException(
                "이미 등록된 좌석입니다. 본인의 티켓이라면 '내 티켓 인증'을 이용해주세요.",
                Map.of("code", "SEAT_ALREADY_REGISTERED")));

        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":7,\"zone\":\"A\",\"row\":\"1\",\"col\":\"1\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"message":"이미 등록된 좌석입니다. 본인의 티켓이라면 '내 티켓 인증'을 이용해주세요.",
                         "code":"SEAT_ALREADY_REGISTERED"}
                        """, true));
    }

    @Test
    void limitReachedIs422() throws Exception {
        when(ticketService.create(eq(1L), any(TicketCreateRequest.class)))
                .thenThrow(new BusinessRuleException("TICKET_LIMIT_REACHED", "등록할 수 있는 티켓은 최대 20개입니다."));

        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":7,\"zone\":\"A\",\"row\":\"1\",\"col\":\"1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json(
                        "{\"code\":\"TICKET_LIMIT_REACHED\",\"message\":\"등록할 수 있는 티켓은 최대 20개입니다.\"}", true));
    }

    @Test
    void mineReturnsListOfOwnActiveTickets() throws Exception {
        when(ticketService.listMine(1L)).thenReturn(List.of(ticket()));

        mockMvc.perform(auth(get("/api/tickets/me")))
                .andExpect(status().isOk())
                .andExpect(content().json("[" + TICKET_JSON + "]", true));
    }

    @Test
    void deleteReturns204AndNotFoundFor404() throws Exception {
        mockMvc.perform(auth(delete("/api/tickets/500"))).andExpect(status().isNoContent());
        verify(ticketService).deactivate(1L, 500L);

        doThrow(new NotFoundException("티켓을 찾을 수 없습니다.")).when(ticketService).deactivate(1L, 404L);
        mockMvc.perform(auth(delete("/api/tickets/404")))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"message\":\"티켓을 찾을 수 없습니다.\"}", true));
        mockMvc.perform(auth(delete("/api/tickets/abc")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"요청 형식이 올바르지 않습니다.\"}", true));
    }

    @Test
    void createRejectsOversizedAndMissingFields() throws Exception {
        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":7,\"zone\":\"" + "가".repeat(201) + "\",\"row\":\"" + "1".repeat(101)
                                + "\",\"col\":\"" + "1".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"zone":"구역은 50자 이하로 입력해주세요.","row":"열은 20자 이하로 입력해주세요.",
                         "col":"번은 20자 이하로 입력해주세요."}
                        """, true));
        // sessionId만 null
        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":null,\"zone\":\"A\",\"row\":\"1\",\"col\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"sessionId\":\"회차를 선택해주세요.\"}", true));
        // 본문 누락 / 깨진 JSON
        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"요청 형식이 올바르지 않습니다.\"}", true));
        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        verify(ticketService, never()).create(any(), any());
    }

    @Test
    void multipleServiceFieldErrorsAreReturnedTogether() throws Exception {
        java.util.Map<String, String> errors = new java.util.LinkedHashMap<>();
        errors.put("zone", "구역에 사용할 수 없는 문자가 있습니다.");
        errors.put("row", "열은 1 이상이어야 합니다.");
        when(ticketService.create(eq(1L), any(TicketCreateRequest.class)))
                .thenThrow(FieldValidationException.ofAll(errors));

        mockMvc.perform(auth(post("/api/tickets")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":7,\"zone\":\"A\",\"row\":\"0\",\"col\":\"1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(
                        "{\"zone\":\"구역에 사용할 수 없는 문자가 있습니다.\",\"row\":\"열은 1 이상이어야 합니다.\"}", true));
    }

    @Test
    void deletingSomeoneElsesTicketIs404WithoutRevealingExistence() throws Exception {
        // 서비스는 본인 티켓이 아니면 없는 티켓과 같은 NotFoundException 을 던진다
        doThrow(new NotFoundException("티켓을 찾을 수 없습니다.")).when(ticketService).deactivate(1L, 999L);

        mockMvc.perform(auth(delete("/api/tickets/999")))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"message\":\"티켓을 찾을 수 없습니다.\"}", true));
    }

    @Test
    void mineWithoutTokenOrWithBadTokenIs401() throws Exception {
        mockMvc.perform(get("/api/tickets/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/tickets/me").header("Authorization", "Bearer bad"))
                .andExpect(status().isUnauthorized());
        verify(ticketService, never()).listMine(any());
    }
}
