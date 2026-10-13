package com.matjari.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.matjari.domain.ExchangeHistory;

import java.time.LocalDateTime;

/**
 * 내 교환 이력 1건: '(기존 자리) -> (바꾼 자리)' 스냅샷. 공연 제목·공연장·회차 시각·구역/열/번은 교환 완료 때의 값이라 이후 공연이 바뀌어도 변하지 않는다.
 * matchId 는 완료된 매칭(채팅 기록 열람 링크용). 상대 정보(닉네임 등)는 담지 않는다. 시각은 오프셋 없는 KST 문자열.
 */
public record ExchangeHistoryResponse(
        Long id,
        Long matchId,
        Long performanceId,
        String performanceTitle,
        String venueName,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime oldStartsAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime newStartsAt,
        Seat oldSeat,
        Seat newSeat,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime completedAt
) {
    public record Seat(String zone, String row, String col) {
    }

    public static ExchangeHistoryResponse from(ExchangeHistory h) {
        return new ExchangeHistoryResponse(h.getId(), h.getMatchId(), h.getPerformanceId(), h.getPerformanceTitle(), h.getVenueName(),
                h.getOldStartsAt(), h.getNewStartsAt(),
                new Seat(h.getOldZoneLabel(), h.getOldRowLabel(), h.getOldColLabel()),
                new Seat(h.getNewZoneLabel(), h.getNewRowLabel(), h.getNewColLabel()),
                h.getCreatedAt());
    }
}
