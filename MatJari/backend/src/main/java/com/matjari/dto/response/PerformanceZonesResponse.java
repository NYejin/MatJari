package com.matjari.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** GET /api/performances/{id}/zones — 구역 목록(sortOrder 순)과 좌석표 가로/세로 비율(없으면 null). */
public record PerformanceZonesResponse(BigDecimal seatmapAspect, List<PerformanceDetailResponse.Zone> zones) {}
