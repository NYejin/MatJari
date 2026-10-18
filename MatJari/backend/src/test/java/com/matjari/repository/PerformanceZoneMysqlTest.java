package com.matjari.repository;

import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceZone;
import com.matjari.dto.request.PerformanceCreateRequest;
import com.matjari.dto.request.PerformanceCreateRequest.ZoneInput;
import com.matjari.dto.response.PerformanceDetailResponse;
import com.matjari.dto.response.PerformanceZonesResponse;
import com.matjari.service.PerformanceService;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * V21(performance_zone, performance.seatmap_aspect) 실제 MySQL 검증 (MATJARI_IT_* 환경변수가 있을 때만).
 * `X_zoneschema_it` 별도 DB 를 쓴다. 확인: ddl-auto=validate 로 엔티티 매핑이 뜸, 공연 등록과 구역 저장 왕복(JSON 포함),
 * 같은 (공연, name_key) 유니크 위반, 다른 공연은 같은 키 허용, 모양 CHECK 거부, 구역 개수·조회 순서.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PerformanceZoneMysqlTest {

    private static final LocalDateTime SHOW = LocalDateTime.of(2099, 1, 1, 19, 0);

    private static String zoneUrl() {
        Matcher m = Pattern.compile("^(jdbc:mysql://[^/]+/)([^/?]+)(\\?.*)?$").matcher(RealMysqlSupport.url().trim());
        if (!m.matches()) {
            fail("MATJARI_IT_JDBC_URL 형식을 해석할 수 없습니다: " + RealMysqlSupport.url());
        }
        String db = m.group(2).replaceAll("_it$", "").replaceAll("_zoneschema$", "") + "_zoneschema_it";
        String query = m.group(3) == null ? "?" : m.group(3) + "&";
        return m.group(1) + db + query + "createDatabaseIfNotExist=true";
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.assertSafe();
        registry.add("spring.datasource.url", PerformanceZoneMysqlTest::zoneUrl);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private PerformanceService service;
    @Autowired
    private PerformanceRepository performanceRepository;
    @Autowired
    private PerformanceZoneRepository zoneRepository;

    @BeforeEach
    void setUp() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_zoneschema_it");
        jdbc.update("DELETE FROM performance_zone");
        jdbc.update("DELETE FROM performance_session");
        jdbc.update("DELETE FROM performance");
        jdbc.update("DELETE FROM users");
        jdbc.update("INSERT INTO users(id,email,password,nickname,role,created_at) VALUES (1,'u1@t','x','U1','USER',NOW(6))");
    }

    private static List<List<List<Integer>>> triangle(int x) {
        return List.of(List.of(List.of(x, 0), List.of(x + 10, 0), List.of(x, 10)));
    }

    private PerformanceDetailResponse register(String url, List<ZoneInput> zones, BigDecimal aspect) {
        return service.create(1L, new PerformanceCreateRequest(url, "제목", "공연장", List.of(SHOW), zones, aspect));
    }

    @Test
    void 등록과_함께_저장한_구역이_JSON_포함_왕복하고_정렬된다() {
        PerformanceDetailResponse created = register("https://ticket.yes24.com/Perf/1", List.of(
                new ZoneInput("B구역", List.of(List.of(List.of(1, 2), List.of(3, 4), List.of(5, 6)))),
                new ZoneInput("A 구역", null),
                new ZoneInput("b구역", triangle(100))), new BigDecimal("1.7777"));

        assertThat(created.zones()).extracting("name").containsExactly("B구역", "A 구역");
        PerformanceDetailResponse read = service.get(created.id(), 1L);
        assertThat(read.seatmapAspect()).isEqualByComparingTo("1.778");
        assertThat(read.zones()).extracting("name").containsExactly("B구역", "A 구역");
        assertThat(read.zones().get(0).shapePolygons()).hasSize(2);
        assertThat(read.zones().get(0).shapePolygons().get(0)).containsExactly(
                List.of(1, 2), List.of(3, 4), List.of(5, 6));
        assertThat(read.zones().get(1).shapePolygons()).isNull();

        PerformanceZonesResponse zones = service.getZones(created.id());
        assertThat(zones.seatmapAspect()).isEqualByComparingTo("1.778");
        assertThat(zones.zones()).hasSize(2);
        assertThat(zoneRepository.countByPerformanceId(created.id())).isEqualTo(2);
        assertThat(zoneRepository.findByPerformanceIdOrderBySortOrderAscIdAsc(created.id()))
                .extracting("nameKey").containsExactly("B구역", "A구역");
        assertThat(service.search(null, 0, 20, null).content()).singleElement()
                .satisfies(p -> assertThat(p.zoneCount()).isEqualTo(2));
    }

    @Test
    void 구역과_비율이_없는_기존_방식_등록은_그대로_동작한다() {
        PerformanceDetailResponse created = register("https://ticket.yes24.com/Perf/2", null, null);
        assertThat(created.zones()).isEmpty();
        PerformanceZonesResponse zones = service.getZones(created.id());
        assertThat(zones.zones()).isEmpty();
        assertThat(zones.seatmapAspect()).isNull();
        assertThat(service.search(null, 0, 20, null).content()).singleElement()
                .satisfies(p -> assertThat(p.zoneCount()).isZero());
    }

    @Test
    void 같은_공연의_같은_name_key는_유니크_위반_다른_공연은_허용() {
        Long first = register("https://ticket.yes24.com/Perf/3", List.of(new ZoneInput("A", null)), null).id();
        Long second = register("https://ticket.yes24.com/Perf/4", List.of(new ZoneInput("A", null)), null).id();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Performance p = performanceRepository.getReferenceById(first);
            zoneRepository.saveAndFlush(PerformanceZone.create(p, "A", "A", 5, null));
        })).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(PerformanceZone.UNIQUE_NAME_KEY);

        // utf8mb4_bin 이므로 대소문자 키는 다른 키(서비스는 항상 대문자 키를 만든다)
        tx.executeWithoutResult(s -> zoneRepository.saveAndFlush(
                PerformanceZone.create(performanceRepository.getReferenceById(second), "a", "a", 5, null)));
        assertThat(zoneRepository.countByPerformanceId(second)).isEqualTo(2);
    }

    @Test
    void 모양_CHECK와_이름_CHECK는_DB가_거부한다() {
        Long id = register("https://ticket.yes24.com/Perf/5", null, null).id();
        String insert = "INSERT INTO performance_zone(performance_id,name,name_key,sort_order,shape_polygons,created_at) "
                + "VALUES (?,?,?,?,?,NOW(6))";
        assertThat(jdbc.update(insert, id, "ok", "OK", 0, "[[[0,0],[1,1],[2,2]]]")).isEqualTo(1);
        assertRejected("ck_performance_zone_shape", () -> jdbc.update(insert, id, "z1", "Z1", 1, "[[[0,0],[1,1]]]"));
        assertRejected("ck_performance_zone_shape", () -> jdbc.update(insert, id, "z2", "Z2", 1, "[]"));
        assertRejected("ck_performance_zone_shape", () -> jdbc.update(insert, id, "z3", "Z3", 1, "{\"a\":1}"));
        assertRejected("ck_performance_zone_name", () -> jdbc.update(insert, id, "  ", "Z4", 1, null));
        assertRejected("ck_performance_zone_sort", () -> jdbc.update(insert, id, "z5", "Z5", -1, null));
        assertRejected("ck_performance_seatmap_aspect",
                () -> jdbc.update("UPDATE performance SET seatmap_aspect = 0 WHERE id = ?", id));
    }

    private void assertRejected(String constraint, Runnable statement) {
        assertThatThrownBy(statement::run).isInstanceOf(DataAccessException.class).hasMessageContaining(constraint);
    }
}
