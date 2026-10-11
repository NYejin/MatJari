package com.seatswap.domain;

/** 알림 종류 3종 (V10 ck_notification_type 과 같은 값). */
public enum NotificationType {
    /** 새 제안: 제안받은 쪽(b)에게. */
    PROPOSAL_RECEIVED,
    /** 상대가 예약: 예약하지 않은 쪽에게. */
    MATCH_RESERVED,
    /** 상대가 예약 취소: 취소하지 않은 쪽에게. */
    RESERVATION_CANCELED
}
