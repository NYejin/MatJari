package com.matjari.controller;

import com.matjari.dto.response.PageResponse;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.AdminPerformanceService;
import com.matjari.service.AdminPerformanceService.Detail;
import com.matjari.service.AdminPerformanceService.EditRequest;
import com.matjari.service.AdminPerformanceService.ListItem;
import com.matjari.service.AdminPerformanceService.ReasonRequest;
import com.matjari.service.AdminPerformanceService.SessionChangesRequest;
import com.matjari.service.AdminPerformanceService.ZonesRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 공연 관리. `/api/admin/**` 는 SecurityConfig 에서 ADMIN 만 허용한다. */
@RestController
@RequestMapping("/api/admin/performances")
@RequiredArgsConstructor
public class AdminPerformanceController {

    private final AdminPerformanceService service;

    @GetMapping
    public PageResponse<ListItem> list(@RequestParam(required = false) String query,
                                       @RequestParam(defaultValue = "ALL") String hidden,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return service.list(query, hidden, page, size);
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/hide")
    public ResponseEntity<Void> hide(@PathVariable Long id, @RequestBody(required = false) ReasonRequest request,
                                     @AuthenticationPrincipal AuthUserPrincipal principal) {
        service.hide(principal.userId(), id, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unhide")
    public ResponseEntity<Void> unhide(@PathVariable Long id, @RequestBody(required = false) ReasonRequest request,
                                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        service.unhide(principal.userId(), id, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    public Detail edit(@PathVariable Long id, @RequestBody EditRequest request,
                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        return service.edit(principal.userId(), id, request);
    }

    @PostMapping("/{id}/session-changes")
    public Detail changeSessions(@PathVariable Long id, @RequestBody SessionChangesRequest request,
                                 @AuthenticationPrincipal AuthUserPrincipal principal) {
        return service.changeSessions(principal.userId(), id, request);
    }

    @PutMapping("/{id}/zones")
    public Detail replaceZones(@PathVariable Long id, @RequestBody ZonesRequest request,
                               @AuthenticationPrincipal AuthUserPrincipal principal) {
        return service.replaceZones(principal.userId(), id, request);
    }
}
