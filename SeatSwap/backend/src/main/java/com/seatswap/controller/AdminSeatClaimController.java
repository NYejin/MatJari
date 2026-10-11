package com.seatswap.controller;

import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.SeatClaimDtos.AdminSeatClaimResponse;
import com.seatswap.dto.response.SeatClaimDtos.ReviewNoteRequest;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.SeatClaimService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 좌석 소유 인증 요청 처리. /api/admin/** 는 SecurityConfig 가 ADMIN 만 허용한다(일반 사용자 403, 미인증 401). */
@RestController
@RequestMapping("/api/admin/seat-claims")
@RequiredArgsConstructor
public class AdminSeatClaimController {

    private final SeatClaimService seatClaimService;

    /** status 생략 시 전체. 잘못된 status·page·size 는 400. */
    @GetMapping
    public PageResponse<AdminSeatClaimResponse> list(@RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return seatClaimService.adminList(status, page, size);
    }

    /** 현재 그 좌석의 활성 티켓을 시스템이 내린다. 409 TICKET_RESERVED / TICKET_EXCHANGED / CLAIM_ALREADY_REVIEWED. */
    @PostMapping("/{id}/approve")
    public AdminSeatClaimResponse approve(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return seatClaimService.approve(principal.userId(), id);
    }

    @PostMapping("/{id}/reject")
    public AdminSeatClaimResponse reject(@PathVariable Long id,
                                         @RequestBody(required = false) ReviewNoteRequest body,
                                         @AuthenticationPrincipal AuthUserPrincipal principal) {
        return seatClaimService.reject(principal.userId(), id, body == null ? null : body.note());
    }
}
