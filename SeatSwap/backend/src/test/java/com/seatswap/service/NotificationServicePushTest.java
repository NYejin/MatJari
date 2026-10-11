package com.seatswap.service;

import com.seatswap.domain.Notification;
import com.seatswap.domain.NotificationType;
import com.seatswap.repository.NotificationQueryRepository;
import com.seatswap.repository.NotificationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 알림 실시간 푸시는 커밋 뒤에만, 롤백이면 보내지 않고, 전파 실패는 삼킨다 (트랜잭션 동기화만 흉내 낸 단위 테스트). */
class NotificationServicePushTest {

    private NotificationRepository repository;
    private NotificationPushPublisher publisher;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationRepository.class);
        publisher = mock(NotificationPushPublisher.class);
        service = new NotificationService(repository, mock(NotificationQueryRepository.class), Clock.systemUTC(), publisher);
        when(repository.saveAndFlush(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            ReflectionTestUtils.setField(n, "id", 99L);
            return n;
        });
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void 저장_직후에는_보내지_않고_커밋_뒤에_수신자에게_한_번_보낸다() {
        service.notify(2L, NotificationType.PROPOSAL_RECEIVED, 7L, "PROPOSAL_RECEIVED:7");

        verify(publisher, never()).publishCreated(any(), any(), any(), any());
        runAfterCommit();
        verify(publisher).publishCreated(2L, 99L, NotificationType.PROPOSAL_RECEIVED, 7L);
    }

    @Test
    void 롤백되면_보내지_않는다() {
        service.notify(2L, NotificationType.MATCH_RESERVED, 7L, null);

        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(publisher, never()).publishCreated(any(), any(), any(), any());
    }

    @Test
    void 전파가_실패해도_예외를_밖으로_던지지_않는다() {
        doThrow(new IllegalStateException("broker down")).when(publisher).publishCreated(any(), any(), any(), any());
        service.notify(2L, NotificationType.RESERVATION_CANCELED, 7L, null);

        runAfterCommit(); // 던지면 테스트가 실패한다
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
    }

    private static void runAfterCommit() {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        syncs.forEach(TransactionSynchronization::afterCommit);
    }
}
