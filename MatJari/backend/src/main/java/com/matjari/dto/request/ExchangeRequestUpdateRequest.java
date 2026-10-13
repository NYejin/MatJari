package com.matjari.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 교환 희망 조건 수정(전체 교체). 등록 요청에서 ticketId 만 뺀 형태다. */
public record ExchangeRequestUpdateRequest(
        @NotEmpty(message = "희망 회차를 1개 이상 선택해주세요.")
        @Size(max = 100, message = "항목 수가 너무 많습니다.")
        List<@NotNull(message = "희망 회차 정보가 올바르지 않습니다.") @Valid WantSessionInput> wantSessions,

        @NotEmpty(message = "희망 좌석 범위를 1개 이상 입력해주세요.")
        @Size(max = 1000, message = "항목 수가 너무 많습니다.")
        List<@NotNull(message = "희망 좌석 범위 정보가 올바르지 않습니다.") @Valid WantRangeInput> ranges
) {}
