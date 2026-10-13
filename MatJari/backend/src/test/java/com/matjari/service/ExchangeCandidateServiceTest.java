package com.matjari.service;

import com.matjari.domain.ExchangeRequest;
import com.matjari.domain.ExchangeRequestStatus;
import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.Ticket;
import com.matjari.domain.TicketStatus;
import com.matjari.domain.User;
import com.matjari.dto.response.ExchangeCandidateResponse;
import com.matjari.dto.response.PageResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.ForbiddenException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ExchangeCandidateRepository;
import com.matjari.repository.ExchangeRequestRepository;
import com.matjari.repository.ExchangeTicketLockRepository;
import com.matjari.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.matjari.service.PerformanceFixtures.performance;
import static com.matjari.service.PerformanceFixtures.session;
import static com.matjari.service.PerformanceFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 기준 시각 2026-10-06 12:00 KST (PerformanceFixtures). 후보 SQL 자체는 ExchangeCandidateQueryTest(실제 MySQL)에서 검증한다. */
class ExchangeCandidateServiceTest {

    private static final LocalDateTime TODAY_START = LocalDateTime.of(2026, 10, 6, 0, 0);

    private ExchangeRequestRepository requestRepository;
    private TicketRepository ticketRepository;
    private ExchangeCandidateRepository candidateRepository;
    private ExchangeTicketLockRepository lockRepository;
    private ExchangeCandidateService service;

    private final User me = user(1L, "나");
    private final User other = user(2L, "상대");
    private final Performance performance = performance(10L, "KSPO DOME", other);
    private final PerformanceSession openSession = session(7L, performance, LocalDateTime.of(2026, 11, 1, 19, 0));
    private final PerformanceSession closedSession = session(10L, performance, LocalDateTime.of(2026, 10, 5, 19, 0));
    // 오늘 이른 회차: 마감(내일 0시) 전이므로 아직 조회 가능
    private final PerformanceSession todaySession = session(11L, performance, LocalDateTime.of(2026, 10, 6, 10, 0));

    @BeforeEach
    void setUp() {
        requestRepository = mock(ExchangeRequestRepository.class);
        ticketRepository = mock(TicketRepository.class);
        candidateRepository = mock(ExchangeCandidateRepository.class);
        lockRepository = mock(ExchangeTicketLockRepository.class);
        service = new ExchangeCandidateService(requestRepository, ticketRepository, candidateRepository,
                PerformanceFixtures.timePolicy(), lockRepository);
    }

    private ExchangeRequest stub(long requestId, User owner, PerformanceSession session, TicketStatus ticketStatus,
                                 ExchangeRequestStatus requestStatus) {
        Ticket t = Ticket.create(owner, session, "A", "A", "1", "1", "1", "1");
        ReflectionTestUtils.setField(t, "id", 500L);
        ReflectionTestUtils.setField(t, "status", ticketStatus);
        ExchangeRequest r = ExchangeRequest.create(t);
        ReflectionTestUtils.setField(r, "id", requestId);
        ReflectionTestUtils.setField(r, "status", requestStatus);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(r));
        when(ticketRepository.findWithSessionById(500L)).thenReturn(Optional.of(t));
        return r;
    }

    private static ExchangeCandidateRepository.Row row(long requestId, String myType, Integer myAmount,
                                                       String type, Integer amount) {
        return new ExchangeCandidateRepository.Row(requestId, requestId + 1000, "B", "2", "3", 8L,
                LocalDateTime.of(2026, 11, 2, 19, 0), "닉", 2, type, amount, myType, myAmount,
                LocalDateTime.of(2026, 10, 5, 9, 0));
    }

    @Test
    void notFoundIs404() {
        when(requestRepository.findById(900L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findCandidates(1L, 900L, 0, 20)).isInstanceOf(NotFoundException.class);
        verify(candidateRepository, never()).findCandidates(anyLong(), any(), anyInt(), anyLong());
    }

    @Test
    void othersRequestIs403() {
        stub(900L, other, openSession, TicketStatus.ACTIVE, ExchangeRequestStatus.OPEN);
        assertThatThrownBy(() -> service.findCandidates(1L, 900L, 0, 20)).isInstanceOf(ForbiddenException.class);
        verify(candidateRepository, never()).findCandidates(anyLong(), any(), anyInt(), anyLong());
    }

    @Test
    void closedRequestIs422() {
        stub(900L, me, openSession, TicketStatus.ACTIVE, ExchangeRequestStatus.CLOSED);
        assertThatThrownBy(() -> service.findCandidates(1L, 900L, 0, 20))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_ACTIVE"));
    }

    @Test
    void deletedRequestIs409RequestDeleted() {
        stub(900L, me, openSession, TicketStatus.ACTIVE, ExchangeRequestStatus.DELETED);
        assertThatThrownBy(() -> service.findCandidates(1L, 900L, 0, 20))
                .isInstanceOfSatisfying(com.matjari.exception.ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("code", "REQUEST_DELETED"));
        verify(candidateRepository, never()).findCandidates(anyLong(), any(), anyInt(), anyLong());
    }

    @Test
    void othersDeletedRequestIsStill403() {
        stub(900L, other, openSession, TicketStatus.ACTIVE, ExchangeRequestStatus.DELETED);
        assertThatThrownBy(() -> service.findCandidates(1L, 900L, 0, 20)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void inactiveTicketIs422() {
        stub(900L, me, openSession, TicketStatus.INACTIVE, ExchangeRequestStatus.OPEN);
        assertThatThrownBy(() -> service.findCandidates(1L, 900L, 0, 20))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_ACTIVE"));
    }

    @Test
    void closedSessionTicketIs422() {
        stub(900L, me, closedSession, TicketStatus.ACTIVE, ExchangeRequestStatus.OPEN);
        assertThatThrownBy(() -> service.findCandidates(1L, 900L, 0, 20))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SESSION_CLOSED"));
    }

    // ---- keyset(커서) 조각

    @Test
    void sliceReturnsNextCursorOnlyWhenThereIsMoreAndPassesSizePlusOne() {
        stub(900L, me, openSession, TicketStatus.ACTIVE, ExchangeRequestStatus.OPEN);
        // size=2 -> 저장소에는 3개를 요청하고, 3개가 오면 다음 조각이 있다
        when(candidateRepository.findCandidatesAfter(900L, TODAY_START, null, 3))
                .thenReturn(List.of(row(1, "ANY", null, "ANY", null), row(2, "ANY", null, "ANY", null), row(3, "ANY", null, "ANY", null)));

        var slice = service.findCandidateSlice(1L, 900L, null, 2);

        assertThat(slice.content()).extracting(ExchangeCandidateResponse::requestId).containsExactly(1L, 2L);
        assertThat(slice.hasNext()).isTrue();
        var decoded = ExchangeCandidateRepository.Cursor.decode(slice.nextCursor());
        assertThat(decoded.requestId()).isEqualTo(2L);
        assertThat(decoded.priority()).isEqualTo(2);
    }

    @Test
    void sliceEndsWithNullCursorAndUsesGivenCursor() {
        stub(900L, me, openSession, TicketStatus.ACTIVE, ExchangeRequestStatus.OPEN);
        var cursor = new ExchangeCandidateRepository.Cursor(1, LocalDateTime.of(2026, 10, 5, 9, 0), 40L);
        when(candidateRepository.findCandidatesAfter(900L, TODAY_START, cursor, 21))
                .thenReturn(List.of(row(5, "ANY", null, "ANY", null)));

        var slice = service.findCandidateSlice(1L, 900L, cursor.encode(), 20);

        assertThat(slice.hasNext()).isFalse();
        assertThat(slice.nextCursor()).isNull();
        assertThat(slice.content()).hasSize(1);
    }

    @Test
    void sliceClampsSizeAndRejectsBrokenCursorBeforeAnyQuery() {
        stub(900L, me, openSession, TicketStatus.ACTIVE, ExchangeRequestStatus.OPEN);
        when(candidateRepository.findCandidatesAfter(900L, TODAY_START, null, 101)).thenReturn(List.of());
        assertThat(service.findCandidateSlice(1L, 900L, "  ", 1000).size()).isEqualTo(100);

        assertThatThrownBy(() -> service.findCandidateSlice(1L, 900L, "garbage", 20))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("cursor"));
    }

    @Test
    void sliceKeepsTheSameAccessRulesAsPaging() {
        stub(900L, other, openSession, TicketStatus.ACTIVE, ExchangeRequestStatus.OPEN);
        assertThatThrownBy(() -> service.findCandidateSlice(1L, 900L, null, 20)).isInstanceOf(ForbiddenException.class);
        when(requestRepository.findById(901L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findCandidateSlice(1L, 901L, null, 20)).isInstanceOf(NotFoundException.class);
        verify(candidateRepository, never()).findCandidatesAfter(anyLong(), any(), any(), anyInt());
    }

    @Test
    void todaysSessionIsStillSearchableUntilEndOfDay() {
        stub(900L, me, todaySession, TicketStatus.ACTIVE, ExchangeRequestStatus.OPEN);
        when(candidateRepository.findCandidates(900L, TODAY_START, 20, 0)).thenReturn(List.of());
        when(candidateRepository.countCandidates(900L, TODAY_START)).thenReturn(0L);

        PageResponse<ExchangeCandidateResponse> page = service.findCandidates(1L, 900L, 0, 20);
        assertThat(page.content()).isEmpty();
        assertThat(page.totalPages()).isZero();
    }

    @Test
    void sizeIsClampedAndOffsetUsesPage() {
        stub(900L, me, openSession, TicketStatus.ACTIVE, ExchangeRequestStatus.OPEN);
        when(candidateRepository.findCandidates(eq(900L), eq(TODAY_START), anyInt(), anyLong())).thenReturn(List.of());
        when(candidateRepository.countCandidates(900L, TODAY_START)).thenReturn(250L);

        PageResponse<ExchangeCandidateResponse> page = service.findCandidates(1L, 900L, 2, 1000);

        verify(candidateRepository).findCandidates(900L, TODAY_START, 100, 200);
        assertThat(page.size()).isEqualTo(100);
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(250);
        assertThat(page.totalPages()).isEqualTo(3);

        service.findCandidates(1L, 900L, 0, 0);
        verify(candidateRepository).findCandidates(900L, TODAY_START, 1, 0);
    }

    @Test
    void negativePageIs400() {
        assertThatThrownBy(() -> service.findCandidates(1L, 900L, -1, 20)).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void responseMapsRowAndExposesNoPersonalData() {
        stub(900L, me, openSession, TicketStatus.ACTIVE, ExchangeRequestStatus.OPEN);
        when(candidateRepository.findCandidates(900L, TODAY_START, 20, 0))
                .thenReturn(List.of(row(901L, "POS", 5000, "NEG", -8000)));
        when(candidateRepository.countCandidates(900L, TODAY_START)).thenReturn(1L);

        ExchangeCandidateResponse c = service.findCandidates(1L, 900L, 0, 20).content().get(0);

        assertThat(c.requestId()).isEqualTo(901L);
        assertThat(c.nickname()).isEqualTo("닉");
        assertThat(c.wantPriority()).isEqualTo(2);
        assertThat(c.extraType()).isEqualTo("NEG");
        assertThat(c.myExtraType()).isEqualTo("POS");
        assertThat(c.settlementHint()).isEqualTo(new ExchangeCandidateResponse.SettlementHint(5000, 8000));
        assertThat(ExchangeCandidateResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .noneMatch(n -> n.toLowerCase().contains("email") || n.toLowerCase().contains("trust")
                        || n.toLowerCase().contains("score") || n.equals("userId"));
    }

    // ---------- settlementHint ----------

    @Test
    void hintIsOverlapWhenPosAndNegHaveAmounts() {
        // 내가 받아야 하는 최소 5000, 상대가 낼 수 있는 최대 8000 -> [5000, 8000]
        assertThat(ExchangeCandidateService.settlementHint("POS", 5000, "NEG", -8000))
                .isEqualTo(new ExchangeCandidateResponse.SettlementHint(5000, 8000));
        // 방향 반대: 내가 낼 수 있는 최대 8000, 상대가 받아야 하는 최소 5000
        assertThat(ExchangeCandidateService.settlementHint("NEG", -8000, "POS", 5000))
                .isEqualTo(new ExchangeCandidateResponse.SettlementHint(5000, 8000));
        // 경계: m == p
        assertThat(ExchangeCandidateService.settlementHint("POS", 5000, "NEG", -5000))
                .isEqualTo(new ExchangeCandidateResponse.SettlementHint(5000, 5000));
    }

    @Test
    void hintIsNullWhenNoOverlapOrNotApplicable() {
        assertThat(ExchangeCandidateService.settlementHint("POS", 9000, "NEG", -8000)).isNull();   // p < m
        assertThat(ExchangeCandidateService.settlementHint("NEG", -1000, "POS", 5000)).isNull();   // p < m
        assertThat(ExchangeCandidateService.settlementHint("POS", 5000, "ANY", null)).isNull();    // 금액 없음
        assertThat(ExchangeCandidateService.settlementHint("X", null, "NEG", -3000)).isNull();
        assertThat(ExchangeCandidateService.settlementHint("NEG", -3000, "NEG", -3000)).isNull();  // POS-NEG 아님
        assertThat(ExchangeCandidateService.settlementHint("POS", 3000, "POS", 3000)).isNull();
    }

    @Test
    void hintDoesNotOverflowOnIntegerMinValue() {
        // -Integer.MIN_VALUE 는 int 로는 오버플로(자기 자신)이므로 long 으로 계산한다
        assertThat(ExchangeCandidateService.settlementHint("POS", 5000, "NEG", Integer.MIN_VALUE))
                .isEqualTo(new ExchangeCandidateResponse.SettlementHint(5000, 2147483648L));
        assertThat(ExchangeCandidateService.settlementHint("NEG", Integer.MIN_VALUE, "POS", Integer.MAX_VALUE))
                .isEqualTo(new ExchangeCandidateResponse.SettlementHint(Integer.MAX_VALUE, 2147483648L));
        assertThat(ExchangeCandidateService.settlementHint("POS", Integer.MAX_VALUE, "NEG", -1)).isNull();
    }
}
