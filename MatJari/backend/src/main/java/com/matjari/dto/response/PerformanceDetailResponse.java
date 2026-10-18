package com.matjari.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 공연 상세. sessions는 startsAt 오름차순. canEdit = 현재 사용자가 등록자인지
 * (제목 수정, 공연 삭제, 회차 수정·삭제 버튼 노출용 — 서버도 동일하게 검사한다).
 */
public record PerformanceDetailResponse(
        Long id,
        String title,
        String sourceUrl,
        String venueName,
        BigDecimal seatmapAspect,
        List<Zone> zones,
        Registrant registrant,
        boolean canEdit,
        List<SessionResponse> sessions,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt
) {
    public record Registrant(Long id, String nickname) {}

    /** 구역(sortOrder 순). shapePolygons 는 없으면 null. */
    public record Zone(String name, List<List<List<Integer>>> shapePolygons) {}
}
