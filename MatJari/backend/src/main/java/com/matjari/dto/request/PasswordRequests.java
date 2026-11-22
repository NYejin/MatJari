package com.matjari.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정·변경 요청 본문 모음. 새 비밀번호 규칙은 가입과 같다(8~64자, BCrypt 72바이트는 서비스가 다시 확인).
 * toString 은 비밀번호·토큰을 가려 로그에 남지 않게 한다.
 */
public final class PasswordRequests {

    private PasswordRequests() {}

    /** POST /api/auth/password-reset/request. 이메일은 가입 요청과 같은 규칙으로 정규화한다. */
    public record ResetRequest(
            @NotBlank(message = "이메일을 입력해주세요.")
            @Email(message = "올바른 이메일 형식이 아닙니다.")
            @Size(max = 100, message = "이메일은 100자 이하로 입력해주세요.")
            String email
    ) {
        public ResetRequest {
            email = AuthInputNormalizer.normalizeEmail(email);
        }
    }

    /** POST /api/auth/password-reset/confirm. token 은 링크 프래그먼트의 원문(32바이트 base64url 43자). */
    public record ResetConfirmRequest(
            @NotBlank(message = "재설정 링크가 올바르지 않습니다.")
            @Size(max = 200, message = "재설정 링크가 올바르지 않습니다.")
            String token,

            @NotBlank(message = "새 비밀번호를 입력해주세요.")
            @Size(min = 8, max = 64, message = "비밀번호는 8~64자로 입력해주세요.")
            String newPassword
    ) {
        @Override
        public String toString() {
            return "ResetConfirmRequest[token=****, newPassword=****]";
        }
    }

    /** PATCH /api/users/me/password (또는 /api/auth/password). */
    public record ChangeRequest(
            @NotBlank(message = "현재 비밀번호를 입력해주세요.")
            @Size(max = 128, message = "현재 비밀번호가 올바르지 않습니다.")
            String currentPassword,

            @NotBlank(message = "새 비밀번호를 입력해주세요.")
            @Size(min = 8, max = 64, message = "비밀번호는 8~64자로 입력해주세요.")
            String newPassword
    ) {
        @Override
        public String toString() {
            return "ChangeRequest[currentPassword=****, newPassword=****]";
        }
    }
}
