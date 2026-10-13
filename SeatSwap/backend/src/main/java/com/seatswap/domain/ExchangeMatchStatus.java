package com.seatswap.domain;

/**
 * CHATTING = 후보를 골라 채팅을 시작한 상태(예약 대기), RESERVED = 예약 제안을 상대가 수락해 두 티켓이 잠긴 상태(예약 취소하면 CHATTING 복귀),
 * COMPLETED = 양쪽 '교환 수락'으로 완료(화면 라벨 '교환 완료'), CANCELED = 취소·거절(사용자 또는 시스템). 열린 매칭 = CHATTING 또는 RESERVED.
 */
public enum ExchangeMatchStatus {
    CHATTING,
    RESERVED,
    COMPLETED,
    CANCELED;

    public boolean isOpen() {
        return this == CHATTING || this == RESERVED;
    }
}
