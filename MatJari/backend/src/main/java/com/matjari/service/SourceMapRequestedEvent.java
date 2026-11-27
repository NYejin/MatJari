package com.matjari.service;

/**
 * 추가 예매처 링크가 새로 달렸다는 도메인 이벤트(V34). 링크를 INSERT 하는 트랜잭션 안에서 발행하고(map_status=PENDING 도 같은 트랜잭션에서 기록)
 * {@link SourceMapService} 가 커밋 뒤에만 처리한다(롤백이면 버려진다).
 */
public record SourceMapRequestedEvent(Long performanceId, Long sourceId) {
}
