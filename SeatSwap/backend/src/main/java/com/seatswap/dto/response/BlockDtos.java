package com.seatswap.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/** 사용자 차단 API 응답. 상대 id 는 노출하지 않고 닉네임만 보여준다. */
public final class BlockDtos {

    private BlockDtos() {}

    /** POST /api/exchange/matches/{matchId}/block 결과. alreadyBlocked 이면 canceledMatches 는 0. */
    public record BlockResult(Long blockId, String blockedNickname, int canceledMatches, boolean alreadyBlocked) {}

    /** GET /api/blocks 항목. */
    public record BlockItem(Long id, String blockedNickname,
                            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND)
                            LocalDateTime createdAt) {}
}
