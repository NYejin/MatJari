package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 교환 이력 (V9, append-only). 매칭 1건이 완료되면 사용자별 1행씩 2행이 같은 트랜잭션에서 생긴다.
 * '(기존 자리) -> (바꾼 자리)' 스냅샷(공연 제목·공연장·회차 시각·구역·열·번 표시 원문)과 old_ticket_id / new_ticket_id 를 가진다.
 * 만든 뒤에는 수정·삭제하지 않으므로 setter 가 없다. 연관 엔티티 대신 id 만 가진다.
 */
@Entity
@Table(name = "exchange_history")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class ExchangeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "match_id", nullable = false, updatable = false)
    private Long matchId;

    /** 이력 주인. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "old_ticket_id", nullable = false, updatable = false)
    private Long oldTicketId;

    @Column(name = "new_ticket_id", nullable = false, updatable = false)
    private Long newTicketId;

    @Column(name = "performance_id", nullable = false, updatable = false)
    private Long performanceId;

    @Column(name = "performance_title", nullable = false, updatable = false, length = 200)
    private String performanceTitle;

    @Column(name = "venue_name", nullable = false, updatable = false, length = 100)
    private String venueName;

    @Column(name = "old_starts_at", nullable = false, updatable = false)
    private LocalDateTime oldStartsAt;

    @Column(name = "new_starts_at", nullable = false, updatable = false)
    private LocalDateTime newStartsAt;

    @Column(name = "old_zone_label", nullable = false, updatable = false, length = 50)
    private String oldZoneLabel;

    @Column(name = "old_row_label", nullable = false, updatable = false, length = 20)
    private String oldRowLabel;

    @Column(name = "old_col_label", nullable = false, updatable = false, length = 20)
    private String oldColLabel;

    @Column(name = "new_zone_label", nullable = false, updatable = false, length = 50)
    private String newZoneLabel;

    @Column(name = "new_row_label", nullable = false, updatable = false, length = 20)
    private String newRowLabel;

    @Column(name = "new_col_label", nullable = false, updatable = false, length = 20)
    private String newColLabel;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 한 사용자의 이력 1행. oldTicket = 교환 전 내 티켓(EXCHANGED), newTicket = 새로 INSERT 된 내 티켓(id 가 있어야 한다).
     * 두 티켓은 회차·공연을 읽을 수 있는 상태(fetch 됨)여야 한다. 공연 정보는 교환 전 티켓의 공연에서 가져온다(교환은 공연 단위).
     */
    public static ExchangeHistory of(Long matchId, Ticket oldTicket, Ticket newTicket) {
        if (matchId == null || oldTicket.getId() == null || newTicket.getId() == null) {
            throw new IllegalArgumentException("matchId 와 두 티켓 id 는 필수입니다.");
        }
        Performance performance = oldTicket.getPerformanceSession().getPerformance();
        ExchangeHistory h = new ExchangeHistory();
        h.matchId = matchId;
        h.userId = oldTicket.getUser().getId();
        h.oldTicketId = oldTicket.getId();
        h.newTicketId = newTicket.getId();
        h.performanceId = performance.getId();
        h.performanceTitle = performance.getTitle();
        h.venueName = performance.getVenueName();
        h.oldStartsAt = oldTicket.getPerformanceSession().getStartsAt();
        h.newStartsAt = newTicket.getPerformanceSession().getStartsAt();
        h.oldZoneLabel = oldTicket.getZoneLabel();
        h.oldRowLabel = oldTicket.getRowLabel();
        h.oldColLabel = oldTicket.getColLabel();
        h.newZoneLabel = newTicket.getZoneLabel();
        h.newRowLabel = newTicket.getRowLabel();
        h.newColLabel = newTicket.getColLabel();
        return h;
    }
}
