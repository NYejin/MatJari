package com.matjari.domain;

/** 공연 단위 좌석 키 (구역, 열, 번) — 모두 SeatKeyNormalizer 정규화 키다. 회차는 포함하지 않는다. */
public record SeatKey(String zoneKey, String rowKey, String colKey) {
}
