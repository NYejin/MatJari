package com.matjari.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/** 공연 수정 제안 API의 요청·응답 (JSON 계약은 프론트와 합의된 그대로). 시각은 오프셋 없는 KST 초 단위 문자열. */
public final class PerformanceEditProposalDtos {

    private PerformanceEditProposalDtos() {}

    /**
     * POST /api/performances/{id}/edit-proposals. 검증(최소 하나, 길이, 회차 규칙)은 서비스가 {필드: 메시지} 400 으로 한다.
     * sessionChanges 는 선택(없거나 비어 있으면 회차는 그대로).
     */
    public record CreateRequest(String newTitle, String newVenueName, String reason,
                                List<SessionChangeRequest> sessionChanges) {
        public CreateRequest(String newTitle, String newVenueName, String reason) {
            this(newTitle, newVenueName, reason, null);
        }
    }

    /** 회차 변경 요청 하나. type = ADD / CHANGE / REMOVE (대소문자 무시). startsAt 은 오프셋 없는 로컬 시각(KST 벽시계). */
    public record SessionChangeRequest(String type, Long sessionId, LocalDateTime startsAt) {}

    /**
     * 회차 변경 응답. startsAt = 새 시각(REMOVE 는 null), currentStartsAt = 바꾸거나 지우는 회차의 기존 시각
     * (ADD 는 null, 승인된 제안은 승인 시점의 기존 시각, 그 밖에는 조회 시점의 현재 값이며 회차가 사라졌으면 null).
     */
    public record SessionChangeView(
            String type,
            Long sessionId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime startsAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
            LocalDateTime currentStartsAt) {}

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
            LocalDateTime reviewedAt,
            List<SessionChangeView> sessionChanges) {}

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
            String reviewerNickname,
            List<SessionChangeView> sessionChanges) {}
}
