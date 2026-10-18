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
 * 관리자 직접 조치 기록 (V22). 추가만 하고 수정·삭제하지 않는다. 대상은 FK 가 아니라 (targetType, targetId) 로만 가리킨다.
 * detail 은 변경 전후 값 등 보조 정보(JSON 문자열, 없으면 null).
 */
@Entity
@Table(name = "admin_action_log")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class AdminActionLog {

    public static final int REASON_MAX_LENGTH = 500;

    public enum Action {
        PERFORMANCE_HIDE, PERFORMANCE_UNHIDE, PERFORMANCE_EDIT, PERFORMANCE_SESSIONS, PERFORMANCE_ZONES,
        USER_SUSPEND, USER_UNSUSPEND, USER_ROLE_CHANGE, USER_FORCE_WITHDRAW
    }

    public enum TargetType {
        PERFORMANCE, USER
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_id", nullable = false, updatable = false)
    private Long adminId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 40)
    private Action action;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "target_type", nullable = false, updatable = false, length = 20)
    private TargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private Long targetId;

    @Column(updatable = false, length = REASON_MAX_LENGTH)
    private String reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json", updatable = false)
    private String detail;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static AdminActionLog of(Long adminId, Action action, TargetType targetType, Long targetId, String reason, String detailJson) {
        AdminActionLog log = new AdminActionLog();
        log.adminId = adminId;
        log.action = action;
        log.targetType = targetType;
        log.targetId = targetId;
        log.reason = reason;
        log.detail = detailJson;
        return log;
    }
}
