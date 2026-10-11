package com.seatswap.controller;

import com.seatswap.config.SecurityConfig;
import com.seatswap.dto.response.ExchangeMatchResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.service.ExchangeMatchQueryService;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.security.CustomUserDetailsService;
import com.seatswap.security.JwtAccessDeniedHandler;
import com.seatswap.security.JwtAuthenticationEntryPoint;
import com.seatswap.security.JwtTokenProvider;
import com.seatswap.security.SecurityErrorResponseWriter;
import com.seatswap.service.ExchangeMatchService;
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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/exchange/requests/{id}/proposals, /api/exchange/matches/{id}/... — 실제 SecurityConfig + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = ExchangeMatchController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class ExchangeMatchControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private ExchangeMatchService matchService;
    @MockBean
    private ExchangeMatchQueryService queryService;

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

    private static ExchangeMatchResponse full(String status) {
        return new ExchangeMatchResponse(50L, status, "A", "SENT", 900L, 700L,
                new ExchangeMatchResponse.Seat("A구역", "3", "5", 7L, LocalDateTime.of(2026, 11, 1, 19, 0)),
                800L, 600L,
                new ExchangeMatchResponse.Seat("B구역", "4", "6", 8L, LocalDateTime.of(2026, 11, 2, 19, 0)),
                "상대", "X", null, "POS", 30000, false, true, false, true, false, true,
                "ME", LocalDateTime.of(2026, 10, 8, 12, 0, 5), false, false, null, null,
                LocalDateTime.of(2026, 10, 8, 11, 0, 0), LocalDateTime.of(2026, 10, 8, 12, 0, 5), 3);
    }

    private static ExchangeMatchResponse response(String status) {
        return full(status);
    }

    private static final String FULL_JSON = """
            {"id":50,"status":"CHATTING","mySide":"A","role":"SENT","myRequestId":900,"myTicketId":700,
             "mySeat":{"zone":"A구역","row":"3","col":"5","sessionId":7,"startsAt":"2026-11-01T19:00"},
             "counterpartRequestId":800,"counterpartTicketId":600,
             "counterpartSeat":{"zone":"B구역","row":"4","col":"6","sessionId":8,"startsAt":"2026-11-02T19:00"},
             "counterpartNickname":"상대","myExtraType":"X","myExtraAmount":null,
             "counterpartExtraType":"POS","counterpartExtraAmount":30000,"myRequestDeleted":false,"counterpartRequestDeleted":true,"myTicketExchanged":false,"counterpartTicketExchanged":true,"myTicketReservedElsewhere":false,"counterpartTicketReservedElsewhere":true,
             "reservedBy":"ME","reservedAt":"2026-10-08T12:00:05","myAccepted":false,"counterpartAccepted":false,"canceledBy":null,"canceledAt":null,
             "createdAt":"2026-10-08T11:00:00","updatedAt":"2026-10-08T12:00:05","unreadChatCount":3}
            """;

    @Test
    void allEndpointsRequireLogin() throws Exception {
        mockMvc.perform(post("/api/exchange/requests/900/proposals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRequestId\":800}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
        for (String action : new String[]{"reserve", "unreserve", "reject", "cancel"}) {
            mockMvc.perform(post("/api/exchange/matches/50/" + action)).andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
        }
    }

    @Test
    void proposeReturns201WithMatchShape() throws Exception {
        when(matchService.propose(1L, 900L, 800L)).thenReturn(full("CHATTING"));

        mockMvc.perform(auth(post("/api/exchange/requests/900/proposals")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRequestId\":800}"))
                .andExpect(status().isCreated())
                .andExpect(content().json(FULL_JSON, true));
    }

    @Test
    void proposeValidatesBody() throws Exception {
        mockMvc.perform(auth(post("/api/exchange/requests/900/proposals")).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"targetRequestId\":\"교환을 제안할 상대 요청을 선택해주세요.\"}", true));
        mockMvc.perform(auth(post("/api/exchange/requests/900/proposals")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRequestId\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"요청 형식이 올바르지 않습니다.\"}", true));
        mockMvc.perform(auth(post("/api/exchange/requests/abc/proposals")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetRequestId\":800}"))
                .andExpect(status().isBadRequest());
        verify(matchService, never()).propose(anyLong(), anyLong(), anyLong());
    }

    @Test
    void proposeMapsErrorCodes() throws Exception {
        when(matchService.propose(1L, 901L, 800L)).thenThrow(new ForbiddenException("본인"));
        when(matchService.propose(1L, 902L, 800L)).thenThrow(new NotFoundException("없음"));
        when(matchService.propose(1L, 903L, 800L)).thenThrow(new ConflictException("진행 중",
                Map.of("code", "MATCH_ALREADY_OPEN", "matchId", 7L)));
        when(matchService.propose(1L, 904L, 800L)).thenThrow(new BusinessRuleException("NOT_A_CANDIDATE", "조건"));

        String body = "{\"targetRequestId\":800}";
        mockMvc.perform(auth(post("/api/exchange/requests/901/proposals")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(content().json("{\"message\":\"본인\"}", true));
        mockMvc.perform(auth(post("/api/exchange/requests/902/proposals")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound()).andExpect(content().json("{\"message\":\"없음\"}", true));
        mockMvc.perform(auth(post("/api/exchange/requests/903/proposals")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"진행 중\",\"code\":\"MATCH_ALREADY_OPEN\",\"matchId\":7}", true));
        mockMvc.perform(auth(post("/api/exchange/requests/904/proposals")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("{\"code\":\"NOT_A_CANDIDATE\",\"message\":\"조건\"}", true));
    }

    @Test
    void reserveUnreserveRejectCancelReturn200WithMatch() throws Exception {
        when(matchService.reserve(1L, 50L)).thenReturn(response("RESERVED"));
        when(matchService.unreserve(1L, 53L)).thenReturn(response("CHATTING"));
        when(matchService.reject(1L, 51L)).thenReturn(response("CANCELED"));
        when(matchService.cancel(1L, 52L)).thenReturn(response("CANCELED"));

        mockMvc.perform(auth(post("/api/exchange/matches/50/reserve"))).andExpect(status().isOk())
                .andExpect(content().json("{\"id\":50,\"status\":\"RESERVED\",\"reservedBy\":\"ME\",\"reservedAt\":\"2026-10-08T12:00:05\","
                        + "\"myAccepted\":false,\"counterpartAccepted\":false}"));
        mockMvc.perform(auth(post("/api/exchange/matches/53/unreserve"))).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"CHATTING\"}"));
        mockMvc.perform(auth(post("/api/exchange/matches/51/reject"))).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"CANCELED\"}"));
        mockMvc.perform(auth(post("/api/exchange/matches/52/cancel"))).andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"CANCELED\"}"));
    }

    @Test
    void oldAcceptEndpointIsGone() throws Exception {
        mockMvc.perform(auth(post("/api/exchange/matches/50/accept"))).andExpect(status().is4xxClientError());
    }

    @Test
    void actionsMapErrorCodes() throws Exception {
        when(matchService.reserve(1L, 61L)).thenThrow(new ForbiddenException("참여자"));
        when(matchService.reserve(1L, 62L)).thenThrow(new NotFoundException("없음"));
        when(matchService.reserve(1L, 63L)).thenThrow(new ConflictException("이미 취소된 매칭입니다.",
                Map.of("code", "MATCH_STATE_CONFLICT", "status", "CANCELED", "action", "RESERVE")));
        when(matchService.reserve(1L, 64L)).thenThrow(new ConflictException("예약됨",
                Map.of("code", "TICKET_ALREADY_RESERVED")));
        when(matchService.cancel(1L, 65L)).thenThrow(new ConflictException("먼저 예약을 취소해주세요.",
                Map.of("code", "MATCH_STATE_CONFLICT", "status", "RESERVED", "action", "CANCEL")));

        mockMvc.perform(auth(post("/api/exchange/matches/61/reserve"))).andExpect(status().isForbidden());
        mockMvc.perform(auth(post("/api/exchange/matches/62/reserve"))).andExpect(status().isNotFound());
        mockMvc.perform(auth(post("/api/exchange/matches/63/reserve"))).andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"message":"이미 취소된 매칭입니다.","code":"MATCH_STATE_CONFLICT","status":"CANCELED","action":"RESERVE"}
                        """, true));
        mockMvc.perform(auth(post("/api/exchange/matches/64/reserve"))).andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"예약됨\",\"code\":\"TICKET_ALREADY_RESERVED\"}", true));
        mockMvc.perform(auth(post("/api/exchange/matches/65/cancel"))).andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"message":"먼저 예약을 취소해주세요.","code":"MATCH_STATE_CONFLICT","status":"RESERVED","action":"CANCEL"}
                        """, true));
    }

    @Test
    void nonNumericMatchIdIs400AndGetIsNotAllowed() throws Exception {
        mockMvc.perform(auth(post("/api/exchange/matches/abc/reserve"))).andExpect(status().isBadRequest());
        mockMvc.perform(auth(get("/api/exchange/matches/50/reserve"))).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(auth(get("/api/exchange/matches/50/unreserve"))).andExpect(status().isMethodNotAllowed());
        verify(matchService, never()).reserve(anyLong(), any());
        verify(matchService, never()).unreserve(anyLong(), any());
    }

    // ------------------------------------------------------------------ 내 매칭 조회

    @Test
    void mineAndGetRequireLogin() throws Exception {
        mockMvc.perform(get("/api/exchange/matches/me")).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
        mockMvc.perform(get("/api/exchange/matches/50")).andExpect(status().isUnauthorized());
        verify(queryService, never()).findMine(anyLong(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void mineReturnsPageShapeAndPassesFilters() throws Exception {
        when(queryService.findMine(1L, "SENT", List.of("CHATTING", "RESERVED"), 1, 5))
                .thenReturn(new PageResponse<>(List.of(full("CHATTING")), 1, 5, 6, 2));

        mockMvc.perform(auth(get("/api/exchange/matches/me")).param("role", "SENT")
                        .param("status", "CHATTING", "RESERVED").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"page\":1,\"size\":5,\"totalElements\":6,\"totalPages\":2,\"content\":["
                        + FULL_JSON + "]}", true));
    }

    @Test
    void mineDefaultsToAllWithoutFilters() throws Exception {
        when(queryService.findMine(1L, null, null, 0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(auth(get("/api/exchange/matches/me"))).andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0,\"totalPages\":0}", true));
    }

    @Test
    void mineInvalidFilterIs400() throws Exception {
        when(queryService.findMine(1L, "BOTH", null, 0, 20)).thenThrow(new FieldValidationException("role", "role은 SENT, RECEIVED, ALL 중 하나여야 합니다."));

        mockMvc.perform(auth(get("/api/exchange/matches/me")).param("role", "BOTH"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"role\":\"role은 SENT, RECEIVED, ALL 중 하나여야 합니다.\"}", true));
        mockMvc.perform(auth(get("/api/exchange/matches/me")).param("page", "abc")).andExpect(status().isBadRequest());
    }

    @Test
    void getReturnsMatchShapeAnd404ForNonParticipant() throws Exception {
        when(queryService.findOne(1L, 50L)).thenReturn(full("CHATTING"));
        when(queryService.findOne(1L, 51L)).thenThrow(new NotFoundException("매칭을 찾을 수 없습니다."));

        mockMvc.perform(auth(get("/api/exchange/matches/50"))).andExpect(status().isOk())
                .andExpect(content().json(FULL_JSON, true));
        mockMvc.perform(auth(get("/api/exchange/matches/51"))).andExpect(status().isNotFound())
                .andExpect(content().json("{\"message\":\"매칭을 찾을 수 없습니다.\"}", true));
        mockMvc.perform(auth(get("/api/exchange/matches/abc"))).andExpect(status().isBadRequest());
    }

    @Test
    void completeReturns200WithMatchAndRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/exchange/matches/50/complete")).andExpect(status().isUnauthorized());
        when(matchService.complete(1L, 50L)).thenReturn(response("COMPLETED"));

        mockMvc.perform(auth(post("/api/exchange/matches/50/complete"))).andExpect(status().isOk())
                .andExpect(content().json("{\"id\":50,\"status\":\"COMPLETED\",\"myTicketExchanged\":false,"
                        + "\"counterpartTicketExchanged\":true,\"myTicketReservedElsewhere\":false,\"counterpartTicketReservedElsewhere\":true}"));
    }

    @Test
    void completeMapsErrorCodes() throws Exception {
        when(matchService.complete(1L, 71L)).thenThrow(new NotFoundException("없음"));
        when(matchService.complete(1L, 72L)).thenThrow(new ConflictException("예약한 뒤에 교환 수락할 수 있어요.",
                Map.of("code", "MATCH_STATE_CONFLICT", "status", "CHATTING", "action", "COMPLETE")));

        mockMvc.perform(auth(post("/api/exchange/matches/71/complete"))).andExpect(status().isNotFound());
        mockMvc.perform(auth(post("/api/exchange/matches/72/complete"))).andExpect(status().isConflict())
                .andExpect(content().json("""
                        {"message":"예약한 뒤에 교환 수락할 수 있어요.","code":"MATCH_STATE_CONFLICT","status":"CHATTING","action":"COMPLETE"}
                        """, true));
    }
}
