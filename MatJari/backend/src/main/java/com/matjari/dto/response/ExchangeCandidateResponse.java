package com.matjari.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 매칭 후보 1건 = 조건이 서로 맞는 상대의 교환 요청 1건. 점수·랭킹·신뢰도 없음, 개인정보(이메일 등) 없음.
 * zone/row/col 은 상대가 입력한 표시용 원문(label)이다. wantPriority 는 내 희망 회차 중 상대 티켓 회차의 우선순위(1이 가장 높음).
 * 추가금은 범위 단위다. myExtraType/myExtraAmount 는 내 희망 범위 중 상대 좌석을 포함한 범위의 값, extraType/extraAmount 는
 * 상대 희망 범위 중 내 좌석을 포함한 범위의 값이다. 금액은 매칭 판정에 쓰이지 않는 참고 표시용이다
 * (POS 는 받을 금액 양수, NEG 는 낼 수 있는 금액 음수).
 * settlementHint 는 한쪽 POS(받아야 하는 최소 m) · 다른 쪽 NEG(낼 수 있는 최대 p) 이고 둘 다 금액이 있을 때만 계산한 참고 구간 [m, p]이다.
 * p &lt; m 이거나 해당 조합이 아니면 null. 매칭 여부와는 무관하다.
 */
public record ExchangeCandidateResponse(
        Long requestId,
        Long ticketId,
        String zone,
        String row,
        String col,
        Long sessionId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE)
        LocalDateTime startsAt,
        String nickname,
        int wantPriority,
        String extraType,
        Integer extraAmount,
        String myExtraType,
        Integer myExtraAmount,
        SettlementHint settlementHint,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime requestedAt
) {
    public record SettlementHint(long min, long max) {}
}
