package com.seatswap.repository;

import com.seatswap.testsupport.RealMysqlSupport;
import com.seatswap.domain.ChatMessage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V11(chat_message, chat_read_state) 스키마·저장소 검증 (실제 MySQL, SEATSWAP_IT_* 환경변수가 있을 때만).
 * 다른 IT 와 데이터가 섞이지 않게 `X_chat_it` 별도 DB 를 쓴다(이름은 `_it` 로 끝난다). 확인 항목:
 * 마이그레이션 재실행 안전, 제약 이름, CHECK·FK·UNIQUE 거부, FK 인덱스 단일 컬럼, 페이징 쿼리 EXPLAIN 에 filesort 없음,
 * 읽음 포인터 단조 증가, 두 탭 동시 갱신(최초 INSERT 경합 포함)에서 교착 0.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChatSchemaMysqlTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2030, 1, 1, 12, 0);

    private static String chatUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("SEATSWAP_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "") + "_chat_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.assertSafe();
        registry.add("spring.datasource.url", ChatSchemaMysqlTest::chatUrl);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private ChatReadStateRepository readState;
    @Autowired
    private ChatMessageRepository messages;

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(chatUrl(), RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    @BeforeEach
    void setUp() {
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(chatUrl().replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_chat_it");
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_chat_it");
        flyway("11").clean();
        assertThat(flyway("11").migrate().migrationsExecuted).isEqualTo(11);
        seed();
    }

    /** 사용자 1~2, 공연·회차 1, 티켓 1~2, 요청 1~2, 매칭 1(CHATTING)과 매칭 2~20(CANCELED, 같은 쌍). */
    private void seed() {
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,?,NOW(6))",
                    i, "u" + i + "@t", "x", "U" + i, "USER");
        }
        jdbc.update("INSERT INTO performance(id,registrant_id,source_key,source_url,title,venue_name,created_at,updated_at) VALUES (1,1,'k','http://x','T','V',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO performance_session(id,performance_id,starts_at,created_at) VALUES (1,1,'2030-01-01 19:00:00',NOW(6))");
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO ticket(id,user_id,performance_session_id,zone_label,zone_key,row_label,row_key,col_label,col_key,status,created_at,updated_at) "
                    + "VALUES (?,?,1,'A','A','1','1',?,?,'ACTIVE',NOW(6),NOW(6))", i, (i % 2) + 1, String.valueOf(i), String.valueOf(i));
            jdbc.update("INSERT INTO exchange_request(id,ticket_id,status,created_at,updated_at) VALUES (?,?,'OPEN',NOW(6),NOW(6))", i, i);
        }
        jdbc.update("INSERT INTO exchange_match(id,request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,"
                + "created_at,updated_at,a_extra_type,b_extra_type) VALUES (1,1,2,1,2,2,1,'CHATTING',NOW(6),NOW(6),'ANY','ANY')");
        for (int id = 2; id <= 20; id++) {
            jdbc.update("INSERT INTO exchange_match(id,request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,"
                    + "created_at,updated_at,canceled_at,a_extra_type,b_extra_type) VALUES (?,1,2,1,2,2,1,'CANCELED',NOW(6),NOW(6),NOW(6),'ANY','ANY')", id);
        }
    }

    private static final String INSERT_MESSAGE = "INSERT INTO chat_message (match_id, sender_id, content, client_message_id, created_at) VALUES (?,?,?,?,NOW(6))";

    private void assertRejected(String constraint, String sql, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args)).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }

    @Test
    void 마이그레이션은_재실행해도_안전하고_데이터와_제약_이름이_스펙대로다() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).isZero();
        jdbc.update(INSERT_MESSAGE, 1, 1, "안녕", "client-msg-0001");
        jdbc.update("INSERT INTO chat_read_state (match_id,user_id,last_read_message_id,updated_at) VALUES (1,1,0,NOW(6))");

        jdbc.update("DELETE FROM flyway_schema_history WHERE version = '11'");
        assertThat(flyway("11").migrate().migrationsExecuted).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).as("재실행이 데이터를 지우지 않는다").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_read_state", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class))
                .as("임시 프로시저가 남지 않는다").isZero();

        assertThat(columns("chat_message")).containsExactly("id", "match_id", "sender_id", "content", "client_message_id", "created_at");
        assertThat(columns("chat_read_state")).containsExactly("match_id", "user_id", "last_read_message_id", "updated_at");
        assertThat(jdbc.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'chat_message' AND COLUMN_NAME = 'client_message_id'", String.class)).isEqualTo("ascii_bin");

        List<String> constraints = jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND TABLE_NAME IN ('chat_message','chat_read_state') AND CONSTRAINT_TYPE <> 'CHECK'", String.class);
        assertThat(constraints).contains("uk_chat_message_client", "fk_chat_message_match", "fk_chat_message_sender",
                "fk_chat_read_state_match", "fk_chat_read_state_user");
        assertThat(jdbc.queryForList("SELECT CONSTRAINT_NAME FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME LIKE '%chat%'", String.class))
                .containsExactlyInAnyOrder("ck_chat_message_content", "ck_chat_message_client_id", "ck_chat_read_state_pointer");

        // FK 인덱스는 모두 단일 컬럼(갱신 컬럼이 섞이지 않는다). 유니크는 (client_message_id, sender_id) 2컬럼
        for (String[] idx : new String[][]{{"chat_message", "idx_chat_message_match"}, {"chat_message", "idx_chat_message_sender"},
                {"chat_read_state", "idx_chat_read_state_user"}}) {
            assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = ? AND INDEX_NAME = ?", String.class, idx[0], idx[1])).as(idx[1]).hasSize(1);
        }
        assertThat(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'chat_message' AND INDEX_NAME = 'uk_chat_message_client' ORDER BY SEQ_IN_INDEX", String.class))
                .containsExactly("client_message_id", "sender_id");
        // 모든 FK 컬럼이 인덱스의 첫 컬럼으로 덮인다
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'chat_read_state' AND INDEX_NAME = 'PRIMARY' AND SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'match_id'", Integer.class)).isEqualTo(1);
    }

    private List<String> columns(String table) {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
                + "ORDER BY ORDINAL_POSITION", String.class, table);
    }

    @Test
    void CHECK_FK_UNIQUE가_잘못된_행을_거부한다() {
        assertRejected("ck_chat_message_content", INSERT_MESSAGE, 1, 1, "   ", "client-msg-0001");
        assertRejected("ck_chat_message_content", INSERT_MESSAGE, 1, 1, "", "client-msg-0001");
        assertRejected("ck_chat_message_client_id", INSERT_MESSAGE, 1, 1, "hi", "short77"); // 7자
        assertThatThrownBy(() -> jdbc.update(INSERT_MESSAGE, 1, 1, "hi", "x".repeat(37))).isInstanceOf(DataAccessException.class); // 37자
        assertThatThrownBy(() -> jdbc.update(INSERT_MESSAGE, 1, 1, "a".repeat(1001), "client-msg-0002")).isInstanceOf(DataAccessException.class);
        assertRejected("fk_chat_message_match", INSERT_MESSAGE, 999, 1, "hi", "client-msg-0003");
        assertRejected("fk_chat_message_sender", INSERT_MESSAGE, 1, 999, "hi", "client-msg-0004");

        // 경계값은 통과: 8자 / 36자 id, 본문 1000자
        assertThat(jdbc.update(INSERT_MESSAGE, 1, 1, "a".repeat(1000), "12345678")).isEqualTo(1);
        assertThat(jdbc.update(INSERT_MESSAGE, 1, 1, "hi", "x".repeat(36))).isEqualTo(1);

        // (client_message_id, sender_id) 유니크: 같은 보낸 사람 중복 거부, 다른 보낸 사람·대소문자 다르면 허용
        assertThat(jdbc.update(INSERT_MESSAGE, 1, 1, "hi", "client-msg-AAAA")).isEqualTo(1);
        assertRejected("uk_chat_message_client", INSERT_MESSAGE, 1, 1, "again", "client-msg-AAAA");
        assertThat(jdbc.update(INSERT_MESSAGE, 1, 2, "hi", "client-msg-AAAA")).isEqualTo(1);
        assertThat(jdbc.update(INSERT_MESSAGE, 1, 1, "hi", "client-msg-aaaa")).as("ascii_bin 은 대소문자 구분").isEqualTo(1);

        String upsertState = "INSERT INTO chat_read_state (match_id,user_id,last_read_message_id,updated_at) VALUES (?,?,?,NOW(6))";
        assertRejected("ck_chat_read_state_pointer", upsertState, 1, 1, -1);
        assertRejected("fk_chat_read_state_match", upsertState, 999, 1, 0);
        assertRejected("fk_chat_read_state_user", upsertState, 1, 999, 0);
        assertThat(jdbc.update(upsertState, 1, 1, 0)).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(upsertState, 1, 1, 5)).isInstanceOf(DataAccessException.class).hasMessageContaining("Duplicate");

        // 부모 삭제는 FK 로 막힌다(ON DELETE 동작 없음)
        assertThatThrownBy(() -> jdbc.update("DELETE FROM exchange_match WHERE id = 1")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void 페이징_쿼리는_인덱스만_쓰고_filesort가_없다() {
        // 매칭 20개에 3000건을 번갈아 넣는다(실제처럼 한 매칭의 메시지가 PK 에서 흩어진다). 매칭 1 은 150건
        List<Object[]> batch = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            batch.add(new Object[]{1 + (i % 20), (i % 2) + 1, "m" + i, String.format("cid-%08d", i)});
        }
        jdbc.batchUpdate(INSERT_MESSAGE, batch);
        jdbc.execute("ANALYZE TABLE chat_message");

        // ChatMessageRepository 의 네이티브 쿼리와 같은 문장(LIMIT 31 = size 30 + 1)
        String latest = "SELECT m.* FROM chat_message m FORCE INDEX (idx_chat_message_match) WHERE m.match_id = 1 ORDER BY m.id DESC LIMIT 31";
        String before = "SELECT m.* FROM chat_message m FORCE INDEX (idx_chat_message_match) WHERE m.match_id = 1 AND m.id < 2000 ORDER BY m.id DESC LIMIT 31";
        String after = "SELECT m.* FROM chat_message m FORCE INDEX (idx_chat_message_match) WHERE m.match_id = 1 AND m.id > 2000 ORDER BY m.id ASC LIMIT 101";
        String max = "SELECT MAX(id) FROM chat_message WHERE match_id = 1 AND id <= 2000";
        for (String sql : List.of(latest, before, after, max)) {
            List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + sql);
            for (Map<String, Object> row : plan) {
                String extra = String.valueOf(row.get("Extra"));
                assertThat(extra).as(sql + " -> " + row).doesNotContain("filesort");
                if (!sql.startsWith("SELECT MAX")) {
                    assertThat(String.valueOf(row.get("key"))).as(sql + " -> " + row).isEqualTo("idx_chat_message_match");
                }
            }
        }

        // 의미 확인: before/after 경계와 정렬, 읽음 포인터 절단용 MAX
        List<Long> ids = jdbc.queryForList("SELECT id FROM chat_message WHERE match_id = 1 ORDER BY id", Long.class);
        long cut = ids.get(75);
        assertThat(jdbc.queryForList("SELECT id FROM chat_message WHERE match_id = 1 AND id < ? ORDER BY id DESC LIMIT 31", Long.class, cut))
                .hasSize(31).isSortedAccordingTo(java.util.Comparator.reverseOrder()).allMatch(id -> id < cut);
        assertThat(jdbc.queryForObject("SELECT MAX(id) FROM chat_message WHERE match_id = 1 AND id <= ?", Long.class, cut + 100000)).isEqualTo(ids.get(ids.size() - 1));
        assertThat(jdbc.queryForObject("SELECT MAX(id) FROM chat_message WHERE match_id = 2 AND id <= 1", Long.class)).as("메시지 없음 -> null").isNull();

        // 저장소 메서드(네이티브 FORCE INDEX 쿼리·JPQL)가 실제로 실행되고 같은 결과를 낸다
        List<ChatMessage> latestPage = messages.findLatest(1L, 31);
        assertThat(latestPage).extracting(ChatMessage::getId).containsExactlyElementsOf(ids.subList(ids.size() - 31, ids.size()).reversed());
        assertThat(messages.findBefore(1L, cut, 31)).extracting(ChatMessage::getId).hasSize(31).allMatch(id -> id < cut)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        assertThat(messages.findAfter(1L, ids.get(20), 100)).extracting(ChatMessage::getId).hasSize(100).allMatch(id -> id > ids.get(20))
                .isSortedAccordingTo(java.util.Comparator.naturalOrder());
        assertThat(messages.findAfter(1L, ids.get(ids.size() - 1), 100)).isEmpty();
        assertThat(messages.findMaxIdUpTo(1L, cut + 100000)).isEqualTo(ids.get(ids.size() - 1));
        assertThat(messages.findMaxIdUpTo(2L, 1L)).isNull();
        assertThat(latestPage.get(0).getCreatedAt()).as("native 결과도 엔티티로 매핑된다").isNotNull();
    }

    @Test
    void 저장소로_저장하면_감사_시각이_채워지고_clientMessageId로_기존_행을_찾는다() {
        ChatMessage saved = messages.saveAndFlush(ChatMessage.of(1L, 1L, "안녕하세요", "client-msg-0001"));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(messages.findByClientMessageIdAndSenderId("client-msg-0001", 1L)).get().extracting(ChatMessage::getId).isEqualTo(saved.getId());
        assertThat(messages.findByClientMessageIdAndSenderId("client-msg-0001", 2L)).isEmpty();
        assertThat(messages.findByClientMessageIdAndSenderId("CLIENT-MSG-0001", 1L)).as("대소문자 구분").isEmpty();
        assertThatThrownBy(() -> messages.saveAndFlush(ChatMessage.of(1L, 1L, "중복", "client-msg-0001"))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void 읽음_포인터는_단조_증가하고_최초_INSERT와_재시도_경로가_동작한다() {
        assertThat(readState.find(1L, 1L)).isEmpty();
        readState.advance(1L, 1L, 10, NOW);                       // 행 없음 -> INSERT
        assertThat(readState.find(1L, 1L)).contains(10L);
        readState.advance(1L, 1L, 7, NOW.plusSeconds(1));          // 내려가지 않는다
        assertThat(readState.find(1L, 1L)).contains(10L);
        readState.advance(1L, 1L, 10, NOW.plusSeconds(2));         // 같은 값 재호출 안전
        assertThat(readState.find(1L, 1L)).contains(10L);
        readState.advance(1L, 1L, 25, NOW.plusSeconds(3));
        assertThat(readState.find(1L, 1L)).contains(25L);
        assertThat(readState.find(1L, 2L)).as("다른 사용자와 독립").isEmpty();
        assertThat(readState.find(2L, 1L)).as("다른 매칭과 독립").isEmpty();

        // DuplicateKey 재시도 경로: UPDATE 와 INSERT 사이에 다른 탭이 INSERT 한 상황을 흉내 낸다
        // (UPDATE 가 0행을 보고 INSERT 로 간 뒤 Duplicate 가 나는 경합은 아래 동시성 테스트가 실제로 일으킨다)
        readState.advance(2L, 2L, 0, NOW);                         // 0 도 INSERT 가능(CHECK >= 0)
        assertThat(readState.find(2L, 2L)).contains(0L);
        readState.advance(2L, 2L, 3, NOW);
        assertThat(readState.find(2L, 2L)).contains(3L);
    }

    @Test
    void 동시에_두_탭이_읽음을_갱신해도_교착이_없고_최댓값이_남는다() throws Exception {
                ExecutorService pool = Executors.newFixedThreadPool(6);
        int rounds = 300;
        int[] failures = new int[1];
        List<String> errors = new ArrayList<>();
        try {
            for (int round = 0; round < rounds; round++) {
                // 매 라운드 새 사용자(1) 행으로 시작 -> 최초 INSERT 경합을 일으킨다
                jdbc.update("DELETE FROM chat_read_state WHERE match_id = 1");
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Object>> futures = new ArrayList<>();
                long[][] plan = {{1, 5}, {1, 9}, {2, 4}, {2, 8}};   // {userId, value}: 사용자별 두 탭
                for (long[] p : plan) {
                    Callable<Object> task = () -> {
                        start.await();
                        // 호출자 트랜잭션 안에서 불러도 advance 는 트랜잭션 밖에서 실행되어야 한다
                        tx.executeWithoutResult(s -> readState.advance(1L, p[0], p[1], NOW));
                        return null;
                    };
                    futures.add(pool.submit(task));
                }
                // 읽음 갱신과 같은 시간에 메시지 INSERT 도 섞는다
                int r = round;
                futures.add(pool.submit(() -> {
                    start.await();
                    tx.executeWithoutResult(s -> jdbc.update(INSERT_MESSAGE, 1, 1, "x", String.format("r%07d-aaaa", r)));
                    return null;
                }));
                start.countDown();
                for (Future<Object> f : futures) {
                    try {
                        f.get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        failures[0]++;
                        if (errors.size() < 3) {
                            errors.add(String.valueOf(e.getCause() == null ? e : e.getCause()));
                        }
                    }
                }
                assertThat(failures[0]).as("round " + round + " 실패 " + errors).isZero();
                assertThat(readState.find(1L, 1L)).as("round " + round).contains(9L);
                assertThat(readState.find(1L, 2L)).as("round " + round).contains(8L);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(failures[0]).as("교착·잠금 대기 초과 등 실패 " + errors).isZero();
    }
}
