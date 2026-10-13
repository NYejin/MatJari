package com.matjari.domain;

/** 매칭에 가할 수 있는 동작. 허용 상태 전이 표는 {@link ExchangeMatch#isAllowed} 와 ExchangeMatchService Javadoc 참고. */
public enum ExchangeMatchAction {
    PROPOSE,
    /** 예약 제안(POST /reserve). */
    RESERVE,
    RESERVE_ACCEPT,
    RESERVE_DECLINE,
    RESERVE_WITHDRAW,
    UNRESERVE,
    REJECT,
    CANCEL,
    COMPLETE
}
