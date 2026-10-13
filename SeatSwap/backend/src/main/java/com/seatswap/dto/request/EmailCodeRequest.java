package com.seatswap.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 이메일 인증번호 요청. 이메일은 가입 요청과 같은 규칙으로 정규화한다. */
public record EmailCodeRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 100, message = "이메일은 100자 이하로 입력해주세요.")
        String email
) {
    public EmailCodeRequest {
        email = AuthInputNormalizer.normalizeEmail(email);
    }
}
