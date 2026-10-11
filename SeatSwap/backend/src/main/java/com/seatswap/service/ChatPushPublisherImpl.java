package com.seatswap.service;

import com.seatswap.dto.response.ChatDtos;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * STOMP 사용자 큐 푸시 구현. 사용자 이름은 userId 문자열(CONNECT 의 Principal 이름)이고, 같은 사용자의 모든 세션(탭)에 전달된다.
 * 커밋 후·트랜잭션 밖에서 {@link ChatService} 가 호출한다. 예외는 서비스가 삼키고 로그만 남긴다.
 */
@Component
public class ChatPushPublisherImpl implements ChatPushPublisher {

    static final String DESTINATION = "/queue/chat";

    private final SimpMessagingTemplate messagingTemplate;

    public ChatPushPublisherImpl(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public void publishMessage(Long matchId, ChatDtos.Message message, Collection<Long> recipientUserIds) {
        ChatDtos.PushMessage payload = ChatDtos.PushMessage.of(matchId, message);
        for (Long userId : recipientUserIds) {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), DESTINATION, payload);
        }
    }

    @Override
    public void publishRead(Long matchId, Long recipientUserId, long lastReadMessageId) {
        messagingTemplate.convertAndSendToUser(String.valueOf(recipientUserId), DESTINATION,
                ChatDtos.PushRead.of(matchId, lastReadMessageId));
    }
}
