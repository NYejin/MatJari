package com.matjari.controller;

import com.matjari.dto.response.ModerationDtos.AdminBlockResponse;
import com.matjari.dto.response.ModerationDtos.AdminReport;
import com.matjari.dto.response.ModerationDtos.AdminReportMessagePage;
import com.matjari.dto.response.ModerationDtos.AdminSanctionResponse;
import com.matjari.dto.response.ModerationDtos.DismissRequest;
import com.matjari.dto.response.ModerationDtos.ResolveRequest;
import com.matjari.dto.response.PageResponse;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.AdminModerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 신고 처리·이용 정지·차단 현황. /api/admin/** 는 SecurityConfig 가 ADMIN 만 허용한다(일반 사용자 403, 미인증 401). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminModerationController {

    private final AdminModerationService service;

    @GetMapping("/reports")
    public PageResponse<AdminReport> reports(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String reason,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.listReports(status, reason, page, size);
    }

    @GetMapping("/reports/{id}")
    public AdminReport report(@PathVariable Long id) {
        return service.getReport(id);
    }

    /** 채팅 근거(그 신고의 매칭 메시지만, 최신부터 위로). */
    @GetMapping("/reports/{id}/messages")
    public AdminReportMessagePage messages(@PathVariable Long id,
                                           @RequestParam(required = false) Long before,
                                           @RequestParam(required = false) Integer size) {
        return service.messages(id, before, size);
    }

    /** 조치. sanction 이 있으면 같은 트랜잭션에서 이용 정지 + 열린 매칭 시스템 취소. 409 REPORT_ALREADY_HANDLED / CANNOT_SANCTION_ADMIN / SANCTION_ALREADY_ACTIVE. */
    @PostMapping("/reports/{id}/resolve")
    public AdminReport resolve(@PathVariable Long id,
                               @RequestBody(required = false) ResolveRequest body,
                               @AuthenticationPrincipal AuthUserPrincipal principal) {
        return service.resolve(principal.userId(), id, body);
    }

    @PostMapping("/reports/{id}/dismiss")
    public AdminReport dismiss(@PathVariable Long id,
                               @RequestBody(required = false) DismissRequest body,
                               @AuthenticationPrincipal AuthUserPrincipal principal) {
        return service.dismiss(principal.userId(), id, body == null ? null : body.note());
    }

    @GetMapping("/sanctions")
    public PageResponse<AdminSanctionResponse> sanctions(@RequestParam(required = false) String active,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return service.listSanctions(active, page, size);
    }

    @PostMapping("/sanctions/{id}/revoke")
    public AdminSanctionResponse revoke(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return service.revoke(principal.userId(), id);
    }

    /** 차단 현황(읽기 전용). */
    @GetMapping("/blocks")
    public PageResponse<AdminBlockResponse> blocks(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return service.listBlocks(page, size);
    }
}
