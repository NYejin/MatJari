package com.matjari.repository;

import com.matjari.domain.NotificationType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 메일 알림(V29) 전용 JDBC 읽기·쓰기. users·매칭·티켓 행은 잠그지 않는다(잠금 없는 SELECT). 쓰기는 notification 의 emailed_at 을
 * id 목록({@code WHERE id IN (...)}, 500개씩)으로만 채우고(범위 UPDATE 금지) user_notification_setting 의 last_email_at 을 PK 한 행으로 upsert 한다.
 * 읽는 개인정보는 수신 이메일 주소뿐이고 채팅 본문·상대 닉네임·좌석은 읽지 않는다(공연 제목만).
 */
@Repository
public class NotificationMailRepository {

    public static final int BATCH = 500;

    /** 메일 대상 후보 알림 한 건. */
    public record Pending(long id, long userId, NotificationType type) {}

    /** 수신자(이메일·탈퇴 여부·알림 설정). 설정 행이 없으면 emailEnabled=true. */
    public record Recipient(String email, boolean withdrawn, boolean emailEnabled, LocalDateTime lastEmailAt) {}

    private final JdbcTemplate jdbc;

    public NotificationMailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 아직 메일 처리 전이고 안 읽은 알림(created_at 이 [floor, cutoff] 안). idx_notification_email_scan(emailed_at, created_at) 범위. */
    public List<Pending> findPending(LocalDateTime cutoff, LocalDateTime floor, int limit) {
        return jdbc.query("""
                SELECT n.id, n.user_id, n.type FROM notification n
                WHERE n.emailed_at IS NULL AND n.read_at IS NULL AND n.created_at <= ? AND n.created_at >= ?
                ORDER BY n.user_id, n.id LIMIT ?
                """, (rs, i) -> new Pending(rs.getLong("id"), rs.getLong("user_id"), NotificationType.valueOf(rs.getString("type"))),
                cutoff, floor, limit);
    }

    /** 수신자 정보. 사용자가 없으면 null. */
    public Recipient loadRecipient(Long userId) {
        List<Recipient> found = jdbc.query("""
                SELECT u.email, u.withdrawn_at, s.email_enabled, s.last_email_at
                FROM users u LEFT JOIN user_notification_setting s ON s.user_id = u.id
                WHERE u.id = ?
                """, (rs, i) -> new Recipient(rs.getString("email"), rs.getObject("withdrawn_at") != null,
                rs.getObject("email_enabled") == null || rs.getBoolean("email_enabled"),
                rs.getObject("last_email_at", LocalDateTime.class)), userId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** 이용 정지 중인가(활성 = revoked_at IS NULL AND (ends_at IS NULL OR ends_at > now)). */
    public boolean isSuspended(Long userId, LocalDateTime now) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM user_sanction WHERE user_id = ? AND revoked_at IS NULL "
                + "AND (ends_at IS NULL OR ends_at > ?)", Integer.class, userId, now);
        return n != null && n > 0;
    }

    /** ids 중 지금도 안 읽었고 메일 처리 전이며 created_at <= cutoff 인 것(발송 직전 재확인, 지연 동안 읽었으면 빠진다). */
    public List<Long> stillUnreadIds(List<Long> ids, LocalDateTime cutoff) {
        List<Long> result = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + BATCH));
            Object[] args = new Object[chunk.size() + 1];
            for (int i = 0; i < chunk.size(); i++) {
                args[i] = chunk.get(i);
            }
            args[chunk.size()] = cutoff;
            result.addAll(jdbc.query("SELECT id FROM notification WHERE id IN (" + placeholders(chunk.size())
                    + ") AND read_at IS NULL AND emailed_at IS NULL AND created_at <= ? ORDER BY id",
                    (rs, i) -> rs.getLong(1), args));
        }
        return result;
    }

    /** 알림 id -> 공연 제목(새 제안·새 후보 알림만 넘긴다). 공개 정보인 제목만 읽는다. */
    public Map<Long, String> performanceTitles(List<Long> notificationIds) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (int from = 0; from < notificationIds.size(); from += BATCH) {
            List<Long> chunk = notificationIds.subList(from, Math.min(notificationIds.size(), from + BATCH));
            jdbc.query("""
                    SELECT n.id, p.title
                    FROM notification n
                    LEFT JOIN exchange_match m   ON m.id = n.match_id
                    LEFT JOIN exchange_request r ON r.id = n.request_id
                    JOIN ticket t                ON t.id = COALESCE(m.ticket_a_id, r.ticket_id)
                    JOIN performance_session s   ON s.id = t.performance_session_id
                    JOIN performance p           ON p.id = s.performance_id
                    WHERE n.id IN (""" + placeholders(chunk.size()) + ")",
                    rs -> {
                        result.put(rs.getLong("id"), rs.getString("title"));
                    }, chunk.toArray());
        }
        return result;
    }

    /**
     * ids 의 emailed_at 을 채운다(id 목록으로만, 범위 UPDATE 금지). 이미 처리된 것과 cutoff 뒤에 되살아난(created_at > cutoff) 재사용 알림은 건드리지 않는다.
     */
    public void markEmailed(List<Long> ids, LocalDateTime now, LocalDateTime cutoff) {
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + BATCH));
            Object[] args = new Object[chunk.size() + 2];
            args[0] = now;
            for (int i = 0; i < chunk.size(); i++) {
                args[i + 1] = chunk.get(i);
            }
            args[chunk.size() + 1] = cutoff;
            jdbc.update("UPDATE notification SET emailed_at = ? WHERE id IN (" + placeholders(chunk.size())
                    + ") AND emailed_at IS NULL AND created_at <= ?", args);
        }
    }

    /** 마지막 메일 시각 기록(PK 한 행 upsert, 설정 행이 없으면 켜짐으로 만든다). */
    public void touchLastEmail(Long userId, LocalDateTime now) {
        jdbc.update("INSERT INTO user_notification_setting (user_id, email_enabled, last_email_at, updated_at) VALUES (?, 1, ?, ?) "
                + "ON DUPLICATE KEY UPDATE last_email_at = VALUES(last_email_at)", userId, now, now);
    }

    private static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
