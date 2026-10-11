package com.seatswap.service;

/**
 * 열린 매칭(CHATTING·RESERVED) 시스템 취소. 차단(두 사용자 사이)과 이용 정지(한 사용자의 모든 매칭)가 쓴다.
 * 반드시 활성 트랜잭션 안에서 호출한다(MANDATORY). 구현은 잠금 순서 티켓 id↑ → 요청 id↑ → 매칭 id↑ 를 지킨 뒤 상태를 다시 확인하고
 * CANCELED(canceled_by_id NULL = 시스템)로 바꾸며 예약 잠금(exchange_ticket_lock)을 해제한다. 요청(exchange_request)은 건드리지 않는다.
 * COMPLETED 매칭과 교환 이력은 그대로 둔다.
 */
public interface OpenMatchCanceller {

    /** 두 사용자 사이의 열린 매칭을 취소하고 취소한 건수를 돌려준다. */
    int cancelBetween(long userA, long userB);

    /** 한 사용자가 참여한 모든 열린 매칭을 취소하고 취소한 건수를 돌려준다. */
    int cancelAllOf(long userId);
}
