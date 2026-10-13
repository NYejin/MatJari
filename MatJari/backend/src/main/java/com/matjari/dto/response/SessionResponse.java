package com.matjari.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.matjari.domain.PerformanceSession;

import java.time.LocalDateTime;

/** 회차. startsAt: 타임존 없는 KST 현지 시각 "yyyy-MM-ddTHH:mm" (초 없음). */
public record SessionResponse(
        Long id,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE)
        LocalDateTime startsAt
) {
    public static SessionResponse from(PerformanceSession session) {
        return new SessionResponse(session.getId(), session.getStartsAt());
    }
}
