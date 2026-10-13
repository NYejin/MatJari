package com.matjari.controller;

import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.PerformanceEditProposalDtos.AdminEditProposal;
import com.matjari.dto.response.PerformanceEditProposalDtos.RejectRequest;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.PerformanceEditProposalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 공연 수정 제안 (관리자). `/api/admin/**` 는 SecurityConfig 에서 ADMIN 만 허용한다. */
@RestController
@RequestMapping("/api/admin/performance-edit-proposals")
@RequiredArgsConstructor
public class AdminPerformanceEditProposalController {

    private final PerformanceEditProposalService service;

    @GetMapping
    public PageResponse<AdminEditProposal> list(@RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.listForAdmin(status, page, size);
    }

    @PostMapping("/{id}/approve")
    public AdminEditProposal approve(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return service.approve(principal.userId(), id);
    }

    @PostMapping("/{id}/reject")
    public AdminEditProposal reject(@PathVariable Long id,
                                    @RequestBody(required = false) RejectRequest request,
                                    @AuthenticationPrincipal AuthUserPrincipal principal) {
        return service.reject(principal.userId(), id, request == null ? null : request.note());
    }
}
