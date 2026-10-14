package com.matjari.dto.response;

/** 가입 이메일 인증번호 사용 여부(GET /api/auth/signup/email-verification). */
public record EmailVerificationStatusResponse(boolean enabled) {
}
