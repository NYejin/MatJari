package com.matjari.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 교환 희망 조건 등록. 희망 회차는 요청 단위, 희망 범위는 (구역, 열 from~to, 번 from~to, 추가금) 여러 건이다. 추가금은 범위마다 있다.
 * 리스트 크기 상한(@Size)은 비정상적으로 큰 본문을 거르는 거친 한도이며, 실제 안전 상한(범위 50개·펼친 좌석 5,000건)은 서비스가 422로 처리한다.
 */
public record ExchangeRequestCreateRequest(
        @NotNull(message = "티켓을 선택해주세요.")
        Long ticketId,

        @NotEmpty(message = "희망 회차를 1개 이상 선택해주세요.")
        @Size(max = 100, message = "항목 수가 너무 많습니다.")
        List<@NotNull(message = "희망 회차 정보가 올바르지 않습니다.") @Valid WantSessionInput> wantSessions,

        @NotEmpty(message = "희망 좌석 범위를 1개 이상 입력해주세요.")
        @Size(max = 1000, message = "항목 수가 너무 많습니다.")
        List<@NotNull(message = "희망 좌석 범위 정보가 올바르지 않습니다.") @Valid WantRangeInput> ranges
) {
    public ExchangeRequestUpdateRequest asUpdate() {
        return new ExchangeRequestUpdateRequest(wantSessions, ranges);
    }
}
