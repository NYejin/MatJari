package com.seatswap.repository;

import com.seatswap.dto.response.ExchangeMatchResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 내 매칭 조회 (읽기 전용, 잠금 없음). 매칭 1건당 한 번의 조인으로 양쪽 티켓 좌석·회차·추가금 스냅샷·요청 삭제 여부·닉네임을 모두 읽어 N+1이 없다.
 * 호출자가 a측/b측인지는 SQL이 아니라 {@link Row#toResponse}가 가른다(양쪽을 항상 읽으므로 CASE 가 필요 없다).
 * 티켓 교환 완료(EXCHANGED) 여부는 이미 조인한 ta/tb 의 status 로, '다른 매칭에서 예약 중'은 exchange_ticket_lock PK 점조회 EXISTS 서브쿼리로 계산한다(조인 폭증 없음).
 * 접근 경로: 내 사용자 id -> idx_exchange_match_user_a / user_b (ALL 이면 index_merge) -> 매칭 행 -> 나머지는 PK 점조회.
 * 전제: 목록은 INNER JOIN 8개, COUNT 는 exchange_match 만 센다. FK 때문에 고아 행이 없어 두 결과가 같다(users 익명화·티켓 삭제를 도입하면 LEFT JOIN 또는 COUNT 에도 같은 조인 필요).
 * STRAIGHT_JOIN: 소규모 테이블(회차 등)에서 옵티마이저가 작은 테이블부터 해시 조인으로 시작하는 것을 막고 매칭 행(사용자 인덱스)에서 시작하도록 고정한다.
 * unreadChatCount 는 호출자 기준 상관 서브쿼리다: 상대가 보낸(sender_id <> 나) 메시지 중 내 읽음 포인터(chat_read_state, 없으면 0)보다 큰 id 의 수.
 * 매칭 상태와 무관하게(닫힌 매칭 포함) 센다. chat_message 는 idx_chat_message_match(match_id, id)로 범위 스캔, 포인터는 PK 점조회다. SELECT 절의 자리표시자 2개(나, 나)가 WHERE 보다 먼저 바인딩된다.
 * 정렬은 updated_at DESC, id DESC (사용자당 매칭 수는 작아 filesort 로 충분하며 전용 인덱스는 만들지 않았다. README EXPLAIN 참고).
 */
@Repository
public class ExchangeMatchQueryRepository {

    /** 목록 role 필터. */
    public enum Role { SENT, RECEIVED, ALL }

    private static final String SELECT = """
            SELECT STRAIGHT_JOIN m.id, m.status, m.user_a_id, m.user_b_id,
                   m.request_a_id, m.request_b_id, m.ticket_a_id, m.ticket_b_id,
                   m.reserved_by_id, m.reserved_at, m.a_completed_at, m.b_completed_at, m.canceled_by_id, m.canceled_at, m.created_at, m.updated_at,
                   ta.zone_label AS a_zone, ta.row_label AS a_row, ta.col_label AS a_col,
                   psa.id AS a_session_id, psa.starts_at AS a_starts_at,
                   tb.zone_label AS b_zone, tb.row_label AS b_row, tb.col_label AS b_col,
                   psb.id AS b_session_id, psb.starts_at AS b_starts_at,
                   m.a_extra_type, m.a_extra_amount, m.b_extra_type, m.b_extra_amount,
                   ra.status AS a_request_status, rb.status AS b_request_status,
                   ta.status AS a_ticket_status, tb.status AS b_ticket_status,
                   EXISTS (SELECT 1 FROM exchange_ticket_lock la WHERE la.ticket_id = ta.id AND la.match_id <> m.id) AS a_reserved_elsewhere,
                   EXISTS (SELECT 1 FROM exchange_ticket_lock lb WHERE lb.ticket_id = tb.id AND lb.match_id <> m.id) AS b_reserved_elsewhere,
                   ua.nickname AS a_nickname, ub.nickname AS b_nickname,
                   (SELECT COUNT(*) FROM chat_message c WHERE c.match_id = m.id AND c.sender_id <> ?
                       AND c.id > COALESCE((SELECT r.last_read_message_id FROM chat_read_state r WHERE r.match_id = m.id AND r.user_id = ?), 0)) AS unread_chat_count
            FROM exchange_match m
            JOIN ticket ta               ON ta.id = m.ticket_a_id
            JOIN performance_session psa ON psa.id = ta.performance_session_id
            JOIN ticket tb               ON tb.id = m.ticket_b_id
            JOIN performance_session psb ON psb.id = tb.performance_session_id
            JOIN exchange_request ra     ON ra.id = m.request_a_id
            JOIN exchange_request rb     ON rb.id = m.request_b_id
            JOIN users ua                ON ua.id = m.user_a_id
            JOIN users ub                ON ub.id = m.user_b_id
            """;

    private static final String ORDER = " ORDER BY m.updated_at DESC, m.id DESC LIMIT ? OFFSET ?";

    private final JdbcTemplate jdbc;

    public ExchangeMatchQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 조회 전용 값. 양쪽(a/b)을 그대로 담고 호출자 기준 변환은 {@link #toResponse}가 한다. */
    public record Row(Long id, String status, Long userAId, Long userBId,
                      Long requestAId, Long requestBId, Long ticketAId, Long ticketBId,
                      Long reservedById, LocalDateTime reservedAt, boolean aAccepted, boolean bAccepted, Long canceledById, LocalDateTime canceledAt,
                      LocalDateTime createdAt, LocalDateTime updatedAt,
                      ExchangeMatchResponse.Seat aSeat, ExchangeMatchResponse.Seat bSeat,
                      String aExtraType, Integer aExtraAmount, String bExtraType, Integer bExtraAmount,
                      boolean aRequestDeleted, boolean bRequestDeleted,
                      boolean aTicketExchanged, boolean bTicketExchanged,
                      boolean aReservedElsewhere, boolean bReservedElsewhere,
                      String aNickname, String bNickname, int unreadChatCount) {

        public ExchangeMatchResponse toResponse(Long userId) {
            boolean mineIsA = userId.equals(userAId);
            String canceledBy = null;
            if ("CANCELED".equals(status)) {
                canceledBy = canceledById == null ? "SYSTEM" : userId.equals(canceledById) ? "ME" : "COUNTERPART";
            }
            String reservedBy = null;
            if ("RESERVED".equals(status) && reservedById != null) {
                reservedBy = userId.equals(reservedById) ? "ME" : "COUNTERPART";
            }
            return new ExchangeMatchResponse(
                    id, status, mineIsA ? "A" : "B", mineIsA ? "SENT" : "RECEIVED",
                    mineIsA ? requestAId : requestBId, mineIsA ? ticketAId : ticketBId, mineIsA ? aSeat : bSeat,
                    mineIsA ? requestBId : requestAId, mineIsA ? ticketBId : ticketAId, mineIsA ? bSeat : aSeat,
                    mineIsA ? bNickname : aNickname,
                    mineIsA ? aExtraType : bExtraType, mineIsA ? aExtraAmount : bExtraAmount,
                    mineIsA ? bExtraType : aExtraType, mineIsA ? bExtraAmount : aExtraAmount,
                    mineIsA ? aRequestDeleted : bRequestDeleted, mineIsA ? bRequestDeleted : aRequestDeleted,
                    mineIsA ? aTicketExchanged : bTicketExchanged, mineIsA ? bTicketExchanged : aTicketExchanged,
                    mineIsA ? aReservedElsewhere : bReservedElsewhere, mineIsA ? bReservedElsewhere : aReservedElsewhere,
                    reservedBy, reservedAt, mineIsA ? aAccepted : bAccepted, mineIsA ? bAccepted : aAccepted,
                    canceledBy, canceledAt, createdAt, updatedAt, unreadChatCount);
        }
    }

    /** 내가 참여한 매칭 1건. 비참여자·없는 매칭은 빈 결과(호출자가 404 로 합친다). */
    public Optional<Row> findOne(Long matchId, Long userId) {
        List<Row> rows = jdbc.query(SELECT + " WHERE m.id = ? AND (m.user_a_id = ? OR m.user_b_id = ?)",
                (rs, i) -> map(rs), userId, userId, matchId, userId, userId);
        return rows.stream().findFirst();
    }

    /** @param statuses 비어 있으면 상태 필터 없음. 값은 호출자가 enum 으로 검증한 이름이다. */
    public List<Row> findMine(Long userId, Role role, List<String> statuses, int limit, long offset) {
        List<Object> params = new ArrayList<>();
        params.add(userId);   // SELECT 절의 안 읽은 채팅 수 서브쿼리 (sender_id <> ?, r.user_id = ?)
        params.add(userId);
        String where = where(userId, role, statuses, params);
        params.add(limit);
        params.add(offset);
        return jdbc.query(SELECT + where + ORDER, (rs, i) -> map(rs), params.toArray());
    }

    public long countMine(Long userId, Role role, List<String> statuses) {
        List<Object> params = new ArrayList<>();
        String where = where(userId, role, statuses, params);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM exchange_match m" + where, Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    /** 현재 목록 SQL(필터 포함). 실행계획 확인용. */
    public static String listSql(Role role, int statusCount) {
        return SELECT + whereSql(role, statusCount) + ORDER;
    }

    private static String where(Long userId, Role role, List<String> statuses, List<Object> params) {
        params.add(userId);
        if (role == Role.ALL) {
            params.add(userId);
        }
        params.addAll(statuses);
        return whereSql(role, statuses.size());
    }

    private static String whereSql(Role role, int statusCount) {
        StringBuilder sb = new StringBuilder(switch (role) {
            case SENT -> " WHERE m.user_a_id = ?";
            case RECEIVED -> " WHERE m.user_b_id = ?";
            case ALL -> " WHERE (m.user_a_id = ? OR m.user_b_id = ?)";
        });
        if (statusCount > 0) {
            sb.append(" AND m.status IN (").append(String.join(",", Collections.nCopies(statusCount, "?"))).append(")");
        }
        return sb.toString();
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getLong("id"), rs.getString("status"), rs.getLong("user_a_id"), rs.getLong("user_b_id"),
                rs.getLong("request_a_id"), rs.getLong("request_b_id"), rs.getLong("ticket_a_id"), rs.getLong("ticket_b_id"),
                (Long) rs.getObject("reserved_by_id"), rs.getObject("reserved_at", LocalDateTime.class),
                rs.getObject("a_completed_at") != null, rs.getObject("b_completed_at") != null,
                (Long) rs.getObject("canceled_by_id"), rs.getObject("canceled_at", LocalDateTime.class),
                rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class),
                new ExchangeMatchResponse.Seat(rs.getString("a_zone"), rs.getString("a_row"), rs.getString("a_col"),
                        rs.getLong("a_session_id"), rs.getObject("a_starts_at", LocalDateTime.class)),
                new ExchangeMatchResponse.Seat(rs.getString("b_zone"), rs.getString("b_row"), rs.getString("b_col"),
                        rs.getLong("b_session_id"), rs.getObject("b_starts_at", LocalDateTime.class)),
                rs.getString("a_extra_type"), (Integer) rs.getObject("a_extra_amount"),
                rs.getString("b_extra_type"), (Integer) rs.getObject("b_extra_amount"),
                "DELETED".equals(rs.getString("a_request_status")), "DELETED".equals(rs.getString("b_request_status")),
                "EXCHANGED".equals(rs.getString("a_ticket_status")), "EXCHANGED".equals(rs.getString("b_ticket_status")),
                rs.getBoolean("a_reserved_elsewhere"), rs.getBoolean("b_reserved_elsewhere"),
                rs.getString("a_nickname"), rs.getString("b_nickname"), rs.getInt("unread_chat_count"));
    }
}
