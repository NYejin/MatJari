package com.matjari.service.aiextract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * API 크레딧을 다 썼는지 판단한다(사용자 지시 2026-10-22: 다 쓰면 자동으로 직접 입력만).
 * ①추정 잔액(설정 기준 잔액 − 그 뒤 ai_call_log 비용, 대시보드와 같은 계산)이 0 이하이거나
 * ②Anthropic 이 크레딧 부족으로 거절한 적이 있으면(재시작·충전 전까지) 소진으로 본다. 추정 잔액은 1분간 캐시한다.
 * 기준 잔액이 0 이면 ①은 보지 않는다.
 */
@Component
public class AiCredit {

    private static final Logger log = LoggerFactory.getLogger(AiCredit.class);
    private static final long CACHE_MS = 60_000;

    private final JdbcTemplate jdbc;
    private final AiProperties properties;
    private final Clock clock;
    private final AtomicBoolean rejected = new AtomicBoolean();
    private volatile long cachedAt = Long.MIN_VALUE;
    private volatile boolean cachedExhausted;

    public AiCredit(JdbcTemplate jdbc, AiProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean exhausted() {
        if (rejected.get()) {
            return true;
        }
        double baseline = properties.getCreditBaselineUsd();
        if (baseline <= 0) {
            return false;
        }
        long now = clock.millis();
        if (now - cachedAt < CACHE_MS) {
            return cachedExhausted;
        }
        boolean result;
        try {
            LocalDateTime from = LocalDateTime.parse(properties.getCreditBaselineAt());
            Long micro = jdbc.queryForObject("SELECT COALESCE(SUM(cost_micro_usd), 0) FROM ai_call_log WHERE called_at >= ?",
                    Long.class, Timestamp.valueOf(from));
            result = baseline - (micro == null ? 0 : micro) / 1_000_000.0 <= 0;
        } catch (RuntimeException e) {
            result = false; // 계산할 수 없으면 막지 않는다(호출 실패는 기존 경로가 처리)
        }
        cachedExhausted = result;
        cachedAt = now;
        return result;
    }

    /** Anthropic 이 크레딧 부족으로 거절했을 때(AnthropicDetailExtractor). 재시작하거나 충전 후 설정을 바꾸면 풀린다. */
    public void markRejected() {
        if (rejected.compareAndSet(false, true)) {
            log.warn("AI 크레딧 부족 응답: 이후 AI 읽기를 끄고 직접 입력만 보인다(충전 후 재시작 필요)");
        }
    }
}
