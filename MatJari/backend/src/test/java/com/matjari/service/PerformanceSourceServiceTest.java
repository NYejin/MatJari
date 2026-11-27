package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSource;
import com.matjari.domain.User;
import com.matjari.dto.response.PerformancePreviewResponse;
import com.matjari.dto.response.PerformanceSourceDtos.Candidate;
import com.matjari.dto.response.PerformanceSourceDtos.SimilarResponse;
import com.matjari.dto.response.PerformanceSourceDtos.SourceItem;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.PerformanceRepository;
import com.matjari.repository.PerformanceSessionRepository;
import com.matjari.repository.PerformanceSourceRepository;
import com.matjari.repository.PerformanceZoneRepository;
import com.matjari.service.linkinfo.PerformancePreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.matjari.service.PerformanceFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 한 공연을 여러 예매처 링크로 묶기(V32) 서비스 규칙. 잠금·SQL 은 MySQL 통합 테스트(PerformanceSourceMysqlTest)가 확인한다. */
class PerformanceSourceServiceTest {

    private static final String NOL_URL = "https://nol.yanolja.com/ticket/products/26014836";
    private static final String NOL_KEY = "interpark:26014836";

    private PerformanceRepository performanceRepository;
    private PerformanceSessionRepository sessionRepository;
    private PerformanceSourceRepository sourceRepository;
    private PerformancePreviewService previewService;
    private TicketService ticketService;
    private PerformanceZoneRepository zoneRepository;
    private PerformanceSourceService service;

    private final User owner = user(1L, "등록자");
    private Performance performance; // melon:100, 코엑스 D홀, 12/10~12/20

    @BeforeEach
    void setUp() {
        performanceRepository = mock(PerformanceRepository.class);
        sessionRepository = mock(PerformanceSessionRepository.class);
        sourceRepository = mock(PerformanceSourceRepository.class);
        previewService = mock(PerformancePreviewService.class);
        ticketService = mock(TicketService.class);
        zoneRepository = mock(PerformanceZoneRepository.class);
        service = new PerformanceSourceService(performanceRepository, sessionRepository, sourceRepository,
                new SourceKeyResolver(), previewService, ticketService, PerformanceFixtures.timePolicy(),
                PerformanceFixtures.noopTransactionManager(), zoneRepository, mock(org.springframework.context.ApplicationEventPublisher.class));

        performance = performance(100L, "코엑스 D홀", "melon:100", "아이유 콘서트 HEAVEN");
        when(performanceRepository.findById(100L)).thenReturn(Optional.of(performance));
        when(performanceRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(performance));
        when(sessionRepository.findRanges(anyCollection())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> range(id, 5, LocalDateTime.of(2026, 12, 10, 19, 0), LocalDateTime.of(2026, 12, 20, 19, 0))).toList();
        });
        when(sourceRepository.countByPerformanceId(100L)).thenReturn(1L);
        when(sourceRepository.saveAndFlush(any(PerformanceSource.class))).thenAnswer(inv -> {
            PerformanceSource s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", 55L);
            return s;
        });
        when(sourceRepository.findByPerformanceIdOrderByIdAsc(100L)).thenReturn(List.of(
                source(1L, 100L, "melon:100", "https://ticket.melon.com/performance/index.htm?prodId=100")));
    }

    private static Performance performance(long id, String venue, String key, String title) {
        Performance p = Performance.create(venue, title, "https://example.com/" + key, key, user(1L, "등록자"));
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private static PerformanceSource source(Long id, Long performanceId, String key, String url) {
        PerformanceSource s = PerformanceSource.create(performanceId, key, url, 1L);
        ReflectionTestUtils.setField(s, "id", id);
        return s;
    }

    private static PerformanceSessionRepository.SessionRange range(Long id, long count, LocalDateTime first, LocalDateTime last) {
        PerformanceSessionRepository.SessionRange r = mock(PerformanceSessionRepository.SessionRange.class);
        when(r.getPerformanceId()).thenReturn(id);
        when(r.getSessionCount()).thenReturn(count);
        when(r.getFirstStartsAt()).thenReturn(first);
        when(r.getLastStartsAt()).thenReturn(last);
        return r;
    }

    private static PerformancePreviewResponse preview(String venue, LocalDate start, LocalDate end) {
        return PerformancePreviewResponse.ok("interpark", "[아이유] 콘서트 HEAVEN", venue, start, end, null);
    }

    private void stubPreview(PerformancePreviewResponse response) {
        when(previewService.preview(eq(1L), anyString())).thenReturn(response);
    }

    // ---- 표기 ----

    @Test
    void 사이트_표기는_키_접두사로_정한다() {
        assertThat(PerformanceSourceService.siteOf("melon:1")).isEqualTo("melon");
        assertThat(PerformanceSourceService.siteNameOf("interpark")).isEqualTo("인터파크(NOL)");
        assertThat(PerformanceSourceService.siteNameOf("ticketlink")).isEqualTo("티켓링크");
        assertThat(PerformanceSourceService.siteNameOf("yes24")).isEqualTo("YES24");
        assertThat(PerformanceSourceService.siteNameOf("melon")).isEqualTo("멜론티켓");
        assertThat(PerformanceSourceService.siteOf("url:example.com/a")).isEqualTo("etc");
    }

    @Test
    void 링크_목록은_대표가_먼저이고_그다음_추가_순이다() {
        when(sourceRepository.findByPerformanceIdOrderByIdAsc(100L)).thenReturn(List.of(
                source(1L, 100L, "melon:100", "https://m/1"),
                source(2L, 100L, NOL_KEY, NOL_URL)));
        // 대표 링크 행이 가장 먼저가 아닌 경우(데이터가 뒤섞여도)에도 대표가 먼저
        Performance other = performance(100L, "코엑스 D홀", NOL_KEY, "제목");
        List<SourceItem> items = service.list(other);
        assertThat(items).extracting(SourceItem::primary).containsExactly(true, false);
        assertThat(items.get(0).site()).isEqualTo("interpark");
        assertThat(items.get(1).siteName()).isEqualTo("멜론티켓");
    }

    @Test
    void 링크_행이_없으면_대표_링크만_합성한다() {
        when(sourceRepository.findByPerformanceIdOrderByIdAsc(100L)).thenReturn(List.of());
        assertThat(service.list(performance)).singleElement().satisfies(i -> {
            assertThat(i.primary()).isTrue();
            assertThat(i.id()).isNull();
        });
    }

    // ---- 같은 공연 후보 ----

    @Test
    void 후보는_공연장이_같고_기간이_겹치는_공연만_제목_유사도_순으로_최대_3개() {
        Performance a = performance(1L, "코엑스 D홀 (COEX HALL D)", "melon:1", "전혀 다른 제목");
        Performance b = performance(2L, "코엑스 D홀", "melon:2", "2026 아이유 콘서트 HEAVEN");
        Performance c = performance(3L, "KSPO DOME", "melon:3", "아이유 콘서트 HEAVEN"); // 공연장 다름
        Performance d = performance(4L, "코엑스 D홀", "melon:4", "아이유 콘서트");
        Performance e = performance(5L, "코엑스 D홀", "melon:5", "아이유 콘서트 HEAVEN 앵콜");
        when(performanceRepository.findVisibleWithSessionsOverlapping(any(), any(), any())).thenReturn(List.of(a, b, c, d, e));
        when(sourceRepository.findByPerformanceIdIn(anyCollection())).thenReturn(List.of(
                source(10L, 2L, "melon:2", "u"), source(11L, 2L, NOL_KEY, "u2")));

        List<Candidate> result = service.findSimilar("[아이유] 콘서트 HEAVEN", "코엑스 D홀", LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 14));

        // 유사도가 가장 높은 b(2) 가 먼저, 공연장이 다른 c(3) 는 제외, 유사도가 낮은 a(1)도 공연장+기간이 맞으면 포함하되 3개까지
        assertThat(result).hasSize(3);
        assertThat(result.get(0).id()).isEqualTo(2L);
        assertThat(result).extracting(Candidate::id).doesNotContain(3L);
        assertThat(result.get(0).sites()).containsExactly("melon", "interpark");
        assertThat(result.get(0).sessionCount()).isEqualTo(5);
        assertThat(result.get(0).firstSessionAt()).isEqualTo(LocalDateTime.of(2026, 12, 10, 19, 0));
    }

    @Test
    void 기간이_겹치지_않으면_후보가_아니다() {
        when(performanceRepository.findVisibleWithSessionsOverlapping(any(), any(), any())).thenReturn(List.of(
                performance(2L, "코엑스 D홀", "melon:2", "아이유 콘서트 HEAVEN")));
        assertThat(service.findSimilar("아이유 콘서트 HEAVEN", "코엑스 D홀", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3))).isEmpty();
    }

    @Test
    void 기간이_없으면_제목_유사도로만_판정한다() {
        when(performanceRepository.findVisibleWithSessionsOverlapping(any(), any(), any())).thenReturn(List.of(
                performance(2L, "코엑스 D홀", "melon:2", "아이유 콘서트 HEAVEN"),
                performance(3L, "코엑스 D홀", "melon:3", "완전히 다른 전시")));
        List<Candidate> result = service.findSimilar("아이유 콘서트 HEAVEN", "코엑스 D홀", null, null);
        assertThat(result).extracting(Candidate::id).containsExactly(2L);
    }

    @Test
    void 공연장이_비어_있으면_조회하지_않는다() {
        assertThat(service.findSimilar("제목", " ", LocalDate.of(2026, 12, 12), null)).isEmpty();
        verifyNoInteractions(performanceRepository);
    }

    @Test
    void similar는_이미_등록된_링크를_알려_주고_후보는_비운다() {
        when(sourceRepository.findBySourceKey(NOL_KEY)).thenReturn(Optional.of(source(2L, 100L, NOL_KEY, NOL_URL)));
        stubPreview(preview("코엑스 D홀", LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 14)));

        SimilarResponse response = service.similar(1L, NOL_URL);

        assertThat(response.existingPerformanceId()).isEqualTo(100L);
        assertThat(response.candidates()).isEmpty();
        assertThat(response.preview().status()).isEqualTo("OK");
        assertThat(response.preview().venueName()).isEqualTo("코엑스 D홀");
    }

    @Test
    void similar는_미리보기를_못_읽으면_후보가_없다() {
        stubPreview(PerformancePreviewResponse.empty("FETCH_FAILED", "interpark"));
        SimilarResponse response = service.similar(1L, NOL_URL);
        assertThat(response.candidates()).isEmpty();
        assertThat(response.existingPerformanceId()).isNull();
        assertThat(response.preview().status()).isEqualTo("FETCH_FAILED");
        verify(performanceRepository, never()).findVisibleWithSessionsOverlapping(any(), any(), any());
    }

    @Test
    void similar는_형식_오류를_400으로_돌려준다() {
        assertThatThrownBy(() -> service.similar(1L, "not a url")).isInstanceOf(FieldValidationException.class);
        verifyNoInteractions(previewService);
    }

    // ---- 링크 추가 ----

    @Test
    void 지원_사이트가_아닌_링크는_400_sourceUrl이다() {
        assertThatThrownBy(() -> service.addByUser(1L, 100L, "https://example.com/show/1"))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getErrors()).containsKey("sourceUrl"));
        verifyNoInteractions(previewService);
        verify(sourceRepository, never()).saveAndFlush(any());
    }

    @Test
    void 없는_공연은_404이다() {
        when(performanceRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addByUser(1L, 999L, NOL_URL)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 다른_공연에_이미_있는_링크는_409_SOURCE_ALREADY_REGISTERED와_공연_id() {
        when(sourceRepository.findBySourceKey(NOL_KEY)).thenReturn(Optional.of(source(9L, 200L, NOL_KEY, NOL_URL)));
        assertThatThrownBy(() -> service.addByUser(1L, 100L, NOL_URL))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getDetails()).containsEntry("code", "SOURCE_ALREADY_REGISTERED").containsEntry("performanceId", 200L);
                });
        verifyNoInteractions(previewService);
    }

    @Test
    void 같은_공연에_이미_있는_링크는_멱등이고_미리보기도_잠금도_없다() {
        when(sourceRepository.findBySourceKey(NOL_KEY)).thenReturn(Optional.of(source(2L, 100L, NOL_KEY, NOL_URL)));

        PerformanceSourceService.AddResult result = service.addByUser(1L, 100L, NOL_URL);

        assertThat(result.created()).isFalse();
        assertThat(result.sources()).isNotEmpty();
        verifyNoInteractions(previewService);
        verify(ticketService, never()).lockActiveUser(anyLong());
        verify(sourceRepository, never()).saveAndFlush(any());
    }

    @Test
    void 숨긴_공연은_409_PERFORMANCE_HIDDEN이다() {
        performance.hide(9L, "사유", LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThatThrownBy(() -> service.addByUser(1L, 100L, NOL_URL))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("code", "PERFORMANCE_HIDDEN"));
        verifyNoInteractions(previewService);
    }

    @Test
    void 같은_공연으로_확인되면_users_잠금_후_공연_잠금_순서로_링크를_만든다() {
        stubPreview(preview("코엑스 D홀 (COEX HALL D)", LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 14)));

        PerformanceSourceService.AddResult result = service.addByUser(1L, 100L, NOL_URL + "?utm_source=x");

        assertThat(result.created()).isTrue();
        assertThat(result.added().getSourceKey()).isEqualTo(NOL_KEY);
        assertThat(result.added().getAddedById()).isEqualTo(1L);
        InOrder order = inOrder(previewService, ticketService, performanceRepository, sourceRepository);
        order.verify(previewService).preview(eq(1L), anyString());     // 외부 요청은 잠금 밖에서 먼저
        order.verify(ticketService).lockActiveUser(1L);                // 첫 쿼리는 users FOR UPDATE
        order.verify(performanceRepository).findByIdForUpdate(100L);
        order.verify(sourceRepository).saveAndFlush(any(PerformanceSource.class));
    }

    @Test
    void 공연장이_다르면_422_SOURCE_NOT_SAME_PERFORMANCE() {
        stubPreview(preview("KSPO DOME", LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 14)));
        assertThatThrownBy(() -> service.addByUser(1L, 100L, NOL_URL))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SOURCE_NOT_SAME_PERFORMANCE"));
        verify(sourceRepository, never()).saveAndFlush(any());
    }

    @Test
    void 기간이_회차_범위와_겹치지_않으면_422() {
        stubPreview(preview("코엑스 D홀", LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 5)));
        assertThatThrownBy(() -> service.addByUser(1L, 100L, NOL_URL))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SOURCE_NOT_SAME_PERFORMANCE"));
    }

    @Test
    void 미리보기를_못_읽으면_같은_공연으로_확인할_수_없어_422() {
        stubPreview(PerformancePreviewResponse.empty("FETCH_FAILED", "interpark"));
        assertThatThrownBy(() -> service.addByUser(1L, 100L, NOL_URL))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("SOURCE_NOT_SAME_PERFORMANCE"));
    }

    @Test
    void 기간이_없는_링크는_제목_유사도로_검증한다() {
        stubPreview(PerformancePreviewResponse.ok("interpark", "[아이유] 콘서트 HEAVEN", "코엑스 D홀", null, null, null));
        assertThat(service.addByUser(1L, 100L, NOL_URL).created()).isTrue();

        stubPreview(PerformancePreviewResponse.ok("interpark", "완전히 다른 전시", "코엑스 D홀", null, null, null));
        assertThatThrownBy(() -> service.addByUser(1L, 100L, "https://nol.yanolja.com/ticket/products/777"))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 공연당_링크는_최대_6개() {
        stubPreview(preview("코엑스 D홀", LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 14)));
        when(sourceRepository.countByPerformanceId(100L)).thenReturn(6L);
        assertThatThrownBy(() -> service.addByUser(1L, 100L, NOL_URL))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SOURCE_LIMIT_REACHED");
                });
        when(sourceRepository.countByPerformanceId(100L)).thenReturn(5L);
        assertThat(service.addByUser(1L, 100L, NOL_URL).created()).isTrue();
    }

    // ---- 관리자용 (검증 없음) / 삭제 ----

    @Test
    void 관리자_경로는_같은_공연_검증을_하지_않지만_나머지_규칙은_같다() {
        PerformanceSourceService.AddResult result = service.addLocked(performance, NOL_URL, NOL_KEY, 9L, null, false);
        assertThat(result.created()).isTrue();
        assertThat(result.added().getAddedById()).isEqualTo(9L);

        when(sourceRepository.countByPerformanceId(100L)).thenReturn(6L);
        assertThatThrownBy(() -> service.addLocked(performance, "https://nol.yanolja.com/ticket/products/2", "interpark:2", 9L, null, false))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 대표_링크는_삭제할_수_없고_다른_공연의_링크는_404() {
        when(sourceRepository.findById(1L)).thenReturn(Optional.of(source(1L, 100L, "melon:100", "u")));
        assertThatThrownBy(() -> service.removeLocked(performance, 1L))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "PRIMARY_SOURCE"));
        when(sourceRepository.findById(5L)).thenReturn(Optional.of(source(5L, 200L, NOL_KEY, "u")));
        assertThatThrownBy(() -> service.removeLocked(performance, 5L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.removeLocked(performance, 6L)).isInstanceOf(NotFoundException.class);
        verify(sourceRepository, never()).delete(any());
    }

    @Test
    void 추가_링크는_삭제된다() {
        PerformanceSource added = source(2L, 100L, NOL_KEY, NOL_URL);
        when(sourceRepository.findById(2L)).thenReturn(Optional.of(added));
        assertThat(service.removeLocked(performance, 2L)).isSameAs(added);
        verify(sourceRepository).delete(added);
    }
}
