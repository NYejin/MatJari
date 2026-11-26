package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 티켓 또는 교환 희망 조건에 붙인 사진 한 장 (V33). 둘 중 정확히 하나만 값이다(DB CHECK). 파일은 서버 디스크
 * ({@code matjari.upload.dir/listing/})에 imageKey(소문자 UUID) 이름으로 있고 deletedAt 은 정리 작업·탈퇴가 파일을 지운 시각이다.
 * 사용자가 직접 지운 사진은 행 자체를 지운다. 연관 대상은 id 값만 둔다(FK 는 DB 에 있고 조회는 id 로만 한다).
 */
@Entity
@Table(name = "listing_image")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class ListingImage {

    /** 티켓·요청당 사진 상한. */
    public static final int MAX_PER_TARGET = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private Long ownerId;

    @Column(name = "ticket_id", updatable = false)
    private Long ticketId;

    @Column(name = "request_id", updatable = false)
    private Long requestId;

    @Column(name = "image_key", nullable = false, updatable = false, length = 64)
    private String imageKey;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private byte sortOrder;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public static ListingImage ofTicket(Long ownerId, Long ticketId, String imageKey, int sortOrder) {
        ListingImage i = new ListingImage();
        i.ownerId = ownerId;
        i.ticketId = ticketId;
        i.imageKey = imageKey;
        i.sortOrder = (byte) sortOrder;
        return i;
    }

    public static ListingImage ofRequest(Long ownerId, Long requestId, String imageKey, int sortOrder) {
        ListingImage i = new ListingImage();
        i.ownerId = ownerId;
        i.requestId = requestId;
        i.imageKey = imageKey;
        i.sortOrder = (byte) sortOrder;
        return i;
    }

    public boolean isFileDeleted() {
        return deletedAt != null;
    }

    public void markFileDeleted(LocalDateTime now) {
        if (deletedAt == null) {
            deletedAt = now;
        }
    }
}
