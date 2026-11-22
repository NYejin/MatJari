package com.matjari.service;

import com.matjari.domain.NotificationType;
import com.matjari.repository.NotificationQueryRepository;
import com.matjari.repository.NotificationRepository;
import com.matjari.exception.FieldValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NEW_CANDIDATE 재사용 알림(V29): dedup 키·PK 먼저 되살리기(emailed_at 초기화는 revive/upsert SQL)·없을 때만 upsert. */
class NotificationServiceNewCandidateTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 12, 0);

    private NotificationRepository repository;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        service = new NotificationService(repository, mock(NotificationQueryRepository.class),
                Clock.fixed(NOW.atZone(KST).toInstant(), KST), mock(NotificationPushPublisher.class));
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void 기존_행이_있으면_PK로_되살리고_upsert하지_않는다() {
        when(repository.findIdsByDedupKey("NEW_CANDIDATE:200:2")).thenReturn(List.of(77L));
        when(repository.reviveReusable(77L, NOW)).thenReturn(1);

        service.notifyNewCandidate(2L, 200L);

        verify(repository).reviveReusable(77L, NOW);
        verify(repository, never()).upsertReusable(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void 행이_없을_때만_upsert하고_매칭_없이_요청_id를_쓴다() {
        when(repository.findIdsByDedupKey("NEW_CANDIDATE:200:2")).thenReturn(List.of(), List.of(88L));

        service.notifyNewCandidate(2L, 200L);

        verify(repository, never()).reviveReusable(anyLong(), any());
        verify(repository).upsertReusable(2L, "NEW_CANDIDATE", null, 200L, "NEW_CANDIDATE:200:2", NOW);
    }

    @Test
    void 키는_요청_id와_수신자_id로_만든다() {
        when(repository.findIdsByDedupKey("NEW_CANDIDATE:9:5")).thenReturn(List.of(1L));
        service.notifyNewCandidate(5L, 9L);
        verify(repository, org.mockito.Mockito.atLeastOnce()).findIdsByDedupKey(eq("NEW_CANDIDATE:9:5"));
    }

    /** 재사용 알림을 되살리는 두 SQL 은 emailed_at 을 NULL 로 되돌려 새 알림이 다시 메일 대상이 되게 한다(실DB 동작은 MySQL IT). */
    @Test
    void 되살리는_SQL은_emailed_at도_NULL로_되돌린다() throws Exception {
        for (String name : List.of("reviveReusable", "upsertReusable")) {
            java.lang.reflect.Method m = java.util.Arrays.stream(NotificationRepository.class.getMethods())
                    .filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            String sql = m.getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
            assertThat(sql).as(name).contains("read_at = NULL").contains("emailed_at = NULL").contains("created_at = :now");
        }
    }

    @Test
    void 알림_종류_필터에_NEW_CANDIDATE를_받아들인다() {
        when(repository.findUnreadIds(1L, NotificationType.NEW_CANDIDATE)).thenReturn(List.of());
        assertThat(service.markAllRead(1L, "NEW_CANDIDATE")).isZero();
        assertThatThrownBy(() -> service.markAllRead(1L, "FOO")).isInstanceOf(FieldValidationException.class);
        verify(repository, never()).markAllRead(any(), anyLong(), any());
    }
}
