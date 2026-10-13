package com.matjari.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/** 공연 목록 항목. nextSessionStartsAt: 지금 이후 가장 가까운 회차(없으면 null). */
public record PerformanceSummaryResponse(
        Long id,
        String title,
        String venueName,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE)
        LocalDateTime nextSessionStartsAt,
        long sessionCount
) {}
