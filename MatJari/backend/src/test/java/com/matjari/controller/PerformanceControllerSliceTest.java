package com.matjari.controller;

import com.matjari.config.SecurityConfig;
import com.matjari.dto.request.PerformanceCreateRequest;
import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.PerformanceDetailResponse;
import com.matjari.dto.response.PerformanceLookupResponse;
import com.matjari.dto.response.PerformanceSummaryResponse;
import com.matjari.dto.response.PerformanceZonesResponse;
import com.matjari.dto.response.SessionResponse;
import com.matjari.exception.ConflictException;
import com.matjari.exception.NotFoundException;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.CustomUserDetailsService;
import com.matjari.security.JwtAccessDeniedHandler;
import com.matjari.security.JwtAuthenticationEntryPoint;
import com.matjari.security.JwtTokenProvider;
import com.matjari.security.SecurityErrorResponseWriter;
import com.matjari.dto.response.PerformancePreviewResponse;
import com.matjari.exception.FieldValidationException;
import com.matjari.service.PerformanceService;
import com.matjari.service.PerformanceSourceService;
import com.matjari.service.linkinfo.PerformancePreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/performances — 실제 SecurityConfig + JwtAuthenticationFilter + GlobalExceptionHandler, 서비스는 mock. */
@WebMvcTest(controllers = PerformanceController.class)
@Import({SecurityConfig.class, JwtAuthenticationEntryPoint.class, JwtAccessDeniedHandler.class,
        SecurityErrorResponseWriter.class})
class PerformanceControllerSliceTest {

    private static final LocalDateTime SHOW = LocalDateTime.of(2026, 11, 1, 19, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtTokenProvider tokenProvider;
    @MockBean
    private CustomUserDetailsService userDetailsService;
    @MockBean
    private PerformanceService performanceService;
    @MockBean
    private PerformancePreviewService previewService;
    @MockBean
    private PerformanceSourceService sourceService;

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

    private static PerformanceDetailResponse detail(boolean canEdit) {
        return new PerformanceDetailResponse(100L, "두아 리파 내한", "https://tickets.interpark.com/goods/1",
                "KSPO DOME", null, null, List.of(),
                new PerformanceDetailResponse.Registrant(1L, "등록자"), canEdit,
                List.of(new SessionResponse(7L, SHOW)),
                LocalDateTime.of(2026, 10, 6, 14, 3, 21), false, List.of());
    }

    @Test
    void allEndpointsRequireLogin() throws Exception {
        mockMvc.perform(get("/api/performances")).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"message\":\"로그인이 필요합니다.\"}", true));
        mockMvc.perform(post("/api/performances").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listResponseShape() throws Exception {
        when(performanceService.search(eq("두아"), eq(0), eq(20), eq(null))).thenReturn(new PageResponse<>(
                List.of(new PerformanceSummaryResponse(100L, "두아 리파 내한",
                        "KSPO DOME", SHOW, 2, 3),
                        new PerformanceSummaryResponse(101L, "지난 공연",
                                "KSPO DOME", null, 1, 0)),
                0, 20, 2, 1));

        mockMvc.perform(auth(get("/api/performances").param("query", "두아")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"content":[
                          {"id":100,"title":"두아 리파 내한","venueName":"KSPO DOME",
                           "nextSessionStartsAt":"2026-11-01T19:00","sessionCount":2,"zoneCount":3},
                          {"id":101,"title":"지난 공연","venueName":"KSPO DOME",
                           "nextSessionStartsAt":null,"sessionCount":1,"zoneCount":0}],
                         "page":0,"size":20,"totalElements":2,"totalPages":1}
                        """, true));
    }

    @Test
    void lookupShapeIncludesNullPerformanceId() throws Exception {
        when(performanceService.lookup("https://example.com/x")).thenReturn(PerformanceLookupResponse.notFound());
        when(performanceService.lookup("https://tickets.interpark.com/goods/1"))
                .thenReturn(PerformanceLookupResponse.found(100L));

        mockMvc.perform(auth(get("/api/performances/lookup").param("sourceUrl", "https://example.com/x")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"exists\":false,\"performanceId\":null}", true));
        mockMvc.perform(auth(get("/api/performances/lookup")
                        .param("sourceUrl", "https://tickets.interpark.com/goods/1")))
                .andExpect(content().json("{\"exists\":true,\"performanceId\":100}", true));
    }

    @Test
    void detailResponseShape() throws Exception {
        when(performanceService.get(100L, 1L)).thenReturn(detail(true));

        mockMvc.perform(auth(get("/api/performances/100")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"id":100,"title":"두아 리파 내한","sourceUrl":"https://tickets.interpark.com/goods/1",
                         "venueName":"KSPO DOME","seatmapAspect":null,"seatmapImage":null,"zones":[],
                         "registrant":{"id":1,"nickname":"등록자"},"canEdit":true,
                         "sessions":[{"id":7,"startsAt":"2026-11-01T19:00"}],
                         "createdAt":"2026-10-06T14:03:21","hidden":false,"sources":[]}
                        """, true));
    }

    @Test
    void zonesEndpointShapeAndNotFound() throws Exception {
        when(performanceService.getZones(100L)).thenReturn(new PerformanceZonesResponse(
                new java.math.BigDecimal("1.500"),
                new com.matjari.dto.response.SeatmapImageResponse("https://cdnticket.melon.co.kr/resource/image/upload/product/a.jpg",
                        new java.math.BigDecimal("0.100000"), new java.math.BigDecimal("0.250000"),
                        new java.math.BigDecimal("0.800000"), new java.math.BigDecimal("0.500000")), List.of(
                new PerformanceDetailResponse.Zone("A구역", List.of(List.of(List.of(0, 0), List.of(10, 0), List.of(10, 10)))),
                new PerformanceDetailResponse.Zone("스탠딩", null)),
                List.of(new PerformanceZonesResponse.MapItem(7L, "interpark", "인터파크(NOL)", false, "PENDING", null, null, List.of()))));
        when(performanceService.getZones(404L)).thenThrow(new NotFoundException("공연을 찾을 수 없습니다."));

        mockMvc.perform(auth(get("/api/performances/100/zones")))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"seatmapAspect":1.5,"seatmapImage":{"url":"https://cdnticket.melon.co.kr/resource/image/upload/product/a.jpg",
                          "left":0.1,"top":0.25,"width":0.8,"height":0.5},"zones":[
                          {"name":"A구역","shapePolygons":[[[0,0],[10,0],[10,10]]]},
                          {"name":"스탠딩","shapePolygons":null}],
                          "maps":[{"sourceId":7,"site":"interpark","siteName":"인터파크(NOL)","primary":false,"status":"PENDING",
                            "aspect":null,"seatmapImage":null,"zones":[]}]}
                        """, true));
        mockMvc.perform(auth(get("/api/performances/404/zones"))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/performances/100/zones")).andExpect(status().isUnauthorized());
    }

    @Test
    void createAcceptsZonesAndRejectsOverHundred() throws Exception {
        when(performanceService.create(eq(1L), any(PerformanceCreateRequest.class), anyBoolean())).thenReturn(detail(true));
        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceUrl":"https://tickets.interpark.com/goods/1","title":"t","venueName":"V",
                         "sessions":["2026-11-01T19:00"],"seatmapAspect":1.25,"ticket":{"sessionStartsAt":"2026-11-01T19:00","zone":"A","row":"1","col":"2"},
                         "zones":[{"name":"A","shapePolygons":[[[0,0],[1,0],[1,1]]]},{"name":"B"},
                                  {"name":"C","shapePolygons":"bad"}]}
                        """))
                .andExpect(status().isCreated());
        verify(performanceService).create(eq(1L), argThat(r -> r.zones().size() == 3
                && r.zones().get(1).shapePolygons() == null
                && r.seatmapAspect().compareTo(new java.math.BigDecimal("1.25")) == 0), eq(false));

        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 1001; i++) {
            many.append(i == 0 ? "" : ",").append("{\"name\":\"z").append(i).append("\"}");
        }
        many.append("]");
        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"sourceUrl\":\"https://a.com\",\"title\":\"t\",\"venueName\":\"V\","
                                + "\"sessions\":[\"2026-11-01T19:00\"],\"zones\":" + many + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.zones").value("구역은 한 번에 1,000개까지 등록할 수 있습니다."));
    }

    @Test
    void notFoundAndBadPathVariable() throws Exception {
        when(performanceService.get(404L, 1L)).thenThrow(new NotFoundException("공연을 찾을 수 없습니다."));
        mockMvc.perform(auth(get("/api/performances/404")))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"message\":\"공연을 찾을 수 없습니다.\"}", true));
        mockMvc.perform(auth(get("/api/performances/abc")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"요청 형식이 올바르지 않습니다.\"}", true));
    }

    @Test
    void createReturns201AndAcceptsMinuteFormat() throws Exception {
        when(performanceService.create(eq(1L), any(PerformanceCreateRequest.class), anyBoolean())).thenReturn(detail(true));

        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceUrl":"https://tickets.interpark.com/goods/1","title":"두아 리파 내한",
                         "venueName":"KSPO DOME","sessions":["2026-11-01T19:00","2026-11-02T18:00"],"ticket":{"sessionStartsAt":"2026-11-01T19:00","zone":"A","row":"1","col":"2"}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(100));
        verify(performanceService).create(eq(1L), argThat(r ->
                r.sessions().equals(List.of(SHOW, LocalDateTime.of(2026, 11, 2, 18, 0)))), eq(false));
    }

    @Test
    void createDuplicateIs409WithPerformanceId() throws Exception {
        when(performanceService.create(eq(1L), any(PerformanceCreateRequest.class), anyBoolean()))
                .thenThrow(new ConflictException("이미 등록된 공연입니다.", Map.of("performanceId", 77L)));

        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceUrl":"https://tickets.interpark.com/goods/1","title":"t","venueName":"KSPO DOME",
                         "sessions":["2026-11-01T19:00"],"ticket":{"sessionStartsAt":"2026-11-01T19:00","zone":"A","row":"1","col":"2"}}
                        """))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"이미 등록된 공연입니다.\",\"performanceId\":77}", true));
    }

    @Test
    void adminCanCreatePerformanceWithoutTicket() throws Exception {
        when(tokenProvider.validateToken("admin")).thenReturn(true);
        when(tokenProvider.isRefreshToken("admin")).thenReturn(false);
        when(tokenProvider.getUserId("admin")).thenReturn(9L);
        when(userDetailsService.loadUserById(9L)).thenReturn(AuthUserPrincipal.of(9L, "admin@b.com", com.matjari.domain.UserRole.ADMIN));
        when(performanceService.create(any(), any(), anyBoolean())).thenReturn(detail(false));
        mockMvc.perform(post("/api/performances").header("Authorization", "Bearer admin").contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceUrl":"https://a.com","title":"t","venueName":"V","sessions":["2026-11-01T19:00"]}
                        """))
                .andExpect(status().isCreated());
        verify(performanceService).create(eq(9L), any(PerformanceCreateRequest.class), eq(true)); // 관리자는 같은 공연 후보 검사를 건너뛴다
    }

    @Test
    void createPassesConfirmNewPerformanceAndSurfacesSimilarCandidatesAs409() throws Exception {
        var candidate = new com.matjari.dto.response.PerformanceSourceDtos.Candidate(7L, "아이유 콘서트", "코엑스 D홀",
                LocalDateTime.of(2026, 12, 10, 19, 0), LocalDateTime.of(2026, 12, 20, 19, 0), 5, List.of("melon"));
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("code", "SIMILAR_PERFORMANCE_EXISTS");
        details.put("candidates", List.of(candidate));
        when(performanceService.create(eq(1L), any(PerformanceCreateRequest.class), anyBoolean()))
                .thenThrow(new ConflictException("같은 공연일 수 있는 공연이 이미 있어요.", details));
        String body = """
                {"sourceUrl":"https://a.com","title":"t","venueName":"V","sessions":["2026-11-01T19:00"],
                 "ticket":{"sessionStartsAt":"2026-11-01T19:00","zone":"A","row":"1","col":"2"},"confirmNewPerformance":%s}
                """;
        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content(body.formatted("false")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIMILAR_PERFORMANCE_EXISTS"))
                .andExpect(jsonPath("$.candidates[0].id").value(7))
                .andExpect(jsonPath("$.candidates[0].firstSessionAt").value("2026-12-10T19:00"))
                .andExpect(jsonPath("$.candidates[0].sites[0]").value("melon"));

        when(performanceService.create(eq(1L), argThat(r -> Boolean.TRUE.equals(r.confirmNewPerformance())), anyBoolean()))
                .thenReturn(detail(true));
        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content(body.formatted("true")))
                .andExpect(status().isCreated());
    }

    @Test
    void similarReturnsPreviewExistingIdAndCandidates() throws Exception {
        var candidate = new com.matjari.dto.response.PerformanceSourceDtos.Candidate(7L, "아이유 콘서트", "코엑스 D홀",
                LocalDateTime.of(2026, 12, 10, 19, 0), LocalDateTime.of(2026, 12, 20, 19, 0), 5, List.of("melon", "interpark"));
        when(sourceService.similar(1L, "https://nol.yanolja.com/ticket/products/1")).thenReturn(
                new com.matjari.dto.response.PerformanceSourceDtos.SimilarResponse(
                        new com.matjari.dto.response.PerformanceSourceDtos.PreviewInfo("OK", "아이유 콘서트", "코엑스 D홀",
                                java.time.LocalDate.of(2026, 12, 10), java.time.LocalDate.of(2026, 12, 20)),
                        null, List.of(candidate)));

        mockMvc.perform(auth(get("/api/performances/similar")).param("sourceUrl", "https://nol.yanolja.com/ticket/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preview.status").value("OK"))
                .andExpect(jsonPath("$.preview.periodStart").value("2026-12-10"))
                .andExpect(jsonPath("$.existingPerformanceId").doesNotExist())
                .andExpect(jsonPath("$.candidates[0].title").value("아이유 콘서트"))
                .andExpect(jsonPath("$.candidates[0].sessionCount").value(5))
                .andExpect(jsonPath("$.candidates[0].sites[1]").value("interpark"));
        mockMvc.perform(get("/api/performances/similar").param("sourceUrl", "x")).andExpect(status().isUnauthorized());
    }

    @Test
    void addSourceReturns201ForNewAnd200ForIdempotentAndPassesErrors() throws Exception {
        var item = new com.matjari.dto.response.PerformanceSourceDtos.SourceItem(2L, "interpark", "인터파크(NOL)", "https://nol", false);
        when(sourceService.addByUser(1L, 100L, "https://nol")).thenReturn(
                new PerformanceSourceService.AddResult(List.of(item), true, null));
        when(sourceService.addByUser(1L, 100L, "https://again")).thenReturn(
                new PerformanceSourceService.AddResult(List.of(item), false, null));
        when(sourceService.addByUser(1L, 100L, "https://other")).thenThrow(new ConflictException(
                "이미 다른 공연에 등록된 링크입니다.", Map.of("code", "SOURCE_ALREADY_REGISTERED", "performanceId", 200L)));
        when(sourceService.addByUser(1L, 100L, "https://mismatch")).thenThrow(
                new com.matjari.exception.BusinessRuleException("SOURCE_NOT_SAME_PERFORMANCE", "같은 공연으로 확인되지 않아요."));

        mockMvc.perform(auth(post("/api/performances/100/sources")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceUrl\":\"https://nol\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sources[0].siteName").value("인터파크(NOL)"))
                .andExpect(jsonPath("$.sources[0].primary").value(false));
        mockMvc.perform(auth(post("/api/performances/100/sources")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceUrl\":\"https://again\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(auth(post("/api/performances/100/sources")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceUrl\":\"https://other\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.performanceId").value(200));
        mockMvc.perform(auth(post("/api/performances/100/sources")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceUrl\":\"https://mismatch\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SOURCE_NOT_SAME_PERFORMANCE"));
        mockMvc.perform(auth(post("/api/performances/100/sources")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceUrl\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.sourceUrl").value("티켓팅 링크를 입력해주세요."));
        mockMvc.perform(post("/api/performances/100/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceUrl\":\"https://nol\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createValidation() throws Exception {
        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceUrl":"","title":" ","sessions":[]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("""
                        {"sourceUrl":"티켓팅 링크를 입력해주세요.","title":"공연 제목을 입력해주세요.",
                         "venueName":"공연장 이름을 입력해주세요.","sessions":"회차를 1개 이상 입력해주세요."}
                        """, true));
        // 일반 사용자는 티켓 없이 공연만 등록할 수 없다(관리자만 가능)
        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceUrl":"https://a.com","title":"t","venueName":"V","sessions":["2026-11-01T19:00"]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"ticket\":\"내 티켓 정보를 입력해주세요.\"}", true));
        // 티켓 칸 검사: 중첩 필드는 ticket.* 키 (공연만 따로 등록하는 길은 없다)
        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceUrl":"https://a.com","title":"t","venueName":"V","sessions":["2026-11-01T19:00"],
                         "ticket":{"zone":" ","row":"1","col":""}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$['ticket.sessionStartsAt']").value("회차를 선택해주세요."))
                .andExpect(jsonPath("$['ticket.zone']").value("구역을 입력해주세요."))
                .andExpect(jsonPath("$['ticket.col']").value("번을 입력해주세요."));
        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceUrl":"https://a.com","title":"t","venueName":"KSPO DOME","sessions":["2026-11-01 19:00"]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"요청 형식이 올바르지 않습니다.\"}", true));
        verify(performanceService, never()).create(any(), any(), anyBoolean());
    }

    @Test
    void asOfParameterIsParsedAndInvalidFormatIs400() throws Exception {
        when(performanceService.search(eq(null), eq(1), eq(20), eq(LocalDateTime.of(2026, 10, 6, 12, 0))))
                .thenReturn(new PageResponse<>(List.of(), 1, 20, 0, 0));
        mockMvc.perform(auth(get("/api/performances").param("page", "1").param("asOf", "2026-10-06T12:00")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"content\":[],\"page\":1,\"size\":20,\"totalElements\":0,\"totalPages\":0}", true));

        mockMvc.perform(auth(get("/api/performances").param("asOf", "2026-10-06 12:00")))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"message\":\"요청 형식이 올바르지 않습니다.\"}", true));
    }

    @Test
    void unclassifiedIntegrityViolationIs409GenericMessage() throws Exception {
        when(performanceService.create(eq(1L), any(PerformanceCreateRequest.class), anyBoolean()))
                .thenThrow(new DataIntegrityViolationException("fk"));
        mockMvc.perform(auth(post("/api/performances")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"sourceUrl":"https://tickets.interpark.com/goods/1","title":"두아 리파 내한",
                         "venueName":"KSPO DOME","sessions":["2026-11-01T19:00"],"ticket":{"sessionStartsAt":"2026-11-01T19:00","zone":"A","row":"1","col":"2"}}
                        """))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"요청이 다른 변경과 충돌했습니다. 다시 시도해주세요.\"}", true));
    }

    @Test
    void previewRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/performances/preview").param("sourceUrl", "https://ticket.yes24.com/Perf/1"))
                .andExpect(status().isUnauthorized());
        verify(previewService, never()).preview(any(), any());
    }

    @Test
    void previewOkShape() throws Exception {
        when(previewService.preview(1L, "https://ticket.yes24.com/Perf/1")).thenReturn(
                PerformancePreviewResponse.ok("yes24", "공연 A", "홀", java.time.LocalDate.of(2026, 10, 11),
                        java.time.LocalDate.of(2026, 10, 13), "https://tkfile.yes24.com/p.jpg"));
        mockMvc.perform(auth(get("/api/performances/preview").param("sourceUrl", "https://ticket.yes24.com/Perf/1")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"OK\",\"site\":\"yes24\",\"title\":\"공연 A\","
                        + "\"venueName\":\"홀\",\"periodStart\":\"2026-10-11\",\"periodEnd\":\"2026-10-13\",\"posterUrl\":\"https://tkfile.yes24.com/p.jpg\","
                        + "\"sessionCandidates\":[{\"date\":\"2026-10-11\",\"time\":null},{\"date\":\"2026-10-12\",\"time\":null},"
                        + "{\"date\":\"2026-10-13\",\"time\":null}]}", true));
    }

    @Test
    void previewNonOkStatusesAre200WithNulls() throws Exception {
        when(previewService.preview(1L, "https://tickets.interpark.com/goods/1"))
                .thenReturn(PerformancePreviewResponse.empty("UNSUPPORTED_SITE", null));
        mockMvc.perform(auth(get("/api/performances/preview").param("sourceUrl", "https://tickets.interpark.com/goods/1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNSUPPORTED_SITE"))
                .andExpect(jsonPath("$.site").doesNotExist())
                .andExpect(jsonPath("$.title").doesNotExist());
    }

    @Test
    void previewBadUrlIs400() throws Exception {
        when(previewService.preview(1L, "nope"))
                .thenThrow(new FieldValidationException("sourceUrl", "올바른 티켓팅 링크(http/https)가 아닙니다."));
        mockMvc.perform(auth(get("/api/performances/preview").param("sourceUrl", "nope")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void previewRateLimitedIs429WithRetryAfter() throws Exception {
        when(previewService.preview(1L, "https://ticket.yes24.com/Perf/1"))
                .thenThrow(new PerformancePreviewService.PreviewRateLimitedException(42));
        mockMvc.perform(auth(get("/api/performances/preview").param("sourceUrl", "https://ticket.yes24.com/Perf/1")))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.code").value("PREVIEW_RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(42));
    }
}
