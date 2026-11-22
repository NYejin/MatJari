package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 채팅 메시지 (V11, append-only). 만든 뒤에는 수정·삭제하지 않으므로 setter 가 없다. 예외는 V30 의 image_deleted_at 하나뿐이다.
 * 사진 메시지(V30)는 content 가 빈 문자열이고 imageKey 가 있다. 연관 엔티티 대신 id 만 가진다.
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

    /** 사진 파일 키(UUID, V30). 텍스트 메시지는 null. */
    @Column(name = "image_key", updatable = false, length = 64)
    private String imageKey;

    /** 보관 기간이 지나 파일을 지운 시각(V30). 정리 작업만 네이티브 UPDATE 로 채운다(이 엔티티로는 쓰지 않는다). */
    @Column(name = "image_deleted_at", insertable = false, updatable = false)
    private LocalDateTime imageDeletedAt;

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

    /** 사진 메시지: 본문은 빈 문자열이다(ck_chat_message_content 는 image_key 가 있으면 빈 본문을 허용). */
    public static ChatMessage ofImage(Long matchId, Long senderId, String clientMessageId, String imageKey) {
        ChatMessage m = of(matchId, senderId, "", clientMessageId);
        m.imageKey = imageKey;
        return m;
    }

    public boolean hasImage() {
        return imageKey != null;
    }

    /** 사진이 있었으나 보관 기간이 지나 지워졌는가. */
    public boolean isImageExpired() {
        return imageKey != null && imageDeletedAt != null;
    }
}
