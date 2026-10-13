package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 채팅 메시지 (V11, append-only). 만든 뒤에는 수정·삭제하지 않으므로 setter 가 없다. 연관 엔티티 대신 id 만 가진다.
 * (clientMessageId, senderId) 는 유니크이며 같은 전송을 다시 보내면 기존 행을 돌려주는 멱등 처리에 쓴다.
 * 본문 정규화·검증은 서비스에서 하고, DB CHECK 는 빈 본문과 clientMessageId 길이만 마지막으로 막는다.
 */
@Entity
@Table(name = "chat_message")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class ChatMessage {

    public static final int MAX_CONTENT_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false, updatable = false)
    private Long matchId;

    @Column(name = "sender_id", nullable = false, updatable = false)
    private Long senderId;

    @Column(nullable = false, updatable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    @Column(name = "client_message_id", nullable = false, updatable = false, length = 36)
    private String clientMessageId;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static ChatMessage of(Long matchId, Long senderId, String content, String clientMessageId) {
        ChatMessage m = new ChatMessage();
        m.matchId = matchId;
        m.senderId = senderId;
        m.content = content;
        m.clientMessageId = clientMessageId;
        return m;
    }
}
