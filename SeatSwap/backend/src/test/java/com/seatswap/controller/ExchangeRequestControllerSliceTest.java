package com.seatswap.controller;

import com.seatswap.config.SecurityConfig;
import com.seatswap.dto.request.ExchangeRequestCreateRequest;
import com.seatswap.dto.request.ExchangeRequestUpdateRequest;
import com.seatswap.dto.response.ExchangeRequestResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.security.CustomUserDetailsService;
import com.seatswap.security.JwtAccessDeniedHandler;
import com.seatswap.security.JwtAuthenticationEntryPoint;
import com.seatswap.security.JwtTokenProvider;
import com.seatswap.security.SecurityErrorResponseWriter;
import com.seatswap.dto.response.ExchangeCandidateResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.service.ExchangeCandidateService;
import com.seatswap.service.ExchangeRequestService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/exchange/requests — 실제 SecurityConfig + JwtAuthenticationFilter + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = ExchangeRequestController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class ExchangeRequestControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private ExchangeRequestService exchangeRequestService;
    @MockBean
    private ExchangeCandidateService exchangeCandidateService;

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

    private static final String BODY = """
            {"ticketId":500,
             "wantSessions":[{"sessionId":8,"priority":1}],
             "ranges":[{"zone":"B","rowFrom":"1","rowTo":"2","colFrom":"3","colTo":"5","extraType":"NEG","extraAmount":-10000}]}
            """;

    private static final String PATCH_BODY = """
            {"wantSessions":[{"sessionId":8,"priority":1}],
             "ranges":[{"zone":"B","rowFrom":"1","rowTo":"2","colFrom":"3","colTo":"5","extraType":"X"}]}
            """;

    private static ExchangeRequestResponse response() {
        return new ExchangeRequestResponse(900L, 500L, "OPEN",
                List.of(new ExchangeRequestResponse.WantSessionItem(8L, 1, LocalDateTime.of(2026, 11, 2, 19, 0))),
                List.of(new ExchangeRequestResponse.WantRangeItem("B", "1", "2", "3", "5", "NEG", -10000)),
                6,
                LocalDateTime.of(2026, 10, 6, 14, 3, 21),
                LocalDateTime.of(2026, 10, 6, 14, 3, 21));
    }

    private static final String RESPONSE_JSON = """
            {"id":900,"ticketId":500,"status":"OPEN",
             "wantSessions":[{"sessionId":8,"priority":1,"startsAt":"2026-11-02T19:00"}],
             "ranges":[{"zone":"B","rowFrom":"1","rowTo":"2","colFrom":"3","colTo":"5","extraType":"NEG","extraAmount":-10000}],
             "wantSeatCount":6,"createdAt":"2026-10-06T14:03:21","updatedAt":"2026-10-06T14:03:21"}
            """;

    @Test
    void candidatesRequireLogin() throws Exception {
        mockMvc.perform(get("/api/exchange/requests/900/candidates")).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
    }

    @Test
    void candidatesReturnPageShapeWithDefaults() throws Exception {
        var item = new ExchangeCandidateResponse(901L, 1001L, "B", "2", "3", 8L,
                LocalDateTime.of(2026, 11, 2, 19, 0), "닉", 1, "NEG", -8000, "POS", 5000,
                new ExchangeCandidateResponse.SettlementHint(5000, 8000), LocalDateTime.of(2026, 10, 5, 9, 0, 1));
        when(exchangeCandidateService.findCandidates(1L, 900L, 0, 20))
                .thenReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1));

        mockMvc.perform(auth(get("/api/exchange/requests/900/candidates")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"content":[{"requestId":901,"ticketId":1001,"zone":"B","row":"2","col":"3","sessionId":8,
                          "startsAt":"2026-11-02T19:00","nickname":"닉","wantPriority":1,
                          "extraType":"NEG","extraAmount":-8000,"myExtraType":"POS","myExtraAmount":5000,
                          "settlementHint":{"min":5000,"max":8000},"requestedAt":"2026-10-05T09:00:01"}],
                         "page":0,"size":20,"totalElements":1,"totalPages":1}
                        """, true));
    }

    @Test
    void candidatesPassPageAndSizeAndNullHint() throws Exception {
        var item = new ExchangeCandidateResponse(901L, 1001L, "B", "2", "3", 8L,
                LocalDateTime.of(2026, 11, 2, 19, 0), "닉", 1, "X", null, "ANY", null, null,
                LocalDateTime.of(2026, 10, 5, 9, 0, 1));
        when(exchangeCandidateService.findCandidates(1L, 900L, 2, 50))
                .thenReturn(new PageResponse<>(List.of(item), 2, 50, 101, 3));

        mockMvc.perform(auth(get("/api/exchange/requests/900/candidates?page=2&size=50")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[{\"settlementHint\":null,\"extraAmount\":null}],\"page\":2,\"size\":50}"));
        verify(exchangeCandidateService).findCandidates(1L, 900L, 2, 50);
    }

    @Test
    void candidateScrollReturnsSliceShapeAndPassesCursorAndSize() throws Exception {
        var item = new ExchangeCandidateResponse(901L, 1001L, "B", "2", "3", 8L,
                LocalDateTime.of(2026, 11, 2, 19, 0), "닉", 1, "X", null, "ANY", null, null,
                LocalDateTime.of(2026, 10, 5, 9, 0, 1));
        when(exchangeCandidateService.findCandidateSlice(1L, 900L, "abc", 5))
                .thenReturn(new com.seatswap.dto.response.CandidateSliceResponse(List.of(item), 5, true, "next-cursor"));

        mockMvc.perform(auth(get("/api/exchange/requests/900/candidates/scroll?cursor=abc&size=5")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[{\"requestId\":901}],\"size\":5,\"hasNext\":true,\"nextCursor\":\"next-cursor\"}"));
        verify(exchangeCandidateService).findCandidateSlice(1L, 900L, "abc", 5);
    }

    @Test
    void candidateScrollDefaultsToFirstSliceAndRequiresLogin() throws Exception {
        when(exchangeCandidateService.findCandidateSlice(1L, 900L, null, 20))
                .thenReturn(new com.seatswap.dto.response.CandidateSliceResponse(List.of(), 20, false, null));

        mockMvc.perform(auth(get("/api/exchange/requests/900/candidates/scroll")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[],\"size\":20,\"hasNext\":false,\"nextCursor\":null}", true));
        mockMvc.perform(get("/api/exchange/requests/900/candidates/scroll")).andExpect(status().isUnauthorized());
    }

    @Test
    void candidatesMapErrorCodes() throws Exception {
        when(exchangeCandidateService.findCandidates(1L, 901L, 0, 20)).thenThrow(new ForbiddenException("본인"));
        when(exchangeCandidateService.findCandidates(1L, 902L, 0, 20)).thenThrow(new NotFoundException("없음"));
        when(exchangeCandidateService.findCandidates(1L, 903L, 0, 20))
                .thenThrow(new BusinessRuleException("TICKET_NOT_ACTIVE", "닫힘"));

        mockMvc.perform(auth(get("/api/exchange/requests/901/candidates"))).andExpect(status().isForbidden());
        mockMvc.perform(auth(get("/api/exchange/requests/902/candidates"))).andExpect(status().isNotFound());
        mockMvc.perform(auth(get("/api/exchange/requests/903/candidates"))).andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("{\"code\":\"TICKET_NOT_ACTIVE\",\"message\":\"닫힘\"}"));
    }

    @Test
    void deletedRequestCandidatesAndPatchAre409RequestDeleted() throws Exception {
        when(exchangeCandidateService.findCandidates(1L, 910L, 0, 20))
                .thenThrow(new ConflictException("삭제된 교환 요청입니다.", Map.of("code", "REQUEST_DELETED")));
        when(exchangeRequestService.update(eq(1L), eq(910L), any(ExchangeRequestUpdateRequest.class)))
                .thenThrow(new ConflictException("삭제된 교환 요청입니다.", Map.of("code", "REQUEST_DELETED")));

        mockMvc.perform(auth(get("/api/exchange/requests/910/candidates"))).andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"삭제된 교환 요청입니다.\",\"code\":\"REQUEST_DELETED\"}", true));
        mockMvc.perform(auth(patch("/api/exchange/requests/910")).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"삭제된 교환 요청입니다.\",\"code\":\"REQUEST_DELETED\"}", true));
    }

    @Test
    void candidatesRejectNonNumericId() throws Exception {
        mockMvc.perform(auth(get("/api/exchange/requests/abc/candidates"))).andExpect(status().isBadRequest());
    }

    @Test
    void allEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/exchange/requests/me")).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
        mockMvc.perform(post("/api/exchange/requests").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/exchange/requests/900").contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/exchange/requests/900")).andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns201WithResponseShape() throws Exception {
        when(exchangeRequestService.create(eq(1L), any(ExchangeRequestCreateRequest.class))).thenReturn(response());

        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(content().json(RESPONSE_JSON, true));
        verify(exchangeRequestService).create(eq(1L), argThat(r ->
                r.ticketId().equals(500L) && r.ranges().get(0).extraType().equals("NEG")
                        && r.ranges().get(0).extraAmount() == -10000 && r.wantSessions().get(0).priority() == 1 && r.ranges().get(0).colTo().equals("5")));
    }

    @Test
    void createValidationUsesFieldKeysWithIndexes() throws Exception {
        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wantSessions":[{"priority":0}],
                                 "ranges":[{"zone":"","rowFrom":"1","rowTo":"2","colFrom":"1","extraType":" "}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"ticketId":"티켓을 선택해주세요.",
                         "wantSessions[0].sessionId":"회차를 선택해주세요.",
                         "wantSessions[0].priority":"우선순위는 1 이상이어야 합니다.",
                         "ranges[0].zone":"구역을 입력해주세요.","ranges[0].colTo":"번 끝을 입력해주세요.",
                         "ranges[0].extraType":"추가금 유형을 선택해주세요."}
                        """, true));
        verify(exchangeRequestService, never()).create(any(), any());
    }

    @Test
    void emptyListsAreRejected() throws Exception {
        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":500,\"wantSessions\":[],\"ranges\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"wantSessions":"희망 회차를 1개 이상 선택해주세요.","ranges":"희망 좌석 범위를 1개 이상 입력해주세요."}
                        """, true));
        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":500,\"wantSessions\":[null],\"ranges\":[null]}"))
                .andExpect(status().isBadRequest());
        verify(exchangeRequestService, never()).create(any(), any());
    }

    @Test
    void malformedBodiesAre400WithMessage() throws Exception {
        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"요청 형식이 올바르지 않습니다.\"}", true));
        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ticketId\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"요청 형식이 올바르지 않습니다.\"}", true));
        mockMvc.perform(auth(patch("/api/exchange/requests/abc")).contentType(MediaType.APPLICATION_JSON)
                        .content(PATCH_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void serviceFieldErrorsKeepSameFormat() throws Exception {
        when(exchangeRequestService.create(eq(1L), any(ExchangeRequestCreateRequest.class)))
                .thenThrow(FieldValidationException.ofAll(new java.util.LinkedHashMap<>(Map.of(
                        "ranges[0].colTo", "번의 끝은 시작보다 크거나 같아야 합니다."))));

        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"ranges[0].colTo\":\"번의 끝은 시작보다 크거나 같아야 합니다.\"}", true));
    }

    @Test
    void duplicateRequestIs409WithCode() throws Exception {
        when(exchangeRequestService.create(eq(1L), any(ExchangeRequestCreateRequest.class)))
                .thenThrow(new ConflictException("이 티켓에는 이미 교환 요청이 있습니다.", Map.of("code", "REQUEST_ALREADY_EXISTS")));

        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().json(
                        "{\"message\":\"이 티켓에는 이미 교환 요청이 있습니다.\",\"code\":\"REQUEST_ALREADY_EXISTS\"}", true));
    }

    @Test
    void seatLimitIs422WithCountAndLimit() throws Exception {
        when(exchangeRequestService.create(eq(1L), any(ExchangeRequestCreateRequest.class)))
                .thenThrow(new BusinessRuleException("WANT_SEAT_LIMIT_EXCEEDED", "희망 좌석은 최대 5000석입니다.",
                        Map.of("count", 5001, "limit", 5000)));

        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("""
                        {"code":"WANT_SEAT_LIMIT_EXCEEDED","message":"희망 좌석은 최대 5000석입니다.","count":5001,"limit":5000}
                        """, true));
    }

    @Test
    void extraConflictIs422WithRangePairs() throws Exception {
        when(exchangeRequestService.create(eq(1L), any(ExchangeRequestCreateRequest.class)))
                .thenThrow(new BusinessRuleException("WANT_EXTRA_CONFLICT", "겹치는 희망 좌석 범위의 추가금이 서로 다릅니다.",
                        Map.of("conflicts", List.of(List.of(0, 1)))));

        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("""
                        {"code":"WANT_EXTRA_CONFLICT","message":"겹치는 희망 좌석 범위의 추가금이 서로 다릅니다.","conflicts":[[0,1]]}
                        """, true));
    }

    @Test
    void rangeExtraErrorKeysCarryTheRangeIndex() throws Exception {
        when(exchangeRequestService.create(eq(1L), any(ExchangeRequestCreateRequest.class)))
                .thenThrow(FieldValidationException.ofAll(new java.util.LinkedHashMap<>(Map.of(
                        "ranges[1].extraAmount", "받을 금액은 0보다 큰 금액을 입력해주세요."))));

        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"ranges[1].extraAmount\":\"받을 금액은 0보다 큰 금액을 입력해주세요.\"}", true));
    }

    @Test
    void forbiddenAndNotFoundForTicketOwnership() throws Exception {
        when(exchangeRequestService.create(eq(1L), any(ExchangeRequestCreateRequest.class)))
                .thenThrow(new ForbiddenException("본인의 티켓과 교환 요청만 다룰 수 있습니다."));
        mockMvc.perform(auth(post("/api/exchange/requests")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"message\":\"본인의 티켓과 교환 요청만 다룰 수 있습니다.\"}", true));

        when(exchangeRequestService.update(eq(1L), eq(404L), any(ExchangeRequestUpdateRequest.class)))
                .thenThrow(new NotFoundException("교환 요청을 찾을 수 없습니다."));
        mockMvc.perform(auth(patch("/api/exchange/requests/404")).contentType(MediaType.APPLICATION_JSON)
                        .content(PATCH_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void mineReturnsListAndPassesOptionalTicketId() throws Exception {
        when(exchangeRequestService.listMine(1L, null)).thenReturn(List.of(response()));
        when(exchangeRequestService.listMine(1L, 500L)).thenReturn(List.of());

        mockMvc.perform(auth(get("/api/exchange/requests/me")))
                .andExpect(status().isOk())
                .andExpect(content().json("[" + RESPONSE_JSON + "]", true));
        mockMvc.perform(auth(get("/api/exchange/requests/me")).param("ticketId", "500"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", true));
        verify(exchangeRequestService).listMine(eq(1L), isNull());
        mockMvc.perform(auth(get("/api/exchange/requests/me")).param("ticketId", "abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchReturns200WithUpdatedRequest() throws Exception {
        when(exchangeRequestService.update(eq(1L), eq(900L), any(ExchangeRequestUpdateRequest.class)))
                .thenReturn(response());

        mockMvc.perform(auth(patch("/api/exchange/requests/900")).contentType(MediaType.APPLICATION_JSON)
                        .content(PATCH_BODY))
                .andExpect(status().isOk())
                .andExpect(content().json(RESPONSE_JSON, true));
        verify(exchangeRequestService).update(eq(1L), eq(900L), argThat(r ->
                r.ranges().get(0).extraType().equals("X") && r.ranges().get(0).extraAmount() == null
                        && r.ranges().size() == 1));
    }

    @Test
    void deleteReturns204AndPropagatesErrors() throws Exception {
        mockMvc.perform(auth(delete("/api/exchange/requests/900"))).andExpect(status().isNoContent());
        verify(exchangeRequestService).delete(1L, 900L);

        doThrow(new ForbiddenException("본인의 티켓과 교환 요청만 다룰 수 있습니다.")).when(exchangeRequestService).delete(1L, 901L);
        mockMvc.perform(auth(delete("/api/exchange/requests/901"))).andExpect(status().isForbidden());
        doThrow(new NotFoundException("교환 요청을 찾을 수 없습니다.")).when(exchangeRequestService).delete(1L, 902L);
        mockMvc.perform(auth(delete("/api/exchange/requests/902"))).andExpect(status().isNotFound());
        doThrow(new ConflictException("예약된 매칭이 있습니다.", Map.of("code", "ACTIVE_MATCH_EXISTS")))
                .when(exchangeRequestService).delete(1L, 903L);
        mockMvc.perform(auth(delete("/api/exchange/requests/903"))).andExpect(status().isConflict());
    }
}
