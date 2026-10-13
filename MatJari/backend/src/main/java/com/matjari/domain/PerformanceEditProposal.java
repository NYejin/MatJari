package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 공연 수정 제안 (V12, V20). 사용자가 공연 제목·공연장 이름·회차(추가·시각 변경·삭제) 중 하나 이상의 변경을 제안하고 관리자가 승인/거절한다.
 * 링크는 수정 대상이 아니다. 회차 변경은 session_changes(JSON)에 담는다. 승인하면 승인 시점의 이전 값(제목·공연장 둘 다)을 old_* 에 스냅샷한다.
 *
 * DB 제약: 한 사용자가 한 공연에 PENDING 은 1건(uk_performance_edit_proposal_pending, 생성 컬럼 pending_proposer_id 는
 * DB 가 계산하므로 매핑하지 않는다). 사용자당 PENDING 합계 상한·'현재 값과 같음' 판정은 서비스가 한다.
 * 잠금 순서(서비스): 제안 행 FOR UPDATE -> 공연 행 FOR UPDATE. 처리된 행은 다시 바뀌지 않는다.
 */
@Entity
@Table(name = "performance_edit_proposal")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class PerformanceEditProposal {

    public static final String UNIQUE_PENDING = "uk_performance_edit_proposal_pending";
    public static final int REASON_MAX_LENGTH = 500;
    public static final int REVIEW_NOTE_MAX_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_id", nullable = false, updatable = false)
    private Performance performance;

    /** 제안자 -> users. */
    @Column(name = "proposer_id", nullable = false, updatable = false)
    private Long proposerId;

    /** 제안한 새 제목. 바꾸지 않으면 NULL. */
    @Column(name = "new_title", updatable = false, length = Performance.TITLE_MAX_LENGTH)
    private String newTitle;

    /** 제안한 새 공연장 이름. 바꾸지 않으면 NULL. */
    @Column(name = "new_venue_name", updatable = false, length = Performance.VENUE_NAME_MAX_LENGTH)
    private String newVenueName;

    /** 제안한 회차 변경(V20, JSON). 없으면 NULL. 승인 때 CHANGE·REMOVE 항목에 기존 시각(oldStartsAt)을 기록한다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "session_changes", columnDefinition = "json")
    private List<SessionChange> sessionChanges;

    @Column(updatable = false, length = REASON_MAX_LENGTH)
    private String reason;

    /** varchar 컬럼 — Hibernate 6의 MySQL native enum 매핑 방지. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AdminReviewStatus status = AdminReviewStatus.PENDING;

    /** 승인 시점의 이전 제목(APPROVED 일 때만). */
    @Column(name = "old_title", length = Performance.TITLE_MAX_LENGTH)
    private String oldTitle;

    /** 승인 시점의 이전 공연장 이름(APPROVED 일 때만). */
    @Column(name = "old_venue_name", length = Performance.VENUE_NAME_MAX_LENGTH)
    private String oldVenueName;

    /** 처리한 관리자 -> users. */
    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = REVIEW_NOTE_MAX_LENGTH)
    private String reviewNote;

    /** 생성/수정 시각은 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 제안 생성. 텍스트는 Performance.cleanDisplayText 로 정리하고 빈 값은 NULL 로 본다.
     * 새 제목·새 공연장 중 하나 이상이 있어야 한다. 길이·'현재 값과 같음' 검증은 서비스가 먼저 400/422 로 응답해야 한다
     * (여기의 IllegalArgumentException 은 최후 방어선).
     */
    public static PerformanceEditProposal create(Performance performance, Long proposerId,
                                                 String newTitle, String newVenueName, String reason) {
        return create(performance, proposerId, newTitle, newVenueName, null, reason);
    }

    public static PerformanceEditProposal create(Performance performance, Long proposerId,
                                                 String newTitle, String newVenueName,
                                                 List<SessionChange> sessionChanges, String reason) {
        if (performance == null || proposerId == null) {
            throw new IllegalArgumentException("performance 와 proposerId 는 필수입니다.");
        }
        PerformanceEditProposal p = new PerformanceEditProposal();
        p.performance = performance;
        p.proposerId = proposerId;
        p.newTitle = optionalText(newTitle, Performance.TITLE_MAX_LENGTH, "newTitle");
        p.newVenueName = optionalText(newVenueName, Performance.VENUE_NAME_MAX_LENGTH, "newVenueName");
        p.sessionChanges = sessionChanges == null || sessionChanges.isEmpty() ? null : List.copyOf(sessionChanges);
        if (p.newTitle == null && p.newVenueName == null && p.sessionChanges == null) {
            throw new IllegalArgumentException("새 제목·새 공연장 이름·회차 변경 중 하나 이상이 필요합니다.");
        }
        p.reason = optionalText(reason, REASON_MAX_LENGTH, "reason");
        p.status = AdminReviewStatus.PENDING;
        return p;
    }

    /** 항상 non-null. */
    public List<SessionChange> sessionChangesOrEmpty() {
        return sessionChanges == null ? List.of() : sessionChanges;
    }

    /** 승인 때 호출: CHANGE·REMOVE 항목에 기존 시각을 기록한다(JSON 컬럼이라 새 목록으로 바꿔야 변경으로 인식된다). */
    public void recordOldSessionTimes(Map<Long, LocalDateTime> currentStartsBySessionId) {
        if (sessionChanges == null) {
            return;
        }
        List<SessionChange> updated = new ArrayList<>();
        for (SessionChange c : sessionChanges) {
            LocalDateTime old = c.sessionId() == null ? null : currentStartsBySessionId.get(c.sessionId());
            updated.add(old == null ? c : c.withOldStartsAt(old.toString()));
        }
        this.sessionChanges = List.copyOf(updated);
    }

    public boolean isPending() {
        return status == AdminReviewStatus.PENDING;
    }

    /**
     * 승인. 서비스가 제안 행과 공연 행을 FOR UPDATE 로 잠근 뒤, 공연에 새 값을 반영하기 직전의 현재 값을 넘긴다.
     * PENDING 이 아니면 IllegalStateException(서비스가 먼저 409 로 막는다).
     */
    public void approve(Long reviewerId, String currentTitle, String currentVenueName, String note, LocalDateTime now) {
        requirePending();
        if (reviewerId == null || now == null || currentTitle == null || currentVenueName == null) {
            throw new IllegalArgumentException("reviewerId·now·현재 제목·현재 공연장은 필수입니다.");
        }
        this.oldTitle = currentTitle;
        this.oldVenueName = currentVenueName;
        review(AdminReviewStatus.APPROVED, reviewerId, note, now);
    }

    /** 거절. PENDING 이 아니면 IllegalStateException. */
    public void reject(Long reviewerId, String note, LocalDateTime now) {
        requirePending();
        if (reviewerId == null || now == null) {
            throw new IllegalArgumentException("reviewerId 와 now 는 필수입니다.");
        }
        review(AdminReviewStatus.REJECTED, reviewerId, note, now);
    }

    private void review(AdminReviewStatus result, Long reviewerId, String note, LocalDateTime now) {
        this.status = result;
        this.reviewedById = reviewerId;
        this.reviewedAt = now;
        this.reviewNote = optionalText(note, REVIEW_NOTE_MAX_LENGTH, "reviewNote");
        this.updatedAt = now;
    }

    private void requirePending() {
        if (!isPending()) {
            throw new IllegalStateException("PENDING 제안만 처리할 수 있습니다: " + status);
        }
    }

    /** 정리 후 비면 NULL, 길이 초과는 IllegalArgumentException. */
    static String optionalText(String value, int maxLength, String field) {
        String cleaned = Performance.cleanDisplayText(value);
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > maxLength) {
            throw new IllegalArgumentException(field + "는 " + maxLength + "자 이하여야 합니다.");
        }
        return cleaned;
    }

    /**
     * 회차 변경 하나(JSON 원소). type = ADD / CHANGE / REMOVE. startsAt·oldStartsAt 은 오프셋 없는 로컬 시각 문자열(ISO, 초 포함 가능).
     * ADD: sessionId 없음 / CHANGE: sessionId + 새 startsAt / REMOVE: sessionId 만.
     */
    public record SessionChange(String type, Long sessionId, String startsAt, String oldStartsAt) {
        public static final String ADD = "ADD";
        public static final String CHANGE = "CHANGE";
        public static final String REMOVE = "REMOVE";

        public SessionChange withOldStartsAt(String old) {
            return new SessionChange(type, sessionId, startsAt, old);
        }

        public LocalDateTime startsAtTime() {
            return startsAt == null ? null : LocalDateTime.parse(startsAt);
        }
    }
}
