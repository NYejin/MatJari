package com.seatswap.service;

import com.seatswap.dto.response.ChatDtos;

import java.util.Collection;

/**
 * 채팅 실시간 전파 자리(커밋 후, 트랜잭션 밖에서만 호출된다). 구현 실패는 저장에 영향을 주지 않으므로 서비스가 예외를 삼키고 로그만 남긴다.
 * 기본은 {@link #NOOP}(구현 빈이 없을 때 ChatService 가 사용)이며, STOMP 구현(A3)은 이 인터페이스를 구현한 @Component 를 하나 등록하면 된다.
 * 구현은 {@code convertAndSendToUser(userId 문자열, "/queue/chat", payload)} 로 보낸다(payload 는 ChatDtos.PushMessage / PushRead).
 */
public interface ChatPushPublisher {

    ChatPushPublisher NOOP = new ChatPushPublisher() {
        @Override
        public void publishMessage(Long matchId, ChatDtos.Message message, Collection<Long> recipientUserIds) {
            // 아무것도 하지 않는다
        }

        @Override
        public void publishRead(Long matchId, Long recipientUserId, long lastReadMessageId) {
            // 아무것도 하지 않는다
        }
    };

    /** 새 메시지를 두 참여자(보낸 사람의 다른 탭 포함)에게 보낸다. */
    void publishMessage(Long matchId, ChatDtos.Message message, Collection<Long> recipientUserIds);

    /** 읽은 사람의 상대에게 "여기까지 읽음"을 알린다. */
    void publishRead(Long matchId, Long recipientUserId, long lastReadMessageId);
}
