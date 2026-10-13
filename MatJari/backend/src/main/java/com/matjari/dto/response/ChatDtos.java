package com.matjari.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 채팅 REST·STOMP 가 함께 쓰는 DTO 모음 (파일 수를 줄이려고 한 곳에 둔다).
 * 시각은 오프셋 없는 KST 초 단위 문자열(다른 응답과 같은 형식)이다. STOMP 푸시 본문은 {@link PushMessage}, {@link PushRead} 를 쓴다.
 */
public final class ChatDtos {

    private ChatDtos() {}

    /** 메시지 한 건: REST 응답과 STOMP MESSAGE 푸시의 message 가 같은 모양. */
    public record Message(
            Long id,
            Long senderId,
            String content,
            String clientMessageId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime createdAt
    ) {}

    /** 목록 응답: messages 는 항상 id 오름차순. before/최신 조회에서 hasMore = 더 오래된 메시지가 있음, after 조회에서 hasMore = 더 새 메시지가 있음. */
    public record MessagePage(
            List<Message> messages,
            boolean hasMore,
            long myLastReadMessageId,
            long counterpartLastReadMessageId
    ) {}

    /** 전송 요청. 본문 정규화·길이 검증은 서비스가 한다(빈 본문·1000자 초과도 400). */
    public record SendRequest(
            @NotNull(message = "메시지를 입력해주세요.")
            String content,
            @NotBlank(message = "clientMessageId가 필요합니다.")
            String clientMessageId
    ) {}

    /** 읽음 포인터 요청. 서버가 그 매칭의 실제 메시지 id 로 잘라서 저장한다. */
    public record ReadRequest(
            @NotNull(message = "lastReadMessageId가 필요합니다.")
            @PositiveOrZero(message = "lastReadMessageId는 0 이상이어야 합니다.")
            Long lastReadMessageId
    ) {}

    /** STOMP {@code /user/queue/chat} 새 메시지 푸시: {"type":"MESSAGE","matchId":..,"message":{..}}. */
    public record PushMessage(String type, Long matchId, Message message) {
        public static PushMessage of(Long matchId, Message message) {
            return new PushMessage("MESSAGE", matchId, message);
        }
    }

    /** STOMP 읽음 푸시: {"type":"READ","matchId":..,"lastReadMessageId":..} (상대가 여기까지 읽음). */
    public record PushRead(String type, Long matchId, long lastReadMessageId) {
        public static PushRead of(Long matchId, long lastReadMessageId) {
            return new PushRead("READ", matchId, lastReadMessageId);
        }
    }
}
