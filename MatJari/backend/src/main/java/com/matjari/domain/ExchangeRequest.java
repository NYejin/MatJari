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
 * 티켓의 교환 희망 1건 (티켓당 미삭제 요청 1개, uk_exchange_request_live_ticket = UNIQUE(live_flag, ticket_id)).
 * 추가금은 요청이 아니라 희망 범위(ExchangeWantRange)가 가진다(V6). 삭제는 소프트 삭제다: status DELETED + deleted_at(V7),
 * 삭제된 요청의 범위·좌석·회차 행은 서비스가 지운다. live_flag 는 DB 생성 컬럼이라 매핑하지 않는다.
 */
@Entity
@Table(name = "exchange_request")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class ExchangeRequest {

    public static final String UNIQUE_TICKET = "uk_exchange_request_live_ticket";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private Ticket ticket;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ExchangeRequestStatus status = ExchangeRequestStatus.OPEN;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static ExchangeRequest create(Ticket ticket) {
        if (ticket == null) {
            throw new IllegalArgumentException("ticket은 필수입니다.");
        }
        ExchangeRequest request = new ExchangeRequest();
        request.ticket = ticket;
        request.status = ExchangeRequestStatus.OPEN;
        return request;
    }

    /** 소프트 삭제. 이미 삭제됐다면 아무 일도 하지 않는다(멱등). */
    public void markDeleted(LocalDateTime now) {
        if (isDeleted()) {
            return;
        }
        this.status = ExchangeRequestStatus.DELETED;
        this.deletedAt = now;
        this.updatedAt = now;
    }

    public boolean isDeleted() {
        return status == ExchangeRequestStatus.DELETED;
    }

    /** 희망 범위·회차만 바뀌어도 updated_at 이 갱신되도록 명시적으로 시각을 찍는다(Auditing은 변경 감지된 경우에만 동작). */
    public void touch(LocalDateTime now) {
        this.updatedAt = now;
    }

    public boolean isOpen() {
        return status == ExchangeRequestStatus.OPEN;
    }
}
