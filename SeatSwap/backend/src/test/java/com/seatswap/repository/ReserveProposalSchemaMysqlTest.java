package com.seatswap.repository;

import com.seatswap.testsupport.RealMysqlSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V15(예약 제안 컬럼 + 알림 type 6종) 이관·스키마 검증 (실제 MySQL, SEATSWAP_IT_* 환경변수가 있을 때만).
 * 다른 IT 와 데이터가 섞이지 않게 `X_v15schema_it` 별도 DB 를 쓴다. Spring 컨텍스트 없이 Flyway + JdbcTemplate 만 쓴다.
 * V14 상태의 DB(매칭 CHATTING/RESERVED/CANCELED/COMPLETED, 기존 3종 알림)를 V15 로 올린다.
 * 확인: 기존 데이터 불변, 재실행·중간 실패 뒤 재적용 안전, 새 컬럼·FK·단일 컬럼 인덱스, CHECK(짝 NULL·CHATTING 전용·참여자),
 * notification type 6종 허용·그 외 거부, CHAT_MESSAGE dedup 형식과 (수신자, 매칭)당 1행 upsert 재사용, FK 동작.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
class ReserveProposalSchemaMysqlTest {

    private static final String NOTI = "INSERT INTO notification (user_id, type, match_id, dedup_key, created_at) VALUES (?,?,?,?,NOW(6))";

    private static String schemaUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("SEATSWAP_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "").replaceAll("_v15schema$", "") + "_v15schema_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    private JdbcTemplate jdbc;

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(schemaUrl(), RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    @BeforeEach
    void setUp() {
        RealMysqlSupport.assertSafe();
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(schemaUrl().replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_v15schema_it");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl(), RealMysqlSupport.user(), RealMysqlSupport.password()));
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_v15schema_it");
        flyway("14").clean();
        assertThat(flyway("14").migrate().migrationsExecuted).as("V1~V14 전체 적용").isEqualTo(14);
        seedV14();
    }

    /**
     * 사용자 1~4(4 = 제3자), 공연·회차 1, 티켓 1~6(사용자 1,2,1,2,1,2), 요청 1~6.
     * 매칭: m1 CHATTING(t1-t2) / m2 RESERVED(t3-t4, 예약자 a, 잠금 2행) / m3 CANCELED(t5-t6) / m4 COMPLETED(t5-t6).
     * 기존 3종 알림 각 1건.
     */
    private void seedV14() {
        for (int i = 1; i <= 4; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at,trust_score) VALUES (?,?,?,?,'USER',NOW(6),0)",
                    i, "u" + i + "@t", "x", "U" + i);
        }
        jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) VALUES (1,1,'k','http://x','T','V',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO performance_session(id,performance_id,starts_at,created_at) VALUES (1,1,'2030-01-01 19:00:00',NOW(6))");
        for (int i = 1; i <= 6; i++) {
            jdbc.update("INSERT INTO ticket(id,user_id,performance_session_id,zone_label,zone_key,row_label,row_key,col_label,col_key,status,created_at,updated_at) "
                    + "VALUES (?,?,1,'A','A','1','1',?,?,'ACTIVE',NOW(6),NOW(6))", i, i % 2 == 1 ? 1 : 2, String.valueOf(i), String.valueOf(i));
            jdbc.update("INSERT INTO exchange_request(id,ticket_id,status,created_at,updated_at) VALUES (?,?,'OPEN',NOW(6),NOW(6))", i, i);
        }
        String ins = "INSERT INTO exchange_match(id,request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,"
                + "reserved_by_id,reserved_at,a_completed_at,b_completed_at,canceled_at,created_at,updated_at,a_extra_type,b_extra_type) "
                + "VALUES (?,?,?,?,?,1,2,?,?,?,?,?,?,NOW(6),NOW(6),'ANY','ANY')";
        jdbc.update(ins, 1, 1, 2, 1, 2, "CHATTING", null, null, null, null, null);
        jdbc.update(ins, 2, 3, 4, 3, 4, "RESERVED", 1, "2030-01-02 10:00:00", null, null, null);
        jdbc.update(ins, 3, 5, 6, 5, 6, "CANCELED", null, null, null, null, "2030-01-03 10:00:00");
        jdbc.update(ins, 4, 5, 6, 5, 6, "COMPLETED", null, null, "2030-01-04 10:00:00", "2030-01-04 11:00:00", null);
        jdbc.update("INSERT INTO exchange_ticket_lock(ticket_id,match_id,created_at) VALUES (3,2,NOW(6)),(4,2,NOW(6))");
        jdbc.update(NOTI, 2, "PROPOSAL_RECEIVED", 1, "PROPOSAL_RECEIVED:1");
        jdbc.update(NOTI, 2, "MATCH_RESERVED", 2, null);
        jdbc.update(NOTI, 1, "RESERVATION_CANCELED", 2, null);
    }

    private List<Map<String, Object>> matchSnapshot() {
        return jdbc.queryForList("SELECT id, status, reserved_by_id, reserved_at, a_completed_at, b_completed_at, canceled_at FROM exchange_match ORDER BY id");
    }

    private List<Map<String, Object>> notificationSnapshot() {
        return jdbc.queryForList("SELECT id, user_id, type, match_id, dedup_key, read_at, created_at FROM notification ORDER BY id");
    }

    private List<String> constraints(String table) {
        return jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ?", String.class, table);
    }

    private void assertRejected(String sql, String constraint, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    private void assertMigrated() {
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME LIKE 'reserve%' ORDER BY ORDINAL_POSITION", String.class))
                .containsExactly("reserved_by_id", "reserved_at", "reserve_proposed_by_id", "reserve_proposed_at");
        assertThat(jdbc.queryForObject("SELECT CONCAT(COLUMN_TYPE, ' ', IS_NULLABLE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME = 'reserve_proposed_by_id'", String.class)).isEqualTo("bigint YES");
        assertThat(jdbc.queryForObject("SELECT CONCAT(COLUMN_TYPE, ' ', IS_NULLABLE) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_match' AND COLUMN_NAME = 'reserve_proposed_at'", String.class)).isEqualTo("datetime(6) YES");
        assertThat(constraints("exchange_match")).contains("fk_exchange_match_reserve_proposed_by",
                "ck_exchange_match_reserve_proposal_pair", "ck_exchange_match_reserve_proposal_status",
                "ck_exchange_match_reserve_proposed_by_party",
                // V8 제약은 그대로
                "fk_exchange_match_reserved_by", "ck_exchange_match_reserved", "ck_exchange_match_reserved_by_party", "ck_exchange_match_completed");
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'exchange_match' AND INDEX_NAME = 'idx_exchange_match_reserve_proposed_by' ORDER BY SEQ_IN_INDEX", String.class))
                .as("FK 전용 단일 컬럼 인덱스").containsExactly("reserve_proposed_by_id");
        assertThat(jdbc.queryForList("SELECT CONCAT(UPDATE_RULE, '/', DELETE_RULE) FROM information_schema.REFERENTIAL_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'fk_exchange_match_reserve_proposed_by'", String.class))
                .containsExactly("NO ACTION/NO ACTION");

        assertThat(constraints("notification")).contains("ck_notification_type", "ck_notification_match_required", "ck_notification_chat_dedup");
        String clause = jdbc.queryForObject("SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME = 'ck_notification_type'", String.class);
        for (String type : List.of("PROPOSAL_RECEIVED", "MATCH_RESERVED", "RESERVATION_CANCELED",
                "RESERVATION_PROPOSED", "RESERVATION_REJECTED", "CHAT_MESSAGE")) {
            assertThat(clause).contains(type);
        }
        // 인덱스 개수 불변(notification 은 V10 그대로, 모두 단일 컬럼)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'notification'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME <> 'flyway_schema_history'", Integer.class)).as("테이블 수 20 그대로").isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                .as("임시 프로시저가 남지 않는다").isZero();
    }

    @Test
    void V14에서_V15를_올리고_다시_실행해도_안전하며_기존_데이터는_그대로다() {
        List<Map<String, Object>> matchesBefore = matchSnapshot();
        List<Map<String, Object>> notificationsBefore = notificationSnapshot();

        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(1);
        assertMigrated();
        assertThat(matchSnapshot()).isEqualTo(matchesBefore);
        assertThat(notificationSnapshot()).isEqualTo(notificationsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_match WHERE reserve_proposed_by_id IS NOT NULL OR reserve_proposed_at IS NOT NULL",
                Integer.class)).as("기존 행은 제안 없음").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exchange_ticket_lock", Integer.class)).isEqualTo(2);

        // 재실행: 제안·새 type 행이 있는 채로 history 에서 V15 만 지우고 다시 적용해도 바뀌지 않는다
        jdbc.update("UPDATE exchange_match SET reserve_proposed_by_id = 2, reserve_proposed_at = NOW(6) WHERE id = 1");
        jdbc.update(NOTI, 1, "RESERVATION_PROPOSED", 1, null);
        jdbc.update(NOTI, 1, "CHAT_MESSAGE", 1, "CHAT_MESSAGE:1:1");
        List<Map<String, Object>> matchesAfter = matchSnapshot();
        List<Map<String, Object>> notificationsAfter = notificationSnapshot();
        jdbc.update("DELETE FROM flyway_schema_history WHERE version = '15'");
        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(1);
        assertMigrated();
        assertThat(matchSnapshot()).isEqualTo(matchesAfter);
        assertThat(notificationSnapshot()).isEqualTo(notificationsAfter);
        assertThat(jdbc.queryForObject("SELECT reserve_proposed_by_id FROM exchange_match WHERE id = 1", Long.class)).isEqualTo(2L);
    }

    @Test
    void V15가_중간까지만_적용된_DB에서도_이어서_끝까지_적용된다() {
        // 컬럼만 생기고, notification 의 type CHECK 가 빠진 상태(DROP 만 된 상태를 흉내)
        jdbc.execute("ALTER TABLE exchange_match ADD COLUMN reserve_proposed_by_id BIGINT NULL AFTER reserved_at, "
                + "ADD COLUMN reserve_proposed_at DATETIME(6) NULL AFTER reserve_proposed_by_id");
        jdbc.execute("ALTER TABLE notification DROP CHECK ck_notification_type");

        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(1);
        assertMigrated();
        assertRejected(NOTI, "ck_notification_type", 1, "FOO", 1, null);
    }

    @Test
    void 예약_제안_CHECK는_한쪽만_NULL_CHATTING_외_상태_제3자를_거부한다() {
        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(1);

        // 한쪽만 NULL
        assertRejected("UPDATE exchange_match SET reserve_proposed_by_id = 1 WHERE id = 1", "ck_exchange_match_reserve_proposal_pair");
        assertRejected("UPDATE exchange_match SET reserve_proposed_at = NOW(6) WHERE id = 1", "ck_exchange_match_reserve_proposal_pair");
        // 제3자(사용자 4)는 제안자가 될 수 없다
        assertRejected("UPDATE exchange_match SET reserve_proposed_by_id = 4, reserve_proposed_at = NOW(6) WHERE id = 1",
                "ck_exchange_match_reserve_proposed_by_party");
        // RESERVED·CANCELED·COMPLETED 에는 제안이 있을 수 없다
        for (long id : new long[]{2, 3, 4}) {
            assertRejected("UPDATE exchange_match SET reserve_proposed_by_id = 1, reserve_proposed_at = NOW(6) WHERE id = ?",
                    "ck_exchange_match_reserve_proposal_status", id);
        }
        // CHATTING 에서 참여자(a 또는 b)는 제안할 수 있다
        assertThat(jdbc.update("UPDATE exchange_match SET reserve_proposed_by_id = 2, reserve_proposed_at = NOW(6) WHERE id = 1")).isEqualTo(1);
        assertThat(jdbc.update("UPDATE exchange_match SET reserve_proposed_by_id = 1, reserve_proposed_at = NOW(6) WHERE id = 1")).isEqualTo(1);
        // 제안이 걸린 채로 CHATTING 을 벗어나면(수락 -> RESERVED, 취소 -> CANCELED) 같은 문장에서 제안을 비워야 한다
        assertRejected("UPDATE exchange_match SET status = 'CANCELED', canceled_at = NOW(6) WHERE id = 1", "ck_exchange_match_reserve_proposal_status");
        assertRejected("UPDATE exchange_match SET status = 'RESERVED', reserved_by_id = 1, reserved_at = NOW(6) WHERE id = 1",
                "ck_exchange_match_reserve_proposal_status");
        assertThat(jdbc.update("UPDATE exchange_match SET status = 'RESERVED', reserved_by_id = reserve_proposed_by_id, reserved_at = NOW(6), "
                + "reserve_proposed_by_id = NULL, reserve_proposed_at = NULL WHERE id = 1")).as("수락: 제안자를 예약자로 옮기고 제안을 비운다").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reserved_by_id FROM exchange_match WHERE id = 1", Long.class)).isEqualTo(1L);
        // 예약 취소 뒤 다시 제안 -> 시스템 취소(제안 비움)
        jdbc.update("UPDATE exchange_match SET status = 'CHATTING', reserved_by_id = NULL, reserved_at = NULL WHERE id = 1");
        jdbc.update("UPDATE exchange_match SET reserve_proposed_by_id = 2, reserve_proposed_at = NOW(6) WHERE id = 1");
        assertThat(jdbc.update("UPDATE exchange_match SET status = 'CANCELED', canceled_at = NOW(6), "
                + "reserve_proposed_by_id = NULL, reserve_proposed_at = NULL WHERE id = 1")).isEqualTo(1);
    }

    @Test
    void 제안자_FK는_없는_사용자를_거부하고_제안자_삭제를_막는다() {
        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(1);
        // 없는 사용자는 참여자 CHECK 에 먼저 걸리므로, 참여자 CHECK 를 잠시 끄고 FK 만 확인한다
        jdbc.execute("ALTER TABLE exchange_match ALTER CHECK ck_exchange_match_reserve_proposed_by_party NOT ENFORCED");
        try {
            assertRejected("UPDATE exchange_match SET reserve_proposed_by_id = 999, reserve_proposed_at = NOW(6) WHERE id = 1",
                    "fk_exchange_match_reserve_proposed_by");
        } finally {
            jdbc.execute("ALTER TABLE exchange_match ALTER CHECK ck_exchange_match_reserve_proposed_by_party ENFORCED");
        }
        // 제안자 사용자는 삭제할 수 없다(ON DELETE 동작 없음, 탈퇴는 익명화)
        jdbc.update("UPDATE exchange_match SET reserve_proposed_by_id = 2, reserve_proposed_at = NOW(6) WHERE id = 1");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = 2")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void notification_type은_6종만_허용한다() {
        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbc.update(NOTI, 1, "PROPOSAL_RECEIVED", 2, "PROPOSAL_RECEIVED:2")).isEqualTo(1);
        assertThat(jdbc.update(NOTI, 1, "MATCH_RESERVED", 1, null)).isEqualTo(1);
        assertThat(jdbc.update(NOTI, 1, "RESERVATION_CANCELED", 1, null)).isEqualTo(1);
        // 새 3종. 예약 제안·거절은 반복마다 1건(dedup_key NULL 여러 번 허용)
        assertThat(jdbc.update(NOTI, 2, "RESERVATION_PROPOSED", 1, null)).isEqualTo(1);
        assertThat(jdbc.update(NOTI, 2, "RESERVATION_PROPOSED", 1, null)).isEqualTo(1);
        assertThat(jdbc.update(NOTI, 1, "RESERVATION_REJECTED", 1, null)).isEqualTo(1);
        assertThat(jdbc.update(NOTI, 1, "RESERVATION_REJECTED", 1, null)).isEqualTo(1);
        assertThat(jdbc.update(NOTI, 2, "CHAT_MESSAGE", 1, "CHAT_MESSAGE:1:2")).isEqualTo(1);

        // (type 컬럼은 V10 부터 utf8mb4_0900_ai_ci 라 대소문자만 다른 값은 CHECK 를 통과한다 — 앱은 enum 이름만 쓴다)
        for (String bad : List.of("FOO", "RESERVATION_ACCEPTED", "CHAT", "")) {
            assertRejected(NOTI, "ck_notification_type", 1, bad, 1, null);
        }
        assertThatThrownBy(() -> jdbc.update(NOTI, 1, null, 1, null)).isInstanceOf(DataAccessException.class);
        // match_id 필수(V10)는 새 type 에도 적용
        assertRejected(NOTI, "ck_notification_match_required", 1, "RESERVATION_PROPOSED", null, null);
        assertRejected(NOTI, "fk_notification_match", 1, "RESERVATION_REJECTED", 999, null);
    }

    @Test
    void CHAT_MESSAGE는_수신자_매칭당_한_행이고_upsert로_되살린다() {
        assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(1);

        // dedup_key 는 반드시 CHAT_MESSAGE:{matchId}:{recipientId}
        assertRejected(NOTI, "ck_notification_chat_dedup", 2, "CHAT_MESSAGE", 1, null);
        assertRejected(NOTI, "ck_notification_chat_dedup", 2, "CHAT_MESSAGE", 1, "CHAT_MESSAGE:1");
        assertRejected(NOTI, "ck_notification_chat_dedup", 2, "CHAT_MESSAGE", 1, "CHAT_MESSAGE:1:1");
        assertRejected(NOTI, "ck_notification_chat_dedup", 2, "CHAT_MESSAGE", 1, "CHAT_MESSAGE:2:2");
        // 다른 type 은 dedup 형식 제약이 없다(기존 규칙)
        assertThat(jdbc.update(NOTI, 2, "MATCH_RESERVED", 1, null)).isEqualTo(1);

        String upsert = "INSERT INTO notification (user_id, type, match_id, dedup_key, created_at) "
                + "VALUES (?, 'CHAT_MESSAGE', ?, CONCAT('CHAT_MESSAGE:', ?, ':', ?), ?) "
                + "ON DUPLICATE KEY UPDATE read_at = NULL, created_at = VALUES(created_at)";
        jdbc.update(upsert, 2, 1, 1, 2, "2030-05-01 10:00:00");
        jdbc.update(upsert, 1, 1, 1, 1, "2030-05-01 10:00:01");   // 상대 쪽은 별도 행
        long id = jdbc.queryForObject("SELECT id FROM notification WHERE dedup_key = 'CHAT_MESSAGE:1:2'", Long.class);
        // 읽음 처리 -> 안 읽은 개수에서 빠진다
        jdbc.update("UPDATE notification SET read_at = NOW(6) WHERE dedup_key = 'CHAT_MESSAGE:1:2' AND read_at IS NULL");
        assertThat(jdbc.queryForObject("SELECT unread_user_id FROM notification WHERE id = ?", Long.class, id)).isNull();
        // 새 메시지 -> 같은 행이 안 읽음으로 되살아난다(행 수 불변, id 불변)
        jdbc.update(upsert, 2, 1, 1, 2, "2030-05-01 10:05:00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE'", Integer.class)).isEqualTo(2);
        Map<String, Object> row = jdbc.queryForMap("SELECT id, read_at, unread_user_id, created_at FROM notification WHERE dedup_key = 'CHAT_MESSAGE:1:2'");
        assertThat(((Number) row.get("id")).longValue()).isEqualTo(id);
        assertThat(row.get("read_at")).isNull();
        assertThat(((Number) row.get("unread_user_id")).longValue()).isEqualTo(2L);
        assertThat(row.get("created_at").toString()).startsWith("2030-05-01T10:05");
        // '읽은 알림 삭제'로 행이 없어졌으면 새로 만든다
        jdbc.update("UPDATE notification SET read_at = NOW(6) WHERE id = ?", id);
        jdbc.update("DELETE FROM notification WHERE user_id = 2 AND read_at IS NOT NULL");
        jdbc.update(upsert, 2, 1, 1, 2, "2030-05-01 10:10:00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedup_key = 'CHAT_MESSAGE:1:2' AND read_at IS NULL", Integer.class))
                .isEqualTo(1);
        // 닫힌 매칭(m3 CANCELED)의 채팅 알림도 저장 가능(종료된 매칭 라벨로 남긴다)
        jdbc.update(upsert, 2, 3, 3, 2, "2030-05-01 10:20:00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE dedup_key = 'CHAT_MESSAGE:3:2'", Integer.class)).isEqualTo(1);
    }
}
