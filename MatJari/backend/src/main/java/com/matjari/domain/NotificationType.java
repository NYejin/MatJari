package com.matjari.domain;

/** 알림 종류 6종 (V10 + V15) (V10 ck_notification_type 과 같은 값). */
public enum NotificationType {
    /** 새 제안: 제안받은 쪽(b)에게. */
    PROPOSAL_RECEIVED,
    /** 상대가 예약 제안을 수락함(예약 확정): 제안자에게. */
    MATCH_RESERVED,
    /** 상대가 예약 취소: 취소하지 않은 쪽에게. */
    RESERVATION_CANCELED,
    /** 상대가 예약을 제안함: 제안받은 쪽에게(V15). */
    RESERVATION_PROPOSED,
    /** 내 예약 제안이 거절됨: 제안자에게(V15). */
    RESERVATION_REJECTED,
    /** 새 채팅 메시지: 수신자에게. (수신자, 매칭)당 행 1개를 재사용한다(V15, dedup_key = CHAT_MESSAGE:{matchId}:{recipientId}). */
    CHAT_MESSAGE,
    /** 내 교환 요청에 새 후보가 생김: 요청 소유자에게(V29). (수신자, 요청)당 행 1개를 재사용한다(dedup_key = NEW_CANDIDATE:{requestId}:{recipientId}, match_id 없음). */
    NEW_CANDIDATE
}
