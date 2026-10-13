package com.matjari.service.social;

/**
 * 콜백 흐름의 실패. {@link SocialError} 값이 프런트 로그인 페이지의 socialError 쿼리가 된다.
 * 메시지·원인에는 code/token/secret/이메일을 싣지 않는다.
 */
public class SocialLoginFailure extends RuntimeException {

    /** socialError 값 = 프런트 매핑 키. */
    public enum SocialError {
        INVALID_STATE, ACCESS_DENIED, PROVIDER_ERROR, EMAIL_EXISTS, ACCOUNT_SUSPENDED, SERVER_BUSY,
        // 계정 연결(link) 모드 전용: 프런트 /me?socialLinkError= 값
        INVALID_LINK, PROVIDER_DISABLED
    }

    private final SocialError error;

    public SocialLoginFailure(SocialError error) {
        super(error.name(), null, false, false);
        this.error = error;
    }

    public SocialError getError() {
        return error;
    }
}
