package com.seatswap.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 일회용 로그인 코드 교환 요청. */
public record SocialExchangeRequest(
        @NotBlank(message = "로그인 코드가 없어요.")
        @Size(max = 200, message = "로그인 코드가 올바르지 않아요.")
        String code) {

    // 로그에 코드가 남지 않게 toString 을 가린다
    @Override
    public String toString() {
        return "SocialExchangeRequest[***]";
    }
}
