package com.matjari.repository;

import com.matjari.dto.request.PerformanceCreateRequest;
import com.matjari.dto.response.PerformanceDetailResponse;
import com.matjari.dto.response.PerformancePreviewResponse;
import com.matjari.dto.response.PerformanceSourceDtos.SourceItem;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.service.AdminPerformanceService;
import com.matjari.service.PerformanceService;
import com.matjari.service.PerformanceSourceService;
import com.matjari.service.linkinfo.PerformancePreviewService;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * V32(performance_source) 실제 MySQL 검증 (MATJARI_IT_* 환경변수가 있을 때만). `X_srcgrp_it` 별도 DB.
 * 확인: ddl-auto=validate 로 엔티티 매핑, 공연 등록이 대표 링크 행을 함께 만듦, 같은 공연 후보·등록 409/confirm, 링크 추가 규칙(다른 공연 409·멱등·숨김·상한),
 * lookup 이 추가 링크를 찾음, 같은 링크를 두 공연에 동시에 달 때 하나만 성공, 관리자 추가·삭제·대표 삭제 불가·조치 기록, 공연 완전 삭제가 링크 행을 먼저 지움.
 * 미리보기(외부 요청)는 목으로 대체한다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PerformanceSourceMysqlTest {

    private static final LocalDateTime D1 = LocalDateTime.of(2099, 12, 10, 19, 0);
    private static final LocalDateTime D2 = LocalDateTime.of(2099, 12, 12, 19, 0);
    private static final String MELON = "https://ticket.melon.com/performance/index.htm?prodId=100";
    private static final String NOL = "https://nol.yanolja.com/ticket/products/26014836";

    private static String srcUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "").replaceAll("_srcgrp$", "") + "_srcgrp_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.assertSafe();
        registry.add("spring.datasource.url", PerformanceSourceMysqlTest::srcUrl);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PerformanceService performanceService;
    @Autowired
    private PerformanceSourceService sourceService;
    @Autowired
    private AdminPerformanceService adminService;

    @MockBean
    private PerformancePreviewService previewService;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_srcgrp_it");
        for (String t : List.of("ai_extract_usage", "admin_action_log", "ticket", "performance_edit_proposal", "performance_zone",
                "performance_session", "performance_source", "performance", "refresh_token", "users")) {
            jdbc.update("DELETE FROM " + t);
        }
        jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (1,'u1@t','x','U1','USER',NOW(6))");
        jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (9,'a9@t','x','관리자','ADMIN',NOW(6))");
        when(previewService.preview(anyLong(), anyString())).thenReturn(
                PerformancePreviewResponse.ok("interpark", "아이유 콘서트", "코엑스 D홀",
                        LocalDate.of(2099, 12, 11), LocalDate.of(2099, 12, 12), null));
    }

    private PerformanceDetailResponse register(String url, String title, String venue, boolean confirm) {
        return performanceService.create(1L, new PerformanceCreateRequest(url, title, venue, List.of(D1, D2), null, null, null, confirm));
    }

    private Long registerMelon() {
        return register(MELON, "아이유 콘서트", "코엑스 D홀 (COEX HALL D)", false).id();
    }

    private int sourceRows(Long performanceId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM performance_source WHERE performance_id = ?", Integer.class, performanceId);
    }

    @Test
    void 공연_등록은_대표_링크_행을_함께_만들고_상세에_담긴다() {
        PerformanceDetailResponse created = register(MELON, "아이유 콘서트", "코엑스 D홀", false);

        assertThat(sourceRows(created.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_key FROM performance_source WHERE performance_id = ?", String.class, created.id()))
                .isEqualTo("melon:100");
        assertThat(jdbc.queryForObject("SELECT added_by_id FROM performance_source WHERE performance_id = ?", Long.class, created.id()))
                .isEqualTo(1L);
        assertThat(created.sources()).singleElement().satisfies(s -> {
            assertThat(s.primary()).isTrue();
            assertThat(s.site()).isEqualTo("melon");
            assertThat(s.siteName()).isEqualTo("멜론티켓");
            assertThat(s.id()).isNotNull();
        });
        assertThat(performanceService.get(created.id(), 1L).sources()).hasSize(1);
        assertThat(performanceService.lookup(MELON).performanceId()).isEqualTo(created.id());
    }

    @Test
    void 비슷한_공연이_있으면_409_후보를_돌려주고_confirm이면_새_공연으로_등록된다() {
        Long first = registerMelon();

        assertThatThrownBy(() -> register("https://ticket.melon.com/performance/index.htm?prodId=200", "아이유 콘서트", "코엑스 D홀", false))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getDetails()).containsEntry("code", "SIMILAR_PERFORMANCE_EXISTS");
                    assertThat((List<?>) e.getDetails().get("candidates")).hasSize(1);
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance", Integer.class)).isEqualTo(1);

        Long second = register("https://ticket.melon.com/performance/index.htm?prodId=200", "아이유 콘서트", "코엑스 D홀", true).id();
        assertThat(second).isNotEqualTo(first);
        // 공연장이 다르면 확인 없이 등록
        assertThat(register("https://ticket.melon.com/performance/index.htm?prodId=300", "다른 공연", "KSPO DOME", false).id()).isNotNull();
        // 관리자는 검사를 건너뛴다
        assertThat(performanceService.create(9L, new PerformanceCreateRequest("https://ticket.melon.com/performance/index.htm?prodId=400",
                "아이유 콘서트", "코엑스 D홀", List.of(D1), null, null, null, null), true).id()).isNotNull();
    }

    @Test
    void 같은_공연_후보_조회는_공연장_정규화와_기간_겹침으로_찾는다() {
        Long first = registerMelon();
        var hit = sourceService.findSimilar("[아이유] 콘서트", "코엑스 D홀", LocalDate.of(2099, 12, 12), LocalDate.of(2099, 12, 20));
        assertThat(hit).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(first);
            assertThat(c.sessionCount()).isEqualTo(2);
            assertThat(c.firstSessionAt()).isEqualTo(D1);
            assertThat(c.lastSessionAt()).isEqualTo(D2);
            assertThat(c.sites()).containsExactly("melon");
        });
        assertThat(sourceService.findSimilar("아이유", "코엑스 D홀", LocalDate.of(2099, 12, 13), LocalDate.of(2099, 12, 20))).isEmpty();
        assertThat(sourceService.findSimilar("아이유", "KSPO DOME", LocalDate.of(2099, 12, 12), null)).isEmpty();
        // 숨긴 공연은 후보가 아니다
        adminService.hide(9L, first, "점검");
        assertThat(sourceService.findSimilar("아이유", "코엑스 D홀", LocalDate.of(2099, 12, 12), null)).isEmpty();
    }

    @Test
    void 링크_추가_규칙_다른_공연_409_멱등_상한_lookup() {
        Long first = registerMelon();
        Long other = register("https://ticket.melon.com/performance/index.htm?prodId=300", "다른 공연", "KSPO DOME", false).id();

        PerformanceSourceService.AddResult added = sourceService.addByUser(1L, first, NOL);
        assertThat(added.created()).isTrue();
        assertThat(added.sources()).extracting(SourceItem::site).containsExactly("melon", "interpark");
        assertThat(added.sources()).extracting(SourceItem::primary).containsExactly(true, false);
        assertThat(sourceRows(first)).isEqualTo(2);

        // lookup 과 등록 중복 확인이 추가 링크를 찾는다
        assertThat(performanceService.lookup(NOL).performanceId()).isEqualTo(first);
        assertThatThrownBy(() -> register(NOL, "아무거나", "아무데나", true))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("performanceId", first));
        // 같은 공연에 다시: 멱등
        assertThat(sourceService.addByUser(1L, first, NOL).created()).isFalse();
        assertThat(sourceRows(first)).isEqualTo(2);
        // 다른 공연에: 409 + 이미 달린 공연 id
        assertThatThrownBy(() -> sourceService.addByUser(1L, other, NOL))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getDetails()).containsEntry("code", "SOURCE_ALREADY_REGISTERED").containsEntry("performanceId", first);
                });
        // 같은 공연이 아니면 422 (다른 공연장 미리보기)
        when(previewService.preview(anyLong(), anyString())).thenReturn(PerformancePreviewResponse.ok("interpark", "아이유 콘서트", "KSPO DOME",
                LocalDate.of(2099, 12, 11), LocalDate.of(2099, 12, 12), null));
        assertThatThrownBy(() -> sourceService.addByUser(1L, first, "https://nol.yanolja.com/ticket/products/2"))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SOURCE_NOT_SAME_PERFORMANCE"));

        // 상한 6개 (대표 1 + 추가 5)
        when(previewService.preview(anyLong(), anyString())).thenReturn(PerformancePreviewResponse.ok("interpark", "아이유 콘서트", "코엑스 D홀",
                LocalDate.of(2099, 12, 11), LocalDate.of(2099, 12, 12), null));
        for (int i = 1; i <= 4; i++) {
            assertThat(sourceService.addByUser(1L, first, "https://nol.yanolja.com/ticket/products/" + (1000 + i)).created()).isTrue();
        }
        assertThat(sourceRows(first)).isEqualTo(6);
        assertThatThrownBy(() -> sourceService.addByUser(1L, first, "https://nol.yanolja.com/ticket/products/2000"))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SOURCE_LIMIT_REACHED"));

        // 숨긴 공연은 409 PERFORMANCE_HIDDEN
        adminService.hide(9L, first, "점검");
        assertThatThrownBy(() -> sourceService.addByUser(1L, first, "https://nol.yanolja.com/ticket/products/3000"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "PERFORMANCE_HIDDEN"));
    }

    @Test
    void 같은_링크를_두_공연에_동시에_달면_하나만_성공한다() throws Exception {
        Long first = registerMelon();
        Long second = register("https://ticket.melon.com/performance/index.htm?prodId=200", "아이유 콘서트", "코엑스 D홀", true).id();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Callable<String>> tasks = List.of(
                    () -> race(start, first),
                    () -> race(start, second));
            List<Future<String>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            List<String> results = new java.util.ArrayList<>();
            for (Future<String> f : futures) {
                results.add(f.get());
            }
            assertThat(results).containsExactlyInAnyOrder("CREATED", "CONFLICT");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_source WHERE source_key = 'interpark:26014836'", Integer.class))
                .isEqualTo(1);
    }

    private String race(CountDownLatch start, Long performanceId) throws InterruptedException {
        start.await();
        try {
            return sourceService.addByUser(1L, performanceId, NOL).created() ? "CREATED" : "SAME";
        } catch (ConflictException e) {
            return "CONFLICT";
        }
    }

    @Test
    void 관리자_추가_삭제는_검증없이_하고_대표_링크는_지울_수_없으며_조치_기록이_남는다() {
        Long first = registerMelon();
        // 같은 공연 검증 없이(미리보기를 읽지 않음) 추가
        var added = adminService.addSource(9L, first, new AdminPerformanceService.SourceAddRequest(NOL, "다른 판매처"));
        assertThat(added.created()).isTrue();
        assertThat(added.added().getAddedById()).isEqualTo(9L);
        assertThat(adminService.addSource(9L, first, new AdminPerformanceService.SourceAddRequest(NOL, "또")).created()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE action = 'PERFORMANCE_EDIT' AND target_id = ?",
                Integer.class, first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT detail FROM admin_action_log WHERE target_id = ?", String.class, first))
                .contains("addedSource").contains("interpark:26014836");
        assertThat(adminService.get(first).sources()).extracting(SourceItem::primary).containsExactly(true, false);

        Long primaryId = adminService.get(first).sources().get(0).id();
        assertThatThrownBy(() -> adminService.removeSource(9L, first, primaryId, "x"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "PRIMARY_SOURCE"));
        assertThat(sourceRows(first)).isEqualTo(2);

        var remaining = adminService.removeSource(9L, first, added.added().getId(), "잘못 붙임");
        assertThat(remaining).hasSize(1);
        assertThat(sourceRows(first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE action = 'PERFORMANCE_EDIT' AND target_id = ?",
                Integer.class, first)).isEqualTo(2);
        // 삭제한 링크는 다시 등록할 수 있다
        assertThat(performanceService.lookup(NOL).exists()).isFalse();
    }

    @Test
    void 공연_완전_삭제는_링크_행을_먼저_지운다() {
        Long first = registerMelon();
        sourceService.addByUser(1L, first, NOL);
        adminService.hide(9L, first, "점검");

        adminService.delete(9L, first, "정리");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_source", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance", Integer.class)).isZero();
    }

    @Test
    void 스키마_제약_유일_키와_FK() {
        Long first = registerMelon();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'performance_source' AND INDEX_NAME = 'uk_performance_source_source_key' AND NON_UNIQUE = 0", Integer.class)).isEqualTo(1);
        // FK 인덱스는 단일 컬럼
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = 'performance_source' AND INDEX_NAME = 'idx_performance_source_performance'", Integer.class)).isEqualTo(1);
        // 키는 대소문자를 구분한다(utf8mb4_bin)
        jdbc.update("INSERT INTO performance_source(performance_id, source_key, source_url, created_at) VALUES (?, 'url:Example.com/A', 'u', NOW(6))", first);
        jdbc.update("INSERT INTO performance_source(performance_id, source_key, source_url, created_at) VALUES (?, 'url:example.com/a', 'u', NOW(6))", first);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_source(performance_id, source_key, source_url, created_at) VALUES (?, 'melon:100', 'u', NOW(6))", first))
                .isInstanceOf(DataAccessException.class); // 대표 링크 키와 중복
        assertThatThrownBy(() -> jdbc.update("INSERT INTO performance_source(performance_id, source_key, source_url, created_at) VALUES (999999, 'melon:7', 'u', NOW(6))"))
                .isInstanceOf(DataAccessException.class); // 없는 공연
    }
}
