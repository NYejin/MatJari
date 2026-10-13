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
 * 사용자가 보유한 티켓(좌석 1개). 좌석 키는 (회차, 구역, 열, 번)이며 텍스트로 입력받는다.
 * *_label은 표시용 원문(공백 정리), *_key는 정규화 키(SeatKeyNormalizer)다. 키는 서비스가 계산해 넘긴다.
 *
 * 같은 회차·구역·열·번의 ACTIVE 티켓은 1개만 허용한다 — DB 생성 컬럼 active_flag + uk_ticket_active_seat
 * (active_flag는 DB가 계산하므로 매핑하지 않는다). 내린 티켓은 INACTIVE로 남긴다(소프트 삭제).
 *
 * 교환은 같은 공연(Performance)끼리 가능하다 (같은 공연의 다른 회차 티켓끼리도 교환 가능, 2026-10-07 결정).
 * 티켓은 회차(PerformanceSession)를 참조하고, 공연은 performanceSession.performance로 얻는다 — performance_id를 중복으로 두지 않는다
 * (두 FK가 서로 다른 공연을 가리키는 불일치를 원천 차단).
 */
@Entity
@Table(name = "ticket")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class Ticket {

    public static final String UNIQUE_ACTIVE_SEAT = "uk_ticket_active_seat";
    public static final int ZONE_MAX_LENGTH = 50;
    public static final int ROW_MAX_LENGTH = 20;
    public static final int COL_MAX_LENGTH = 20;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_session_id", nullable = false, updatable = false)
    private PerformanceSession performanceSession;

    @Column(name = "zone_label", nullable = false, updatable = false, length = ZONE_MAX_LENGTH)
    private String zoneLabel;

    @Column(name = "zone_key", nullable = false, updatable = false, length = ZONE_MAX_LENGTH)
    private String zoneKey;

    @Column(name = "row_label", nullable = false, updatable = false, length = ROW_MAX_LENGTH)
    private String rowLabel;

    @Column(name = "row_key", nullable = false, updatable = false, length = ROW_MAX_LENGTH)
    private String rowKey;

    @Column(name = "col_label", nullable = false, updatable = false, length = COL_MAX_LENGTH)
    private String colLabel;

    @Column(name = "col_key", nullable = false, updatable = false, length = COL_MAX_LENGTH)
    private String colKey;

    /** varchar 컬럼 — Hibernate 6의 MySQL native enum 매핑 방지. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private TicketStatus status = TicketStatus.ACTIVE;

    /** 생성/수정 시각은 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** 라벨·키는 SeatKeyNormalizer를 거친 값이어야 한다(길이 검증은 서비스에서 먼저 수행, 여기는 최후 방어선). */
    public static Ticket create(User user, PerformanceSession performanceSession,
                                String zoneLabel, String zoneKey,
                                String rowLabel, String rowKey,
                                String colLabel, String colKey) {
        requireNonNull(user, "user");
        requireNonNull(performanceSession, "performanceSession");
        Ticket ticket = new Ticket();
        ticket.user = user;
        ticket.performanceSession = performanceSession;
        ticket.zoneLabel = requireText(zoneLabel, ZONE_MAX_LENGTH, "zoneLabel");
        ticket.zoneKey = requireText(zoneKey, ZONE_MAX_LENGTH, "zoneKey");
        ticket.rowLabel = requireText(rowLabel, ROW_MAX_LENGTH, "rowLabel");
        ticket.rowKey = requireText(rowKey, ROW_MAX_LENGTH, "rowKey");
        ticket.colLabel = requireText(colLabel, COL_MAX_LENGTH, "colLabel");
        ticket.colKey = requireText(colKey, COL_MAX_LENGTH, "colKey");
        ticket.status = TicketStatus.ACTIVE;
        return ticket;
    }

    public boolean isActive() {
        return status == TicketStatus.ACTIVE;
    }

    public boolean isExchanged() {
        return status == TicketStatus.EXCHANGED;
    }

    /**
     * 교환 완료 시 새 자리 티켓: 소유자는 그대로(owner), 회차·구역·열·번(label·key)은 상대의 기존 티켓에서 그대로 복사한다.
     * 키는 이미 정규화된 값이므로 다시 정규화하지 않는다. 상태는 ACTIVE.
     */
    public static Ticket exchangedFrom(User owner, Ticket counterpartOld) {
        requireNonNull(counterpartOld, "counterpartOld");
        return create(owner, counterpartOld.performanceSession,
                counterpartOld.zoneLabel, counterpartOld.zoneKey,
                counterpartOld.rowLabel, counterpartOld.rowKey,
                counterpartOld.colLabel, counterpartOld.colKey);
    }

    /** 보유자 여부. LAZY 프록시의 id만 읽으므로 추가 쿼리가 나가지 않는다. */
    public boolean isOwnedBy(Long userId) {
        return userId != null && user != null && userId.equals(user.getId());
    }

    /** 티켓 내리기(소프트 삭제). 이미 INACTIVE면 아무 일도 하지 않는다. */
    public void deactivate() {
        this.status = TicketStatus.INACTIVE;
    }

    /** 교환 완료로 기존 티켓을 닫는다. ACTIVE 에서만 가능하다(이미 내렸거나 교환된 티켓은 IllegalStateException). */
    public void markExchanged() {
        if (status != TicketStatus.ACTIVE) {
            throw new IllegalStateException("ACTIVE 티켓만 교환 완료 처리할 수 있습니다: " + status);
        }
        this.status = TicketStatus.EXCHANGED;
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + "는 1~" + maxLength + "자여야 합니다.");
        }
        return value;
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "는 필수입니다.");
        }
    }
}
