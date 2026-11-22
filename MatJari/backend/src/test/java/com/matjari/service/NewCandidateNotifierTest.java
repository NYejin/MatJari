package com.matjari.service;

import com.matjari.repository.CandidateNoticeRepository;
import com.matjari.repository.ExchangeCandidateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 새 후보 알림: 후보 소유자에게만(본인 제외), 같은 쌍은 한 번, 자격 없는 요청·실패는 조용히 넘어간다. 저장소·서비스는 mock. */
class NewCandidateNotifierTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 12, 0);

    private ExchangeCandidateRepository candidates;
    private CandidateNoticeRepository notices;
    private NotificationService notifications;
    private NewCandidateNotifier notifier;

    @BeforeEach
    void setUp() {
        candidates = mock(ExchangeCandidateRepository.class);
        notices = mock(CandidateNoticeRepository.class);
        notifications = mock(NotificationService.class);
        when(notices.requestTicketId(anyLong())).thenAnswer(inv -> inv.<Long>getArgument(0) / 10);   // 요청 100 -> 티켓 10
        notifier = new NewCandidateNotifier(candidates, notices, notifications, mock(PlatformTransactionManager.class),
                Clock.fixed(NOW.atZone(KST).toInstant(), KST), Runnable::run);
    }

    private static ExchangeCandidateRepository.Row row(long requestId, long ticketId) {
        return new ExchangeCandidateRepository.Row(requestId, ticketId, "A", "1", "1", 1L, NOW.plusDays(3), "상대", 1,
                "ANY", null, "ANY", null, NOW.minusDays(1));
    }

    private void candidatesOf(long myRequestId, ExchangeCandidateRepository.Row... rows) {
        when(candidates.findCandidates(eq(myRequestId), eq(NOW.toLocalDate().atStartOfDay()), eq(NewCandidateNotifier.MAX_CANDIDATES), eq(0L)))
                .thenReturn(List.of(rows));
    }

    @Test
    void 후보_요청의_소유자에게_그_요청_기준으로_알림을_쓴다() {
        when(notices.findEligibleOwner(100L, NOW)).thenReturn(1L);
        candidatesOf(100L, row(200L, 20L), row(300L, 30L));
        when(notices.ticketOwners(List.of(20L, 30L))).thenReturn(Map.of(20L, 2L, 30L, 3L));
        when(notices.insertIfAbsent(anyLong(), eq(10L), eq(NOW))).thenReturn(1);

        assertThat(notifier.notifyCandidatesOf(100L)).isEqualTo(2);

        verify(notices).insertIfAbsent(200L, 10L, NOW);
        verify(notices).insertIfAbsent(300L, 10L, NOW);
        verify(notifications).notifyNewCandidate(2L, 200L);
        verify(notifications).notifyNewCandidate(3L, 300L);
    }

    @Test
    void 요청_소유자_본인에게는_보내지_않는다() {
        when(notices.findEligibleOwner(100L, NOW)).thenReturn(1L);
        candidatesOf(100L, row(200L, 20L), row(300L, 30L));
        when(notices.ticketOwners(List.of(20L, 30L))).thenReturn(Map.of(20L, 1L, 30L, 3L));
        when(notices.insertIfAbsent(anyLong(), anyLong(), any())).thenReturn(1);

        assertThat(notifier.notifyCandidatesOf(100L)).isEqualTo(1);

        verify(notifications, never()).notifyNewCandidate(eq(1L), anyLong());
        verify(notifications).notifyNewCandidate(3L, 300L);
        verify(notices, never()).insertIfAbsent(eq(200L), anyLong(), any());
    }

    @Test
    void 이미_알린_쌍은_다시_알리지_않는다() {
        when(notices.findEligibleOwner(100L, NOW)).thenReturn(1L);
        candidatesOf(100L, row(200L, 20L), row(300L, 30L));
        when(notices.ticketOwners(List.of(20L, 30L))).thenReturn(Map.of(20L, 2L, 30L, 3L));
        when(notices.insertIfAbsent(200L, 10L, NOW)).thenReturn(0);   // 이미 있음
        when(notices.insertIfAbsent(300L, 10L, NOW)).thenReturn(1);

        assertThat(notifier.notifyCandidatesOf(100L)).isEqualTo(1);

        verify(notifications, never()).notifyNewCandidate(eq(2L), anyLong());
        verify(notifications).notifyNewCandidate(3L, 300L);
    }

    @Test
    void 자격_없는_요청이면_후보를_조회하지_않는다() {
        when(notices.findEligibleOwner(100L, NOW)).thenReturn(null);   // 닫힘·삭제·티켓 내림·예약 잠금·숨김 공연·정지

        assertThat(notifier.notifyCandidatesOf(100L)).isZero();

        verify(candidates, never()).findCandidates(anyLong(), any(), anyInt(), anyLong());
        verify(notifications, never()).notifyNewCandidate(anyLong(), anyLong());
    }

    @Test
    void 후보가_없으면_아무것도_쓰지_않는다() {
        when(notices.findEligibleOwner(100L, NOW)).thenReturn(1L);
        candidatesOf(100L);

        assertThat(notifier.notifyCandidatesOf(100L)).isZero();
        verify(notices, never()).insertIfAbsent(anyLong(), anyLong(), any());
    }

    @Test
    void 한_쌍이_실패해도_나머지는_계속하고_예외를_던지지_않는다() {
        when(notices.findEligibleOwner(100L, NOW)).thenReturn(1L);
        candidatesOf(100L, row(200L, 20L), row(300L, 30L));
        when(notices.ticketOwners(List.of(20L, 30L))).thenReturn(Map.of(20L, 2L, 30L, 3L));
        when(notices.insertIfAbsent(anyLong(), anyLong(), any())).thenReturn(1);
        doThrow(new IllegalStateException("deadlock")).when(notifications).notifyNewCandidate(2L, 200L);

        assertThat(notifier.notifyCandidatesOf(100L)).isEqualTo(1);
        verify(notifications).notifyNewCandidate(3L, 300L);
    }

    @Test
    void 조회_예외도_삼킨다() {
        when(notices.findEligibleOwner(100L, NOW)).thenThrow(new IllegalStateException("db down"));
        assertThat(notifier.notifyCandidatesOf(100L)).isZero();
    }

    @Test
    void 커밋_뒤_이벤트가_오면_처리를_실행기에_맡긴다() {
        when(notices.findEligibleOwner(100L, NOW)).thenReturn(1L);
        candidatesOf(100L, row(200L, 20L));
        when(notices.ticketOwners(List.of(20L))).thenReturn(Map.of(20L, 2L));
        when(notices.insertIfAbsent(200L, 10L, NOW)).thenReturn(1);

        notifier.onRequestChanged(new NewCandidateCheckEvent(100L));

        verify(notifications).notifyNewCandidate(2L, 200L);
    }
}
