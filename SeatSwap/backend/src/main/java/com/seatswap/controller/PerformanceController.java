package com.seatswap.controller;

import com.seatswap.dto.request.PerformanceCreateRequest;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.PerformanceDetailResponse;
import com.seatswap.dto.response.PerformanceLookupResponse;
import com.seatswap.dto.response.PerformancePreviewResponse;
import com.seatswap.dto.response.PerformanceSummaryResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.PerformanceService;
import com.seatswap.service.linkinfo.PerformancePreviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 공연 API (FR-02): 목록·링크 조회·상세·등록만 제공한다. 모두 로그인 필요 (SecurityConfig anyRequest().authenticated()).
 * 공연·회차는 등록 후 사용자가 수정·삭제할 수 없다 (제목·공연장 변경은 관리자 승인 수정 제안으로만, PerformanceEditProposalController).
 */
@RestController
@RequestMapping("/api/performances")
@RequiredArgsConstructor
public class PerformanceController {

    private final PerformanceService performanceService;
    private final PerformancePreviewService previewService;

    /** 목록. asOf("yyyy-MM-ddTHH:mm", 선택): 페이지를 넘기는 동안 정렬 기준 시각 고정. 형식 오류는 400. */
    @GetMapping
    public PageResponse<PerformanceSummaryResponse> search(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime asOf) {
        return performanceService.search(query, page, size, asOf);
    }

    /** 링크로 기존 공연 조회 — {exists, performanceId|null}. */
    @GetMapping("/lookup")
    public PerformanceLookupResponse lookup(@RequestParam String sourceUrl) {
        return performanceService.lookup(sourceUrl);
    }

    /**
     * 링크 페이지의 공개 HTML에서 제목·공연장·기간 미리보기 (회차 시각은 읽을 수 없다). 항상 200 + status,
     * 형식 오류 400, 사용자당 분당 10회 초과 429 PREVIEW_RATE_LIMITED. 요청 URL은 서버 고정 템플릿뿐이다.
     */
    @GetMapping("/preview")
    public PerformancePreviewResponse preview(@RequestParam String sourceUrl,
                                              @AuthenticationPrincipal AuthUserPrincipal principal) {
        return previewService.preview(principal.userId(), sourceUrl);
    }

    /**
     * 공연 상세용 포스터 이미지 주소 {posterUrl|null}. 서버는 링크 페이지에서 주소 문자열만 읽고 이미지는 브라우저가 원 사이트에서 직접 불러온다.
     * 못 읽거나 제한에 걸리거나 스위치(seatswap.poster.enabled)가 꺼져 있으면 posterUrl=null.
     */
    @GetMapping("/{id}/poster")
    public java.util.Map<String, String> poster(@PathVariable Long id,
                                                @AuthenticationPrincipal AuthUserPrincipal principal) {
        PerformanceDetailResponse detail = performanceService.get(id, principal.userId());
        java.util.Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("posterUrl", previewService.poster(principal.userId(), detail.sourceUrl()));
        return body;
    }

    @GetMapping("/{id}")
    public PerformanceDetailResponse get(@PathVariable Long id,
                                         @AuthenticationPrincipal AuthUserPrincipal principal) {
        return performanceService.get(id, principal.userId());
    }

    /** 201 + 상세. 같은 링크의 공연이 있으면 409 {"message":"이미 등록된 공연입니다.","performanceId":N}. */
    @PostMapping
    public ResponseEntity<PerformanceDetailResponse> create(@Valid @RequestBody PerformanceCreateRequest request,
                                                            @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(performanceService.create(principal.userId(), request));
    }
}
