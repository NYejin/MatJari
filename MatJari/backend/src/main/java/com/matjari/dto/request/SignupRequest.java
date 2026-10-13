package com.matjari.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청 (FR-01).
 * compact constructor에서 email(앞뒤 공백 제거+소문자)/nickname(앞뒤 공백 제거, JS trim과 동일 규칙)을 정규화하므로
 * Bean Validation은 정규화된 값을 기준으로 동작한다. password는 정규화하지 않는다.
 * 길이 상한은 DB 컬럼(255)보다 작게 잡아 컬럼 초과 예외가 나지 않도록 검증 단계에서 막는다.
 */
public record SignupRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 100, message = "이메일은 100자 이하로 입력해주세요.")
        String email,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        @Size(min = 8, max = 64, message = "비밀번호는 8~64자로 입력해주세요.")
        String password,

        @NotBlank(message = "닉네임을 입력해주세요.")
        @Size(min = 2, max = 20, message = "닉네임은 2~20자로 입력해주세요.")
        String nickname,

        /** 이메일로 받은 인증번호(6자리). 인증이 꺼진 환경에서는 비워도 된다. */
        @Size(max = 20, message = "인증번호는 숫자 6자리예요.")
        String emailCode
) {
    /** 인증번호 없이 만드는 호출(인증이 꺼진 환경·기존 테스트). */
    public SignupRequest(String email, String password, String nickname) {
        this(email, password, nickname, null);
    }

    public SignupRequest {
        email = AuthInputNormalizer.normalizeEmail(email);
        nickname = AuthInputNormalizer.normalizeNickname(nickname);
    }

    /** 로그/디버깅 출력에 비밀번호 평문이 남지 않도록 마스킹 */
    @Override
    public String toString() {
        return "SignupRequest[email=" + email + ", password=****, nickname=" + nickname + ", emailCode=****]";
    }
}
