package com.matjari.service;

import com.matjari.service.aiextract.AiProperties;
import com.matjari.service.aiextract.AiUsageLimiter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자 운영 대시보드(GET /api/admin/dashboard): 한 번의 요청으로 수치·최근 14일 추이·처리 대기열·최근 목록·알림을 모은다.
 * 모두 읽기 전용 집계(잠금 없음)이고 시각은 KST 현지 시각(DATETIME) 기준이다. 개인 정보는 닉네임·회원 번호만 담는다.
 */
@Service
public class AdminDashboardService {

    static final int DAYS = 14;
    static final int LIST = 8;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AiUsageLimiter aiLimiter;
    private final AiProperties aiProperties;

    public AdminDashboardService(JdbcTemplate jdbc, Clock clock, AiUsageLimiter aiLimiter, AiProperties aiProperties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.aiLimiter = aiLimiter;
        this.aiProperties = aiProperties;
    }

    public record Daily(LocalDate date, long signups, long tickets, long matches, long completed, long messages,
                        long reports, long aiCalls) {}

    public record Alert(String level, String text, String link) {}

    public record Response(LocalDateTime generatedAt, Map<String, Map<String, Object>> kpis, List<Daily> daily,
                           Map<String, Long> openReportReasons, Map<String, Long> matchStatus,
                           List<Map<String, Object>> topPerformances, List<Map<String, Object>> upcomingSessions,
                           List<Map<String, Object>> openReports, List<Map<String, Object>> pendingClaims,
                           List<Map<String, Object>> pendingProposals, List<Map<String, Object>> recentActions,
                           List<Map<String, Object>> recentUsers, List<Alert> alerts) {}

    @Transactional(readOnly = true)
    public Response dashboard() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime today = now.toLocalDate().atStartOfDay();
        LocalDateTime week = today.minusDays(6);
        LocalDateTime month = today.withDayOfMonth(1);
        Timestamp t0 = Timestamp.valueOf(today);
        Timestamp t7 = Timestamp.valueOf(week);
        Timestamp tm = Timestamp.valueOf(month);
        Timestamp tn = Timestamp.valueOf(now);

        Map<String, Map<String, Object>> k = new LinkedHashMap<>();
        k.put("users", row("""
                SELECT COUNT(*) total,
                       SUM(withdrawn_at IS NULL) active,
                       SUM(withdrawn_at IS NOT NULL) withdrawn,
                       SUM(role = 'ADMIN' AND withdrawn_at IS NULL) admins,
                       SUM(created_at >= ?) newToday,
                       SUM(created_at >= ?) new7d
                FROM users""", t0, t7));
        k.get("users").put("suspended", count("""
                SELECT COUNT(DISTINCT user_id) FROM user_sanction
                WHERE revoked_at IS NULL AND (ends_at IS NULL OR ends_at > ?)""", tn));
        k.put("performances", row("""
                SELECT COUNT(*) total, SUM(hidden_at IS NOT NULL) hidden,
                       SUM(EXISTS (SELECT 1 FROM performance_zone z WHERE z.performance_id = p.id)) withZones,
                       SUM(EXISTS (SELECT 1 FROM performance_session s WHERE s.performance_id = p.id AND s.starts_at >= ?)) upcoming,
                       SUM(created_at >= ?) new7d
                FROM performance p""", t0, t7));
        k.put("tickets", row("""
                SELECT SUM(status = 'ACTIVE') active, SUM(status = 'EXCHANGED') exchanged, SUM(status = 'INACTIVE') inactive,
                       SUM(created_at >= ?) newToday, SUM(created_at >= ?) new7d
                FROM ticket""", t0, t7));
        k.put("requests", row("""
                SELECT SUM(status = 'OPEN') open, SUM(status = 'CLOSED') closed, SUM(status = 'DELETED') deleted
                FROM exchange_request"""));
        k.put("matches", row("""
                SELECT SUM(status = 'CHATTING') chatting, SUM(status = 'RESERVED') reserved,
                       SUM(status = 'COMPLETED') completed, SUM(status = 'CANCELED') canceled,
                       SUM(created_at >= ?) createdToday, SUM(created_at >= ?) created7d,
                       SUM(status = 'COMPLETED' AND updated_at >= ?) completed7d,
                       SUM(status = 'CANCELED' AND updated_at >= ?) canceled7d
                FROM exchange_match""", t0, t7, t7, t7));
        k.put("chat", row("""
                SELECT SUM(created_at >= ?) today, SUM(created_at >= ?) last7d, COUNT(DISTINCT IF(created_at >= ?, match_id, NULL)) activeMatches7d
                FROM chat_message WHERE created_at >= ?""", t0, t7, t7, t7));
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("openReports", count("SELECT COUNT(*) FROM abuse_report WHERE status = 'OPEN'"));
        q.put("oldestOpenReportAt", one("SELECT MIN(created_at) FROM abuse_report WHERE status = 'OPEN'"));
        q.put("pendingClaims", count("SELECT COUNT(*) FROM seat_claim WHERE status = 'PENDING'"));
        q.put("oldestPendingClaimAt", one("SELECT MIN(created_at) FROM seat_claim WHERE status = 'PENDING'"));
        q.put("pendingProposals", count("SELECT COUNT(*) FROM performance_edit_proposal WHERE status = 'PENDING'"));
        q.put("oldestPendingProposalAt", one("SELECT MIN(created_at) FROM performance_edit_proposal WHERE status = 'PENDING'"));
        q.put("reports7d", count("SELECT COUNT(*) FROM abuse_report WHERE created_at >= ?", t7));
        q.put("blocksTotal", count("SELECT COUNT(*) FROM user_block"));
        q.put("blocks7d", count("SELECT COUNT(*) FROM user_block WHERE created_at >= ?", t7));
        q.put("actions7d", count("SELECT COUNT(*) FROM admin_action_log WHERE created_at >= ?", t7));
        k.put("queues", q);
        Map<String, Object> ai = row("""
                SELECT SUM(refunded_at IS NULL) usedMonth, SUM(refunded_at IS NOT NULL) refundedMonth,
                       SUM(refunded_at IS NULL AND used_at >= ?) usedToday, COUNT(DISTINCT user_id) usersMonth
                FROM ai_extract_usage WHERE used_at >= ?""", t0, tm);
        ai.put("globalToday", aiLimiter.globalUsedToday());
        ai.put("dailyGlobalLimit", aiProperties.getDailyGlobal());
        ai.put("monthlyPerUser", aiProperties.getMonthlyPerUser());
        ai.put("cacheEntries", count("SELECT COUNT(*) FROM ai_extract_cache WHERE expires_at_ms > ?", clock.millis()));
        ai.put("usersAtLimit", count("""
                SELECT COUNT(*) FROM (SELECT user_id FROM ai_extract_usage WHERE used_at >= ? AND refunded_at IS NULL
                GROUP BY user_id HAVING COUNT(*) >= ?) x""", tm, aiProperties.getMonthlyPerUser()));
        k.put("ai", ai);

        List<Daily> daily = daily(week.minusDays(DAYS - 7), now.toLocalDate());

        Map<String, Long> reasons = new LinkedHashMap<>();
        jdbc.query("SELECT reason, COUNT(*) n FROM abuse_report WHERE status = 'OPEN' GROUP BY reason ORDER BY n DESC",
                rs -> { reasons.put(rs.getString(1), rs.getLong(2)); });
        Map<String, Long> matchStatus = new LinkedHashMap<>();
        jdbc.query("SELECT status, COUNT(*) FROM exchange_match GROUP BY status",
                rs -> { matchStatus.put(rs.getString(1), rs.getLong(2)); });

        List<Map<String, Object>> top = list("""
                SELECT p.id, p.title, p.venue_name venueName, p.hidden_at IS NOT NULL hidden,
                       COUNT(DISTINCT t.id) activeTickets,
                       COUNT(DISTINCT r.id) openRequests,
                       (SELECT MIN(s2.starts_at) FROM performance_session s2 WHERE s2.performance_id = p.id AND s2.starts_at >= ?) nextSession,
                       (SELECT COUNT(*) FROM performance_zone z WHERE z.performance_id = p.id) zones
                FROM performance p
                JOIN performance_session s ON s.performance_id = p.id
                JOIN ticket t ON t.performance_session_id = s.id AND t.status = 'ACTIVE'
                LEFT JOIN exchange_request r ON r.ticket_id = t.id AND r.status = 'OPEN'
                GROUP BY p.id ORDER BY activeTickets DESC, p.id DESC LIMIT ?""", tn, LIST);
        List<Map<String, Object>> upcoming = list("""
                SELECT s.id sessionId, s.starts_at startsAt, p.id performanceId, p.title,
                       (SELECT COUNT(*) FROM ticket t WHERE t.performance_session_id = s.id AND t.status = 'ACTIVE') activeTickets
                FROM performance_session s JOIN performance p ON p.id = s.performance_id
                WHERE s.starts_at >= ? AND s.starts_at < ? AND p.hidden_at IS NULL
                ORDER BY s.starts_at LIMIT ?""", tn, Timestamp.valueOf(today.plusDays(8)), LIST);
        List<Map<String, Object>> openReports = list("""
                SELECT r.id, r.reason, r.created_at createdAt, a.nickname reporter, b.id reportedId, b.nickname reported,
                       (SELECT COUNT(*) FROM abuse_report x WHERE x.reported_id = r.reported_id) reportedTotal
                FROM abuse_report r JOIN users a ON a.id = r.reporter_id JOIN users b ON b.id = r.reported_id
                WHERE r.status = 'OPEN' ORDER BY r.created_at LIMIT ?""", LIST);
        List<Map<String, Object>> claims = list("""
                SELECT c.id, c.created_at createdAt, u.nickname claimant, p.title, s.starts_at startsAt,
                       c.zone_label zone, c.row_label rowLabel, c.col_label colLabel
                FROM seat_claim c JOIN users u ON u.id = c.claimant_id
                JOIN performance_session s ON s.id = c.performance_session_id JOIN performance p ON p.id = s.performance_id
                WHERE c.status = 'PENDING' ORDER BY c.created_at LIMIT ?""", LIST);
        List<Map<String, Object>> proposals = list("""
                SELECT e.id, e.created_at createdAt, u.nickname proposer, p.id performanceId, p.title,
                       e.new_title newTitle, e.new_venue_name newVenueName, e.session_changes IS NOT NULL hasSessionChanges
                FROM performance_edit_proposal e JOIN users u ON u.id = e.proposer_id JOIN performance p ON p.id = e.performance_id
                WHERE e.status = 'PENDING' ORDER BY e.created_at LIMIT ?""", LIST);
        List<Map<String, Object>> actions = list("""
                SELECT l.id, l.created_at createdAt, l.action, l.target_type targetType, l.target_id targetId, l.reason,
                       u.nickname admin
                FROM admin_action_log l JOIN users u ON u.id = l.admin_id ORDER BY l.created_at DESC, l.id DESC LIMIT ?""", LIST);
        List<Map<String, Object>> users = list("""
                SELECT u.id, u.nickname, u.created_at createdAt, u.password_login_enabled = 0 social,
                       (SELECT COUNT(*) FROM ticket t WHERE t.user_id = u.id AND t.status = 'ACTIVE') activeTickets
                FROM users u WHERE u.withdrawn_at IS NULL ORDER BY u.created_at DESC, u.id DESC LIMIT ?""", LIST);

        return new Response(now, k, daily, reasons, matchStatus, top, upcoming, openReports, claims, proposals,
                actions, users, alerts(k, now));
    }

    private List<Daily> daily(LocalDateTime from, LocalDate to) {
        Timestamp f = Timestamp.valueOf(from);
        Map<LocalDate, long[]> m = new LinkedHashMap<>();
        for (LocalDate d = from.toLocalDate(); !d.isAfter(to); d = d.plusDays(1)) {
            m.put(d, new long[7]);
        }
        String[][] series = {
                {"users", "created_at"}, {"ticket", "created_at"}, {"exchange_match", "created_at"},
                {"exchange_history", "created_at"}, {"chat_message", "created_at"}, {"abuse_report", "created_at"},
                {"ai_extract_usage", "used_at"}};
        for (int i = 0; i < series.length; i++) {
            final int idx = i;
            String sql = i == 3
                    // 교환 이력은 한 교환에 두 행(양쪽)이라 매칭 수로 센다
                    ? "SELECT DATE(created_at) d, COUNT(DISTINCT match_id) n FROM exchange_history WHERE created_at >= ? GROUP BY d"
                    : i == 6
                    ? "SELECT DATE(used_at) d, COUNT(*) n FROM ai_extract_usage WHERE used_at >= ? AND refunded_at IS NULL GROUP BY d"
                    : "SELECT DATE(" + series[i][1] + ") d, COUNT(*) n FROM " + series[i][0] + " WHERE " + series[i][1] + " >= ? GROUP BY d";
            jdbc.query(sql, rs -> {
                long[] v = m.get(rs.getDate(1).toLocalDate());
                if (v != null) {
                    v[idx] = rs.getLong(2);
                }
            }, f);
        }
        List<Daily> out = new ArrayList<>();
        m.forEach((d, v) -> out.add(new Daily(d, v[0], v[1], v[2], v[3], v[4], v[5], v[6])));
        return out;
    }

    private List<Alert> alerts(Map<String, Map<String, Object>> k, LocalDateTime now) {
        List<Alert> out = new ArrayList<>();
        Map<String, Object> q = k.get("queues");
        long reports = num(q.get("openReports"));
        if (reports > 0) {
            boolean old = olderThan(q.get("oldestOpenReportAt"), now, Duration.ofHours(24));
            out.add(new Alert(old ? "danger" : "warn", "처리 대기 신고 " + reports + "건" + (old ? " (24시간 넘은 건 있음)" : ""), "/admin/reports"));
        }
        long claims = num(q.get("pendingClaims"));
        if (claims > 0) {
            boolean old = olderThan(q.get("oldestPendingClaimAt"), now, Duration.ofHours(24));
            out.add(new Alert(old ? "danger" : "warn", "좌석 인증 요청 " + claims + "건 대기" + (old ? " (24시간 넘은 건 있음)" : ""), "/admin/seat-claims"));
        }
        long proposals = num(q.get("pendingProposals"));
        if (proposals > 0) {
            out.add(new Alert("warn", "공연 수정 제안 " + proposals + "건 대기", "/admin/performance-edit-proposals"));
        }
        Map<String, Object> ai = k.get("ai");
        long g = num(ai.get("globalToday"));
        long gl = num(ai.get("dailyGlobalLimit"));
        if (gl > 0 && g * 10 >= gl * 8) {
            out.add(new Alert(g >= gl ? "danger" : "warn", "오늘 AI 호출 " + g + "/" + gl + "회 (전체 하루 상한에 가까움)", null));
        }
        long refunded = num(ai.get("refundedMonth"));
        long used = num(ai.get("usedMonth"));
        if (refunded > 0 && refunded * 5 >= (used + refunded)) {
            out.add(new Alert("warn", "이번 달 AI 실패(반환) " + refunded + "건, 전체의 20% 이상", null));
        }
        long reserved = num(k.get("matches").get("reserved"));
        if (reserved > 0) {
            out.add(new Alert("info", "예약 중인 교환 " + reserved + "건 (양쪽 교환 수락 대기)", null));
        }
        long suspended = num(k.get("users").get("suspended"));
        if (suspended > 0) {
            out.add(new Alert("info", "이용 정지 중인 사용자 " + suspended + "명", "/admin/sanctions"));
        }
        if (out.isEmpty()) {
            out.add(new Alert("ok", "처리할 대기 항목이 없어요", null));
        }
        return out;
    }

    private static boolean olderThan(Object at, LocalDateTime now, Duration d) {
        LocalDateTime t = at instanceof Timestamp ts ? ts.toLocalDateTime() : at instanceof LocalDateTime l ? l : null;
        return t != null && t.isBefore(now.minus(d));
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** 한 행 집계를 이름 → 숫자(NULL 은 0)로. */
    private Map<String, Object> row(String sql, Object... args) {
        Map<String, Object> r = jdbc.queryForMap(sql, args);
        Map<String, Object> out = new LinkedHashMap<>();
        r.forEach((key, v) -> out.put(key, v == null ? 0L : v instanceof Number n ? n.longValue()
                : v instanceof Timestamp ts ? ts.toLocalDateTime() : v));
        return out;
    }

    /** 여러 행. DATETIME 은 KST 현지 시각(LocalDateTime)으로 바꿔 JSON 에 시간대 변환이 끼지 않게 한다. */
    private List<Map<String, Object>> list(String sql, Object... args) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, args)) {
            Map<String, Object> out = new LinkedHashMap<>();
            r.forEach((key, v) -> out.put(key, v instanceof Timestamp ts ? ts.toLocalDateTime() : v));
            rows.add(out);
        }
        return rows;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private Object one(String sql) {
        Object v = jdbc.queryForObject(sql, Object.class);
        return v instanceof Timestamp ts ? ts.toLocalDateTime() : v;
    }
}
