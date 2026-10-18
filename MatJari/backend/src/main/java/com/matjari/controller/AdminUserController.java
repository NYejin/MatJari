package com.matjari.controller;

import com.matjari.dto.response.PageResponse;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.AdminUserService;
import com.matjari.service.AdminUserService.AdminActionLogItem;
import com.matjari.service.AdminUserService.AdminUserDetail;
import com.matjari.service.AdminUserService.AdminUserItem;
import com.matjari.service.AdminUserService.ForceWithdrawRequest;
import com.matjari.service.AdminUserService.RoleRequest;
import com.matjari.service.AdminUserService.SuspendRequest;
import com.matjari.service.AdminUserService.UnsuspendRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 이용자 관리·조치 기록. /api/admin/** 는 SecurityConfig 가 ADMIN 만 허용한다(일반 사용자 403, 미인증 401). */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService service;

    @GetMapping("/users")
    public PageResponse<AdminUserItem> users(@RequestParam(required = false) String query,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String role,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.list(query, status, role, page, size);
    }

    @GetMapping("/users/{id}")
    public AdminUserDetail user(@PathVariable Long id) {
        return service.detail(id);
    }

    @PostMapping("/users/{id}/suspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@PathVariable Long id, @RequestBody(required = false) SuspendRequest body,
                        @AuthenticationPrincipal AuthUserPrincipal principal) {
        service.suspend(principal.userId(), id, body);
    }

    @PostMapping("/users/{id}/unsuspend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsuspend(@PathVariable Long id, @RequestBody(required = false) UnsuspendRequest body,
                          @AuthenticationPrincipal AuthUserPrincipal principal) {
        service.unsuspend(principal.userId(), id, body);
    }

    @PutMapping("/users/{id}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void role(@PathVariable Long id, @RequestBody(required = false) RoleRequest body,
                     @AuthenticationPrincipal AuthUserPrincipal principal) {
        service.changeRole(principal.userId(), id, body);
    }

    @PostMapping("/users/{id}/force-withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forceWithdraw(@PathVariable Long id, @RequestBody(required = false) ForceWithdrawRequest body,
                              @AuthenticationPrincipal AuthUserPrincipal principal) {
        service.forceWithdraw(principal.userId(), id, body);
    }

    @GetMapping("/action-logs")
    public PageResponse<AdminActionLogItem> actionLogs(@RequestParam(required = false) String action,
                                                       @RequestParam(required = false) String targetType,
                                                       @RequestParam(required = false) Long targetId,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return service.actionLogs(action, targetType, targetId, page, size);
    }
}
