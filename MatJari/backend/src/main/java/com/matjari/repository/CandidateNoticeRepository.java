package com.matjari.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 새 후보 알림(V29) 보조 JDBC. 모두 잠금 없는 SELECT 이거나 candidate_notice 의 INSERT IGNORE 하나다.
 * candidate_notice 는 이미 알린 (받는 요청, 후보 요청) 쌍이며 같은 쌍은 다시 알리지 않는다.
 */
@Repository
public class CandidateNoticeRepository {

    private final JdbcTemplate jdbc;

    public CandidateNoticeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 요청 R 이 다른 요청의 후보로 보일 수 있는 상태일 때만 소유자 id 를 돌려준다(아니면 null): 요청 OPEN, 티켓 ACTIVE, 예약 잠금 없음,
     * 공연 숨김 아님, 소유자 이용 정지 아님. 잠금 없는 일반 SELECT 한 번이다.
     */
    public Long findEligibleOwner(Long requestId, LocalDateTime now) {
        List<Long> owners = jdbc.query("""
                SELECT t.user_id
                FROM exchange_request r
                JOIN ticket t                ON t.id = r.ticket_id
                JOIN performance_session s   ON s.id = t.performance_session_id
                JOIN performance p           ON p.id = s.performance_id
                WHERE r.id = ? AND r.status = 'OPEN' AND t.status = 'ACTIVE' AND p.hidden_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM exchange_ticket_lock l WHERE l.ticket_id = t.id)
                  AND NOT EXISTS (SELECT 1 FROM user_sanction us WHERE us.user_id = t.user_id
                                  AND us.revoked_at IS NULL AND (us.ends_at IS NULL OR us.ends_at > ?))
                """, (rs, i) -> rs.getLong(1), requestId, now);
        return owners.isEmpty() ? null : owners.get(0);
    }

    /** 티켓 id -> 소유자 id (잠금 없는 SELECT). */
    public Map<Long, Long> ticketOwners(List<Long> ticketIds) {
        Map<Long, Long> result = new HashMap<>();
        if (ticketIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", Collections.nCopies(ticketIds.size(), "?"));
        jdbc.query("SELECT id, user_id FROM ticket WHERE id IN (" + placeholders + ")",
                rs -> {
                    result.put(rs.getLong("id"), rs.getLong("user_id"));
                }, ticketIds.toArray());
        return result;
    }

    /** (받는 요청, 후보 요청) 쌍을 기록한다. 새로 들어갔으면 1, 이미 있으면 0(INSERT IGNORE). */
    public int insertIfAbsent(Long recipientRequestId, Long candidateRequestId, LocalDateTime now) {
        return jdbc.update("INSERT IGNORE INTO candidate_notice (recipient_request_id, candidate_request_id, created_at) VALUES (?, ?, ?)",
                recipientRequestId, candidateRequestId, now);
    }
}
