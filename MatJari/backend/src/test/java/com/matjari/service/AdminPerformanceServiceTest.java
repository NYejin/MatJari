package com.matjari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matjari.domain.AdminActionLog;
import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.PerformanceZone;
import com.matjari.domain.User;
import com.matjari.dto.request.PerformanceCreateRequest.ZoneInput;
import com.matjari.dto.response.PerformanceEditProposalDtos.SessionChangeRequest;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.PerformanceRepository;
import com.matjari.repository.PerformanceSessionRepository;
import com.matjari.repository.PerformanceZoneRepository;
import com.matjari.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 관리자 공연 관리 서비스 — 규칙·멱등·기록 생성을 mock 저장소로 확인한다(잠금·SQL 은 MySQL 통합 테스트 영역). */
class AdminPerformanceServiceTest {

    private PerformanceRepository performanceRepository;
    private PerformanceSessionRepository sessionRepository;
    private PerformanceZoneRepository zoneRepository;
    private UserRepository userRepository;
    private AdminActionLogger logger;
    private AdminPerformanceService service;
    private Performance performance;
    private PerformanceSession session;

    @BeforeEach
    void setUp() {
        performanceRepository = mock(PerformanceRepository.class);
        sessionRepository = mock(PerformanceSessionRepository.class);
        zoneRepository = mock(PerformanceZoneRepository.class);
        userRepository = mock(UserRepository.class);
        logger = mock(AdminActionLogger.class);
        SessionChangePlanner planner = new SessionChangePlanner(PerformanceFixtures.timePolicy(), sessionRepository);
        service = new AdminPerformanceService(performanceRepository, sessionRepository, zoneRepository,
                userRepository, planner, logger, new ObjectMapper(), PerformanceFixtures.CLOCK,
                mock(org.springframework.jdbc.core.JdbcTemplate.class));

        User registrant = PerformanceFixtures.user(1L, "등록자");
        performance = PerformanceFixtures.performance(100L, "KSPO", registrant);
        session = PerformanceFixtures.session(7L, performance, LocalDateTime.of(2026, 11, 1, 19, 0));
        when(performanceRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(performance));
        when(sessionRepository.findByPerformance_IdOrderByStartsAtAsc(100L)).thenReturn(List.of(session));
        when(zoneRepository.findByPerformanceIdOrderBySortOrderAscIdAsc(100L)).thenReturn(List.of());
        when(userRepository.findById(9L)).thenReturn(Optional.of(PerformanceFixtures.user(9L, "관리자")));
    }

    @Test
    void 숨기면_숨김_상태가_되고_기록을_남긴다_두번째는_멱등이라_기록이_없다() {
        service.hide(9L, 100L, "  저작권   문제 ");
        assertThat(performance.isHidden()).isTrue();
        assertThat(performance.getHiddenReason()).isEqualTo("저작권 문제");
        assertThat(performance.getHiddenById()).isEqualTo(9L);
        verify(logger).log(eq(9L), eq(AdminActionLog.Action.PERFORMANCE_HIDE), eq(AdminActionLog.TargetType.PERFORMANCE),
                eq(100L), eq("저작권 문제"), anyString());

        service.hide(9L, 100L, "다시");
        verify(logger, org.mockito.Mockito.times(1)).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 숨김_사유는_필수이고_200자_이하이며_검증은_DB를_건드리기_전에_한다() {
        assertThatThrownBy(() -> service.hide(9L, 100L, "  ")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.hide(9L, 100L, "가".repeat(201))).isInstanceOf(FieldValidationException.class);
        verifyNoInteractions(performanceRepository, logger);
    }

    @Test
    void 없는_공연은_404() {
        assertThatThrownBy(() -> service.hide(9L, 5L, "x")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.get(5L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 다시_올리면_숨김이_풀리고_숨김이_아니면_멱등이다() {
        service.hide(9L, 100L, "사유");
        service.unhide(9L, 100L, null);
        assertThat(performance.isHidden()).isFalse();
        assertThat(performance.getHiddenById()).isNull();
        assertThat(performance.getHiddenReason()).isNull();
        verify(logger).log(eq(9L), eq(AdminActionLog.Action.PERFORMANCE_UNHIDE), any(), eq(100L), eq(null), anyString());

        service.unhide(9L, 100L, "또");
        verify(logger, org.mockito.Mockito.times(2)).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 제목_공연장을_수정하고_바뀐_값만_기록한다() {
        AdminPerformanceService.Detail detail = service.edit(9L, 100L,
                new AdminPerformanceService.EditRequest(" 새  제목 ", null, "오타"));
        assertThat(detail.title()).isEqualTo("새 제목");
        assertThat(performance.getVenueName()).isEqualTo("KSPO");
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(logger).log(eq(9L), eq(AdminActionLog.Action.PERFORMANCE_EDIT), any(), eq(100L), eq("오타"), json.capture());
        assertThat(json.getValue()).contains("\"title\":\"새 제목\"").doesNotContain("venueName");
    }

    @Test
    void 수정_입력_규칙() {
        assertThatThrownBy(() -> service.edit(9L, 100L, new AdminPerformanceService.EditRequest(null, " ", "사유")))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.edit(9L, 100L, new AdminPerformanceService.EditRequest("제목", null, null)))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.edit(9L, 100L,
                new AdminPerformanceService.EditRequest(null, "가".repeat(101), "사유")))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.edit(9L, 100L,
                new AdminPerformanceService.EditRequest(performance.getTitle(), "KSPO", "사유")))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("EDIT_NO_CHANGE"));
        verify(logger, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 회차_삭제는_사용_중이면_422이고_마지막_회차_삭제는_400() {
        when(sessionRepository.findInUseIds(anyCollection())).thenReturn(List.of(7L));
        PerformanceSession other = PerformanceFixtures.session(8L, performance, LocalDateTime.of(2026, 11, 2, 19, 0));
        when(sessionRepository.findByPerformance_IdOrderByStartsAtAsc(100L)).thenReturn(List.of(session, other));
        assertThatThrownBy(() -> service.changeSessions(9L, 100L, new AdminPerformanceService.SessionChangesRequest(
                List.of(new SessionChangeRequest("REMOVE", 7L, null)), "삭제")))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SESSION_IN_USE"));

        when(sessionRepository.findByPerformance_IdOrderByStartsAtAsc(100L)).thenReturn(List.of(session));
        assertThatThrownBy(() -> service.changeSessions(9L, 100L, new AdminPerformanceService.SessionChangesRequest(
                List.of(new SessionChangeRequest("REMOVE", 7L, null)), "삭제")))
                .isInstanceOf(FieldValidationException.class);
        verify(logger, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 회차_규칙은_수정_제안과_같다_지난_시각과_10분_단위_위반은_400_변경없음은_422() {
        assertThatThrownBy(() -> service.changeSessions(9L, 100L, new AdminPerformanceService.SessionChangesRequest(
                List.of(new SessionChangeRequest("ADD", null, LocalDateTime.of(2026, 10, 1, 19, 0))), "추가")))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.changeSessions(9L, 100L, new AdminPerformanceService.SessionChangesRequest(
                List.of(new SessionChangeRequest("ADD", null, LocalDateTime.of(2026, 12, 1, 19, 5))), "추가")))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.changeSessions(9L, 100L, new AdminPerformanceService.SessionChangesRequest(
                List.of(new SessionChangeRequest("CHANGE", 7L, session.getStartsAt())), "같음")))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("EDIT_NO_CHANGE"));
        assertThatThrownBy(() -> service.changeSessions(9L, 100L,
                new AdminPerformanceService.SessionChangesRequest(List.of(), "빈")))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 회차_추가는_반영하고_기록한다() {
        service.changeSessions(9L, 100L, new AdminPerformanceService.SessionChangesRequest(
                List.of(new SessionChangeRequest("ADD", null, LocalDateTime.of(2026, 12, 1, 19, 0))), "추가"));
        verify(sessionRepository).save(any(PerformanceSession.class));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(logger).log(eq(9L), eq(AdminActionLog.Action.PERFORMANCE_SESSIONS), any(), eq(100L), eq("추가"), json.capture());
        assertThat(json.getValue()).contains("ADD").contains("2026-12-01T19:00");
    }

    @Test
    void 삭제_중_FK_위반은_409_SESSION_EDIT_CONFLICT() {
        PerformanceSession other = PerformanceFixtures.session(8L, performance, LocalDateTime.of(2026, 11, 2, 19, 0));
        when(sessionRepository.findByPerformance_IdOrderByStartsAtAsc(100L)).thenReturn(List.of(session, other));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("fk"))
                .when(sessionRepository).delete(session);
        assertThatThrownBy(() -> service.changeSessions(9L, 100L, new AdminPerformanceService.SessionChangesRequest(
                List.of(new SessionChangeRequest("REMOVE", 7L, null)), "삭제")))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getDetails()).containsEntry("code", "SESSION_EDIT_CONFLICT"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 구역_교체는_기존_구역을_지우고_정리한_목록으로_바꾸며_비율도_바꾼다() {
        PerformanceZone old = PerformanceZone.create(performance, "옛구역", "옛구역", 0, null);
        when(zoneRepository.findByPerformanceIdOrderBySortOrderAscIdAsc(100L)).thenReturn(List.of(old));
        service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(
                List.of(new ZoneInput(" A 구역 ", null), new ZoneInput("a구역", null), new ZoneInput("B구역", null)),
                new BigDecimal("1.50"), "교체"));
        verify(zoneRepository).deleteAll(List.of(old));
        ArgumentCaptor<List<PerformanceZone>> saved = ArgumentCaptor.forClass(List.class);
        verify(zoneRepository).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(PerformanceZone::getName).containsExactly("A 구역", "B구역");
        assertThat(performance.getSeatmapAspect()).isEqualByComparingTo("1.5");
        verify(logger).log(eq(9L), eq(AdminActionLog.Action.PERFORMANCE_ZONES), any(), eq(100L), eq("교체"), anyString());
    }

    @Test
    void 빈_구역_배열은_모두_지운다_이름_31자와_안전_한도_1001개는_400() {
        PerformanceZone old = PerformanceZone.create(performance, "옛구역", "옛구역", 0, null);
        when(zoneRepository.findByPerformanceIdOrderBySortOrderAscIdAsc(100L)).thenReturn(List.of(old));
        service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(List.of(), null, "삭제"));
        verify(zoneRepository).deleteAll(List.of(old));
        verify(zoneRepository, never()).saveAll(any());

        assertThatThrownBy(() -> service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(
                List.of(new ZoneInput("가".repeat(31), null)), null, "x"))).isInstanceOf(FieldValidationException.class);
        List<ZoneInput> many = java.util.stream.IntStream.range(0, 1001).mapToObj(i -> new ZoneInput("Z" + i, null)).toList();
        assertThatThrownBy(() -> service.replaceZones(9L, 100L,
                new AdminPerformanceService.ZonesRequest(many, null, "x"))).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 숨긴_공연_가드는_409_PERFORMANCE_HIDDEN() {
        SessionChangePlanner.ensureNotHidden(performance); // 보이는 공연은 통과
        performance.hide(9L, "사유", PerformanceFixtures.NOW);
        assertThatThrownBy(() -> SessionChangePlanner.ensureNotHidden(performance))
                .isInstanceOf(ConflictException.class)
                .hasMessage("내려간 공연이에요.")
                .satisfies(e -> assertThat(((ConflictException) e).getDetails()).containsEntry("code", "PERFORMANCE_HIDDEN"));
    }

    @Test
    void 상세는_숨김_정보와_회차_사용중을_담는다() {
        performance.hide(9L, "사유", PerformanceFixtures.NOW);
        when(performanceRepository.findDetailById(100L)).thenReturn(Optional.of(performance));
        when(sessionRepository.findInUseIds(anyCollection())).thenReturn(List.of(7L));
        AdminPerformanceService.Detail d = service.get(100L);
        assertThat(d.hidden()).isTrue();
        assertThat(d.hiddenByNickname()).isEqualTo("관리자");
        assertThat(d.sessions()).singleElement().satisfies(s -> assertThat(s.inUse()).isTrue());
        assertThat(d.registrant().nickname()).isEqualTo("등록자");
    }

    // ---- 원본 좌석표 이미지 위치 (V31) ----

    private static final com.matjari.dto.response.SeatmapImageResponse IMAGE = new com.matjari.dto.response.SeatmapImageResponse(
            "https://cdnticket.melon.co.kr/resource/image/upload/product/a.jpg",
            new BigDecimal("0.100000"), new BigDecimal("0.200000"), new BigDecimal("0.800000"), new BigDecimal("0.500000"));
    private static final List<ZoneInput> SHAPED = List.of(
            new ZoneInput("A", List.of(List.of(List.of(0, 0), List.of(10, 0), List.of(10, 10)))));

    private SeatmapImageService images;

    private void withImages() {
        images = mock(SeatmapImageService.class);
        when(images.findStored(eq("interpark:100"), any())).thenReturn(Optional.of(IMAGE));
        service.setSeatmapImages(images);
    }

    @Test
    void LINK_모드는_이_공연_링크의_보관_결과에서만_이미지_위치를_저장한다() {
        withImages();
        service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(SHAPED, new BigDecimal("1.5"), "다시 인식", "LINK"));
        assertThat(performance.getSeatmapImageUrl()).isEqualTo(IMAGE.url());
        assertThat(performance.getSeatmapCropWidth()).isEqualByComparingTo("0.8");
        verify(images).findStored("interpark:100", new BigDecimal("1.500"));
    }

    @Test
    void LINK_모드인데_보관_결과가_없으면_비운다() {
        withImages();
        performance.changeSeatmapImage(IMAGE.url(), IMAGE.left(), IMAGE.top(), IMAGE.width(), IMAGE.height());
        when(images.findStored(any(), any())).thenReturn(Optional.empty());
        service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(SHAPED, new BigDecimal("1.5"), "x", "LINK"));
        assertThat(performance.getSeatmapImageUrl()).isNull();
    }

    @Test
    void KEEP_기본값은_그대로_두고_CLEAR는_비운다() {
        withImages();
        performance.changeSeatmapImage(IMAGE.url(), IMAGE.left(), IMAGE.top(), IMAGE.width(), IMAGE.height());
        service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(SHAPED, new BigDecimal("1.5"), "x"));
        assertThat(performance.getSeatmapImageUrl()).isEqualTo(IMAGE.url());
        service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(SHAPED, new BigDecimal("1.5"), "x", "KEEP"));
        assertThat(performance.getSeatmapImageUrl()).isEqualTo(IMAGE.url());
        service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(SHAPED, new BigDecimal("1.5"), "x", "CLEAR"));
        assertThat(performance.getSeatmapImageUrl()).isNull();
        assertThat(performance.getSeatmapCropTop()).isNull();
        verify(images, never()).findStored(any(), any());
    }

    @Test
    void 모양_있는_구역이_하나도_없으면_모드와_무관하게_비운다() {
        withImages();
        performance.changeSeatmapImage(IMAGE.url(), IMAGE.left(), IMAGE.top(), IMAGE.width(), IMAGE.height());
        service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(
                List.of(new ZoneInput("스탠딩", null)), null, "x", "LINK"));
        assertThat(performance.getSeatmapImageUrl()).isNull();
        verify(images, never()).findStored(any(), any());
    }

    @Test
    void 알_수_없는_모드는_400이고_조치_기록_요약에_모드가_들어간다() {
        withImages();
        assertThatThrownBy(() -> service.replaceZones(9L, 100L,
                new AdminPerformanceService.ZonesRequest(SHAPED, null, "x", "COPY"))).isInstanceOf(FieldValidationException.class);
        service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(SHAPED, new BigDecimal("1.5"), "x", "link"));
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(logger).log(eq(9L), eq(AdminActionLog.Action.PERFORMANCE_ZONES), any(), eq(100L), eq("x"), summary.capture());
        assertThat(summary.getValue()).contains("\"seatmapImageMode\":\"LINK\"").contains(IMAGE.url());
    }

    // ---- 여러 예매처 링크 묶기(V32) ----

    private static final String NOL = "https://nol.yanolja.com/ticket/products/26014836";
    private static final String NOL_KEY = "interpark:26014836";

    private PerformanceSourceService withSources() {
        PerformanceSourceService sources = mock(PerformanceSourceService.class);
        when(sources.requireSupportedKey(NOL)).thenReturn(NOL_KEY);
        when(sources.list(any(Performance.class))).thenReturn(List.of());
        service.setSources(sources);
        return sources;
    }

    private static com.matjari.domain.PerformanceSource added(long id) {
        com.matjari.domain.PerformanceSource s = com.matjari.domain.PerformanceSource.create(100L, NOL_KEY, NOL, 9L);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    @Test
    void 링크를_추가하면_같은_공연_검증_없이_만들고_PERFORMANCE_EDIT_기록을_남긴다() {
        PerformanceSourceService sources = withSources();
        when(sources.addLocked(performance, NOL, NOL_KEY, 9L, null, false))
                .thenReturn(new PerformanceSourceService.AddResult(List.of(), true, added(5L)));

        var result = service.addSource(9L, 100L, new AdminPerformanceService.SourceAddRequest("  " + NOL + " ", " 판매처 추가 "));

        assertThat(result.created()).isTrue();
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(logger).log(eq(9L), eq(AdminActionLog.Action.PERFORMANCE_EDIT), eq(AdminActionLog.TargetType.PERFORMANCE),
                eq(100L), eq("판매처 추가"), summary.capture());
        assertThat(summary.getValue()).contains("\"addedSource\"").contains("\"sourceId\":5").contains(NOL_KEY);
    }

    @Test
    void 이미_이_공연에_있는_링크_추가는_멱등이라_기록이_없다() {
        PerformanceSourceService sources = withSources();
        when(sources.addLocked(performance, NOL, NOL_KEY, 9L, null, false))
                .thenReturn(new PerformanceSourceService.AddResult(List.of(), false, null));

        assertThat(service.addSource(9L, 100L, new AdminPerformanceService.SourceAddRequest(NOL, "x")).created()).isFalse();
        verify(logger, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 링크_추가는_사유가_필수이고_검증은_공연_잠금_전에_한다() {
        withSources();
        assertThatThrownBy(() -> service.addSource(9L, 100L, new AdminPerformanceService.SourceAddRequest(NOL, " ")))
                .isInstanceOf(FieldValidationException.class);
        verify(performanceRepository, never()).findByIdForUpdate(any());
        verify(logger, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 링크_삭제는_사유가_필수이고_기록에_removedSource가_들어간다() {
        PerformanceSourceService sources = withSources();
        when(sources.removeLocked(performance, 5L)).thenReturn(added(5L));

        assertThatThrownBy(() -> service.removeSource(9L, 100L, 5L, null)).isInstanceOf(FieldValidationException.class);
        service.removeSource(9L, 100L, 5L, "잘못 붙임");

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(logger).log(eq(9L), eq(AdminActionLog.Action.PERFORMANCE_EDIT), any(), eq(100L), eq("잘못 붙임"), summary.capture());
        assertThat(summary.getValue()).contains("\"removedSource\"").contains("\"sourceId\":5");
    }

    @Test
    void 대표_링크_삭제가_거절되면_기록이_없다() {
        PerformanceSourceService sources = withSources();
        when(sources.removeLocked(performance, 1L))
                .thenThrow(new ConflictException("대표 링크는 삭제할 수 없어요.", java.util.Map.of("code", "PRIMARY_SOURCE")));

        assertThatThrownBy(() -> service.removeSource(9L, 100L, 1L, "x")).isInstanceOf(ConflictException.class);
        verify(logger, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void 상세는_링크_목록을_담고_연결이_없으면_대표_링크만_합성한다() {
        when(performanceRepository.findDetailById(100L)).thenReturn(Optional.of(performance));
        assertThat(service.get(100L).sources()).singleElement().satisfies(s -> assertThat(s.primary()).isTrue());

        PerformanceSourceService sources = withSources();
        var item = new com.matjari.dto.response.PerformanceSourceDtos.SourceItem(1L, "interpark", "인터파크(NOL)", "u", true);
        when(sources.list(performance)).thenReturn(List.of(item));
        assertThat(service.get(100L).sources()).containsExactly(item);
    }
}
