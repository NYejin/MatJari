package com.matjari.service;

import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.PerformanceEditProposalDtos.AdminEditProposal;
import com.matjari.dto.response.PerformanceEditProposalDtos.CreateRequest;
import com.matjari.dto.response.PerformanceEditProposalDtos.EditProposal;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공연 수정 제안의 실제 MySQL 통합 테스트(서비스 + 트랜잭션 + 잠금). MATJARI_IT_* 환경변수가 없으면 건너뛴다(RealMysqlSupport).
 * 확인: 생성(404·400·EDIT_NO_CHANGE·중복 PENDING 409·사용자 상한 422·처리 후 재제안), 승인 반영과 old 스냅샷,
 * 거절, 같은 공연의 다른 PENDING 보존, 동시 승인 경합(1건만 성공), 동시 생성 경합(상한·중복), 서로 다른 제안 동시 승인(교착 없음).
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PerformanceEditProposalMysqlTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.cleanDatabase();
        registry.add("spring.datasource.url", RealMysqlSupport::url);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private PerformanceEditProposalService service;
    @Autowired
    private JdbcTemplate jdbc;

    private long seq;
    private long admin;

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("performance_edit_proposal", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        seq = 0;
        admin = user("관리자", "ADMIN");
    }

    private static CreateRequest title(String t) {
        return new CreateRequest(t, null, "오타예요");
    }

    private long user(String nickname, String role) {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, 'x', ?)",
                "u" + seq + "@t.com", nickname, role);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long performance(String title, String venue) {
        seq++;
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), ?, 'http://x/1', ?, ?, ?)", "k" + seq, title, venue, admin);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String perfTitle(long id) {
        return jdbc.queryForObject("SELECT title FROM performance WHERE id = ?", String.class, id);
    }

    private String perfVenue(long id) {
        return jdbc.queryForObject("SELECT venue_name FROM performance WHERE id = ?", String.class, id);
    }

    // ------------------------------------------------------------------ 생성

    @Test
    void 생성_성공은_현재_값과_제안_값을_돌려주고_목록에_보인다() {
        long u = user("제안자", "USER");
        long perf = performance("옛 제목", "옛 공연장");

        EditProposal p = service.create(u, perf, new CreateRequest("  새   제목 ", "옛 공연장", " 사유 "));

        assertThat(p.status()).isEqualTo("PENDING");
        assertThat(p.newTitle()).isEqualTo("새 제목");
        assertThat(p.newVenueName()).isNull(); // 현재와 같은 항목은 뺀다
        assertThat(p.reason()).isEqualTo("사유");
        assertThat(p.currentTitle()).isEqualTo("옛 제목");
        assertThat(p.currentVenueName()).isEqualTo("옛 공연장");
        assertThat(p.createdAt()).isNotNull();
        assertThat(p.reviewedAt()).isNull();
        PageResponse<EditProposal> mine = service.listMine(u, 0, 20);
        assertThat(mine.totalElements()).isEqualTo(1);
        assertThat(mine.content().get(0).id()).isEqualTo(p.id());
        assertThat(service.listMine(user("남", "USER"), 0, 20).totalElements()).isZero();
    }

    @Test
    void 생성_오류_계약() {
        long u = user("제안자", "USER");
        long perf = performance("제목", "공연장");

        assertThatThrownBy(() -> service.create(u, 999_999L, title("x"))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.create(u, perf, new CreateRequest("  ", null, "사유")))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.create(u, perf, title("가".repeat(201))))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.create(u, perf, new CreateRequest("x", null, "가".repeat(501))))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.create(u, perf, new CreateRequest("제목", "공연장", null)))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("EDIT_NO_CHANGE"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_edit_proposal", Long.class)).isZero();
    }

    @Test
    void 같은_공연에_내_PENDING이_있으면_409이고_처리되면_다시_제안할_수_있다() {
        long u = user("제안자", "USER");
        long other = user("다른 사람", "USER");
        long perf = performance("제목", "공연장");
        EditProposal first = service.create(u, perf, title("A"));

        assertThatThrownBy(() -> service.create(u, perf, title("B"))).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "EDIT_PROPOSAL_ALREADY_PENDING"));
        assertThat(service.create(other, perf, title("C")).status()).isEqualTo("PENDING"); // 다른 사용자는 가능

        service.reject(admin, first.id(), "안 돼요");
        assertThat(service.create(u, perf, title("B")).status()).isEqualTo("PENDING");
    }

    @Test
    void 사용자당_PENDING_상한은_3건이고_처리하면_자리가_난다() {
        long u = user("제안자", "USER");
        List<EditProposal> made = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            made.add(service.create(u, performance("제목" + i, "공연장"), title("새" + i)));
        }
        long fourth = performance("제목4", "공연장");

        assertThatThrownBy(() -> service.create(u, fourth, title("새4")))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getCode()).isEqualTo("EDIT_PROPOSAL_LIMIT_REACHED"));

        service.reject(admin, made.get(0).id(), null);
        assertThat(service.create(u, fourth, title("새4")).status()).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ 승인·거절

    @Test
    void 승인하면_공연이_바뀌고_이전_값이_스냅샷된다() {
        long u = user("제안자", "USER");
        long perf = performance("옛 제목", "옛 공연장");
        EditProposal p = service.create(u, perf, new CreateRequest("새 제목", null, null));

        AdminEditProposal approved = service.approve(admin, p.id());

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.oldTitle()).isEqualTo("옛 제목");
        assertThat(approved.oldVenueName()).isEqualTo("옛 공연장");
        assertThat(approved.currentTitle()).isEqualTo("새 제목");
        assertThat(approved.reviewerNickname()).isEqualTo("관리자");
        assertThat(approved.proposerNickname()).isEqualTo("제안자");
        assertThat(approved.reviewedAt()).isNotNull();
        assertThat(perfTitle(perf)).isEqualTo("새 제목");
        assertThat(perfVenue(perf)).isEqualTo("옛 공연장"); // 바꾸지 않은 항목은 그대로
        assertThat(jdbc.queryForObject("SELECT status FROM performance_edit_proposal WHERE id = ?", String.class, p.id()))
                .isEqualTo("APPROVED");
    }

    @Test
    void 공연장만_제안해도_승인된다_그리고_같은_공연의_다른_PENDING은_그대로다() {
        long a = user("가", "USER");
        long b = user("나", "USER");
        long perf = performance("제목", "옛 공연장");
        EditProposal pa = service.create(a, perf, new CreateRequest(null, "새 공연장", null));
        EditProposal pb = service.create(b, perf, new CreateRequest("다른 제목", null, null));

        service.approve(admin, pa.id());

        assertThat(perfVenue(perf)).isEqualTo("새 공연장");
        AdminEditProposal stillPending = service.listForAdmin("PENDING", 0, 20).content().get(0);
        assertThat(stillPending.id()).isEqualTo(pb.id());
        assertThat(stillPending.currentVenueName()).isEqualTo("새 공연장"); // 승인 시점 이후의 현재 값과 비교해 보여준다
        // b 의 제안도 승인 시점 현재 값이 스냅샷된다(a 의 승인 결과)
        AdminEditProposal approvedB = service.approve(admin, pb.id());
        assertThat(approvedB.oldVenueName()).isEqualTo("새 공연장");
        assertThat(perfTitle(perf)).isEqualTo("다른 제목");
    }

    @Test
    void 거절은_공연을_바꾸지_않고_사유를_남기며_처리된_제안은_다시_처리할_수_없다() {
        long u = user("제안자", "USER");
        long perf = performance("제목", "공연장");
        EditProposal p = service.create(u, perf, title("새"));

        AdminEditProposal rejected = service.reject(admin, p.id(), "  근거가 부족해요 ");

        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.reviewNote()).isEqualTo("근거가 부족해요");
        assertThat(rejected.oldTitle()).isNull();
        assertThat(perfTitle(perf)).isEqualTo("제목");
        assertThatThrownBy(() -> service.approve(admin, p.id())).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "PROPOSAL_ALREADY_REVIEWED"));
        assertThatThrownBy(() -> service.reject(admin, p.id(), null)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.approve(admin, 999_999L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.reject(admin, p.id(), "가".repeat(301))).isInstanceOf(FieldValidationException.class);
        assertThat(service.listMine(u, 0, 20).content().get(0).reviewNote()).isEqualTo("근거가 부족해요");
    }

    @Test
    void 관리자_목록은_상태_필터와_페이지를_따른다() {
        long perf1 = performance("제목1", "공연장");
        long perf2 = performance("제목2", "공연장");
        long u = user("제안자", "USER");
        EditProposal p1 = service.create(u, perf1, title("A"));
        service.create(u, perf2, title("B"));
        service.approve(admin, p1.id());

        assertThat(service.listForAdmin("PENDING", 0, 20).totalElements()).isEqualTo(1);
        assertThat(service.listForAdmin("approved", 0, 20).totalElements()).isEqualTo(1);
        assertThat(service.listForAdmin(null, 0, 1).totalPages()).isEqualTo(2);
        assertThat(service.listForAdmin("REJECTED", 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.listForAdmin("BOGUS", 0, 20)).isInstanceOf(FieldValidationException.class);
    }

    // ------------------------------------------------------------------ 동시성

    private <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    return task.call();
                } catch (Exception e) {
                    return e;
                }
            }));
        }
        start.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    @Test
    void 같은_제안의_동시_승인은_한_건만_성공한다() throws Exception {
        for (int round = 0; round < 10; round++) {
            long u = user("제안자" + round, "USER");
            long perf = performance("제목" + round, "공연장");
            long id = service.create(u, perf, title("새 제목" + round)).id();
            List<Callable<AdminEditProposal>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> service.approve(admin, id));
            }

            List<Object> results = runConcurrently(tasks);

            long ok = results.stream().filter(r -> r instanceof AdminEditProposal).count();
            long conflict = results.stream().filter(r -> r instanceof ConflictException).count();
            assertThat(ok).as("round %d results=%s", round, results).isEqualTo(1);
            assertThat(conflict).isEqualTo(7);
            assertThat(perfTitle(perf)).isEqualTo("새 제목" + round);
            assertThat(jdbc.queryForObject("SELECT old_title FROM performance_edit_proposal WHERE id = ?", String.class, id))
                    .isEqualTo("제목" + round);
        }
    }

    @Test
    void 같은_제안의_동시_승인과_거절은_한쪽만_성공한다() throws Exception {
        for (int round = 0; round < 10; round++) {
            long u = user("제안자" + round, "USER");
            long perf = performance("제목" + round, "공연장");
            long id = service.create(u, perf, title("새 제목" + round)).id();
            List<Callable<AdminEditProposal>> tasks = new ArrayList<>();
            tasks.add(() -> service.approve(admin, id));
            tasks.add(() -> service.reject(admin, id, "no"));
            tasks.add(() -> service.approve(admin, id));
            tasks.add(() -> service.reject(admin, id, "no"));

            List<Object> results = runConcurrently(tasks);

            assertThat(results.stream().filter(r -> r instanceof AdminEditProposal).count()).as("%s", results).isEqualTo(1);
            assertThat(results.stream().filter(r -> r instanceof ConflictException).count()).isEqualTo(3);
            String status = jdbc.queryForObject("SELECT status FROM performance_edit_proposal WHERE id = ?", String.class, id);
            // 공연은 APPROVED 일 때만 바뀐다(결과가 어긋나지 않는다)
            assertThat(perfTitle(perf)).isEqualTo("APPROVED".equals(status) ? "새 제목" + round : "제목" + round);
        }
    }

    @Test
    void 같은_공연의_서로_다른_제안을_동시에_승인해도_교착_없이_모두_반영된다() throws Exception {
        long perf = performance("제목", "공연장");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(service.create(user("제안자" + i, "USER"), perf, title("새 제목" + i)).id());
        }
        List<Callable<AdminEditProposal>> tasks = new ArrayList<>();
        for (Long id : ids) {
            tasks.add(() -> service.approve(admin, id));
        }

        List<Object> results = runConcurrently(tasks);

        assertThat(results).as(results.toString()).allMatch(r -> r instanceof AdminEditProposal);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_edit_proposal WHERE status = 'APPROVED'", Long.class))
                .isEqualTo(6);
        // 마지막 승인이 공연 값을 정하고, old 스냅샷은 서로 연결된다(각자 직전 승인 값 또는 원래 값)
        assertThat(perfTitle(perf)).startsWith("새 제목");
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT old_title) FROM performance_edit_proposal", Long.class))
                .isEqualTo(6);
    }

    @Test
    void 같은_사용자의_동시_생성도_상한과_중복을_넘지_못한다() throws Exception {
        for (int round = 0; round < 5; round++) {
            long u = user("제안자" + round, "USER");
            List<Long> perfs = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                perfs.add(performance("제목" + round + "_" + i, "공연장"));
            }
            List<Callable<EditProposal>> tasks = new ArrayList<>();
            for (Long perf : perfs) {
                tasks.add(() -> service.create(u, perf, title("새")));
            }
            // 같은 공연에 대한 중복 요청도 섞는다
            tasks.add(() -> service.create(u, perfs.get(0), title("또")));
            tasks.add(() -> service.create(u, perfs.get(0), title("또또")));

            List<Object> results = runConcurrently(tasks);

            assertThat(results.stream().filter(r -> r instanceof EditProposal).count()).as("%s", results).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM performance_edit_proposal WHERE proposer_id = ? AND status = 'PENDING'",
                    Long.class, u)).isEqualTo(3);
            assertThat(results.stream().filter(r -> !(r instanceof EditProposal))
                    .allMatch(r -> r instanceof BusinessRuleException || r instanceof ConflictException)).as("%s", results).isTrue();
        }
    }

    @Test
    void 생성과_승인이_동시에_돌아도_교착이_없다() throws Exception {
        long perf = performance("제목", "공연장");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(service.create(user("제안자" + i, "USER"), perf, title("새 제목" + i)).id());
        }
        List<Callable<Object>> tasks = new ArrayList<>();
        for (Long id : ids) {
            tasks.add(() -> service.approve(admin, id));
        }
        for (int i = 0; i < 5; i++) {
            long newcomer = user("새 제안자" + i, "USER");
            tasks.add(() -> service.create(newcomer, perf, title("동시 제안")));
        }

        List<Object> results = runConcurrently(tasks);

        assertThat(results).as(results.toString()).noneMatch(r -> r instanceof Exception);
        assertThat(results).hasSize(8);
    }
}
