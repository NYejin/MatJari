package com.seatswap.repository;

import com.seatswap.domain.ExtraType;
import com.seatswap.domain.WantExtra;
import com.seatswap.config.ClockConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 매칭 후보 조회 (설계 exchange-schema-design.md 2절 SQL). 네이티브 SQL 한 번의 조인으로 닉네임·회차까지 읽어 N+1이 없다.
 * 접근 경로: 내 요청(PK) -> 내 희망 회차 x 내 희망 좌석(exchange_want_seat PK 앞부분) -> 상대 티켓
 * (uk_ticket_active_seat 점조회) -> 상대 요청(uk_exchange_request_live_ticket, live_flag = 1) -> 상대 희망 회차/좌석(PK 점조회).
 *
 * 판정: 같은 공연, 상대 티켓의 회차 ∈ 내 희망 회차, 내 티켓의 회차 ∈ 상대 희망 회차, 상대 좌석 ∈ 내 희망 좌석, 내 좌석 ∈ 상대 희망 좌석,
 * 추가금 유형 호환(불성립은 POS-POS, POS-X, X-POS 뿐; 금액은 쓰지 않음), 상대 티켓 ACTIVE·상대 요청 OPEN·상대 회차 마감 전·다른 사용자.
 * 추가금은 범위 단위다: 좌석 행(wa, wb)이 속한 범위의 추가금을 비정규화해 가지고 있어 조인이 늘지 않는다. 좌석당 행이 하나(겹침은 같은 추가금만
 * 허용)라 wa·wb 는 점조회이고 결과가 중복되지 않는다. wa = 내 쪽(상대 좌석을 포함한 내 범위), wb = 상대 쪽(내 좌석을 포함한 상대 범위).
 * 정렬: 내 희망 회차 우선순위 -> 상대 요청 최신 -> 요청 id 내림차순. 점수·랭킹 없음.
 */
@Repository
public class ExchangeCandidateRepository {

    /** 후보 한 행. 엔티티가 아니라 조회 전용 값이다. */
    public record Row(Long requestId, Long ticketId, String zone, String row, String col,
                      Long sessionId, LocalDateTime startsAt, String nickname, int wantPriority,
                      String extraType, Integer extraAmount, String myExtraType, Integer myExtraAmount,
                      LocalDateTime requestedAt) {}

    /** 한 요청 쌍의 양쪽 적용 추가금. my = 내(a) 쪽에서 상대 좌석을 포함한 범위, their = 상대(b) 쪽에서 내 좌석을 포함한 범위. 매칭 스냅샷으로 복사된다. */
    public record PairExtras(WantExtra my, WantExtra their) {}

    /**
     * 판정에 필요한 조인. 목록과 COUNT가 공유한다(조인 순서는 STRAIGHT_JOIN 으로 고정).
     * psa 는 FK 로 항상 존재하지만 `psb.performance_id = psa.performance_id`(같은 공연 방어)에 쓰이므로 COUNT 에서도 뺄 수 없다.
     */
    private static final String FROM_CORE = """
            FROM exchange_request a
            JOIN ticket ta                 ON ta.id = a.ticket_id
            JOIN performance_session psa   ON psa.id = ta.performance_session_id
            JOIN exchange_want_session wsa ON wsa.request_id = a.id
            JOIN performance_session psb   ON psb.id = wsa.performance_session_id
                                          AND psb.performance_id = psa.performance_id
            JOIN exchange_want_seat wa     ON wa.request_id = a.id
            JOIN ticket tb                 ON tb.performance_session_id = wsa.performance_session_id
                                          AND tb.zone_key = wa.zone_key
                                          AND tb.row_key  = wa.row_key
                                          AND tb.col_key  = wa.col_key
                                          AND tb.active_flag = 1
            JOIN exchange_request b        ON b.live_flag = 1 AND b.ticket_id = tb.id AND b.status = 'OPEN'
            JOIN exchange_want_session wsb ON wsb.request_id = b.id
                                          AND wsb.performance_session_id = ta.performance_session_id
            JOIN exchange_want_seat wb     ON wb.request_id = b.id
                                          AND wb.zone_key = ta.zone_key
                                          AND wb.row_key  = ta.row_key
                                          AND wb.col_key  = ta.col_key
            """;

    /** 닉네임을 읽기 위한 조인. users 는 tb.user_id FK 로 항상 존재하므로 결과 행 수에 영향이 없어 COUNT 에서는 생략한다. */
    private static final String JOIN_USERS = """
            JOIN users ub                  ON ub.id = tb.user_id
            """;

    private static final String WHERE = """
            WHERE a.id = ? AND a.status = 'OPEN'
              AND tb.user_id <> ta.user_id
              AND NOT (wa.extra_type = 'POS' AND wb.extra_type IN ('POS', 'X'))
              AND NOT (wb.extra_type = 'POS' AND wa.extra_type IN ('POS', 'X'))
              AND psb.starts_at >= ?
            """;

    /**
     * STRAIGHT_JOIN: FROM 절의 순서(내 요청 -> 내 희망 회차·좌석 -> 상대 티켓 -> 상대 요청 ...)대로 조인하도록 고정한다.
     * 힌트가 없으면 요청 수가 적을 때 옵티마이저가 exchange_request(b)를 전체 스캔으로 시작해 작업량이 전체 요청 수에 비례한다
     * (EXPLAIN 확인, README 검증 결과). 고정하면 작업량이 내 희망 좌석 수 x 희망 회차 수에만 비례한다.
     */
    private static final String SELECT = """
            SELECT STRAIGHT_JOIN b.id AS request_id, tb.id AS ticket_id, tb.zone_label, tb.row_label, tb.col_label,
                   psb.id AS session_id, psb.starts_at, ub.nickname, wsa.priority AS want_priority,
                   wb.extra_type AS extra_type, wb.extra_amount AS extra_amount,
                   wa.extra_type AS my_extra_type, wa.extra_amount AS my_extra_amount,
                   b.created_at
            """;

    private static final String ORDER = """
            ORDER BY wsa.priority ASC, b.created_at DESC, b.id DESC
            LIMIT ? OFFSET ?
            """;

    /**
     * V5 제외 조건 (별칭 mo, l 사용). 같은 요청 쌍(방향 무관)의 열린 매칭(CHATTING·RESERVED)이 있거나,
     * 상대 티켓이 예약 잠금(RESERVED 로 두 티켓이 잠김)이면 후보에서 뺀다. 취소·완료된 매칭은 보지 않는다(재매칭 허용).
     * 잠금이 풀리면(예약 취소) 다시 후보로 복귀한다. 내 티켓이 잠겼을 때는 서비스가 조회 자체를 422로 막는다.
     */
    private static final String EXCLUSIONS = """
            AND NOT EXISTS (SELECT 1 FROM exchange_match mo
                            WHERE mo.request_low_id  = LEAST(a.id, b.id)
                              AND mo.request_high_id = GREATEST(a.id, b.id)
                              AND mo.open_flag = 1)
            AND NOT EXISTS (SELECT 1 FROM exchange_ticket_lock l WHERE l.ticket_id = tb.id)
            """;

    /**
     * V13 차단 제외 (별칭 bk1, bk2). 방향은 하나지만 효과는 양방향이라 NOT EXISTS 두 개로 나눈다(OR 로 묶으면 uk_user_block_pair 대신
     * 전체 인덱스 스캔). ta = 내 티켓, tb = 상대 티켓. 제안 시 후보 재검증(findCandidatePair)도 같은 조각을 쓴다.
     */
    private static final String BLOCK_EXCLUSIONS = """
            AND NOT EXISTS (SELECT 1 FROM user_block bk1 WHERE bk1.blocker_id = ta.user_id AND bk1.blocked_id = tb.user_id)
            AND NOT EXISTS (SELECT 1 FROM user_block bk2 WHERE bk2.blocker_id = tb.user_id AND bk2.blocked_id = ta.user_id)
            """;

    private static final DateTimeFormatter SQL_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Clock DEFAULT_CLOCK = Clock.system(ClockConfig.SERVICE_ZONE);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public ExchangeCandidateRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public ExchangeCandidateRepository(JdbcTemplate jdbc) {
        this(jdbc, DEFAULT_CLOCK);
    }

    /**
     * 후보에서 더 제외할 조건을 붙이는 확장 지점 (V5: 같은 쌍 열린 매칭, 예약 잠금 티켓 / V13: 양방향 차단). 정확한 조건식은 backend/README.md '후속 메모 (매칭 후보 조회)' 참고.
     * 활성 제재(이용 정지) 사용자 제외는 현재 시각이 필요해 {@link #sanctionExclusion(LocalDateTime)} 로 따로 붙인다.
     * 규칙(위반하면 IllegalStateException): 조각은 `AND`로 시작하고 파라미터(`?`)를 포함하지 않는다.
     * ta/tb/a/b 별칭을 쓸 수 있다. 앞뒤 개행은 {@link #normalizeExclusions} 가 보장한다.
     */
    static String additionalExclusions() {
        return normalizeExclusions(EXCLUSIONS + BLOCK_EXCLUSIONS);
    }

    /**
     * 활성 제재 사용자 제외(상대 티켓 소유자 tb.user_id). 활성 = revoked_at IS NULL AND (ends_at IS NULL OR ends_at > now).
     * 조각에는 바인딩 파라미터를 쓸 수 없어 now 를 서버가 만든 타임스탬프 리터럴(yyyy-MM-dd HH:mm:ss)로 넣는다(사용자 입력 아님).
     * 내가 정지 상태인 경우는 전역 인증 필터가 막으므로 여기서 보지 않는다.
     */
    static String sanctionExclusion(LocalDateTime now) {
        return normalizeExclusions("AND NOT EXISTS (SELECT 1 FROM user_sanction us WHERE us.user_id = tb.user_id"
                + " AND us.revoked_at IS NULL AND (us.ends_at IS NULL OR us.ends_at > '" + SQL_TIMESTAMP.format(now) + "'))");
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 확장 조각 검증·정규화. 비어 있으면 빈 문자열, 아니면 앞뒤에 개행을 붙인다. */
    static String normalizeExclusions(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return "";
        }
        String trimmed = fragment.strip();
        if (!trimmed.regionMatches(true, 0, "AND", 0, 3) || (trimmed.length() > 3 && Character.isLetterOrDigit(trimmed.charAt(3)))) {
            throw new IllegalStateException("후보 제외 조각은 AND 로 시작해야 합니다.");
        }
        if (trimmed.indexOf('?') >= 0) {
            throw new IllegalStateException("후보 제외 조각에는 바인딩 파라미터(?)를 쓸 수 없습니다.");
        }
        return "\n" + trimmed + "\n";
    }

    /** 현재 목록 SQL(확장 지점 포함). 실행계획 확인용. */
    public static String candidateSql() {
        return candidateSql(LocalDateTime.now(DEFAULT_CLOCK));
    }

    public static String candidateSql(LocalDateTime now) {
        return SELECT + FROM_CORE + JOIN_USERS + WHERE + additionalExclusions() + sanctionExclusion(now) + ORDER;
    }

    /** COUNT 전용 SQL(users 조인 생략). 결과 행 수는 목록 쿼리 전체와 같아야 한다(테스트로 확인). */
    public static String countSql() {
        return countSql(LocalDateTime.now(DEFAULT_CLOCK));
    }

    public static String countSql(LocalDateTime now) {
        return "SELECT COUNT(*) " + FROM_CORE + WHERE + additionalExclusions() + sanctionExclusion(now);
    }

    /**
     * @param todayStart 오늘 0시(KST). 상대 회차의 마감(starts_at 다음날 0시)이 지나지 않은 것은 starts_at >= todayStart 와 같다.
     */
    public List<Row> findCandidates(Long myRequestId, LocalDateTime todayStart, int limit, long offset) {
        return jdbc.query(candidateSql(now()), (rs, i) -> new Row(
                rs.getLong("request_id"), rs.getLong("ticket_id"),
                rs.getString("zone_label"), rs.getString("row_label"), rs.getString("col_label"),
                rs.getLong("session_id"), rs.getObject("starts_at", LocalDateTime.class),
                rs.getString("nickname"), rs.getInt("want_priority"),
                rs.getString("extra_type"), (Integer) rs.getObject("extra_amount"),
                rs.getString("my_extra_type"), (Integer) rs.getObject("my_extra_amount"),
                rs.getObject("created_at", LocalDateTime.class)),
                myRequestId, todayStart, limit, offset);
    }

    public long countCandidates(Long myRequestId, LocalDateTime todayStart) {
        Long count = jdbc.queryForObject(countSql(now()), Long.class, myRequestId, todayStart);
        return count == null ? 0 : count;
    }

    /**
     * 요청 쌍 (a, b) 가 서로 후보 조건(같은 공연, 양방향 회차·좌석, 추가금 호환, 상대 티켓 ACTIVE·요청 OPEN(미삭제)·회차 마감 전, 다른 사용자)을
     * 만족하는가. 후보 목록과 같은 조인·판정(FROM_CORE + WHERE)을 쌍 하나로 좁혀 재사용한다. 만족하면 양쪽 적용 추가금을 돌려준다(매칭 스냅샷용).
     * 쌍에는 (내 회차 x 내 좌석) 점조회 행이 최대 1개라 결과는 0 또는 1행이다. 열린 매칭·예약 잠금 제외 조건은
     * 호출하는 서비스가 사유별로 따로 검사하므로 붙이지 않는다.
     */
    public java.util.Optional<PairExtras> findCandidatePair(Long myRequestId, Long targetRequestId, LocalDateTime todayStart) {
        String sql = "SELECT STRAIGHT_JOIN wa.extra_type AS my_type, wa.extra_amount AS my_amount, "
                + "wb.extra_type AS their_type, wb.extra_amount AS their_amount " + FROM_CORE + WHERE
                + normalizeExclusions(BLOCK_EXCLUSIONS) + sanctionExclusion(now()) + "  AND b.id = ? LIMIT 1";
        return jdbc.query(sql, (rs, i) -> new PairExtras(
                new WantExtra(ExtraType.valueOf(rs.getString("my_type")), (Integer) rs.getObject("my_amount")),
                new WantExtra(ExtraType.valueOf(rs.getString("their_type")), (Integer) rs.getObject("their_amount"))),
                myRequestId, todayStart, targetRequestId).stream().findFirst();
    }

    public boolean isCandidatePair(Long myRequestId, Long targetRequestId, LocalDateTime todayStart) {
        return findCandidatePair(myRequestId, targetRequestId, todayStart).isPresent();
    }
}
