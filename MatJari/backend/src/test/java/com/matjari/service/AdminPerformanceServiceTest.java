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
                userRepository, planner, logger, new ObjectMapper(), PerformanceFixtures.CLOCK);

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
    void 빈_구역_배열은_모두_지운다_이름_31자와_101개는_400() {
        PerformanceZone old = PerformanceZone.create(performance, "옛구역", "옛구역", 0, null);
        when(zoneRepository.findByPerformanceIdOrderBySortOrderAscIdAsc(100L)).thenReturn(List.of(old));
        service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(List.of(), null, "삭제"));
        verify(zoneRepository).deleteAll(List.of(old));
        verify(zoneRepository, never()).saveAll(any());

        assertThatThrownBy(() -> service.replaceZones(9L, 100L, new AdminPerformanceService.ZonesRequest(
                List.of(new ZoneInput("가".repeat(31), null)), null, "x"))).isInstanceOf(FieldValidationException.class);
        List<ZoneInput> many = java.util.stream.IntStream.range(0, 101).mapToObj(i -> new ZoneInput("Z" + i, null)).toList();
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
}
