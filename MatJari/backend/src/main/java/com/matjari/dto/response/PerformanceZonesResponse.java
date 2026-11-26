package com.matjari.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** GET /api/performances/{id}/zones — 구역 목록(sortOrder 순), 좌석표 가로/세로 비율, 원본 좌석표 이미지 위치(없으면 둘 다 null). */
public record PerformanceZonesResponse(BigDecimal seatmapAspect, SeatmapImageResponse seatmapImage,
                                       List<PerformanceDetailResponse.Zone> zones) {}
