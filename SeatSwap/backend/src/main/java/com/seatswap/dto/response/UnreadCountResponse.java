package com.seatswap.dto.response;

import java.util.Map;

/**
 * 안 읽은 알림 개수. byType 은 알림 3종 키를 0이어도 항상 포함한다. 닫힌 매칭(CANCELED/COMPLETED)의 알림도 센다
 * (알림이 사라지는 경로는 사용자의 '읽은 알림 삭제'뿐이다).
 */
public record UnreadCountResponse(long total, Map<String, Long> byType) {}
