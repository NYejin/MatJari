package com.matjari.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 티켓 등록. 좌석 1개를 텍스트로 입력한다 (구역 필수, 열·번은 숫자 또는 문자).
 * 정확한 길이 상한(구역 50·열 20·번 20)과 숫자 상한은 공백 정리·정규화 후 기준으로 서비스에서 검사한다.
 * @Size는 비정상적으로 긴 입력을 거르는 거친 한도다.
 */
public record TicketCreateRequest(
        @NotNull(message = "회차를 선택해주세요.")
        Long sessionId,

        @NotBlank(message = "구역을 입력해주세요.")
        @Size(max = 200, message = "구역은 50자 이하로 입력해주세요.")
        String zone,

        @NotBlank(message = "열을 입력해주세요.")
        @Size(max = 100, message = "열은 20자 이하로 입력해주세요.")
        String row,

        @NotBlank(message = "번을 입력해주세요.")
        @Size(max = 100, message = "번은 20자 이하로 입력해주세요.")
        String col
) {}
