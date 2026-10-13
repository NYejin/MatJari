package com.matjari.controller;

import com.matjari.dto.request.ExchangeRequestCreateRequest;
import com.matjari.dto.request.ExchangeRequestUpdateRequest;
import com.matjari.dto.response.ExchangeRequestResponse;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.dto.response.CandidateSliceResponse;
import com.matjari.dto.response.ExchangeCandidateResponse;
import com.matjari.dto.response.PageResponse;
import com.matjari.service.ExchangeCandidateService;
import com.matjari.service.ExchangeRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 교환 희망 조건 API (FR-04 교환 요청): 등록·내 목록·수정·삭제. 모두 로그인 필요, 본인 티켓·요청만 다룬다. */
@RestController
@RequestMapping("/api/exchange/requests")
@RequiredArgsConstructor
public class ExchangeRequestController {

    private final ExchangeRequestService exchangeRequestService;
    private final ExchangeCandidateService exchangeCandidateService;

    /** 201 + 등록된 요청. 티켓당 1개(중복 409), 남의 티켓 403, 없는 티켓 404, 내린 티켓·내 좌석 포함·상한 초과 422. */
    @PostMapping
    public ResponseEntity<ExchangeRequestResponse> create(@Valid @RequestBody ExchangeRequestCreateRequest request,
                                                          @AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(exchangeRequestService.create(principal.userId(), request));
    }

    /** 내 요청 목록(요청 id 오름차순). ticketId 를 주면 그 티켓의 요청만. 펼친 좌석은 개수만 담는다. */
    @GetMapping("/me")
    public List<ExchangeRequestResponse> mine(@RequestParam(required = false) Long ticketId,
                                              @AuthenticationPrincipal AuthUserPrincipal principal) {
        return exchangeRequestService.listMine(principal.userId(), ticketId);
    }

    /** 범위(추가금 포함)·희망 회차 전체 교체 -> 200 + 갱신된 요청. 남의 요청 403, 없는 요청 404. */
    @PatchMapping("/{id}")
    public ExchangeRequestResponse update(@PathVariable Long id,
                                          @Valid @RequestBody ExchangeRequestUpdateRequest request,
                                          @AuthenticationPrincipal AuthUserPrincipal principal) {
        return exchangeRequestService.update(principal.userId(), id, request);
    }

    /** 매칭 후보(조건이 서로 맞는 상대 요청). 남의 요청 403, 없는 요청 404, 닫힌 요청·내린 티켓·마감 422. size 기본 20, 최대 100. */
    @GetMapping("/{id}/candidates")
    public PageResponse<ExchangeCandidateResponse> candidates(@PathVariable Long id,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size,
                                                              @AuthenticationPrincipal AuthUserPrincipal principal) {
        return exchangeCandidateService.findCandidates(principal.userId(), id, page, size);
    }

    /**
     * 매칭 후보를 keyset(커서)으로 읽는다. 처음에는 cursor 를 비우고, 응답의 nextCursor 를 다음 요청에 그대로 넣는다(hasNext=false 면 끝).
     * 같은 판정·정렬이며 전체 개수와 offset 이 없다. 잘못된 cursor 400, 그 밖의 오류는 /candidates 와 같다. size 기본 20, 최대 100.
     */
    @GetMapping("/{id}/candidates/scroll")
    public CandidateSliceResponse candidateScroll(@PathVariable Long id,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @AuthenticationPrincipal AuthUserPrincipal principal) {
        return exchangeCandidateService.findCandidateSlice(principal.userId(), id, cursor, size);
    }

    /** 소프트 삭제(DELETED) -> 204, 이미 삭제됐으면 멱등 204. CHATTING 매칭은 시스템 취소, RESERVED 가 있으면 409. 남의 요청 403, 없는 요청 404. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal AuthUserPrincipal principal) {
        exchangeRequestService.delete(principal.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
