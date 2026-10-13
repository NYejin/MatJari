package com.matjari.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 희망 회차 1건과 사용자 우선순위 (1이 가장 높음, 같은 값도 허용). */
public record WantSessionInput(
        @NotNull(message = "회차를 선택해주세요.")
        Long sessionId,

        @NotNull(message = "우선순위를 입력해주세요.")
        @Min(value = 1, message = "우선순위는 1 이상이어야 합니다.")
        @Max(value = 999, message = "우선순위는 999 이하여야 합니다.")
        Integer priority
) {}
