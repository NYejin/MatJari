package com.matjari.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest(
        @NotBlank(message = "refresh token이 필요합니다.") String refreshToken
) {}
