package com.matjari.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 공연 등록 (FR-02).
 * sessions: 회차 시작 일시 목록 — KST 현지 시각, 오프셋 없는 ISO 형식("2026-11-01T19:00").
 * 분 단위로 절삭되며, 요청 안의 같은 시각은 하나로 합친다. 지난 일시는 거부한다.
 * title의 정확한 상한(200자)과 venueName의 상한(100자)은 공백 정리 후 기준으로 서비스에서 검사한다.
 */
public record PerformanceCreateRequest(
        @NotBlank(message = "티켓팅 링크를 입력해주세요.")
        @Size(max = 2048, message = "티켓팅 링크는 2048자 이하로 입력해주세요.")
        String sourceUrl,

        @NotBlank(message = "공연 제목을 입력해주세요.")
        @Size(max = 600, message = "공연 제목은 200자 이하로 입력해주세요.")
        String title,

        @NotBlank(message = "공연장 이름을 입력해주세요.")
        @Size(max = 300, message = "공연장 이름은 100자 이하로 입력해주세요.")
        String venueName,

        @NotEmpty(message = "회차를 1개 이상 입력해주세요.")
        @Size(max = 100, message = "회차는 한 번에 100개까지 등록할 수 있습니다.")
        List<@NotNull(message = "회차 일시를 입력해주세요.") LocalDateTime> sessions
) {}
