package com.matjari.domain;

/** OPEN = 매칭 대상, CLOSED = 티켓을 내렸거나 종료된 요청, DELETED = 사용자가 삭제한 요청(소프트 삭제, 후보·수정·목록에서 제외). */
public enum ExchangeRequestStatus {
    OPEN,
    CLOSED,
    DELETED
}
