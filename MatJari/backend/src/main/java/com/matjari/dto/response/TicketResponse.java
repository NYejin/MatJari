package com.matjari.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.Ticket;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 티켓. zone/row/col은 입력한 표시용 원문(공백 정리)이다. startsAt은 KST 현지 시각 "yyyy-MM-ddTHH:mm".
 * 보유자 정보는 담지 않는다 (본인 티켓만 반환하므로 불필요). images 는 이 티켓에 붙인 '내 자리' 사진 참조(V33, 최대 3장, 목록 조회에서만 채운다).
 */
public record TicketResponse(
        Long id,
        Long performanceId,
        String performanceTitle,
        String venueName,
        Long sessionId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE)
        LocalDateTime startsAt,
        String zone,
        String row,
        String col,
        String status,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt,
        List<ListingImageRef> images
) {
    public static TicketResponse from(Ticket ticket) {
        PerformanceSession session = ticket.getPerformanceSession();
        return new TicketResponse(
                ticket.getId(),
                session.getPerformance().getId(),
                session.getPerformance().getTitle(),
                session.getPerformance().getVenueName(),
                session.getId(),
                session.getStartsAt(),
                ticket.getZoneLabel(),
                ticket.getRowLabel(),
                ticket.getColLabel(),
                ticket.getStatus().name(),
                ticket.getCreatedAt(),
                List.of());
    }

    /** 사진 참조(V33)를 채운 사본. */
    public TicketResponse withImages(List<ListingImageRef> images) {
        return new TicketResponse(id, performanceId, performanceTitle, venueName, sessionId, startsAt, zone, row, col,
                status, createdAt, images == null ? List.of() : images);
    }
}
