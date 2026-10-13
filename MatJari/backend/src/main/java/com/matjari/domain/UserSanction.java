package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 이용 정지(제재) (V13). 관리자가 신고를 처리하며(또는 단독으로) 건다. endsAt NULL = 무기한.
 * 활성 = revokedAt IS NULL AND (endsAt IS NULL OR endsAt > now) — 스케줄러 없이 조회 시 판단한다(isActiveAt).
 * 관리자가 해제하면 revokedAt·revokedById 를 기록한다(해제해도 취소된 매칭은 복구하지 않는다 — 서비스).
 *
 * DB 제약: type 'SUSPEND' 만, 메모 필수, endsAt > startsAt, 해제 시각·해제자는 함께, 자기 자신 제재 불가.
 * ADMIN 대상 거부(409 CANNOT_SANCTION_ADMIN)는 서비스가 한다.
 */
@Entity
@Table(name = "user_sanction")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class UserSanction {

    public static final int NOTE_MAX_LENGTH = 300;

    /** 제재 유형. 지금은 이용 정지 하나(확장 여지). */
    public enum Type {
        SUSPEND
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 정지 대상 -> users. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** 근거 신고 -> abuse_report (없으면 NULL). */
    @Column(name = "report_id", updatable = false)
    private Long reportId;

    /** varchar 컬럼 — Hibernate 6의 MySQL native enum 매핑 방지. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private Type type = Type.SUSPEND;

    @Column(nullable = false, updatable = false, length = NOTE_MAX_LENGTH)
    private String note;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private LocalDateTime startsAt;

    /** NULL = 무기한. */
    @Column(name = "ends_at", updatable = false)
    private LocalDateTime endsAt;

    /** 건 관리자 -> users. */
    @Column(name = "created_by_id", nullable = false, updatable = false)
    private Long createdById;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 해제한 관리자 -> users. */
    @Column(name = "revoked_by_id")
    private Long revokedById;

    /** JPA Auditing 이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 이용 정지 생성. days 가 null 이면 무기한, 아니면 startsAt + days. 허용 기간(7|30|null) 판정은 서비스가 먼저 400 으로 한다.
     * 여기의 IllegalArgumentException 은 최후 방어선.
     */
    public static UserSanction suspend(Long userId, Long reportId, String note, LocalDateTime startsAt, Integer days, Long createdById) {
        if (userId == null || startsAt == null || createdById == null) {
            throw new IllegalArgumentException("userId·startsAt·createdById 는 필수입니다.");
        }
        if (userId.equals(createdById)) {
            throw new IllegalArgumentException("자기 자신은 제재할 수 없습니다.");
        }
        if (days != null && days <= 0) {
            throw new IllegalArgumentException("정지 기간은 1일 이상이어야 합니다.");
        }
        String cleaned = AbuseReport.optionalText(note, NOTE_MAX_LENGTH, "note");
        if (cleaned == null) {
            throw new IllegalArgumentException("제재 사유 메모는 필수입니다.");
        }
        UserSanction s = new UserSanction();
        s.userId = userId;
        s.reportId = reportId;
        s.type = Type.SUSPEND;
        s.note = cleaned;
        s.startsAt = startsAt;
        s.endsAt = days == null ? null : startsAt.plusDays(days);
        s.createdById = createdById;
        return s;
    }

    /** now 시점에 유효한 제재인가(해제되지 않았고, 무기한이거나 아직 끝나지 않음). */
    public boolean isActiveAt(LocalDateTime now) {
        return revokedAt == null && (endsAt == null || endsAt.isAfter(now));
    }

    /** 관리자 해제. 이미 해제·만료됐으면 IllegalStateException(서비스가 먼저 409 SANCTION_NOT_ACTIVE). */
    public void revoke(Long adminId, LocalDateTime now) {
        if (adminId == null || now == null) {
            throw new IllegalArgumentException("adminId 와 now 는 필수입니다.");
        }
        if (!isActiveAt(now)) {
            throw new IllegalStateException("활성 제재만 해제할 수 있습니다.");
        }
        this.revokedAt = now;
        this.revokedById = adminId;
    }
}
