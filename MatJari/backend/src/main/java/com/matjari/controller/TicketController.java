package com.matjari.controller;

import com.matjari.dto.request.TicketCreateRequest;
import com.matjari.dto.response.TicketResponse;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.TicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 티켓 API (FR-03): 등록·내 티켓·내리기. 모두 로그인 필요, 본인 티켓만 다룬다. */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    /** 201 + 등록된 티켓. 같은 좌석의 활성 티켓이 있으면 409, 활성 티켓 상한 초과는 422. */
    @PostMapping
    public ResponseEntity<TicketResponse> create(@Valid @RequestBody TicketCreateRequest request,
                                                 @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ticketService.create(principal.userId(), request));
    }

    /** 내 활성 티켓 (회차 시각 오름차순). */
    @GetMapping("/me")
    public List<TicketResponse> mine(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return ticketService.listMine(principal.userId());
    }

    /** 티켓 내리기(소프트 삭제) -> 204. 본인 티켓이 아니면 404, 이미 내린 티켓도 204. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable Long id,
                                           @AuthenticationPrincipal AuthUserPrincipal principal) {
        ticketService.deactivate(principal.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
