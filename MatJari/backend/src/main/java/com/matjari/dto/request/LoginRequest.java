package com.matjari.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** 로그인 요청 (FR-01). email은 가입과 동일하게 앞뒤 공백 제거+소문자 정규화. */
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        String email,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        String password
) {
    public LoginRequest {
        email = AuthInputNormalizer.normalizeEmail(email);
    }

    /** 로그/디버깅 출력에 비밀번호 평문이 남지 않도록 마스킹 */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=****]";
    }
}
