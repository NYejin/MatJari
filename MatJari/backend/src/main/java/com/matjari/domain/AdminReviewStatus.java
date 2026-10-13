package com.matjari.domain;

/**
 * 관리자 처리 대상(공연 수정 제안 performance_edit_proposal, 좌석 소유 인증 요청 seat_claim)의 상태 (V12).
 * PENDING = 처리 대기, APPROVED = 승인, REJECTED = 거절. 처리된 행은 다시 바뀌지 않는다.
 */
public enum AdminReviewStatus {
    PENDING,
    APPROVED,
    REJECTED
}
