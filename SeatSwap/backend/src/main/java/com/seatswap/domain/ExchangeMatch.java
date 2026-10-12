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

/**
 * 후보를 골라 시작한 매칭(채팅방) 1건 (설계 1.7). a = 후보 목록에서 상대를 고른 쪽(제안자), b = 고른 상대.
 * 연관 엔티티 대신 id 만 가진다(요청·티켓·사용자는 서비스가 잠그고 읽는다).
 * request_low_id / request_high_id / open_flag 는 DB 생성 컬럼이라 매핑하지 않는다
 * (uk_exchange_match_open_pair 가 '같은 요청 쌍의 열린 매칭 1개'를 보장한다).
 *
 * 상태: CHATTING(채팅, 예약 전) -> RESERVED(한 명이 예약, 두 티켓 잠금) -> COMPLETED(양쪽 교환 수락), 예약한 사람이든 상대든 누구나
 * RESERVED -> CHATTING 으로 되돌릴 수 있고(unreserve), CHATTING 에서만 취소·거절(CANCELED)된다.
 * 상태 전이는 {@link #isAllowed(ExchangeMatchStatus, ExchangeMatchAction)} 한 곳에서 정의한다.
 * COMPLETED 시점에는 한 트랜잭션에서 기존 두 티켓을 EXCHANGED 로 바꾸고 각자 새 자리 티켓을 만든다(서비스 Javadoc 참고).
 */
@Entity
@Table(name = "exchange_match")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor
public class ExchangeMatch {

    public static final String UNIQUE_OPEN_PAIR = "uk_exchange_match_open_pair";

    /** 매칭에서 호출자가 서 있는 쪽. */
    public enum Side { A, B }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_a_id", nullable = false, updatable = false)
    private Long requestAId;

    @Column(name = "request_b_id", nullable = false, updatable = false)
    private Long requestBId;

    @Column(name = "ticket_a_id", nullable = false, updatable = false)
    private Long ticketAId;

    @Column(name = "ticket_b_id", nullable = false, updatable = false)
    private Long ticketBId;

    @Column(name = "user_a_id", nullable = false, updatable = false)
    private Long userAId;

    @Column(name = "user_b_id", nullable = false, updatable = false)
    private Long userBId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ExchangeMatchStatus status = ExchangeMatchStatus.CHATTING;

    /** 매칭 시점의 추가금 스냅샷(V6, 표시용). a = a측 희망 범위 중 b 티켓 좌석을 포함한 범위, b = b측이 a 좌석을 포함한 범위의 값. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "a_extra_type", nullable = false, updatable = false, length = 10)
    private ExtraType aExtraType;

    @Column(name = "a_extra_amount", updatable = false)
    private Integer aExtraAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "b_extra_type", nullable = false, updatable = false, length = 10)
    private ExtraType bExtraType;

    @Column(name = "b_extra_amount", updatable = false)
    private Integer bExtraAmount;

    // a_reserved_at / b_reserved_at 는 V8 부터 미사용(레거시) 컬럼이라 매핑하지 않는다.

    /** 예약을 제안한 사람(a 또는 b 측 사용자; 수락 시 제안자가 그대로 들어간다). RESERVED 일 때만 값이 있다(ck_exchange_match_reserved). */
    @Column(name = "reserved_by_id")
    private Long reservedById;

    @Column(name = "reserved_at")
    private LocalDateTime reservedAt;

    /**
     * 예약 제안자(a 또는 b 측 사용자, V15). CHATTING 에서만 값이 있고(ck_exchange_match_reserve_proposal_status) 두 컬럼은 함께 NULL/NOT NULL 이다.
     * CHATTING 을 벗어나는 모든 UPDATE(수락 -> RESERVED, 취소 -> CANCELED, 벌크 JPQL 포함)는 같은 문장에서 이 둘을 NULL 로 만든다.
     */
    @Column(name = "reserve_proposed_by_id")
    private Long reserveProposedById;

    @Column(name = "reserve_proposed_at")
    private LocalDateTime reserveProposedAt;

    @Column(name = "a_completed_at")
    private LocalDateTime aCompletedAt;

    @Column(name = "b_completed_at")
    private LocalDateTime bCompletedAt;

    /** CANCELED 일 때만. NULL 이면 시스템 취소(티켓 내림 등). */
    @Column(name = "canceled_by_id")
    private Long canceledById;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /** 새 매칭(CHATTING). a = 제안자 쪽. aExtra/bExtra 는 후보 판정에서 읽은 양쪽 적용 추가금(스냅샷)이다. */
    public static ExchangeMatch propose(Long requestAId, Long requestBId, Long ticketAId, Long ticketBId,
                                        Long userAId, Long userBId, WantExtra aExtra, WantExtra bExtra) {
        ExchangeMatch m = new ExchangeMatch();
        m.requestAId = requestAId;
        m.requestBId = requestBId;
        m.ticketAId = ticketAId;
        m.ticketBId = ticketBId;
        m.userAId = userAId;
        m.userBId = userBId;
        m.aExtraType = aExtra.type();
        m.aExtraAmount = aExtra.amount();
        m.bExtraType = bExtra.type();
        m.bExtraAmount = bExtra.amount();
        m.status = ExchangeMatchStatus.CHATTING;
        return m;
    }

    /**
     * 허용 상태 전이 표(단일 출처; 서비스가 reserve/decline/withdraw 도 이 표로 판정한다). PROPOSE 의 상태는 '같은 요청 쌍의 가장 최근 매칭' 상태이며
     * 열린 매칭(CHATTING·RESERVED)이 있으면 새 매칭을 만들 수 없다(취소·완료된 뒤에는 다시 가능). 멱등 처리는 서비스가 상태를 보고 먼저 걸러내므로
     * 표에서는 '멱등'으로 적는다.
     * <pre>
     *            PROPOSE  RESERVE(제안)         ACCEPT(수락)           DECLINE/WITHDRAW       UNRESERVE  REJECT(b측)  CANCEL  COMPLETE
     * CHATTING   불허     허용(내 제안은 멱등)  허용(제안 있을 때)     허용(제안 없으면 멱등)  멱등 200   허용         허용    불허(예약 전)
     * RESERVED   불허     불허                  멱등 200(제안자·수락자) 멱등 200(제안 없음)    허용       불허         불허    허용
     * COMPLETED  허용     불허                  불허                   불허                   불허       불허         불허    불허
     * CANCELED   허용     불허                  불허                   불허                   불허       불허         불허    불허
     * </pre>
     * {@code isAllowed} 반환값: RESERVE·RESERVE_ACCEPT·REJECT·CANCEL 은 CHATTING 만 true(RESERVED 에서 accept 의 멱등 200 은 서비스가 이 검사 앞에서 거른다),
     * UNRESERVE·DECLINE·WITHDRAW 는 열린 상태에서 true(RESERVED 의 DECLINE/WITHDRAW 는 제안이 없어 서비스가 멱등 200 으로 처리). RESERVED 에서 reject·cancel 은 막고
     * 먼저 예약을 취소해야 한다(409 MATCH_STATE_CONFLICT). 제안 컬럼 변경도 {@code updated_at} 을 갱신하므로 내 매칭 목록 정렬이 바뀐다(의도).
     */
    public static boolean isAllowed(ExchangeMatchStatus status, ExchangeMatchAction action) {
        return switch (action) {
            case PROPOSE -> !status.isOpen();
            case RESERVE, RESERVE_ACCEPT -> status == ExchangeMatchStatus.CHATTING;
            case UNRESERVE -> status.isOpen();
            case RESERVE_DECLINE, RESERVE_WITHDRAW -> status.isOpen();
            case REJECT, CANCEL -> status == ExchangeMatchStatus.CHATTING;
            case COMPLETE -> status == ExchangeMatchStatus.RESERVED;
        };
    }

    public Side sideOf(Long userId) {
        if (userId == null) {
            return null;
        }
        if (userId.equals(userAId)) {
            return Side.A;
        }
        return userId.equals(userBId) ? Side.B : null;
    }

    /** 예약 제안: CHATTING 이고 제안이 없을 때만. 상태는 그대로이고 제안 두 컬럼만 채운다. */
    public void proposeReservation(Long userId, LocalDateTime now) {
        if (status != ExchangeMatchStatus.CHATTING) {
            throw new IllegalStateException("CHATTING 매칭만 예약을 제안할 수 있습니다: " + status);
        }
        if (sideOf(userId) == null) {
            throw new IllegalArgumentException("매칭 참여자만 예약을 제안할 수 있습니다.");
        }
        if (reserveProposedById != null) {
            throw new IllegalStateException("이미 예약 제안이 있습니다.");
        }
        reserveProposedById = userId;
        reserveProposedAt = now;
    }

    /** 제안 지우기(거절·철회). 상태는 바꾸지 않는다. */
    public void clearReservationProposal() {
        reserveProposedById = null;
        reserveProposedAt = null;
    }

    public boolean hasReservationProposal() {
        return reserveProposedById != null;
    }

    /**
     * 예약 수락: CHATTING(+제안 있음) -> RESERVED. reserved_by_id = 제안자, reserved_at = 수락 시각이고 제안 컬럼은 같은 UPDATE 에서 비운다
     * (ck_exchange_match_reserve_proposal_status). 두 티켓의 잠금 INSERT 는 서비스가 같은 트랜잭션에서 한다.
     */
    public void acceptReservation(LocalDateTime now) {
        if (status != ExchangeMatchStatus.CHATTING) {
            throw new IllegalStateException("CHATTING 매칭만 예약을 수락할 수 있습니다: " + status);
        }
        if (reserveProposedById == null) {
            throw new IllegalStateException("수락할 예약 제안이 없습니다.");
        }
        status = ExchangeMatchStatus.RESERVED;
        reservedById = reserveProposedById;
        reservedAt = now;
        clearReservationProposal();
    }

    /** 예약 취소: RESERVED -> CHATTING. 예약 표시와 교환 수락(a/b_completed_at) 표시를 한 번에 비운다(ck_exchange_match_reserved·completed). */
    public void unreserve() {
        if (status != ExchangeMatchStatus.RESERVED) {
            throw new IllegalStateException("RESERVED 매칭만 예약을 취소할 수 있습니다: " + status);
        }
        status = ExchangeMatchStatus.CHATTING;
        reservedById = null;
        reservedAt = null;
        clearReservationProposal();
        aCompletedAt = null;
        bCompletedAt = null;
    }

    /** 취소·거절. canceledById 가 null 이면 시스템 취소. CHATTING 만 취소할 수 있다(RESERVED 는 먼저 unreserve). */
    public void cancel(Long canceledById, LocalDateTime now) {
        if (status != ExchangeMatchStatus.CHATTING) {
            throw new IllegalStateException("CHATTING 매칭만 취소할 수 있습니다: " + status);
        }
        status = ExchangeMatchStatus.CANCELED;
        clearReservationProposal();   // 제안이 걸려 있어도 같은 UPDATE 에서 비운다(CHECK)
        this.canceledById = canceledById;
        canceledAt = now;
    }

    /**
     * 교환 수락 표시: 호출자 쪽 a/b_completed_at 을 기록한다(RESERVED 에서만). 이미 수락했으면 아무것도 바꾸지 않는다(멱등).
     * 상태는 RESERVED 그대로다(ck_exchange_match_completed 는 COMPLETED 에서만 둘 다 NOT NULL 을 요구한다).
     */
    public void markAccepted(Side side, LocalDateTime now) {
        if (status != ExchangeMatchStatus.RESERVED) {
            throw new IllegalStateException("RESERVED 매칭만 교환 수락할 수 있습니다: " + status);
        }
        if (side == null) {
            throw new IllegalArgumentException("매칭 참여자만 교환 수락할 수 있습니다.");
        }
        if (hasAccepted(side)) {
            return;
        }
        if (side == Side.A) {
            aCompletedAt = now;
        } else {
            bCompletedAt = now;
        }
    }

    /**
     * 양쪽이 모두 수락한 순간 RESERVED -> COMPLETED. 같은 문장에서 reserved_by_id·reserved_at 을 NULL 로 만든다(ck_exchange_match_reserved).
     * 매칭 행의 티켓 id 는 교환 전 티켓 그대로다. 티켓·이력·요청·잠금 처리는 서비스가 같은 트랜잭션에서 한다.
     */
    public void complete(LocalDateTime now) {
        if (status != ExchangeMatchStatus.RESERVED) {
            throw new IllegalStateException("RESERVED 매칭만 완료할 수 있습니다: " + status);
        }
        if (aCompletedAt == null || bCompletedAt == null) {
            throw new IllegalStateException("양쪽이 모두 교환 수락해야 완료할 수 있습니다.");
        }
        status = ExchangeMatchStatus.COMPLETED;
        reservedById = null;
        reservedAt = null;
    }

    /** 호출자 쪽 교환 수락 표시(a/b_completed_at)가 있는가. */
    public boolean hasAccepted(Side side) {
        return (side == Side.A ? aCompletedAt : bCompletedAt) != null;
    }

    public boolean isOpen() {
        return status.isOpen();
    }
}
