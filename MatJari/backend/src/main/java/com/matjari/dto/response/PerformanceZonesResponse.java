package com.matjari.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET /api/performances/{id}/zones — 대표 지도의 구역 목록(sortOrder 순), 좌석표 가로/세로 비율, 원본 좌석표 이미지 위치(없으면 둘 다 null).
 * maps(V34): 예매처별 지도 목록. 대표 지도(구역이 있을 때만) 먼저, 그다음 추가 링크 순. 기존 필드는 대표 지도 그대로다.
 */
public record PerformanceZonesResponse(BigDecimal seatmapAspect, SeatmapImageResponse seatmapImage,
                                       List<PerformanceDetailResponse.Zone> zones, List<MapItem> maps) {

    public PerformanceZonesResponse(BigDecimal seatmapAspect, SeatmapImageResponse seatmapImage,
                                    List<PerformanceDetailResponse.Zone> zones) {
        this(seatmapAspect, seatmapImage, zones, List.of());
    }

    /**
     * 예매처 지도 하나. sourceId 는 대표 지도면 null. status 는 대표 지도는 READY, 추가 링크는 READY 또는 PENDING(읽는 중, zones 빈 배열).
     * 지도마다 좌표계(원본 이미지)가 달라 합치지 않는다.
     */
    public record MapItem(Long sourceId, String site, String siteName, boolean primary, String status, BigDecimal aspect,
                          SeatmapImageResponse seatmapImage, List<PerformanceDetailResponse.Zone> zones) {}
}
