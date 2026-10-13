package com.matjari.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 희망 범위 1건: 구역 1개 + 열 from~to + 번 from~to + 추가금(범위마다). 숫자 열·번만 범위이고 문자는 from=to(하나씩 추가).
 * extraType: X | ANY | POS | NEG. extraAmount: POS는 0보다 큰 값, NEG는 0보다 작은 값, X/ANY는 null(참고 표시용, 매칭 판정은 유형만).
 * 길이는 공백 정리·정규화 후 기준으로 서비스(SeatKeyNormalizer)가 정확히 검사한다. @Size는 거친 한도다.
 */
public record WantRangeInput(
        @NotBlank(message = "구역을 입력해주세요.")
        @Size(max = 200, message = "입력이 너무 깁니다.")
        String zone,

        @NotBlank(message = "열 시작을 입력해주세요.")
        @Size(max = 100, message = "입력이 너무 깁니다.")
        String rowFrom,

        @NotBlank(message = "열 끝을 입력해주세요.")
        @Size(max = 100, message = "입력이 너무 깁니다.")
        String rowTo,

        @NotBlank(message = "번 시작을 입력해주세요.")
        @Size(max = 100, message = "입력이 너무 깁니다.")
        String colFrom,

        @NotBlank(message = "번 끝을 입력해주세요.")
        @Size(max = 100, message = "입력이 너무 깁니다.")
        String colTo,

        @NotBlank(message = "추가금 유형을 선택해주세요.")
        @Size(max = 20, message = "추가금 유형은 X, ANY, POS, NEG 중 하나여야 합니다.")
        String extraType,

        Integer extraAmount
) {}
