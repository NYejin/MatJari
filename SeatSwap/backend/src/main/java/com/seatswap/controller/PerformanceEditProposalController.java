package com.seatswap.controller;

import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.PerformanceEditProposalDtos.CreateRequest;
import com.seatswap.dto.response.PerformanceEditProposalDtos.EditProposal;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.PerformanceEditProposalService;
import lombok.RequiredArgsConstructor;
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

/**
 * 공연 수정 제안 (사용자). 로그인 필요. `/api/performances/edit-proposals/me` 는 리터럴 경로라
 * PerformanceController 의 `/api/performances/{id}` 보다 우선한다(둘 다 GET, 리터럴이 더 구체적).
 */
@RestController
@RequestMapping("/api/performances")
@RequiredArgsConstructor
public class PerformanceEditProposalController {

    private final PerformanceEditProposalService service;

    @PostMapping("/{id}/edit-proposals")
    public ResponseEntity<EditProposal> create(@PathVariable Long id, @RequestBody CreateRequest request,
                                               @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.userId(), id, request));
    }

    @GetMapping("/edit-proposals/me")
    public PageResponse<EditProposal> mine(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size,
                                           @AuthenticationPrincipal AuthUserPrincipal principal) {
        return service.listMine(principal.userId(), page, size);
    }
}
