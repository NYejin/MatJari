package com.matjari.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 소셜 계정 연결 확정 요청(콜백이 프래그먼트로 넘긴 일회용 코드). */
public record SocialConfirmRequest(
        @NotBlank(message = "연결 확인 코드가 없어요.")
        @Size(max = 200, message = "연결 확인 코드가 올바르지 않아요.")
        String code) {

    // 로그에 코드가 남지 않게 toString 을 가린다
    @Override
    public String toString() {
        return "SocialConfirmRequest[***]";
    }
}
