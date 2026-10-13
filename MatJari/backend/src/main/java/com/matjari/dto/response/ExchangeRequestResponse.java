package com.matjari.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 내 교환 희망 조건. 펼친 개별 좌석 목록은 담지 않고 개수(wantSeatCount, 겹침 제거 후)만 담는다.
 * 범위의 zone은 입력한 표시용 원문, 열·번 from/to는 정규화 값이다(숫자는 앞 0 제거, 영문은 대문자).
 * 추가금은 요청이 아니라 범위마다 있다(ranges[].extraType/extraAmount). 금액은 매칭 계산에 쓰지 않는 참고 표시용이다
 * (+는 내가 받을 금액, -는 내가 낼 수 있는 금액). status 는 OPEN | CLOSED (삭제된 요청은 목록에 나오지 않는다).
 */
public record ExchangeRequestResponse(
        Long id,
        Long ticketId,
        String status,
        List<WantSessionItem> wantSessions,
        List<WantRangeItem> ranges,
        int wantSeatCount,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime updatedAt
) {
    public record WantSessionItem(
            Long sessionId,
            int priority,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE)
            LocalDateTime startsAt
    ) {}

    public record WantRangeItem(String zone, String rowFrom, String rowTo, String colFrom, String colTo,
                                String extraType, Integer extraAmount) {}
}
