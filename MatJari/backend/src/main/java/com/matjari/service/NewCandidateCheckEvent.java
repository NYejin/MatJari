package com.matjari.service;

/**
 * 교환 요청이 생성되었거나 희망 조건이 수정되었다는 도메인 이벤트(V29). 요청 서비스의 트랜잭션 안에서 발행하고
 * {@link NewCandidateNotifier} 가 커밋 뒤에만 처리한다(롤백이면 버려진다).
 */
public record NewCandidateCheckEvent(Long requestId) {
}
