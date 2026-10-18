package com.matjari.controller;

import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.aiextract.DetailExtractionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 링크의 상세 정보에서 회차(날짜·시각)와 좌석 구역(이름·윤곽)을 읽는 AI 추출. 로그인 필요.
 * 400 형식 오류·UNSUPPORTED_SITE, 429 AI_RATE_LIMITED(+Retry-After), 503 AI_UNAVAILABLE·PAGE_FETCH_FAILED.
 */
@RestController
@RequestMapping("/api/performances")
@RequiredArgsConstructor
public class PerformanceAiExtractController {

    public record AiExtractRequest(@NotBlank(message = "링크를 입력해주세요.") @Size(max = 2000, message = "링크가 너무 깁니다.") String sourceUrl) {}

    private final DetailExtractionService extractionService;

    @PostMapping("/ai-extract")
    public DetailExtractionService.Response extract(@Valid @RequestBody AiExtractRequest request,
                                                    @AuthenticationPrincipal AuthUserPrincipal principal) {
        return extractionService.extract(principal.userId(), request.sourceUrl());
    }
}
