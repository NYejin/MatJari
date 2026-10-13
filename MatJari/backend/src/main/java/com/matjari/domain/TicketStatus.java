package com.matjari.domain;

/**
 * 티켓 상태. INACTIVE = 사용자가 내린 티켓(소프트 삭제), EXCHANGED = 교환 완료로 더 이상 내 자리가 아닌 기존 티켓
 * (V9, 새 자리는 별도 새 티켓으로 INSERT 된다). EXCHANGED 는 내릴 수 없고 active_flag 가 NULL 이라 좌석 유일 키에서 빠진다.
 */
public enum TicketStatus {
    ACTIVE,
    INACTIVE,
    EXCHANGED
}
