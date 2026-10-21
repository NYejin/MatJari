package com.matjari.service.aiextract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * 구역이 많은 공연장(돔 경기장 등)은 같은 공연장에 이미 등록된 공연의 구역 이름을 AI 1차 읽기에 참고로 준다(사용자 결정 2026-10-21).
 * 사용자가 확인하고 등록한 이름이라 번호 누락·오독을 줄인다. 공연마다 무대 배치가 달라 이름은 참고만 하고 결과에 그대로 넣지 않는다.
 * 공연장 이름은 텍스트라 공백·대소문자·전각을 무시한 정확 일치로만 찾는다. 실패하면 빈 목록(참고 없이 읽는다).
 */
@Slf4j
@Component
public class VenueZoneHints {

    /** 참고로 줄 이름 수 상한(프롬프트 길이 보호). */
    static final int MAX_NAMES = 300;

    private final JdbcTemplate jdbc;
    private final AiProperties properties;

    public VenueZoneHints(JdbcTemplate jdbc, AiProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /** 같은 공연장에서 구역이 venue-hint-min-zones 개 이상인 가장 최근 공연의 구역 이름(등록 순서). 없으면 빈 목록. */
    public List<String> namesFor(String venue) {
        String key = venueKey(venue);
        if (key.isEmpty()) {
            return List.of();
        }
        try {
            // 공연 수가 많지 않아 venue_name 함수 비교(전체 훑기)를 쓴다. 숨긴 공연은 참고하지 않는다.
            List<Long> ids = jdbc.queryForList("""
                    SELECT p.id FROM performance p
                    WHERE p.hidden_at IS NULL
                      AND LOWER(REPLACE(REPLACE(p.venue_name, ' ', ''), '　', '')) = ?
                      AND (SELECT COUNT(*) FROM performance_zone z WHERE z.performance_id = p.id) >= ?
                    ORDER BY p.created_at DESC, p.id DESC LIMIT 1
                    """, Long.class, key, properties.getVenueHintMinZones());
            if (ids.isEmpty()) {
                return List.of();
            }
            return jdbc.queryForList(
                    "SELECT name FROM performance_zone WHERE performance_id = ? ORDER BY sort_order, id LIMIT " + MAX_NAMES,
                    String.class, ids.get(0));
        } catch (RuntimeException e) {
            log.info("같은 공연장 구역 참고 조회 실패: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** NFKC → 공백 제거 → 소문자. DB 쪽 비교(LOWER(REPLACE(...)))와 같은 기준(전각 영숫자는 NFKC 로 맞춘다). */
    static String venueKey(String venue) {
        if (venue == null) {
            return "";
        }
        return Normalizer.normalize(venue, Normalizer.Form.NFKC).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
