package com.matjari.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 티켓·교환 희망 조건 사진 보관 정리 (V33). 채팅 사진 정리({@link ChatImageCleanupService})와 같은 구조다. 두 가지를 주기적으로 지운다.
 * <ol>
 *   <li><b>보관 기간이 지난 사진</b>: 티켓이 INACTIVE·EXCHANGED 가 되었거나 요청이 CLOSED·DELETED 가 된 지 {@code matjari.listing-image.retention-days}(기본 30일)가
 *       지난 사진. 닫힌 시각은 ticket.updated_at(상태 전이가 마지막 갱신), 요청은 deleted_at(없으면 updated_at)이다. 그 티켓·요청이 걸린 매칭에
 *       <b>처리되지 않은(OPEN) 신고</b>가 있으면 처리될 때까지 지우지 않는다(신고 근거). 파일을 먼저 지우고(없어도 성공) 성공하면 행의 deleted_at 을 PK 로 기록한다 —
 *       기록이 실패하면 다음 주기에 다시 한다.</li>
 *   <li><b>고아 파일</b>: 살아 있는 행이 가리키지 않는 파일(저장 직후 서버가 멈췄거나 사용자 삭제 뒤 파일 삭제가 실패한 경우). INSERT 커밋까지의 틈을
 *       지키려고 {@code grace-minutes}(기본 60분)보다 오래된 파일만 본다.</li>
 * </ol>
 * 티켓·요청·매칭 행은 잠그지 않는다(잠금 없는 일관 읽기, 갱신은 listing_image PK 한 행). 한 번에 {@code batch}(기본 200)건까지만 처리한다.
 * 이미지 바이트·경로는 로그에 남기지 않는다.
 */
@Slf4j
@Service
public class ListingImageCleanupService {

    /** 한 번 실행의 결과(테스트·로그용). */
    public record Result(int expiredDeleted, int orphanDeleted) {
    }

    private record Target(long id, String key) {
    }

    private static final int IN_CHUNK = 100;
    static final int ORPHAN_SCAN_LIMIT = 5_000;

    private static final String OPEN_REPORT_ON_TICKET = """
            AND NOT EXISTS (SELECT 1 FROM abuse_report ar JOIN exchange_match m ON m.id = ar.match_id
                            WHERE ar.status = 'OPEN' AND m.ticket_a_id = t.id)
            AND NOT EXISTS (SELECT 1 FROM abuse_report ar JOIN exchange_match m ON m.id = ar.match_id
                            WHERE ar.status = 'OPEN' AND m.ticket_b_id = t.id)
            """;

    private static final String OPEN_REPORT_ON_REQUEST = """
            AND NOT EXISTS (SELECT 1 FROM abuse_report ar JOIN exchange_match m ON m.id = ar.match_id
                            WHERE ar.status = 'OPEN' AND m.request_a_id = r.id)
            AND NOT EXISTS (SELECT 1 FROM abuse_report ar JOIN exchange_match m ON m.id = ar.match_id
                            WHERE ar.status = 'OPEN' AND m.request_b_id = r.id)
            """;

    private final JdbcTemplate jdbc;
    private final ListingImageStore store;
    private final Clock clock;
    private final Duration retention;
    private final Duration grace;
    private final int batch;

    public ListingImageCleanupService(JdbcTemplate jdbc, ListingImageStore store, Clock clock,
                                      @Value("${matjari.listing-image.retention-days:30}") long retentionDays,
                                      @Value("${matjari.listing-image.cleanup.grace-minutes:60}") long graceMinutes,
                                      @Value("${matjari.listing-image.cleanup.batch:200}") int batch) {
        this.jdbc = jdbc;
        this.store = store;
        this.clock = clock;
        this.retention = Duration.ofDays(Math.max(1, retentionDays));
        this.grace = Duration.ofMinutes(Math.max(1, graceMinutes));
        this.batch = Math.max(1, batch);
    }

    /** 두 정리를 차례로 실행한다. 한 단계가 실패해도 다른 단계는 계속하고 실패는 종류만 로그로 남긴다. */
    public Result cleanup() {
        int expired = 0;
        int orphans = 0;
        try {
            expired = deleteExpiredImages();
        } catch (RuntimeException e) {
            log.warn("Listing image cleanup (retention) failed: {}", e.getClass().getSimpleName());
        }
        try {
            orphans = deleteOrphanFiles();
        } catch (RuntimeException e) {
            log.warn("Listing image cleanup (orphans) failed: {}", e.getClass().getSimpleName());
        }
        if (expired > 0 || orphans > 0) {
            log.info("Listing image cleanup: expiredDeleted={}, orphanDeleted={}", expired, orphans);
        }
        return new Result(expired, orphans);
    }

    private int deleteExpiredImages() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minus(retention);
        List<Target> targets = jdbc.query(
                "SELECT i.id, i.image_key FROM listing_image i JOIN ticket t ON t.id = i.ticket_id"
                        + " WHERE i.deleted_at IS NULL AND t.status IN ('INACTIVE', 'EXCHANGED') AND t.updated_at < ? "
                        + OPEN_REPORT_ON_TICKET + " ORDER BY i.id LIMIT ?",
                (rs, n) -> new Target(rs.getLong(1), rs.getString(2)), cutoff, batch);
        List<Target> byRequest = jdbc.query(
                "SELECT i.id, i.image_key FROM listing_image i JOIN exchange_request r ON r.id = i.request_id"
                        + " WHERE i.deleted_at IS NULL AND r.status IN ('CLOSED', 'DELETED') AND COALESCE(r.deleted_at, r.updated_at) < ? "
                        + OPEN_REPORT_ON_REQUEST + " ORDER BY i.id LIMIT ?",
                (rs, n) -> new Target(rs.getLong(1), rs.getString(2)), cutoff, batch);
        int deleted = 0;
        for (Target t : concat(targets, byRequest)) {
            if (!ChatImageStore.isValidKey(t.key()) || !store.delete(t.key())) {
                continue;   // 이번엔 못 지웠다 -> 다음 주기에 다시 시도
            }
            jdbc.update("UPDATE listing_image SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL", now, t.id());
            deleted++;
        }
        return deleted;
    }

    private static List<Target> concat(List<Target> a, List<Target> b) {
        List<Target> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private int deleteOrphanFiles() {
        List<String> keys = store.listKeysOlderThan(Instant.now(clock).minus(grace), ORPHAN_SCAN_LIMIT);
        int deleted = 0;
        for (int from = 0; from < keys.size() && deleted < batch; from += IN_CHUNK) {
            List<String> chunk = keys.subList(from, Math.min(keys.size(), from + IN_CHUNK));
            Set<String> live = new HashSet<>(jdbc.queryForList(
                    "SELECT image_key FROM listing_image WHERE deleted_at IS NULL AND image_key IN ("
                            + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")",
                    String.class, chunk.toArray()));
            for (String key : chunk) {
                if (deleted >= batch) {
                    break;
                }
                if (!live.contains(key) && store.delete(key)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }
}
