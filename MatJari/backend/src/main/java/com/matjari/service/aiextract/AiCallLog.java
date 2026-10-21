package com.matjari.service.aiextract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * AI 호출 1번마다 토큰 수와 예상 비용을 ai_call_log(V26)에 남긴다(관리자 대시보드의 비용·남은 크레딧 추정용).
 * 단가는 설정(matjari.ai.price-*)이고, 입력이 long-prompt-tokens 를 넘는 호출은 긴 프롬프트 단가를 쓴다.
 * 기록 실패는 삼킨다(AI 읽기를 막지 않는다).
 */
@Slf4j
@Component
public class AiCallLog {

    private final JdbcTemplate jdbc;
    private final AiProperties properties;
    private final Clock clock;

    public AiCallLog(JdbcTemplate jdbc, AiProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    /** 마이크로 달러(정수): 토큰 × ($/100만 토큰). */
    static long costMicroUsd(long inputTokens, long outputTokens, AiProperties p) {
        boolean longPrompt = inputTokens > p.getLongPromptTokens();
        double in = longPrompt ? p.getPriceLongInputPerMtok() : p.getPriceInputPerMtok();
        double out = longPrompt ? p.getPriceLongOutputPerMtok() : p.getPriceOutputPerMtok();
        return Math.round(inputTokens * in + outputTokens * out);
    }

    public void record(String stage, String model, long inputTokens, long outputTokens) {
        try {
            jdbc.update("INSERT INTO ai_call_log (called_at, stage, model, input_tokens, output_tokens, cost_micro_usd) VALUES (?, ?, ?, ?, ?, ?)",
                    LocalDateTime.now(clock), stage.length() > 20 ? stage.substring(0, 20) : stage,
                    model.length() > 60 ? model.substring(0, 60) : model, inputTokens, outputTokens,
                    costMicroUsd(inputTokens, outputTokens, properties));
        } catch (RuntimeException e) {
            log.info("AI 호출 비용 기록 실패: {}", e.getClass().getSimpleName());
        }
    }
}
