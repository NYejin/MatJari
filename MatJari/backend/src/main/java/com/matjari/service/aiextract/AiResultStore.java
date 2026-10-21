package com.matjari.service.aiextract;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * AI 읽기 결과를 링크(source_key)별로 DB(ai_extract_cache, V23)에 보관한다. 메모리 캐시는 배포·재시작 때 비워지므로
 * 같은 링크를 며칠 뒤 다시 읽어도 AI 를 부르지 않게 한다(기본 168시간, matjari.ai.cache-ttl-hours).
 * 보관은 비용 절감용 보조 수단이라 DB 오류는 삼키고 AI 읽기를 그대로 진행한다.
 */
@Slf4j
@Component
public class AiResultStore {

    /** 저장할 때 함께 지우는 만료 행 수 상한(스케줄러 없이 조금씩 정리) */
    static final int PURGE_BATCH = 50;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiResultStore(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 만료되지 않은 결과. 없거나 읽기·해석에 실패하면 빈 값. */
    public Optional<DetailExtractionService.Response> find(String sourceKey) {
        try {
            List<String> rows = jdbc.queryForList(
                    "SELECT result FROM ai_extract_cache WHERE source_key = ? AND expires_at_ms > ?",
                    String.class, sourceKey, clock.millis());
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(rows.get(0), DetailExtractionService.Response.class));
        } catch (Exception e) {
            log.info("AI 결과 보관 읽기 실패: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** 결과를 저장(같은 링크는 덮어쓴다)하고 만료된 행을 조금 지운다. */
    public void save(String sourceKey, DetailExtractionService.Response response, long ttlMillis) {
        try {
            long now = clock.millis();
            jdbc.update("""
                    INSERT INTO ai_extract_cache (source_key, result, created_at, expires_at_ms) VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE result = VALUES(result), created_at = VALUES(created_at),
                                            expires_at_ms = VALUES(expires_at_ms)
                    """, sourceKey, objectMapper.writeValueAsString(response), LocalDateTime.now(clock), now + ttlMillis);
            jdbc.update("DELETE FROM ai_extract_cache WHERE expires_at_ms <= ? LIMIT " + PURGE_BATCH, now);
        } catch (Exception e) {
            log.info("AI 결과 보관 저장 실패: {}", e.getClass().getSimpleName());
        }
    }
}
