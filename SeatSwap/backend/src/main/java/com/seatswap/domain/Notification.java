package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 알림 (V10). 수신자(userId)별 1행이며 개인정보(닉네임·좌석)는 저장하지 않고 조회 때 매칭에서 조인한다.
 * 읽음 처리·삭제는 벌크 쿼리(NotificationRepository)로 하므로 엔티티에 setter 가 없고 read_at 은 INSERT 하지 않는다(항상 NULL = 안 읽음).
 * unread_user_id(생성 컬럼)는 DB 가 채우므로 매핑하지 않는다.
 */
@Entity
@Table(name = "notification")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 수신자. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 40)
    private NotificationType type;

    @Column(name = "match_id", nullable = false, updatable = false)
    private Long matchId;

    /** 한 번만 생기는 알림(PROPOSAL_RECEIVED:{matchId})만 채운다. */
    @Column(name = "dedup_key", updatable = false, length = 100)
    private String dedupKey;

    /** NULL = 안 읽음. 읽음 처리는 벌크 UPDATE 로만 한다. */
    @Column(name = "read_at", insertable = false, updatable = false)
    private LocalDateTime readAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static Notification of(Long userId, NotificationType type, Long matchId, String dedupKey) {
        Notification n = new Notification();
        n.userId = userId;
        n.type = type;
        n.matchId = matchId;
        n.dedupKey = dedupKey;
        return n;
    }
}
