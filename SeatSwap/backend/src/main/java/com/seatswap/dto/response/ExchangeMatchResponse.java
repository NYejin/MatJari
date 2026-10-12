package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 호출자 기준으로 본 매칭 (POST 응답, 내 매칭 목록, 단건 조회가 같은 모양). my / counterpart 접두어로 나눠 담고
 * a/b 구분은 mySide(A=제안자, B=제안받은 쪽)와 role(SENT=내가 a측, RECEIVED=내가 b측)로만 알린다. 상대 이메일 등 개인정보는 없고 닉네임만 있다.
 * mySeat / counterpartSeat 의 zone·row·col 은 사용자가 입력한 표시용 원문(label)이다. 완료(COMPLETED)가 구현돼도 매칭 행의 티켓 id 는 교환 전 티켓을 계속 가리키므로
 * 이 자리는 항상 교환 전 자리이고, 교환 후 자리는 새 티켓·교환 이력(old_ticket_id/new_ticket_id, 후속)이 담당한다(8차 답변, 2026-10-09).
 * myExtraType/Amount, counterpartExtraType/Amount 는 매칭이 만들어질 때 저장한 추가금 스냅샷(유형 X/ANY/POS/NEG + 참고용 금액)이다. 내 쪽은
 * 내 희망 범위 중 상대 좌석을 포함한 범위의 값, 상대 쪽은 상대 범위 중 내 좌석을 포함한 범위의 값이며 이후 요청이 바뀌어도 변하지 않는다(매칭 판정에 금액은 쓰이지 않는다).
 * myRequestDeleted / counterpartRequestDeleted 는 그 쪽 교환 요청이 삭제(DELETED)됐는지다(삭제돼도 매칭 기록은 남는다).
 * reservedBy 는 RESERVED 일 때 예약을 '제안한' 사람: ME / COUNTERPART (그 외 상태는 null), reservedAt 은 상대가 수락한 시각(RESERVED 일 때만 값).
 * 예약은 한 명이 제안하고 상대가 수락해야 성립하며(V15) 성립 뒤에는 누구나 취소할 수 있다. reservationProposal 은 CHATTING 의 예약 제안 상태를 호출자 기준으로
 * NONE(제안 없음; RESERVED·종료 매칭은 항상 NONE) / MINE(내가 제안, 상대 수락 대기) / THEIRS(상대가 제안, 내가 수락·거절)로 알린다. myAccepted / counterpartAccepted 는 '교환 수락'(a/b_completed_at) 표시이며
 * 예약 취소 시 초기화된다. 둘 다 true 가 되는 순간 매칭이 COMPLETED 가 된다.
 * myTicketExchanged / counterpartTicketExchanged 는 그 쪽 티켓이 교환 완료(status = EXCHANGED)로 닫혔는지다(다른 매칭의 완료로 EXCHANGED 가 된 좌석이 걸린
 * CHATTING 매칭 카드에서 "이미 교환된 좌석이에요"를 보여주는 근거. COMPLETED 매칭 자신의 두 티켓도 true 다).
 * myTicketReservedElsewhere / counterpartTicketReservedElsewhere 는 그 쪽 티켓이 이 매칭이 아닌 다른 매칭의 예약 잠금에 걸려 있는지다
 * (CHATTING 카드에서만 의미가 있다. 이 매칭이 RESERVED 일 때 자기 잠금은 세지 않는다).
 * unreadChatCount 는 상대가 보낸 채팅 메시지 중 내가 아직 읽지 않은 수(읽음 포인터 기준, CANCELED·COMPLETED 매칭도 센다).
 * status 가 RESERVED 이면 두 티켓이 잠겨 있다. canceledBy 는 CANCELED 일 때만: ME / COUNTERPART / SYSTEM.
 */
public record ExchangeMatchResponse(
        Long id,
        String status,
        String mySide,
        String role,
        Long myRequestId,
        Long myTicketId,
        Seat mySeat,
        Long counterpartRequestId,
        Long counterpartTicketId,
        Seat counterpartSeat,
        String counterpartNickname,
        String myExtraType,
        Integer myExtraAmount,
        String counterpartExtraType,
        Integer counterpartExtraAmount,
        boolean myRequestDeleted,
        boolean counterpartRequestDeleted,
        boolean myTicketExchanged,
        boolean counterpartTicketExchanged,
        boolean myTicketReservedElsewhere,
        boolean counterpartTicketReservedElsewhere,
        String reservedBy,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime reservedAt,
        boolean myAccepted,
        boolean counterpartAccepted,
        String canceledBy,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime canceledAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime createdAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
        LocalDateTime updatedAt,
        int unreadChatCount,
        String reservationProposal
) {
    /** 좌석 한 곳: 표시용 구역·열·번 + 회차 id·시작 시각. */
    public record Seat(
            String zone,
            String row,
            String col,
            Long sessionId,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE)
            LocalDateTime startsAt
    ) {}
}
