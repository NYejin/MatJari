package com.seatswap.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 좌석 소유 인증 요청 '내 티켓 인증' (V12). 이미 다른 사람이 활성 티켓으로 등록한 좌석의 실제 소유자가
 * 예매내역 사진 1장과 함께 관리자 확인을 요청한다. 티켓 id 가 아니라 좌석 좌표(회차·구역·열·번)를 담는다.
 * 좌석 label·key 는 Ticket 과 같은 길이이며 SeatKeyNormalizer 를 거친 값을 서비스가 넘긴다.
 *
 * DB 제약: 한 사용자가 같은 좌석에 PENDING 은 1건(uk_seat_claim_pending_seat, 생성 컬럼 pending_claimant_id 는 매핑하지 않는다).
 * 사진은 서버 디스크에 imageKey(UUID 소문자 36자) 이름으로 저장하고, 처리 후 파일을 지운 뒤 markImageDeleted 로 기록한다.
 * resolvedTicketId 는 승인 때 내려진 티켓 id 기록이며 FK 가 아니다(이미 비어 있었으면 NULL).
 * 잠금 순서(서비스): 인증 요청 행 FOR UPDATE -> 티켓 id↑ -> 교환 요청 id↑ -> 매칭. 처리된 행은 상태가 다시 바뀌지 않는다.
 */
@Entity
@Table(name = "seat_claim")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class SeatClaim {

    public static final String UNIQUE_PENDING_SEAT = "uk_seat_claim_pending_seat";
    public static final int MEMO_MAX_LENGTH = 300;
    public static final int REVIEW_NOTE_MAX_LENGTH = 300;
    public static final long IMAGE_MAX_BYTES = 5L * 1024 * 1024;
    public static final Set<String> IMAGE_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Pattern IMAGE_KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 요청자 -> users. */
    @Column(name = "claimant_id", nullable = false, updatable = false)
    private Long claimantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_session_id", nullable = false, updatable = false)
    private PerformanceSession performanceSession;

    @Column(name = "zone_label", nullable = false, updatable = false, length = Ticket.ZONE_MAX_LENGTH)
    private String zoneLabel;

    @Column(name = "zone_key", nullable = false, updatable = false, length = Ticket.ZONE_MAX_LENGTH)
    private String zoneKey;

    @Column(name = "row_label", nullable = false, updatable = false, length = Ticket.ROW_MAX_LENGTH)
    private String rowLabel;

    @Column(name = "row_key", nullable = false, updatable = false, length = Ticket.ROW_MAX_LENGTH)
    private String rowKey;

    @Column(name = "col_label", nullable = false, updatable = false, length = Ticket.COL_MAX_LENGTH)
    private String colLabel;

    @Column(name = "col_key", nullable = false, updatable = false, length = Ticket.COL_MAX_LENGTH)
    private String colKey;

    @Column(updatable = false, length = MEMO_MAX_LENGTH)
    private String memo;

    /** 서버 저장 파일 이름(UUID 소문자). 클라이언트 파일명·경로는 쓰지 않는다. */
    @Column(name = "image_key", nullable = false, updatable = false, length = 36)
    private String imageKey;

    /** 파일 시작 바이트(매직 넘버)로 판정한 형식: image/jpeg, image/png, image/webp. */
    @Column(name = "image_content_type", nullable = false, updatable = false, length = 20)
    private String imageContentType;

    @Column(name = "image_size", nullable = false, updatable = false)
    private int imageSize;

    /** 처리 후 사진 파일을 지운 시각. PENDING 이면 항상 NULL. */
    @Column(name = "image_deleted_at")
    private LocalDateTime imageDeletedAt;

    /** varchar 컬럼 — Hibernate 6의 MySQL native enum 매핑 방지. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AdminReviewStatus status = AdminReviewStatus.PENDING;

    /** 처리한 관리자 -> users. */
    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = REVIEW_NOTE_MAX_LENGTH)
    private String reviewNote;

    /** 승인 때 시스템이 내린 티켓 id(FK 아님). 승인 시점에 이미 비어 있었으면 NULL. */
    @Column(name = "resolved_ticket_id")
    private Long resolvedTicketId;

    /** 생성/수정 시각은 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 요청 생성. 좌석 label·key 는 SeatKeyNormalizer 를 거친 값, 이미지 형식은 매직 넘버로 판정한 값이어야 한다.
     * 입력 검증(400/422)은 서비스가 먼저 하고, 여기의 IllegalArgumentException 은 최후 방어선이다.
     */
    public static SeatClaim create(Long claimantId, PerformanceSession performanceSession,
                                   String zoneLabel, String zoneKey,
                                   String rowLabel, String rowKey,
                                   String colLabel, String colKey,
                                   String memo, String imageKey, String imageContentType, long imageSize) {
        if (claimantId == null || performanceSession == null) {
            throw new IllegalArgumentException("claimantId 와 performanceSession 은 필수입니다.");
        }
        if (imageKey == null || !IMAGE_KEY.matcher(imageKey).matches()) {
            throw new IllegalArgumentException("imageKey 는 소문자 UUID 여야 합니다.");
        }
        if (imageContentType == null || !IMAGE_CONTENT_TYPES.contains(imageContentType)) {
            throw new IllegalArgumentException("지원하지 않는 이미지 형식입니다: " + imageContentType);
        }
        if (imageSize < 1 || imageSize > IMAGE_MAX_BYTES) {
            throw new IllegalArgumentException("이미지 크기는 1바이트 ~ 5 MiB 여야 합니다.");
        }
        SeatClaim c = new SeatClaim();
        c.claimantId = claimantId;
        c.performanceSession = performanceSession;
        c.zoneLabel = requireText(zoneLabel, Ticket.ZONE_MAX_LENGTH, "zoneLabel");
        c.zoneKey = requireText(zoneKey, Ticket.ZONE_MAX_LENGTH, "zoneKey");
        c.rowLabel = requireText(rowLabel, Ticket.ROW_MAX_LENGTH, "rowLabel");
        c.rowKey = requireText(rowKey, Ticket.ROW_MAX_LENGTH, "rowKey");
        c.colLabel = requireText(colLabel, Ticket.COL_MAX_LENGTH, "colLabel");
        c.colKey = requireText(colKey, Ticket.COL_MAX_LENGTH, "colKey");
        c.memo = PerformanceEditProposal.optionalText(memo, MEMO_MAX_LENGTH, "memo");
        c.imageKey = imageKey;
        c.imageContentType = imageContentType;
        c.imageSize = (int) imageSize;
        c.status = AdminReviewStatus.PENDING;
        return c;
    }

    public boolean isPending() {
        return status == AdminReviewStatus.PENDING;
    }

    public boolean isOwnedBy(Long userId) {
        return userId != null && userId.equals(claimantId);
    }

    public boolean isImageDeleted() {
        return imageDeletedAt != null;
    }

    /**
     * 승인. resolvedTicketId = 이번 승인으로 내린 티켓 id(이미 비어 있었으면 null).
     * PENDING 이 아니면 IllegalStateException(서비스가 먼저 409 로 막는다).
     */
    public void approve(Long reviewerId, Long resolvedTicketId, String note, LocalDateTime now) {
        requirePending();
        review(AdminReviewStatus.APPROVED, reviewerId, note, now);
        this.resolvedTicketId = resolvedTicketId;
    }

    /** 거절. PENDING 이 아니면 IllegalStateException. */
    public void reject(Long reviewerId, String note, LocalDateTime now) {
        requirePending();
        review(AdminReviewStatus.REJECTED, reviewerId, note, now);
    }

    /** 처리 후 사진 파일 삭제 기록. PENDING 이면 IllegalStateException, 이미 기록됐으면 아무 일도 하지 않는다(멱등). */
    public void markImageDeleted(LocalDateTime now) {
        if (isPending()) {
            throw new IllegalStateException("처리 전 요청의 사진은 삭제 기록할 수 없습니다.");
        }
        if (imageDeletedAt == null) {
            this.imageDeletedAt = now;
            this.updatedAt = now;
        }
    }

    private void review(AdminReviewStatus result, Long reviewerId, String note, LocalDateTime now) {
        if (reviewerId == null || now == null) {
            throw new IllegalArgumentException("reviewerId 와 now 는 필수입니다.");
        }
        this.status = result;
        this.reviewedById = reviewerId;
        this.reviewedAt = now;
        this.reviewNote = PerformanceEditProposal.optionalText(note, REVIEW_NOTE_MAX_LENGTH, "reviewNote");
        this.updatedAt = now;
    }

    private void requirePending() {
        if (!isPending()) {
            throw new IllegalStateException("PENDING 요청만 처리할 수 있습니다: " + status);
        }
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + "는 1~" + maxLength + "자여야 합니다.");
        }
        return value;
    }
}
