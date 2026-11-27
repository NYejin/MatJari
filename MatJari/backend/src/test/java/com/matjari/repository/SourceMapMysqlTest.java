package com.matjari.repository;

import com.matjari.dto.request.PerformanceCreateRequest;
import com.matjari.dto.response.PerformanceDetailResponse;
import com.matjari.dto.response.PerformancePreviewResponse;
import com.matjari.dto.response.PerformanceSourceDtos.SourceItem;
import com.matjari.dto.response.PerformanceZonesResponse;
import com.matjari.dto.response.SeatmapImageResponse;
import com.matjari.exception.FieldValidationException;
import com.matjari.service.AdminPerformanceService;
import com.matjari.service.PerformanceService;
import com.matjari.service.PerformanceSourceService;
import com.matjari.service.aiextract.DetailExtractionService;
import com.matjari.service.aiextract.DetailExtractionService.ZoneDto;
import com.matjari.service.linkinfo.PerformancePreviewService;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V34(추가 예매처 링크의 지도) 실제 MySQL 검증 (MATJARI_IT_* 환경변수가 있을 때만). `X_srcmap_it` 별도 DB.
 * 확인: ddl-auto=validate 로 엔티티 매핑, 링크 추가 -> 커밋 뒤 비동기 읽기 -> 지도 저장(PENDING -> READY/FAILED), 사용자 월 횟수 미사용,
 * 지도별 구역 이름 유일(같은 이름이 지도마다 하나씩 가능), 조회 maps 순서, 대표 지도 교체가 추가 지도를 건드리지 않음,
 * 관리자 다시 읽기·삭제·대표 링크 거절·조치 기록, 링크 삭제 시 구역 삭제, 공연 완전 삭제 순서. AI·미리보기(외부 요청)는 목으로 대체한다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SourceMapMysqlTest {

    private static final LocalDateTime D1 = LocalDateTime.of(2099, 12, 10, 19, 0);
    private static final LocalDateTime D2 = LocalDateTime.of(2099, 12, 12, 19, 0);
    private static final String MELON = "https://ticket.melon.com/performance/index.htm?prodId=100";
    private static final String NOL = "https://nol.yanolja.com/ticket/products/26014836";
    private static final String NOL2 = "https://nol.yanolja.com/ticket/products/26014837";
    private static final List<List<List<Integer>>> SHAPE = List.of(List.of(List.of(0, 0), List.of(100, 0), List.of(100, 100)));
    private static final SeatmapImageResponse IMAGE = SeatmapImageResponse.ofRatios(
            "https://ticketimage.interpark.com/Play/image/etc/26/26014836-02.jpg", 0.1, 0.2, 0.5, 0.4);

    private static String srcUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "").replaceAll("_srcmap$", "") + "_srcmap_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.assertSafe();
        registry.add("spring.datasource.url", SourceMapMysqlTest::srcUrl);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PerformanceService performanceService;
    @Autowired
    private PerformanceSourceService sourceService;
    @Autowired
    private AdminPerformanceService adminService;

    @MockBean
    private PerformancePreviewService previewService;
    @MockBean
    private DetailExtractionService extraction;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_srcmap_it");
        // 앞 테스트의 비동기 읽기가 끝나기를 잠깐 기다린 뒤 지운다(공연이 없으면 저장하지 않고 끝난다)
        for (String t : List.of("ai_extract_usage", "admin_action_log", "ticket", "performance_edit_proposal", "performance_zone",
                "performance_session", "performance_source", "performance", "refresh_token", "users")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (1,'u1@t','x','U1','USER',NOW(6))");
        jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (9,'a9@t','x','관리자','ADMIN',NOW(6))");
        when(previewService.preview(anyLong(), anyString())).thenReturn(
                PerformancePreviewResponse.ok("interpark", "아이유 콘서트", "코엑스 D홀",
                        LocalDate.of(2099, 12, 11), LocalDate.of(2099, 12, 12), null));
        when(extraction.extractForSourceMap(anyString())).thenReturn(response("NOL A", "NOL B"));
        when(extraction.extractForAdmin(anyString())).thenReturn(response("N1", "N2", "N3"));
    }

    private static DetailExtractionService.Response response(String... names) {
        List<ZoneDto> zones = java.util.Arrays.stream(names).map(n -> new ZoneDto(n, SHAPE)).toList();
        return new DetailExtractionService.Response(List.of(), null, zones, 1.25, true, false, false, false, false, IMAGE);
    }

    private Long registerMelon() {
        return performanceService.create(1L, new PerformanceCreateRequest(MELON, "아이유 콘서트", "코엑스 D홀", List.of(D1, D2),
                null, null, null, false)).id();
    }

    private PerformanceDetailResponse detail(Long id) {
        return performanceService.get(id, 1L);
    }

    private Long extraSourceId(Long performanceId, String key) {
        return jdbc.queryForObject("SELECT id FROM performance_source WHERE performance_id = ? AND source_key = ?", Long.class, performanceId, key);
    }

    private String mapStatus(Long sourceId) {
        return jdbc.queryForObject("SELECT map_status FROM performance_source WHERE id = ?", String.class, sourceId);
    }

    private void awaitStatus(Long sourceId, String expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (expected.equals(mapStatus(sourceId))) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(mapStatus(sourceId)).isEqualTo(expected);
    }

    private void setPrimaryZones(Long id, String... names) {
        List<PerformanceCreateRequest.ZoneInput> zones = java.util.Arrays.stream(names)
                .map(n -> new PerformanceCreateRequest.ZoneInput(n, SHAPE)).toList();
        adminService.replaceZones(9L, id, new AdminPerformanceService.ZonesRequest(zones, new java.math.BigDecimal("1.5"), "대표 지도"));
    }

    @Test
    void 링크를_붙이면_커밋_뒤_비동기로_읽어_그_링크의_구역으로_저장한다_월_횟수는_세지_않는다() throws Exception {
        Long id = registerMelon();
        setPrimaryZones(id, "멜론 A");
        CountDownLatch release = new CountDownLatch(1);
        when(extraction.extractForSourceMap(anyString())).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return response("NOL A", "NOL B");
        });

        var added = sourceService.addByUser(1L, id, NOL);
        assertThat(added.created()).isTrue();
        Long sourceId = extraSourceId(id, "interpark:26014836");
        // 읽는 동안: PENDING + 지도 목록에는 빈 zones 로 들어간다
        assertThat(mapStatus(sourceId)).isEqualTo("PENDING");
        PerformanceZonesResponse pending = performanceService.getZones(id);
        assertThat(pending.maps()).extracting(PerformanceZonesResponse.MapItem::status).containsExactly("READY", "PENDING");
        assertThat(pending.maps().get(1).zones()).isEmpty();
        assertThat(detail(id).sources()).extracting(SourceItem::mapStatus).containsExactly("READY", "PENDING");

        release.countDown();
        awaitStatus(sourceId, "READY");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_zone WHERE source_id = ?", Integer.class, sourceId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT seatmap_aspect FROM performance_source WHERE id = ?", java.math.BigDecimal.class, sourceId))
                .isEqualByComparingTo("1.25");
        assertThat(jdbc.queryForObject("SELECT seatmap_image_url FROM performance_source WHERE id = ?", String.class, sourceId))
                .isEqualTo(IMAGE.url());
        // 대표 링크 행은 지도 정보를 채우지 않는다
        assertThat(jdbc.queryForObject("SELECT map_status FROM performance_source WHERE source_key = 'melon:100'", String.class)).isEqualTo("NONE");
        // 월 횟수 기록 없음, 사용자 경로 extract 는 호출하지 않는다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_extract_usage", Integer.class)).isZero();
        verify(extraction, never()).extract(anyLong(), anyString());

        // 조회: 대표 지도 먼저, 그다음 추가 링크. 기존 필드는 대표 지도 그대로
        PerformanceZonesResponse zones = performanceService.getZones(id);
        assertThat(zones.zones()).extracting(PerformanceDetailResponse.Zone::name).containsExactly("멜론 A");
        assertThat(zones.maps()).extracting(PerformanceZonesResponse.MapItem::sourceId).containsExactly(null, sourceId);
        assertThat(zones.maps()).extracting(PerformanceZonesResponse.MapItem::primary).containsExactly(true, false);
        assertThat(zones.maps().get(1).site()).isEqualTo("interpark");
        assertThat(zones.maps().get(1).zones()).extracting(PerformanceDetailResponse.Zone::name).containsExactly("NOL A", "NOL B");
        assertThat(zones.maps().get(1).seatmapImage().url()).isEqualTo(IMAGE.url());
        assertThat(detail(id).sources()).extracting(SourceItem::zoneCount).containsExactly(1L, 2L);
        assertThat(detail(id).sources()).extracting(SourceItem::mapStatus).containsExactly("READY", "READY");
        // 대표 지도의 구역 조회·개수는 추가 지도 구역을 세지 않는다
        assertThat(detail(id).zones()).hasSize(1);
    }

    @Test
    void 읽지_못하면_FAILED가_되고_조회_지도_목록에는_나오지_않는다() throws Exception {
        Long id = registerMelon();
        when(extraction.extractForSourceMap(anyString())).thenReturn(
                new DetailExtractionService.Response(List.of(), null, List.of(), null, false, false, false, false));

        sourceService.addByUser(1L, id, NOL);
        Long sourceId = extraSourceId(id, "interpark:26014836");
        awaitStatus(sourceId, "FAILED");

        assertThat(performanceService.getZones(id).maps()).isEmpty();
        assertThat(detail(id).sources()).extracting(SourceItem::mapStatus).containsExactly("NONE", "FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_zone WHERE source_id = ?", Integer.class, sourceId)).isZero();
    }

    @Test
    void 같은_구역_이름이_대표_지도와_추가_지도에_각각_있을_수_있고_한_지도_안에서는_유일하다() throws Exception {
        Long id = registerMelon();
        setPrimaryZones(id, "A");
        when(extraction.extractForSourceMap(anyString())).thenReturn(response("A", "B"));
        sourceService.addByUser(1L, id, NOL);
        Long sourceId = extraSourceId(id, "interpark:26014836");
        awaitStatus(sourceId, "READY");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_zone WHERE performance_id = ? AND name_key = 'A'", Integer.class, id))
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_zone(performance_id, source_id, name, name_key, sort_order, created_at) "
                + "VALUES (?, ?, 'A', 'A', 5, NOW(6))", id, sourceId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_performance_zone_name_key");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_zone(performance_id, source_id, name, name_key, sort_order, created_at) "
                + "VALUES (?, NULL, 'A', 'A', 5, NOW(6))", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_performance_zone_name_key");
        // 없는 링크를 가리킬 수 없다
        assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_zone(performance_id, source_id, name, name_key, sort_order, created_at) "
                + "VALUES (?, 999999, 'Z', 'Z', 5, NOW(6))", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_performance_zone_source");
    }

    @Test
    void 대표_지도_교체는_추가_지도를_건드리지_않는다() throws Exception {
        Long id = registerMelon();
        setPrimaryZones(id, "A", "B");
        sourceService.addByUser(1L, id, NOL);
        Long sourceId = extraSourceId(id, "interpark:26014836");
        awaitStatus(sourceId, "READY");

        setPrimaryZones(id, "C");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_zone WHERE source_id = ?", Integer.class, sourceId)).isEqualTo(2);
        assertThat(performanceService.getZones(id).zones()).extracting(PerformanceDetailResponse.Zone::name).containsExactly("C");
        // 대표 지도를 비워도 추가 지도는 남고, 대표 지도는 maps 에서 빠진다
        adminService.replaceZones(9L, id, new AdminPerformanceService.ZonesRequest(List.of(), null, "비움"));
        assertThat(performanceService.getZones(id).maps()).extracting(PerformanceZonesResponse.MapItem::sourceId).containsExactly(sourceId);
    }

    @Test
    void 관리자_다시_읽기는_덮어쓰고_대표_링크는_400이며_삭제는_NONE이고_조치_기록에_sourceId가_남는다() throws Exception {
        Long id = registerMelon();
        sourceService.addByUser(1L, id, NOL);
        Long sourceId = extraSourceId(id, "interpark:26014836");
        awaitStatus(sourceId, "READY");
        Long primaryId = extraSourceId(id, "melon:100");

        assertThatThrownBy(() -> adminService.reextractSourceMap(9L, id, primaryId, "x")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> adminService.reextractSourceMap(9L, id, sourceId, " ")).isInstanceOf(FieldValidationException.class);

        var item = adminService.reextractSourceMap(9L, id, sourceId, "다시 읽기");
        assertThat(item.zones()).extracting(PerformanceDetailResponse.Zone::name).containsExactly("N1", "N2", "N3");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_zone WHERE source_id = ?", Integer.class, sourceId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE action = 'PERFORMANCE_ZONES' AND target_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT detail FROM admin_action_log WHERE action = 'PERFORMANCE_ZONES'", String.class))
                .contains("\"sourceId\": " + sourceId).contains("REEXTRACT");

        var remaining = adminService.clearSourceMap(9L, id, sourceId, "지도 삭제");
        assertThat(remaining).extracting(SourceItem::mapStatus).containsExactly("NONE", "NONE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_zone WHERE source_id = ?", Integer.class, sourceId)).isZero();
        assertThat(jdbc.queryForObject("SELECT seatmap_image_url FROM performance_source WHERE id = ?", String.class, sourceId)).isNull();
        assertThat(jdbc.queryForObject("SELECT detail FROM admin_action_log WHERE action = 'PERFORMANCE_ZONES' ORDER BY id DESC LIMIT 1", String.class))
                .contains("\"sourceId\": " + sourceId).contains("CLEAR");
        assertThatThrownBy(() -> adminService.clearSourceMap(9L, id, primaryId, "x")).isInstanceOf(FieldValidationException.class);
        assertThat(performanceService.getZones(id).maps()).isEmpty();
    }

    @Test
    void 링크를_삭제하면_그_링크의_구역도_지워지고_공연_완전_삭제도_된다() throws Exception {
        Long id = registerMelon();
        sourceService.addByUser(1L, id, NOL);
        Long first = extraSourceId(id, "interpark:26014836");
        awaitStatus(first, "READY");
        when(extraction.extractForSourceMap(anyString())).thenReturn(response("X"));
        sourceService.addByUser(1L, id, NOL2);
        Long second = extraSourceId(id, "interpark:26014837");
        awaitStatus(second, "READY");

        adminService.removeSource(9L, id, first, "잘못 붙임");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_zone WHERE source_id = ?", Integer.class, first)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_zone WHERE source_id = ?", Integer.class, second)).isEqualTo(1);

        adminService.hide(9L, id, "점검");
        adminService.delete(9L, id, "정리");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_zone", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_source", Integer.class)).isZero();
    }

    @Test
    void 관리자가_링크를_추가해도_같은_방식으로_읽는다() throws Exception {
        Long id = registerMelon();
        var added = adminService.addSource(9L, id, new AdminPerformanceService.SourceAddRequest(NOL, "다른 판매처"));
        awaitStatus(added.added().getId(), "READY");
        assertThat(performanceService.getZones(id).maps()).hasSize(1);
    }
}
