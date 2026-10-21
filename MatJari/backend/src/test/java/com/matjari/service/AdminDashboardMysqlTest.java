package com.matjari.service;

import com.matjari.dto.request.PerformanceCreateRequest;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 관리자 대시보드 집계 SQL 실제 MySQL 검증 (MATJARI_IT_* 환경변수가 있을 때만, `X_dash_it` 별도 DB).
 * 모든 집계·목록 쿼리가 실제 스키마(V1~V24)에서 오류 없이 돌고, 공연+티켓 등록·신고·AI 사용 기록이 수치·대기열·알림에 잡히는지 본다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AdminDashboardMysqlTest {

    private static String dashUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "") + "_dash_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.assertSafe();
        registry.add("spring.datasource.url", AdminDashboardMysqlTest::dashUrl);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdminDashboardService dashboard;
    @Autowired
    private PerformanceService performanceService;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_dash_it");
        for (String t : List.of("ai_extract_usage", "ai_extract_cache", "admin_action_log", "abuse_report", "exchange_request",
                "ticket", "performance_zone", "performance_session", "performance", "users")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (1,'u1@t','x','U1','USER',NOW(6))");
        jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (2,'u2@t','x','U2','ADMIN',NOW(6))");
    }

    @Test
    void allQueriesRunAndReflectData() {
        LocalDateTime show = LocalDateTime.now().plusDays(3).withHour(19).withMinute(0).withSecond(0).withNano(0);
        performanceService.create(1L, new PerformanceCreateRequest("https://ticket.melon.com/performance/index.htm?prodId=7",
                "공연", "홀", List.of(show), List.of(new PerformanceCreateRequest.ZoneInput("A", null)), null,
                new PerformanceCreateRequest.TicketInput(show, "A", "1", "2")));
        // 신고는 근거 매칭(FK)이 필요하지만 집계만 보므로 이 행만 FK 검사를 끄고 넣는다(같은 연결에서)
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            try (var st = con.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                st.execute("INSERT INTO abuse_report(reporter_id,reported_id,match_id,reason,detail,status,created_at,updated_at) "
                        + "VALUES (2,1,999,'ABUSE',NULL,'OPEN',NOW(6) - INTERVAL 2 DAY,NOW(6))");
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        jdbc.update("INSERT INTO ai_extract_usage(user_id,source_key,used_at,refunded_at) VALUES (1,'melon:7',NOW(6),NULL),(1,'melon:8',NOW(6),NOW(6))");

        AdminDashboardService.Response r = dashboard.dashboard();

        assertThat(r.kpis().get("users")).containsEntry("total", 2L).containsEntry("admins", 1L).containsEntry("newToday", 2L);
        assertThat(r.kpis().get("performances")).containsEntry("total", 1L).containsEntry("withZones", 1L).containsEntry("upcoming", 1L);
        assertThat(r.kpis().get("tickets")).containsEntry("active", 1L).containsEntry("newToday", 1L);
        assertThat(r.kpis().get("queues")).containsEntry("openReports", 1L);
        assertThat(r.kpis().get("ai")).containsEntry("usedMonth", 1L).containsEntry("refundedMonth", 1L).containsEntry("usedToday", 1L);
        assertThat(r.daily()).hasSize(AdminDashboardService.DAYS);
        assertThat(r.daily().get(r.daily().size() - 1).tickets()).isEqualTo(1);
        assertThat(r.topPerformances()).hasSize(1);
        assertThat(r.topPerformances().get(0)).containsEntry("activeTickets", 1L);
        assertThat(r.upcomingSessions()).hasSize(1);
        assertThat(r.openReports()).hasSize(1);
        assertThat(r.openReports().get(0).get("createdAt")).isInstanceOf(LocalDateTime.class);
        assertThat(r.openReportReasons()).containsEntry("ABUSE", 1L);
        assertThat(r.recentUsers()).hasSize(2);
        assertThat(r.alerts()).anySatisfy(a -> {
            assertThat(a.level()).isEqualTo("danger"); // 24시간 넘은 신고
            assertThat(a.link()).isEqualTo("/admin/reports");
        });
    }
}
