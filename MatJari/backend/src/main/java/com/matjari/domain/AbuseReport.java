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

/**
 * 사용자 신고 (V13). 매칭 화면에서 참여자가 매칭 상대를 신고한다(닫힌 매칭도 가능). 관리자가 RESOLVED(조치, 제재 포함 가능)
 * 또는 DISMISSED(기각)로 처리하며 처리된 신고는 다시 바뀌지 않는다. 신고 자체는 대상에게 알리지 않고 자동 제재도 없다.
 *
 * DB 제약: (신고자, 매칭)당 OPEN 은 1건(uk_abuse_report_open, 생성 컬럼 open_reporter_id 는 DB 가 계산하므로 매핑하지 않는다),
 * 자기 신고 불가, OTHER 는 detail 필수, OPEN 이면 처리 정보가 비어 있고 처리됐으면 처리자·시각이 있다, sanction_id 는 RESOLVED 만.
 * 사용자당 OPEN 상한(MAX_OPEN_PER_USER)·참여자 판정은 서비스가 한다. sanctionId 는 user_sanction.id 값이지만 FK 가 아니다.
 */
@Entity
@Table(name = "abuse_report")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class AbuseReport {

    public static final String UNIQUE_OPEN = "uk_abuse_report_open";
    public static final int DETAIL_MAX_LENGTH = 500;
    public static final int HANDLER_NOTE_MAX_LENGTH = 300;
    public static final int MAX_OPEN_PER_USER = 10;

    /** 신고 사유. ABUSE 욕설·괴롭힘 / FRAUD 사기 의심·허위 예매내역 / NO_SHOW 연락 두절·교환 불이행 / FAKE_SEAT 허위 좌석 / OTHER(상세 필수). */
    public enum Reason {
        ABUSE,
        FRAUD,
        NO_SHOW,
        FAKE_SEAT,
        OTHER
    }

    /** 처리 상태. OPEN -> RESOLVED | DISMISSED (한 번만). */
    public enum Status {
        OPEN,
        RESOLVED,
        DISMISSED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 신고자 -> users. */
    @Column(name = "reporter_id", nullable = false, updatable = false)
    private Long reporterId;

    /** 신고 대상(매칭 상대) -> users. */
    @Column(name = "reported_id", nullable = false, updatable = false)
    private Long reportedId;

    /** 근거 매칭 -> exchange_match. */
    @Column(name = "match_id", nullable = false, updatable = false)
    private Long matchId;

    /** varchar 컬럼 — Hibernate 6의 MySQL native enum 매핑 방지. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, updatable = false, length = 20)
    private Reason reason;

    @Column(updatable = false, length = DETAIL_MAX_LENGTH)
    private String detail;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    /** 처리한 관리자 -> users. */
    @Column(name = "handled_by_id")
    private Long handledById;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @Column(name = "handler_note", length = HANDLER_NOTE_MAX_LENGTH)
    private String handlerNote;

    /** 처리하며 함께 건 제재 id(user_sanction.id, FK 아님). RESOLVED 일 때만. */
    @Column(name = "sanction_id")
    private Long sanctionId;

    /** 생성/수정 시각은 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 신고 생성. detail 은 앞뒤 공백을 지우고 비면 NULL(줄바꿈은 유지). OTHER 는 detail 필수.
     * 서비스가 먼저 400 으로 응답해야 하며 여기의 IllegalArgumentException 은 최후 방어선이다.
     */
    public static AbuseReport create(Long reporterId, Long reportedId, Long matchId, Reason reason, String detail) {
        if (reporterId == null || reportedId == null || matchId == null || reason == null) {
            throw new IllegalArgumentException("reporterId·reportedId·matchId·reason 은 필수입니다.");
        }
        if (reporterId.equals(reportedId)) {
            throw new IllegalArgumentException("자기 자신은 신고할 수 없습니다.");
        }
        AbuseReport r = new AbuseReport();
        r.reporterId = reporterId;
        r.reportedId = reportedId;
        r.matchId = matchId;
        r.reason = reason;
        r.detail = optionalText(detail, DETAIL_MAX_LENGTH, "detail");
        if (reason == Reason.OTHER && r.detail == null) {
            throw new IllegalArgumentException("기타 사유는 상세 내용이 필요합니다.");
        }
        r.status = Status.OPEN;
        return r;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    /** 조치(RESOLVED). sanctionId 는 같은 트랜잭션에서 만든 제재 id(없으면 NULL). OPEN 이 아니면 IllegalStateException. */
    public void resolve(Long handlerId, String note, Long sanctionId, LocalDateTime now) {
        handle(Status.RESOLVED, handlerId, note, now);
        this.sanctionId = sanctionId;
    }

    /** 기각(DISMISSED). OPEN 이 아니면 IllegalStateException. */
    public void dismiss(Long handlerId, String note, LocalDateTime now) {
        handle(Status.DISMISSED, handlerId, note, now);
    }

    private void handle(Status result, Long handlerId, String note, LocalDateTime now) {
        if (!isOpen()) {
            throw new IllegalStateException("OPEN 신고만 처리할 수 있습니다: " + status);
        }
        if (handlerId == null || now == null) {
            throw new IllegalArgumentException("handlerId 와 now 는 필수입니다.");
        }
        this.status = result;
        this.handledById = handlerId;
        this.handledAt = now;
        this.handlerNote = optionalText(note, HANDLER_NOTE_MAX_LENGTH, "handlerNote");
        this.updatedAt = now;
    }

    /** 앞뒤 공백 제거 후 비면 NULL, 길이(코드 포인트) 초과는 IllegalArgumentException. */
    static String optionalText(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.codePointCount(0, stripped.length()) > maxLength) {
            throw new IllegalArgumentException(field + "는 " + maxLength + "자 이하여야 합니다.");
        }
        return stripped;
    }
}
