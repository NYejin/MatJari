package com.matjari.controller;

import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.aiextract.DetailExtractionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 링크의 상세 정보에서 회차(날짜·시각)와 좌석 구역(이름·윤곽)을 읽는 AI 추출. 로그인 필요.
 * 400 형식 오류·UNSUPPORTED_SITE, 429 AI_RATE_LIMITED·AI_MONTHLY_LIMIT_REACHED(+Retry-After), 503 AI_UNAVAILABLE·PAGE_FETCH_FAILED.
 * 사용자당 매월 3회(KST 달력 월), 보관 결과 반환과 AI 가 읽지 못한 호출은 세지 않는다.
 */
@RestController
@RequestMapping("/api/performances")
@RequiredArgsConstructor
public class PerformanceAiExtractController {

    public record AiExtractRequest(@NotBlank(message = "링크를 입력해주세요.") @Size(max = 2000, message = "링크가 너무 깁니다.") String sourceUrl) {}

    private final DetailExtractionService extractionService;

    /** 이번 달 사용 현황 {limit, used, remaining, resetsAt}. */
    @GetMapping("/ai-extract/quota")
    public com.matjari.service.aiextract.AiUsageLimiter.Quota quota(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return extractionService.quota(principal.userId());
    }

    @PostMapping("/ai-extract")
    public DetailExtractionService.Response extract(@Valid @RequestBody AiExtractRequest request,
                                                    @AuthenticationPrincipal AuthUserPrincipal principal) {
        return extractionService.extract(principal.userId(), request.sourceUrl());
    }
}
