package com.matjari.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 공연 회차 (날짜·시간). Performance 1:N PerformanceSession, PerformanceSession 1:N Ticket.
 * 좌석 교환은 같은 공연이면 다른 회차끼리도 가능하다 (같은 회차로 제한하지 않는다, 2026-10-07 결정).
 *
 * 중복 방지: (performance_id, starts_at) unique. startsAt은 분 단위로 잘라 저장한다
 * (19:00과 19:00:30이 다른 회차로 등록되는 것 방지).
 * startsAt은 한국 공연 기준 현지 시각(KST 벽시계 시각)으로 저장한다.
 *
 * 수정/삭제 정책: 공연 등록 시 함께 만들어지며, 등록 후 추가·수정·삭제는 없다(추후 관리자 수정 제안으로만).
 */
@Entity
@Table(
        name = "performance_session",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_performance_session_performance_starts_at",
                columnNames = {"performance_id", "starts_at"}
        ),
        indexes = @Index(name = "idx_performance_session_starts_at", columnList = "starts_at")
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class PerformanceSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_id", nullable = false, updatable = false)
    private Performance performance;

    /** 회차 시작 일시 (분 단위). */
    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    /** 저장 시 JPA Auditing이 KST(Clock) 기준으로 채운다 — JpaAuditingConfig. */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public static PerformanceSession create(Performance performance, LocalDateTime startsAt) {
        if (performance == null) {
            throw new IllegalArgumentException("performance는 필수입니다.");
        }
        PerformanceSession session = new PerformanceSession();
        session.performance = performance;
        session.startsAt = truncate(startsAt);
        return session;
    }

    /** 티켓 등록·매칭 허용 마감: 회차 당일 끝(다음날 0시, KST). 이 시각 이후에는 등록할 수 없다. */
    public LocalDateTime registrationDeadline() {
        return startsAt.toLocalDate().plusDays(1).atStartOfDay();
    }

    /** 분 단위 절삭. 서비스의 중복 사전 조회도 이 값으로 해야 unique 제약과 판정이 일치한다. */
    public static LocalDateTime truncate(LocalDateTime startsAt) {
        if (startsAt == null) {
            throw new IllegalArgumentException("startsAt은 필수입니다.");
        }
        return startsAt.truncatedTo(ChronoUnit.MINUTES);
    }
}
