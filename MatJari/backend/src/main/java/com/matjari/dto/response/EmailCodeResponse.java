package com.matjari.dto.response;

/** 인증번호 발송 결과: 유효 시간과 다시 보낼 수 있기까지의 대기 시간(초). 코드 자체는 싣지 않는다. */
public record EmailCodeResponse(int expiresInSeconds, int resendAfterSeconds) {
}
