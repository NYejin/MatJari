package com.matjari.dto.request;

import jakarta.validation.constraints.NotNull;

/** 후보 목록에서 고른 상대의 교환 요청 id. */
public record ProposalRequest(
        @NotNull(message = "교환을 제안할 상대 요청을 선택해주세요.")
        Long targetRequestId
) {}
