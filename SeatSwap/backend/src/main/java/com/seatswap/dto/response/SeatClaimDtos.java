package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.seatswap.domain.PerformanceSession;
import com.seatswap.domain.SeatClaim;

import java.time.LocalDateTime;

/** 좌석 소유 인증 요청 응답 DTO (JSON 계약은 설계 admin-spec B). 이미지 경로·키는 노출하지 않는다. */
public final class SeatClaimDtos {

    private SeatClaimDtos() {}

    /** 사용자용. zone/row/col 은 입력한 표시용 원문(공백 정리). */
    public record SeatClaimResponse(
            Long id,
            String status,
            Long performanceId,
            String performanceTitle,
            Long sessionId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE)
            LocalDateTime sessionStartsAt,
            String zone,
            String row,
            String col,
            String memo,
            boolean imageAvailable,
            String reviewNote,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime createdAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime reviewedAt
    ) {
        public static SeatClaimResponse from(SeatClaim c) {
            PerformanceSession s = c.getPerformanceSession();
            return new SeatClaimResponse(c.getId(), c.getStatus().name(),
                    s.getPerformance().getId(), s.getPerformance().getTitle(), s.getId(), s.getStartsAt(),
                    c.getZoneLabel(), c.getRowLabel(), c.getColLabel(), c.getMemo(),
                    c.isPending() && !c.isImageDeleted(), c.getReviewNote(), c.getCreatedAt(), c.getReviewedAt());
        }
    }

    /** 지금 그 좌석을 가진 가장 최근 티켓 요약 (관리자 전용). ticketStatus: ACTIVE / RESERVED_LOCKED / EXCHANGED / INACTIVE. */
    public record Holder(String ticketStatus, String holderNickname) {}

    /** 관리자용 = SeatClaimResponse 필드 + 요청자·처리자·holder. */
    public record AdminSeatClaimResponse(
            Long id,
            String status,
            Long performanceId,
            String performanceTitle,
            Long sessionId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE)
            LocalDateTime sessionStartsAt,
            String zone,
            String row,
            String col,
            String memo,
            boolean imageAvailable,
            String reviewNote,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime createdAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime reviewedAt,
            Long claimantId,
            String claimantNickname,
            String reviewerNickname,
            Holder holder
    ) {
        public static AdminSeatClaimResponse of(SeatClaim c, String claimantNickname, String reviewerNickname, Holder holder) {
            SeatClaimResponse b = SeatClaimResponse.from(c);
            return new AdminSeatClaimResponse(b.id(), b.status(), b.performanceId(), b.performanceTitle(), b.sessionId(),
                    b.sessionStartsAt(), b.zone(), b.row(), b.col(), b.memo(), b.imageAvailable(), b.reviewNote(),
                    b.createdAt(), b.reviewedAt(), c.getClaimantId(), claimantNickname, reviewerNickname, holder);
        }
    }

    /** 거절 요청 본문. note 는 선택(300자 이하는 서비스가 검사). */
    public record ReviewNoteRequest(String note) {}
}
