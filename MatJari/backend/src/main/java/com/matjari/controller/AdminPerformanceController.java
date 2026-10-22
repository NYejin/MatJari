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
import com.matjari.service.aiextract.DetailExtractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** 관리자 공연 관리. `/api/admin/**` 는 SecurityConfig 에서 ADMIN 만 허용한다. */
@RestController
@RequestMapping("/api/admin/performances")
@RequiredArgsConstructor
public class AdminPerformanceController {

    private final AdminPerformanceService service;
    private final DetailExtractionService extractionService;

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

    /** 이 공연에서 이루어진 교환(매칭) 목록 + 상태별 개수. status=ALL|CHATTING|RESERVED|COMPLETED|CANCELED, 50개씩. */
    @GetMapping("/{id}/exchanges")
    public AdminPerformanceService.ExchangePage exchanges(@PathVariable Long id,
                                                          @RequestParam(defaultValue = "ALL") String status,
                                                          @RequestParam(defaultValue = "0") int page) {
        return service.exchanges(id, status, page);
    }

    @PostMapping("/{id}/hide")
    public ResponseEntity<Void> hide(@PathVariable Long id, @RequestBody(required = false) ReasonRequest request,
                                     @AuthenticationPrincipal AuthUserPrincipal principal) {
        service.hide(principal.userId(), id, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    /** 숨긴 공연 완전 삭제(사유 필수). 숨김 아님 409 PERFORMANCE_NOT_HIDDEN, 연결 기록 있음 409 PERFORMANCE_IN_USE. */
    @PostMapping("/{id}/delete")
    public ResponseEntity<Void> delete(@PathVariable Long id, @RequestBody(required = false) ReasonRequest request,
                                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        service.delete(principal.userId(), id, request == null ? null : request.reason());
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

    /**
     * 구역 다시 인식: 공연 링크에서 AI 로 다시 읽은 결과(저장하지 않음). 보관 결과를 쓰지 않고 월 횟수도 세지 않는다(전체 하루 상한은 적용).
     * 관리자가 화면에서 확인한 뒤 PUT /zones 로 교체한다.
     */
    @PostMapping("/{id}/zones/ai-extract")
    public DetailExtractionService.Response reextractZones(@PathVariable Long id) {
        return extractionService.extractForAdmin(service.get(id).sourceUrl());
    }

    /** 좌석표 이미지로 구역 인식(저장하지 않음, 이미지도 저장하지 않음). 결과 확인 뒤 PUT /zones 로 교체한다. */
    @PostMapping(value = "/{id}/zones/seatmap", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DetailExtractionService.Response seatmapZones(@PathVariable Long id, @RequestParam(required = false) MultipartFile image)
            throws IOException {
        service.get(id); // 없는 공연은 404
        return extractionService.extractSeatMapForAdmin(image == null ? null : image.getBytes());
    }

    @PutMapping("/{id}/zones")
    public Detail replaceZones(@PathVariable Long id, @RequestBody ZonesRequest request,
                               @AuthenticationPrincipal AuthUserPrincipal principal) {
        return service.replaceZones(principal.userId(), id, request);
    }
}
