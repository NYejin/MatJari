package com.seatswap.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 닉네임 수정 요청. 규칙은 SignupRequest 와 같다: 앞뒤 공백 제거(JS trim 규칙) 후 2~20자.
 * 제어·제로폭 문자 거부는 서비스(UserService)가 AuthInputNormalizer 로 한다.
 */
public record UpdateNicknameRequest(
        @NotBlank(message = "닉네임을 입력해주세요.")
        @Size(min = 2, max = 20, message = "닉네임은 2~20자로 입력해주세요.")
        String nickname
) {
    public UpdateNicknameRequest {
        nickname = AuthInputNormalizer.normalizeNickname(nickname);
    }
}
