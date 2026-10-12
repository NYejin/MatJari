package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 신고·제재·차단 현황 DTO 모음 (프론트와 합의한 JSON 계약, 변경 금지). 시각은 오프셋 없는 KST 초 단위 문자열.
 */
public final class ModerationDtos {

    private ModerationDtos() {}

    // ------------------------------------------------------------------ 사용자(신고자)

    /** POST /api/exchange/matches/{matchId}/report 본문. 검증은 서비스가 한다(400 {reason|detail: 메시지}). */
    public record ReportCreateRequest(String reason, String detail) {}

    public record ReportCreatedResponse(
            Long id,
            String status,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime createdAt
    ) {}

    /** 내 신고 내역: 처리 상태만 보이고 처리 메모·제재 내용은 숨긴다. */
    public record MyReportResponse(
            Long id,
            Long matchId,
            String reportedNickname,
            String reason,
            String status,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime createdAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime handledAt
    ) {}

    // ------------------------------------------------------------------ 관리자

    public record UserRef(Long id, String nickname) {}

    public record ActiveSanctionRef(
            Long id,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime until
    ) {}

    public record ReportedUser(Long id, String nickname, long totalReports, long openReports, ActiveSanctionRef activeSanction) {}

    public record AdminReport(
            Long id,
            Long matchId,
            String reason,
            String detail,
            String status,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime createdAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime handledAt,
            String handlerNickname,
            String handlerNote,
            UserRef reporter,
            ReportedUser reported
    ) {}

    public record AdminReportMessage(
            Long id,
            Long senderId,
            String senderNickname,
            String content,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime createdAt
    ) {}

    /** 최신부터 위로(id 내림차순). hasMore = 더 오래된 메시지가 있음. */
    public record AdminReportMessagePage(List<AdminReportMessage> messages, boolean hasMore) {}

    /** 처리(조치) 본문: sanction 이 있으면 같은 트랜잭션에서 이용 정지를 건다. days 는 7|30|null(무기한). */
    public record ResolveRequest(String note, SanctionInput sanction) {}

    public record SanctionInput(Integer days, String note) {}

    public record DismissRequest(String note) {}

    public record AdminSanctionResponse(
            Long id,
            Long userId,
            String nickname,
            String type,
            String note,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime startsAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime endsAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime revokedAt,
            boolean active,
            String createdByNickname,
            Long reportId
    ) {}

    public record AdminBlockResponse(
            Long id,
            UserRef blocker,
            UserRef blocked,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime createdAt
    ) {}
}
