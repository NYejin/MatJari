package com.matjari.testsupport;

import com.matjari.dto.response.ExchangeMatchResponse;
import com.matjari.service.ExchangeMatchService;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 예약 제안 -> 상대 수락(V15)으로 RESERVED 를 만드는 테스트 도우미. 옛 "한 명이 reserve 하면 RESERVED" 흐름을 쓰던 통합 테스트가 같은 의도(예약된 매칭 준비)를 유지하게 한다.
 * 제안이 이미 걸려 있지 않은 CHATTING 매칭에서만 의미가 있으며, 이미 RESERVED 등이면 제안 응답(또는 오류)을 그대로 돌려준다.
 */
public final class ReserveFlow {

    private ReserveFlow() {
    }

    /** proposer 가 제안하고 상대가 수락한다. 수락 응답(상대 시점)을 돌려준다. */
    public static ExchangeMatchResponse reserve(ExchangeMatchService service, JdbcTemplate jdbc, long proposerId, long matchId) {
        ExchangeMatchResponse proposed = service.reserve(proposerId, matchId);
        if (!"CHATTING".equals(proposed.status()) || !"MINE".equals(proposed.reservationProposal())) {
            return proposed;
        }
        Long acceptor = jdbc.queryForObject("SELECT IF(user_a_id = ?, user_b_id, user_a_id) FROM exchange_match WHERE id = ?",
                Long.class, proposerId, matchId);
        return service.reserveAccept(acceptor, matchId);
    }
}
