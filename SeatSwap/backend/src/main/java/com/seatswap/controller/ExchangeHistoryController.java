package com.seatswap.controller;

import com.seatswap.dto.response.ExchangeHistoryResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.security.AuthUserPrincipal;
import com.seatswap.service.ExchangeHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 마이페이지 '교환 이력' API. 로그인 필요, 본인 이력만(남의 이력을 가리키는 경로가 없다). */
@RestController
@RequestMapping("/api/exchange/history")
@RequiredArgsConstructor
public class ExchangeHistoryController {

    private final ExchangeHistoryService historyService;

    /** 내 교환 이력(최신순). size 기본 20 최대 100, 잘못된 page·size 는 400. */
    @GetMapping
    public PageResponse<ExchangeHistoryResponse> list(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @AuthenticationPrincipal AuthUserPrincipal principal) {
        return historyService.listMine(principal.userId(), page, size);
    }
}
