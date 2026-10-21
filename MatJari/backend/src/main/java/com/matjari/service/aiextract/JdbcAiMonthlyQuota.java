package com.matjari.service.aiextract;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DB 기반 월 횟수(ai_extract_usage, V24). 예약은 users 행 FOR UPDATE(첫 쿼리) → COUNT → INSERT 를 짧은 트랜잭션에서 하고
 * AI 호출 전에 커밋한다. 반환은 PK 로 refunded_at 만 채운다.
 */
@Component
public class JdbcAiMonthlyQuota implements AiMonthlyQuota {

    private static final String COUNT_SQL =
            "SELECT COUNT(*) FROM ai_extract_usage WHERE user_id = ? AND used_at >= ? AND refunded_at IS NULL";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcAiMonthlyQuota(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public int used(Long userId, LocalDateTime monthStart) {
        Integer n = jdbc.queryForObject(COUNT_SQL, Integer.class, userId, Timestamp.valueOf(monthStart));
        return n == null ? 0 : n;
    }

    @Override
    public long reserve(Long userId, String sourceKey, int limit, LocalDateTime monthStart, LocalDateTime now) {
        Long id = tx.execute(status -> {
            List<Long> locked = jdbc.queryForList(
                    "SELECT id FROM users WHERE id = ? AND withdrawn_at IS NULL FOR UPDATE", Long.class, userId);
            if (locked.isEmpty()) {
                throw new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다.");
            }
            if (used(userId, monthStart) >= limit) {
                return -1L;
            }
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO ai_extract_usage (user_id, source_key, used_at) VALUES (?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, userId);
                ps.setString(2, sourceKey);
                ps.setTimestamp(3, Timestamp.valueOf(now));
                return ps;
            }, keys);
            Number key = keys.getKey();
            return key == null ? -1L : key.longValue();
        });
        return id == null ? -1 : id;
    }

    @Override
    public void refund(long usageId, LocalDateTime now) {
        jdbc.update("UPDATE ai_extract_usage SET refunded_at = ? WHERE id = ? AND refunded_at IS NULL",
                Timestamp.valueOf(now), usageId);
    }
}
