package com.seatswap.controller;

import com.seatswap.dto.response.ModerationDtos.MyReportResponse;
import com.seatswap.dto.response.ModerationDtos.ReportCreateRequest;
import com.seatswap.dto.response.ModerationDtos.ReportCreatedResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.AbuseReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 신고 (FR: 신고). 로그인 필요. 신고는 매칭 화면에서만, 매칭 참여자만(비참여자·없는 매칭 404) 낸다.
 * 규칙·오류는 {@link AbuseReportService} 참고.
 */
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final AbuseReportService reportService;

    /** 201 {id, status:"OPEN", createdAt}. 400 {reason|detail}, 404, 409 REPORT_ALREADY_OPEN, 422 REPORT_LIMIT_REACHED. */
    @PostMapping("/api/exchange/matches/{matchId}/report")
    public ResponseEntity<ReportCreatedResponse> report(@PathVariable Long matchId,
                                                        @RequestBody ReportCreateRequest request,
                                                        @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reportService.create(principal.userId(), matchId, request));
    }

    /** 내 신고 내역(최신순, 처리 상태만). */
    @GetMapping("/api/reports/me")
    public PageResponse<MyReportResponse> mine(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @AuthenticationPrincipal AuthUserPrincipal principal) {
        return reportService.listMine(principal.userId(), page, size);
    }
}
