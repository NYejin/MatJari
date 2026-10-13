package com.matjari.controller;

import com.matjari.dto.response.BlockDtos.BlockItem;
import com.matjari.dto.response.BlockDtos.BlockResult;
import com.matjari.dto.response.PageResponse;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.BlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 사용자 차단 API (로그인 필요). 차단은 매칭 화면에서만 시작한다(임의 userId 로 차단하는 경로는 없다). */
@RestController
@RequiredArgsConstructor
public class BlockController {

    private final BlockService blockService;

    /** 매칭 상대 차단 -> 200 (멱등). 비참여자·없는 매칭 404, 상한 422 BLOCK_LIMIT_REACHED. */
    @PostMapping("/api/exchange/matches/{matchId}/block")
    public BlockResult block(@PathVariable Long matchId, @AuthenticationPrincipal AuthUserPrincipal principal) {
        return blockService.blockFromMatch(principal.userId(), matchId);
    }

    @GetMapping("/api/blocks")
    public PageResponse<BlockItem> list(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size,
                                        @AuthenticationPrincipal AuthUserPrincipal principal) {
        return blockService.listMine(principal.userId(), page, size);
    }

    /** 차단 해제 -> 204. 남의 것·없는 것은 404. */
    @DeleteMapping("/api/blocks/{id}")
    public ResponseEntity<Void> unblock(@PathVariable Long id, @AuthenticationPrincipal AuthUserPrincipal principal) {
        blockService.unblock(principal.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
