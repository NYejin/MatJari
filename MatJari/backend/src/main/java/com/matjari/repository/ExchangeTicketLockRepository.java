package com.matjari.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 예약 잠금(exchange_ticket_lock, PK = ticket_id). 한 티켓은 동시에 한 매칭에서만 예약된다(PK 충돌 = 이미 예약됨).
 * 서비스 트랜잭션에 그대로 참여한다(같은 DataSource 연결). 호출 전에 매칭 행이 flush 되어 있어야 한다(FK).
 */
@Repository
public class ExchangeTicketLockRepository {

    private final JdbcTemplate jdbc;

    public ExchangeTicketLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** PK 충돌이면 DuplicateKeyException (이미 다른 매칭에서 예약됨). */
    public void insert(Long ticketId, Long matchId, LocalDateTime now) {
        jdbc.update("INSERT INTO exchange_ticket_lock (ticket_id, match_id, created_at) VALUES (?, ?, ?)",
                ticketId, matchId, now);
    }

    public int deleteByMatchId(Long matchId) {
        return jdbc.update("DELETE FROM exchange_ticket_lock WHERE match_id = ?", matchId);
    }

    public boolean existsByTicketId(Long ticketId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM exchange_ticket_lock WHERE ticket_id = ?",
                Integer.class, ticketId);
        return n != null && n > 0;
    }

    /** 주어진 티켓 중 하나라도 잠겨 있는가 (이 매칭 자신의 잠금은 호출 전에 멱등 경로로 걸러진다). */
    public boolean existsAnyByTicketIds(Collection<Long> ticketIds) {
        if (ticketIds.isEmpty()) {
            return false;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ticketIds.size(), "?"));
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM exchange_ticket_lock WHERE ticket_id IN (" + placeholders + ")",
                Integer.class, ticketIds.toArray());
        return n != null && n > 0;
    }

    public long countByMatchId(Long matchId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM exchange_ticket_lock WHERE match_id = ?", Long.class, matchId);
        return n == null ? 0 : n;
    }
}
