package com.matjari.service;

import com.matjari.domain.NotificationType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * STOMP 사용자 큐 푸시 구현(채팅과 같은 연결을 쓰고 목적지만 다르다). 사용자 이름은 userId 문자열이며 같은 사용자의 모든 세션(탭)에 전달된다.
 * 본문에는 알림 문구나 상대 정보를 싣지 않는다(id·유형·매칭 id 뿐).
 */
@Component
public class NotificationPushPublisherImpl implements NotificationPushPublisher {

    static final String DESTINATION = "/queue/notifications";

    /** 푸시 본문. type 은 항상 "NOTIFICATION_CREATED". */
    public record Payload(String type, Long notificationId, String notificationType, Long matchId) {
    }

    private final SimpMessagingTemplate messagingTemplate;

    public NotificationPushPublisherImpl(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publishCreated(Long recipientUserId, Long notificationId, NotificationType type, Long matchId) {
        messagingTemplate.convertAndSendToUser(String.valueOf(recipientUserId), DESTINATION,
                new Payload("NOTIFICATION_CREATED", notificationId, type.name(), matchId));
    }
}
