package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSource;
import com.matjari.domain.PerformanceSource.MapStatus;
import com.matjari.domain.PerformanceZone;
import com.matjari.dto.response.PerformanceZonesResponse.MapItem;
import com.matjari.dto.response.SeatmapImageResponse;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.PerformanceRepository;
import com.matjari.repository.PerformanceSourceRepository;
import com.matjari.repository.PerformanceZoneRepository;
import com.matjari.service.aiextract.DetailExtractionService;
import com.matjari.service.aiextract.DetailExtractionService.AiExtractException;
import com.matjari.service.aiextract.DetailExtractionService.ZoneDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 추가 예매처 링크의 지도 읽기·저장·조회(V34) 규칙. 잠금·SQL 은 MySQL 통합 테스트(SourceMapMysqlTest)가 확인한다. */
class SourceMapServiceTest {

    private static final String NOL_URL = "https://nol.yanolja.com/ticket/products/26014836";
    private static final List<List<List<Integer>>> SHAPE = List.of(List.of(List.of(0, 0), List.of(100, 0), List.of(100, 100)));
    private static final SeatmapImageResponse IMAGE = SeatmapImageResponse.ofRatios(
            "https://ticketimage.interpark.com/a.jpg", 0.1, 0.2, 0.5, 0.4);

    private PerformanceRepository performanceRepository;
    private PerformanceSourceRepository sourceRepository;
    private PerformanceZoneRepository zoneRepository;
    private DetailExtractionService extraction;
    private SeatmapImageService seatmapImages;
    private SourceMapService service;

    private Performance performance;
    private PerformanceSource primary;
    private PerformanceSource added;

    @BeforeEach
    void setUp() {
        performanceRepository = mock(PerformanceRepository.class);
        sourceRepository = mock(PerformanceSourceRepository.class);
        zoneRepository = mock(PerformanceZoneRepository.class);
        extraction = mock(DetailExtractionService.class);
        seatmapImages = mock(SeatmapImageService.class);
        service = new SourceMapService(performanceRepository, sourceRepository, zoneRepository, extraction, seatmapImages,
                PerformanceFixtures.noopTransactionManager(), PerformanceFixtures.CLOCK, Runnable::run);

        performance = Performance.create("코엑스", "한로로 콘서트", "https://ticket.melon.com/x", "melon:100",
                PerformanceFixtures.user(1L, "등록자"));
        ReflectionTestUtils.setField(performance, "id", 100L);
        primary = source(1L, 100L, "melon:100", false);
        added = source(2L, 100L, "interpark:26014836", true);

        when(performanceRepository.findById(100L)).thenReturn(Optional.of(performance));
        when(performanceRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(performance));
        when(sourceRepository.findById(2L)).thenReturn(Optional.of(added));
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(primary));
        when(seatmapImages.acceptRead(any())).thenAnswer(inv -> Optional.ofNullable(inv.getArgument(0)));
    }

    private static PerformanceSource source(Long id, Long performanceId, String key, boolean pending) {
        PerformanceSource s = pending
                ? PerformanceSource.createAdditional(performanceId, key, NOL_URL, 1L, PerformanceFixtures.NOW)
                : PerformanceSource.create(performanceId, key, "https://example.com/" + key, 1L);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private static DetailExtractionService.Response response(List<ZoneDto> zones, Double aspect, boolean aiEnabled, boolean aiError,
                                                             boolean verified, SeatmapImageResponse image) {
        return new DetailExtractionService.Response(List.of(), null, zones, aspect, aiEnabled, aiError, false, false, verified, image);
    }

    private static List<ZoneDto> zones(String... names) {
        return java.util.Arrays.stream(names).map(n -> new ZoneDto(n, SHAPE)).toList();
    }

    @SuppressWarnings("unchecked")
    private List<PerformanceZone> savedZones() {
        ArgumentCaptor<List<PerformanceZone>> captor = ArgumentCaptor.forClass(List.class);
        verify(zoneRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------------ 비동기 읽기

    @Test
    void 링크가_붙으면_읽어서_그_링크의_구역으로_저장하고_READY가_된다() {
        when(extraction.extractForSourceMap(NOL_URL)).thenReturn(response(zones("NOL A", "NOL B", "nol  a"), 1.25, true, false, false, IMAGE));

        service.onSourceAdded(new SourceMapRequestedEvent(100L, 2L));

        List<PerformanceZone> saved = savedZones();
        assertThat(saved).extracting(PerformanceZone::getName).containsExactly("NOL A", "NOL B"); // 같은 이름은 한 구역으로 합쳐진다
        assertThat(saved).extracting(PerformanceZone::getSourceId).containsOnly(2L);
        assertThat(saved).extracting(z -> (int) z.getSortOrder()).containsExactly(0, 1);
        verify(zoneRepository).deleteBySourceId(2L);
        assertThat(added.getMapStatus()).isEqualTo(MapStatus.READY);
        assertThat(added.getSeatmapAspect()).isEqualByComparingTo("1.25");
        assertThat(added.getSeatmapImageUrl()).isEqualTo(IMAGE.url());
        assertThat(added.getSeatmapCropWidth()).isEqualByComparingTo("0.5");
        assertThat(added.getMapUpdatedAt()).isNotNull();
        // 사용자 월 횟수를 세지 않는 경로(extractForSourceMap)만 부른다
        verify(extraction, never()).extract(anyLong(), anyString());
        verify(performanceRepository).findByIdForUpdate(100L);
    }

    @Test
    void 모양이_없으면_비율과_이미지_위치도_두지_않는다() {
        when(extraction.extractForSourceMap(NOL_URL)).thenReturn(response(
                List.of(new ZoneDto("NOL A", null)), 1.25, true, false, false, IMAGE));

        service.readPending(100L, 2L);

        assertThat(added.getMapStatus()).isEqualTo(MapStatus.READY);
        assertThat(added.getSeatmapAspect()).isNull();
        assertThat(added.getSeatmapImageUrl()).isNull();
    }

    @Test
    void 구역이_하나도_없으면_READY지만_지도는_없다() {
        when(extraction.extractForSourceMap(NOL_URL)).thenReturn(response(List.of(), null, true, false, false, null));

        service.readPending(100L, 2L);

        assertThat(added.getMapStatus()).isEqualTo(MapStatus.READY);
        verify(zoneRepository, never()).saveAll(anyCollection());
        when(sourceRepository.findByPerformanceIdOrderByIdAsc(100L)).thenReturn(List.of(primary, added));
        assertThat(service.additionalMaps(performance)).isEmpty();
    }

    @Test
    void AI가_꺼졌거나_읽다_실패하면_FAILED() {
        when(extraction.extractForSourceMap(NOL_URL)).thenReturn(response(List.of(), null, false, false, false, null));
        service.readPending(100L, 2L);
        assertThat(added.getMapStatus()).isEqualTo(MapStatus.FAILED);

        PerformanceSource second = source(3L, 100L, "interpark:2", true);
        when(sourceRepository.findById(3L)).thenReturn(Optional.of(second));
        when(extraction.extractForSourceMap(second.getSourceUrl())).thenReturn(response(zones("A"), 1.0, true, true, false, null));
        service.readPending(100L, 3L);
        assertThat(second.getMapStatus()).isEqualTo(MapStatus.FAILED);
        verify(zoneRepository, never()).saveAll(anyCollection());
    }

    @Test
    void 상한_초과_등_예외도_FAILED_로_끝나고_밖으로_던지지_않는다() {
        when(extraction.extractForSourceMap(NOL_URL))
                .thenThrow(new AiExtractException(429, "AI_RATE_LIMITED", "많아요", 10))
                .thenThrow(new IllegalStateException("boom"));
        service.readPending(100L, 2L);
        assertThat(added.getMapStatus()).isEqualTo(MapStatus.FAILED);

        PerformanceSource second = source(3L, 100L, "interpark:2", true);
        when(sourceRepository.findById(3L)).thenReturn(Optional.of(second));
        service.readPending(100L, 3L);
        assertThat(second.getMapStatus()).isEqualTo(MapStatus.FAILED);
    }

    @Test
    void 검수_정답이면_AI가_꺼져_있어도_READY() {
        when(extraction.extractForSourceMap(NOL_URL)).thenReturn(response(zones("V1"), 1.0, false, false, true, null));
        service.readPending(100L, 2L);
        assertThat(added.getMapStatus()).isEqualTo(MapStatus.READY);
        assertThat(savedZones()).hasSize(1);
    }

    @Test
    void 링크가_그_사이_삭제됐거나_이미_처리됐으면_결과를_버린다() {
        when(extraction.extractForSourceMap(NOL_URL)).thenAnswer(inv -> {
            when(sourceRepository.findById(2L)).thenReturn(Optional.empty()); // 읽는 동안 삭제
            return response(zones("A"), 1.0, true, false, false, null);
        });
        service.readPending(100L, 2L);
        verify(zoneRepository, never()).saveAll(anyCollection());
        assertThat(added.getMapStatus()).isEqualTo(MapStatus.PENDING);

        // 처음부터 PENDING 이 아니면(관리자가 먼저 다시 읽음) 외부 요청도 하지 않는다
        PerformanceSource done = source(4L, 100L, "interpark:4", true);
        done.applyMap(MapStatus.READY, null, null, null, null, null, null, PerformanceFixtures.NOW);
        when(sourceRepository.findById(4L)).thenReturn(Optional.of(done));
        service.readPending(100L, 4L);
        verify(extraction, org.mockito.Mockito.times(1)).extractForSourceMap(NOL_URL); // 첫 호출만, 두 번째는 읽지 않았다
    }

    // ------------------------------------------------------------------ 조회

    @Test
    void 추가_지도_목록은_READY이고_구역이_있거나_PENDING인_것만_추가_순서로() {
        PerformanceSource ready = source(2L, 100L, "interpark:1", true);
        ready.applyMap(MapStatus.READY, new BigDecimal("1.5"), null, null, null, null, null, PerformanceFixtures.NOW);
        PerformanceSource readyEmpty = source(3L, 100L, "interpark:2", true);
        readyEmpty.applyMap(MapStatus.READY, null, null, null, null, null, null, PerformanceFixtures.NOW);
        PerformanceSource failed = source(4L, 100L, "ticketlink:3", true);
        failed.applyMap(MapStatus.FAILED, null, null, null, null, null, null, PerformanceFixtures.NOW);
        PerformanceSource pending = source(5L, 100L, "yes24:4", true);
        PerformanceSource none = source(6L, 100L, "interpark:5", false);
        when(sourceRepository.findByPerformanceIdOrderByIdAsc(100L)).thenReturn(List.of(primary, ready, readyEmpty, failed, pending, none));
        when(zoneRepository.findBySourceIds(anyCollection())).thenReturn(List.of(
                PerformanceZone.create(performance, 2L, "NOL A", "NOLA", 0, SHAPE)));

        List<MapItem> maps = service.additionalMaps(performance);

        assertThat(maps).extracting(MapItem::sourceId).containsExactly(2L, 5L);
        assertThat(maps.get(0)).satisfies(m -> {
            assertThat(m.site()).isEqualTo("interpark");
            assertThat(m.siteName()).isEqualTo("인터파크(NOL)");
            assertThat(m.primary()).isFalse();
            assertThat(m.status()).isEqualTo("READY");
            assertThat(m.aspect()).isEqualByComparingTo("1.5");
            assertThat(m.zones()).singleElement().satisfies(z -> assertThat(z.name()).isEqualTo("NOL A"));
        });
        assertThat(maps.get(1).status()).isEqualTo("PENDING");
        assertThat(maps.get(1).zones()).isEmpty();
    }

    // ------------------------------------------------------------------ 관리자

    @Test
    void 관리자_다시_읽기는_대표_링크를_400_다른_공연_링크를_404로_거절한다() {
        assertThatThrownBy(() -> service.reextract(100L, 1L, null)).isInstanceOf(FieldValidationException.class);
        PerformanceSource other = source(9L, 999L, "interpark:9", true);
        when(sourceRepository.findById(9L)).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.reextract(100L, 9L, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.reextract(100L, 77L, null)).isInstanceOf(NotFoundException.class);
        verify(extraction, never()).extractForAdmin(anyString());
    }

    @Test
    void 관리자_다시_읽기는_보관_결과를_쓰지_않는_경로로_읽고_덮어쓴다() {
        when(extraction.extractForAdmin(NOL_URL)).thenReturn(response(zones("N1", "N2"), 1.1, true, false, false, null));
        when(zoneRepository.findBySourceIds(anyCollection())).thenReturn(List.of(
                PerformanceZone.create(performance, 2L, "N1", "N1", 0, SHAPE), PerformanceZone.create(performance, 2L, "N2", "N2", 1, SHAPE)));
        java.util.concurrent.atomic.AtomicReference<SourceMapService.Reextracted> seen = new java.util.concurrent.atomic.AtomicReference<>();

        MapItem item = service.reextract(100L, 2L, seen::set);

        assertThat(item.status()).isEqualTo("READY");
        assertThat(item.zones()).hasSize(2);
        assertThat(added.getMapStatus()).isEqualTo(MapStatus.READY);
        verify(zoneRepository).deleteBySourceId(2L);
        assertThat(seen.get().after()).containsEntry("zones", 2L).containsEntry("sourceId", 2L);
    }

    @Test
    void 관리자_다시_읽기에서_AI가_읽지_못하면_503이고_기존_지도는_그대로() {
        when(extraction.extractForAdmin(NOL_URL)).thenReturn(response(List.of(), null, false, false, false, null));
        assertThatThrownBy(() -> service.reextract(100L, 2L, null))
                .isInstanceOfSatisfying(AiExtractException.class, e -> assertThat(e.getStatus()).isEqualTo(503));
        verify(zoneRepository, never()).deleteBySourceId(anyLong());
        assertThat(added.getMapStatus()).isEqualTo(MapStatus.PENDING);
    }

    @Test
    void 지도_삭제는_구역과_지도_정보를_지우고_NONE으로_두며_대표_링크는_400() {
        added.applyMap(MapStatus.READY, new BigDecimal("1.5"), IMAGE.url(), IMAGE.left(), IMAGE.top(), IMAGE.width(), IMAGE.height(),
                PerformanceFixtures.NOW);

        var before = service.clearLocked(performance, 2L);

        assertThat(before).containsEntry("mapStatus", "READY").containsEntry("sourceId", 2L);
        verify(zoneRepository).deleteBySourceId(2L);
        assertThat(added.getMapStatus()).isEqualTo(MapStatus.NONE);
        assertThat(added.getSeatmapAspect()).isNull();
        assertThat(added.getSeatmapImageUrl()).isNull();
        assertThatThrownBy(() -> service.clearLocked(performance, 1L)).isInstanceOf(FieldValidationException.class);
    }
}
