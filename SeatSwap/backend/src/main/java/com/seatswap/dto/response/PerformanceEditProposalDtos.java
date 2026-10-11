package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/** 공연 수정 제안 API의 요청·응답 (JSON 계약은 프론트와 합의된 그대로). 시각은 오프셋 없는 KST 초 단위 문자열. */
public final class PerformanceEditProposalDtos {

    private PerformanceEditProposalDtos() {}

    /** POST /api/performances/{id}/edit-proposals. 검증(최소 하나, 길이)은 서비스가 {필드: 메시지} 400 으로 한다. */
    public record CreateRequest(String newTitle, String newVenueName, String reason) {}

    /** POST /api/admin/performance-edit-proposals/{id}/reject. 본문이 없거나 note 가 null 이어도 된다(최대 300자). */
    public record RejectRequest(String note) {}

    /** 사용자용. performanceTitle·currentTitle·currentVenueName 은 조회 시점의 공연 값이다. */
    public record EditProposal(
            Long id,
            Long performanceId,
            String performanceTitle,
            String currentTitle,
            String currentVenueName,
            String newTitle,
            String newVenueName,
            String reason,
            String status,
            String reviewNote,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime createdAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime reviewedAt) {}

    /** 관리자용 = EditProposal 필드 + 제안자·이전 값 스냅샷·처리자. */
    public record AdminEditProposal(
            Long id,
            Long performanceId,
            String performanceTitle,
            String currentTitle,
            String currentVenueName,
            String newTitle,
            String newVenueName,
            String reason,
            String status,
            String reviewNote,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime createdAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime reviewedAt,
            Long proposerId,
            String proposerNickname,
            String oldTitle,
            String oldVenueName,
            String reviewerNickname) {}
}
