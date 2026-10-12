package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 알림 1건 (수신자 기준). 알림 행에는 개인정보를 저장하지 않으므로 닉네임·좌석·공연명은 조회 때 매칭에서 조인한 값이다. 상대 이메일 등은 없다.
 * type: PROPOSAL_RECEIVED / RESERVATION_PROPOSED / MATCH_RESERVED(상대가 예약 제안을 수락) / RESERVATION_REJECTED / RESERVATION_CANCELED / CHAT_MESSAGE. role: 이 알림 수신자가 매칭의 SENT(a측) 인지 RECEIVED(b측) 인지.
 * mySeat / counterpartSeat 는 매칭 행의 자리, 즉 항상 교환 전 자리다(ExchangeMatchResponse 와 같은 규칙).
 * matchClosed 는 매칭이 CANCELED/COMPLETED 라는 표시용 플래그일 뿐이며 안 읽은 개수·목록 포함 여부에는 영향이 없다(닫힌 매칭 알림도 그대로 남는다).
 * readAt 은 안 읽음이면 null. 시각은 ExchangeMatchResponse 와 같이 오프셋 없는 KST 문자열("2026-10-06T14:03:21")이다.
 */
public record NotificationResponse(
        Long id,
        String type,
        Long matchId,
        boolean read,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime readAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt,
        String matchStatus,
        boolean matchClosed,
        String role,
        String counterpartNickname,
        String performanceTitle,
        Seat mySeat,
        Seat counterpartSeat
) {
    /** 표시용 구역·열·번 원문. */
    public record Seat(String zone, String row, String col) {}

    /** DELETE /api/notifications/read 응답. */
    public record Deleted(long deleted) {}
}
