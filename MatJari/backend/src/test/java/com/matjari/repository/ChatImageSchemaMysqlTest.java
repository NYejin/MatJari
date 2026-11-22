package com.matjari.repository;

import com.matjari.testsupport.RealMysqlSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V30(chat_message.image_key / image_deleted_at) 검증 (실제 MySQL, MATJARI_IT_* 환경변수가 있을 때만).
 * V11 까지 만든 DB 에 시드를 넣고 V30 까지 올려(실제 업그레이드 경로) 확인한다: 재실행 안전, CHECK 재작성(사진이면 빈 본문 허용),
 * 보관 정리 쿼리(닫힌 뒤 30일, 처리 전 신고가 있으면 제외, 이미 지운 것·열린 매칭 제외). `X_chatimg_it` 별도 DB 를 쓴다.
 * (주의: 이 환경에서는 실행하지 못했다 — MySQL 이 없는 곳에서는 건너뛴다.)
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ChatImageSchemaMysqlTest {

    private static String url() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "") + "_chatimg_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.assertSafe();
        registry.add("spring.datasource.url", ChatImageSchemaMysqlTest::url);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ChatMessageRepository messages;

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(url(), RealMysqlSupport.user(), RealMysqlSupport.password())
                .cleanDisabled(false).target(target).load();
    }

    @BeforeEach
    void setUp() {
        String[] hostAndDb = ExchangeCandidateQueryTest.parseHostAndDb(url().replace("?createDatabaseIfNotExist=true", "")
                .replace("&createDatabaseIfNotExist=true", ""));
        assertThat(hostAndDb).isNotNull();
        assertThat(hostAndDb[0]).isIn("localhost", "127.0.0.1");
        assertThat(hostAndDb[1]).endsWith("_chatimg_it");
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_chatimg_it");
        flyway("11").clean();
        flyway("11").migrate();
        seed();
        flyway("30").migrate();
    }

    /** V11 스키마 기준 시드(ChatSchemaMysqlTest 와 같다): 사용자 1~2, 매칭 1(CHATTING)과 2~6(CANCELED). */
    private void seed() {
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (?,?,?,?,?,NOW(6))", i, "u" + i + "@t", "x", "U" + i, "USER");
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
        for (int id = 2; id <= 6; id++) {
            jdbc.update("INSERT INTO exchange_match(id,request_a_id,request_b_id,ticket_a_id,ticket_b_id,user_a_id,user_b_id,status,"
                    + "created_at,updated_at,canceled_at,a_extra_type,b_extra_type) VALUES (?,1,2,1,2,2,1,'CANCELED',NOW(6),NOW(6),NOW(6),'ANY','ANY')", id);
        }
    }

    private static final String INSERT_TEXT = "INSERT INTO chat_message (match_id, sender_id, content, client_message_id, created_at) VALUES (?,?,?,?,NOW(6))";
    private static final String INSERT_IMAGE = "INSERT INTO chat_message (match_id, sender_id, content, client_message_id, image_key, created_at) VALUES (?,?,'',?,?,NOW(6))";

    private static String key(int n) {
        return String.format("00000000-0000-4000-8000-%012d", n);
    }

    @Test
    void CHECK는_사진이_있을_때만_빈_본문을_허용하고_재실행해도_안전하다() {
        assertThat(jdbc.update(INSERT_TEXT, 1, 1, "안녕", "client-msg-0001")).isEqualTo(1);
        assertThat(jdbc.update(INSERT_IMAGE, 1, 1, "client-msg-0002", key(1))).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(INSERT_TEXT, 1, 1, "  ", "client-msg-0003"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_chat_message_content");
        assertThatThrownBy(() -> jdbc.update("UPDATE chat_message SET image_deleted_at = NOW(6) WHERE client_message_id = 'client-msg-0001'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_chat_message_image_deleted");

        jdbc.update("DELETE FROM flyway_schema_history WHERE version = '30'");
        assertThat(flyway("30").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_message", Integer.class)).as("재실행이 데이터를 지우지 않는다").isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = DATABASE()", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() "
                + "AND CONSTRAINT_NAME = 'ck_chat_message_content'", String.class)).contains("image_key");
    }

    @Test
    void 보관_정리_쿼리는_닫힌_뒤_30일이_지난_사진만_고르고_처리_전_신고가_있으면_제외한다() {
        LocalDateTime now = LocalDateTime.now();
        // 2: 31일 전 종료 -> 대상 / 3: 10일 전 종료 -> 아직 / 4: 31일 전 종료지만 OPEN 신고 -> 제외 / 5: 31일 전 종료, 신고는 처리됨 -> 대상 / 1: 열린 매칭 -> 제외
        jdbc.update("UPDATE exchange_match SET canceled_at = ? WHERE id IN (2,4,5)", now.minusDays(31));
        jdbc.update("UPDATE exchange_match SET canceled_at = ? WHERE id = 3", now.minusDays(10));
        jdbc.update("INSERT INTO abuse_report(reporter_id,reported_id,match_id,reason,status,created_at,updated_at) VALUES (1,2,4,'ABUSE','OPEN',NOW(6),NOW(6))");
        jdbc.update("INSERT INTO abuse_report(reporter_id,reported_id,match_id,reason,status,handled_by_id,handled_at,created_at,updated_at) "
                + "VALUES (1,2,5,'ABUSE','DISMISSED',1,NOW(6),NOW(6),NOW(6))");
        for (int match = 1; match <= 5; match++) {
            jdbc.update(INSERT_IMAGE, match, 1, "client-img-" + match + "000", key(match));
        }
        jdbc.update(INSERT_TEXT, 2, 1, "텍스트는 대상이 아니다", "client-txt-0002");
        // 이미 지운 사진은 다시 고르지 않는다
        jdbc.update(INSERT_IMAGE, 2, 1, "client-img-2999", key(99));
        jdbc.update("UPDATE chat_message SET image_deleted_at = NOW(6) WHERE image_key = ?", key(99));

        List<ChatMessageRepository.ExpiredImage> found = messages.findImagesPastRetention(now.minusDays(30), 100);

        assertThat(found).extracting(ChatMessageRepository.ExpiredImage::getImageKey).containsExactlyInAnyOrder(key(2), key(5));

        assertThat(messages.markImageDeleted(found.get(0).getId(), now)).isEqualTo(1);
        assertThat(messages.markImageDeleted(found.get(0).getId(), now)).as("두 번째는 바뀌는 행이 없다").isZero();
        assertThat(messages.findImagesPastRetention(now.minusDays(30), 100)).hasSize(1);
        assertThat(messages.findLiveImageKeys(List.of(key(1), key(99)))).containsExactly(key(1));
    }
}
