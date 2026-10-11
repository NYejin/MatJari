package com.seatswap.repository;

import com.seatswap.domain.NotificationType;
import com.seatswap.dto.response.NotificationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 알림 조회 (읽기 전용, 잠금 없는 일반 SELECT). 알림 1건당 한 번의 조인(notification -> match -> 양쪽 티켓 -> 회차 -> 공연, 양쪽 users)으로
 * 닉네임·좌석·공연명을 읽어 N+1 이 없다. 수신자가 a측인지는 SQL 이 아니라 행 변환에서 가른다(양쪽을 항상 읽는다).
 * 개인정보 보호: users 에서는 nickname 만 읽는다(이메일 등은 SELECT 하지 않는다).
 * 접근 경로: idx_notification_user(user_id) 로 내 알림만 읽고(보조 인덱스 끝에 PK 가 붙어 id DESC 정렬이 인덱스 역순 스캔) 나머지는 PK 점조회.
 * 안 읽은 개수는 idx_notification_unread(unread_user_id, 생성 컬럼) 로 센다. 목록 COUNT 는 notification 만 센다
 * (match_id 가 NOT NULL + FK 라 INNER JOIN 으로 줄어드는 행이 없다).
 */
@Repository
public class NotificationQueryRepository {

    private static final String SELECT = """
            SELECT STRAIGHT_JOIN n.id, n.type, n.match_id, n.read_at, n.created_at,
                   m.status AS match_status, m.user_a_id,
                   ta.zone_label AS a_zone, ta.row_label AS a_row, ta.col_label AS a_col,
                   tb.zone_label AS b_zone, tb.row_label AS b_row, tb.col_label AS b_col,
                   p.title AS performance_title,
                   ua.nickname AS a_nickname, ub.nickname AS b_nickname
            FROM notification n
            JOIN exchange_match m        ON m.id = n.match_id
            JOIN ticket ta               ON ta.id = m.ticket_a_id
            JOIN performance_session psa ON psa.id = ta.performance_session_id
            JOIN performance p           ON p.id = psa.performance_id
            JOIN ticket tb               ON tb.id = m.ticket_b_id
            JOIN users ua                ON ua.id = m.user_a_id
            JOIN users ub                ON ub.id = m.user_b_id
            WHERE n.user_id = ?
            """;

    private final JdbcTemplate jdbc;

    public NotificationQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** id 내림차순 한 페이지. */
    public List<NotificationResponse> findPage(Long userId, boolean unreadOnly, int limit, long offset) {
        String sql = SELECT + (unreadOnly ? " AND n.read_at IS NULL" : "") + " ORDER BY n.id DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, i) -> {
            boolean mineIsA = userId.equals(rs.getLong("user_a_id"));
            String status = rs.getString("match_status");
            LocalDateTime readAt = rs.getObject("read_at", LocalDateTime.class);
            NotificationResponse.Seat a = new NotificationResponse.Seat(rs.getString("a_zone"), rs.getString("a_row"), rs.getString("a_col"));
            NotificationResponse.Seat b = new NotificationResponse.Seat(rs.getString("b_zone"), rs.getString("b_row"), rs.getString("b_col"));
            return new NotificationResponse(rs.getLong("id"), rs.getString("type"), rs.getLong("match_id"),
                    readAt != null, readAt, rs.getObject("created_at", LocalDateTime.class),
                    status, "CANCELED".equals(status) || "COMPLETED".equals(status),
                    mineIsA ? "SENT" : "RECEIVED",
                    mineIsA ? rs.getString("b_nickname") : rs.getString("a_nickname"),
                    rs.getString("performance_title"),
                    mineIsA ? a : b, mineIsA ? b : a);
        }, userId, limit, offset);
    }

    public long count(Long userId, boolean unreadOnly) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ?"
                + (unreadOnly ? " AND read_at IS NULL" : ""), Long.class, userId);
        return n == null ? 0 : n;
    }

    /** 안 읽은 개수(종류별). 3종 키를 0 으로 채워 돌려준다. 닫힌 매칭 알림도 센다. */
    public Map<NotificationType, Long> unreadCountsByType(Long userId) {
        Map<NotificationType, Long> result = new EnumMap<>(NotificationType.class);
        for (NotificationType t : NotificationType.values()) {
            result.put(t, 0L);
        }
        jdbc.query("SELECT type, COUNT(*) AS cnt FROM notification WHERE unread_user_id = ? GROUP BY type",
                rs -> {
                    result.put(NotificationType.valueOf(rs.getString("type")), rs.getLong("cnt"));
                }, userId);
        return result;
    }
}
