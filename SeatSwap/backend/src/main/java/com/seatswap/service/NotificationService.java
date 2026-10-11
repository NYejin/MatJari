package com.seatswap.service;

import com.seatswap.domain.Notification;
import com.seatswap.domain.NotificationType;
import com.seatswap.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 생성(쓰기 경로). 알림은 상태를 바꾸는 트랜잭션(제안·예약·예약 취소)의 맨 끝에서만 일반 INSERT 로 만든다.
 * 호출자의 트랜잭션이 없으면 예외(MANDATORY)이므로 롤백되면 알림도 함께 사라지고, 상태가 바뀌지 않은 경로(멱등·오류)에서는 호출하지 않는다.
 * 조회·읽음·삭제는 후속 작업이다.
 */
@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * 수신자에게 알림 1건을 만든다. dedupKey 는 한 번만 생기는 알림(PROPOSAL_RECEIVED:{matchId})만 채우고 나머지는 null.
     * 잠금 순서상 notification 쓰기는 트랜잭션의 마지막이어야 한다(호출하는 쪽의 책임).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Notification notify(Long recipientUserId, NotificationType type, Long matchId, String dedupKey) {
        return notificationRepository.saveAndFlush(Notification.of(recipientUserId, type, matchId, dedupKey));
    }

    /** 제안 알림의 dedup 키. */
    public static String proposalDedupKey(Long matchId) {
        return NotificationType.PROPOSAL_RECEIVED.name() + ":" + matchId;
    }
}
