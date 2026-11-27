package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSource;
import com.matjari.domain.PerformanceSource.MapStatus;
import com.matjari.domain.PerformanceZone;
import com.matjari.dto.request.PerformanceCreateRequest;
import com.matjari.dto.response.PerformanceDetailResponse;
import com.matjari.dto.response.PerformanceZonesResponse.MapItem;
import com.matjari.dto.response.SeatmapImageResponse;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.PerformanceRepository;
import com.matjari.repository.PerformanceSourceRepository;
import com.matjari.repository.PerformanceZoneRepository;
import com.matjari.service.aiextract.DetailExtractionService;
import com.matjari.service.aiextract.DetailExtractionService.AiExtractException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 추가 예매처 링크의 좌석표 지도 읽기·저장·조회 (V34).
 *
 * <p>한 공연을 멜론과 NOL 이 구역을 나눠 팔면 대표 링크의 지도에는 한쪽 구역만 색이 있다. 그래서 추가 링크를 붙이면 그 링크의 상세 이미지에서
 * 구역 지도를 따로 읽어 {@code performance_zone.source_id} 로 저장한다(좌표계가 달라 지도별로 둔다). 대표 지도는 source_id NULL 그대로다.
 * 매칭은 구역 텍스트 키로만 비교하므로 이 지도는 입력 보조일 뿐이다.
 *
 * <p><b>비동기 읽기</b>: 링크를 INSERT 한 트랜잭션이 커밋되면({@link TransactionalEventListener} AFTER_COMMIT) 단일 스레드 실행기에서
 * {@link DetailExtractionService#extractForSourceMap}(보관 결과·검수 정답 재사용, 사용자 월 횟수 없음, 전체 하루 상한·동시 실행 상한만 적용,
 * 같은 공연장 참고 이름 없음, 고정 템플릿 URL 만 요청)로 읽는다. AI 호출 동안은 트랜잭션·잠금이 없고, 저장 트랜잭션은 공연 행 FOR UPDATE → 링크 행 순서다.
 * 실패(AI 꺼짐·크레딧 소진·상한 초과·읽기 실패)는 FAILED 로 표시하고 로그만 남긴다(링크 추가는 이미 커밋돼 유지된다).
 * 링크가 그 사이 삭제됐거나 이미 PENDING 이 아니면(관리자가 먼저 다시 읽음) 결과를 버린다. 서버가 읽는 중에 멈추면 PENDING 이 남을 수 있고
 * 관리자 다시 읽기로 복구한다.
 *
 * <p>구역은 {@link PerformanceZoneSanitizer} 규칙으로 정리하고, 원본 좌석표 이미지 위치는 읽은 결과에서만 가져와(사용자 입력 아님) 허용 호스트·https·범위를
 * 다시 검사한다.
 */
@Slf4j
@Service
public class SourceMapService {

    static final int MAX_ZONES = 1000;
    private static final int QUEUE_CAPACITY = 50;

    private final PerformanceRepository performanceRepository;
    private final PerformanceSourceRepository sourceRepository;
    private final PerformanceZoneRepository zoneRepository;
    private final DetailExtractionService extraction;
    private final SeatmapImageService seatmapImages;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Executor executor;
    private final ThreadPoolExecutor ownedExecutor;

    @Autowired
    public SourceMapService(PerformanceRepository performanceRepository, PerformanceSourceRepository sourceRepository,
                            PerformanceZoneRepository zoneRepository, DetailExtractionService extraction,
                            SeatmapImageService seatmapImages, PlatformTransactionManager transactionManager, Clock clock) {
        this(performanceRepository, sourceRepository, zoneRepository, extraction, seatmapImages, transactionManager, clock, null);
    }

    /** 테스트용: executor 를 주면(예: 호출 스레드 실행기) 그것을 쓴다. */
    SourceMapService(PerformanceRepository performanceRepository, PerformanceSourceRepository sourceRepository,
                     PerformanceZoneRepository zoneRepository, DetailExtractionService extraction,
                     SeatmapImageService seatmapImages, PlatformTransactionManager transactionManager, Clock clock,
                     Executor executor) {
        this.performanceRepository = performanceRepository;
        this.sourceRepository = sourceRepository;
        this.zoneRepository = zoneRepository;
        this.extraction = extraction;
        this.seatmapImages = seatmapImages;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        if (executor == null) {
            this.ownedExecutor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY), r -> {
                Thread t = new Thread(r, "source-map-reader");
                t.setDaemon(true);
                return t;
            });
            this.executor = ownedExecutor;
        } else {
            this.ownedExecutor = null;
            this.executor = executor;
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
        }
    }

    // ------------------------------------------------------------------ 읽기 결과

    /** 읽은 결과를 저장 형태로 정리한 것. status 는 READY(구역이 0개일 수 있음) 또는 FAILED. */
    record Outcome(MapStatus status, List<PerformanceZoneSanitizer.Zone> zones, BigDecimal aspect, SeatmapImageResponse image) {
        static Outcome failed() {
            return new Outcome(MapStatus.FAILED, List.of(), null, null);
        }
    }

    Outcome toOutcome(DetailExtractionService.Response r) {
        // AI 가 꺼졌거나(키 없음·크레딧 소진) 읽다 실패했고 검수 정답도 아니면 읽지 못한 것이다
        if (r == null || r.aiError() || (!r.aiEnabled() && !r.verifiedZones())) {
            return Outcome.failed();
        }
        List<PerformanceCreateRequest.ZoneInput> inputs = new ArrayList<>();
        if (r.zones() != null) {
            for (DetailExtractionService.ZoneDto z : r.zones()) {
                if (z != null && inputs.size() < MAX_ZONES) {
                    inputs.add(new PerformanceCreateRequest.ZoneInput(z.name(), z.shapePolygons()));
                }
            }
        }
        List<PerformanceZoneSanitizer.Zone> zones = PerformanceZoneSanitizer.sanitize(inputs);
        boolean anyShape = zones.stream().anyMatch(z -> z.shapePolygons() != null);
        BigDecimal aspect = !anyShape || r.seatmapAspect() == null ? null
                : PerformanceZoneSanitizer.sanitizeAspect(BigDecimal.valueOf(r.seatmapAspect()));
        SeatmapImageResponse image = aspect == null ? null : seatmapImages.acceptRead(r.seatmapImage()).orElse(null);
        return new Outcome(MapStatus.READY, zones, aspect, image);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 공연 행을 잠근 트랜잭션에서 읽은 결과를 그 링크의 지도로 반영한다(그 링크의 기존 구역은 교체). */
    private void applyLocked(Performance performance, PerformanceSource source, Outcome outcome) {
        if (outcome.status() == MapStatus.READY) {
            zoneRepository.deleteBySourceId(source.getId());
            List<PerformanceZone> created = new ArrayList<>();
            for (int i = 0; i < outcome.zones().size(); i++) {
                PerformanceZoneSanitizer.Zone z = outcome.zones().get(i);
                created.add(PerformanceZone.create(performance, source.getId(), z.name(), z.nameKey(), i, z.shapePolygons()));
            }
            if (!created.isEmpty()) {
                zoneRepository.saveAll(created);
            }
            SeatmapImageResponse img = outcome.image();
            source.applyMap(MapStatus.READY, outcome.aspect(), img == null ? null : img.url(),
                    img == null ? null : img.left(), img == null ? null : img.top(),
                    img == null ? null : img.width(), img == null ? null : img.height(), now());
        } else {
            source.applyMap(outcome.status(), null, null, null, null, null, null, now());
        }
        zoneRepository.flush();
        sourceRepository.flush();
    }

    // ------------------------------------------------------------------ 링크 추가 뒤 비동기 읽기

    /** 링크 추가가 커밋된 뒤에만 불린다(롤백이면 이벤트가 버려진다). 실제 읽기는 다른 스레드에서 한다. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSourceAdded(SourceMapRequestedEvent event) {
        try {
            executor.execute(() -> readPending(event.performanceId(), event.sourceId()));
        } catch (RejectedExecutionException e) {
            log.warn("추가 링크 지도 읽기 대기열이 가득 차 FAILED 로 표시합니다 (sourceId={})", event.sourceId());
            markFailed(event.performanceId(), event.sourceId());
        }
    }

    /** 어떤 예외도 밖으로 던지지 않는다. */
    void readPending(Long performanceId, Long sourceId) {
        try {
            String url = tx.execute(s -> {
                PerformanceSource source = sourceRepository.findById(sourceId).orElse(null);
                if (source == null || !source.getPerformanceId().equals(performanceId) || source.getMapStatus() != MapStatus.PENDING) {
                    return null; // 그 사이 삭제됐거나 이미 처리됨
                }
                return source.getSourceUrl();
            });
            if (url == null) {
                return;
            }
            Outcome outcome;
            try {
                outcome = toOutcome(extraction.extractForSourceMap(url)); // 외부 요청·AI 호출: 트랜잭션·잠금 없이
            } catch (AiExtractException e) {
                log.info("추가 링크 지도 읽기 실패 (sourceId={}, code={})", sourceId, e.getCode());
                outcome = Outcome.failed();
            } catch (RuntimeException e) {
                log.info("추가 링크 지도 읽기 실패 (sourceId={}): {}", sourceId, e.getClass().getSimpleName());
                outcome = Outcome.failed();
            }
            Outcome finalOutcome = outcome;
            tx.executeWithoutResult(s -> {
                Performance performance = performanceRepository.findByIdForUpdate(performanceId).orElse(null); // 트랜잭션의 첫 쿼리: 공연 행 FOR UPDATE
                if (performance == null) {
                    return;
                }
                PerformanceSource source = sourceRepository.findById(sourceId).orElse(null);
                if (source == null || !source.getPerformanceId().equals(performanceId) || source.getMapStatus() != MapStatus.PENDING) {
                    return; // 링크가 삭제됐거나 다른 경로가 먼저 끝냈다: 결과를 버린다
                }
                applyLocked(performance, source, finalOutcome);
            });
            log.info("추가 링크 지도 읽기 완료 (sourceId={}, status={}, zones={})", sourceId, finalOutcome.status(), finalOutcome.zones().size());
        } catch (RuntimeException e) {
            log.warn("추가 링크 지도 저장 실패 (sourceId={}): {}", sourceId, e.toString());
            markFailed(performanceId, sourceId);
        }
    }

    private void markFailed(Long performanceId, Long sourceId) {
        try {
            tx.executeWithoutResult(s -> {
                if (performanceRepository.findByIdForUpdate(performanceId).isEmpty()) {
                    return;
                }
                PerformanceSource source = sourceRepository.findById(sourceId).orElse(null);
                if (source != null && source.getPerformanceId().equals(performanceId) && source.getMapStatus() == MapStatus.PENDING) {
                    source.applyMap(MapStatus.FAILED, null, null, null, null, null, null, now());
                    sourceRepository.flush();
                }
            });
        } catch (RuntimeException e) {
            log.warn("추가 링크 지도 실패 표시 실패 (sourceId={}): {}", sourceId, e.toString());
        }
    }

    // ------------------------------------------------------------------ 관리자

    /** 관리자 다시 읽기의 결과(저장 뒤 상태). before 는 조치 기록용 요약. */
    public record Reextracted(MapItem map, Map<String, Object> before, Map<String, Object> after) {}

    /**
     * 관리자 다시 읽기(동기): 보관 결과를 쓰지 않고 새로 읽어(성공하면 보관 결과를 덮는다) 그 링크의 지도를 교체한다. 월 횟수는 세지 않는다.
     * 이 메서드는 트랜잭션 밖에서 호출한다(AI 호출 동안 잠금을 잡지 않는다). 링크가 이 공연 것이 아니면 404, 대표 링크면 400 sourceId.
     * AI 가 읽지 못하면 503 AI_UNAVAILABLE(기존 지도는 그대로). 저장 트랜잭션은 공연 행 FOR UPDATE 가 첫 쿼리이고, afterLocked 는 그 트랜잭션 맨 끝에서 불린다(조치 기록).
     */
    public MapItem reextract(Long performanceId, Long sourceId, java.util.function.Consumer<Reextracted> afterLocked) {
        String url = tx.execute(s -> sourceUrlOf(performanceId, sourceId));
        Outcome outcome;
        DetailExtractionService.Response response = extraction.extractForAdmin(url); // 429 등 AiExtractException 은 그대로 올린다
        outcome = toOutcome(response);
        if (outcome.status() == MapStatus.FAILED) {
            throw new AiExtractException(503, "AI_UNAVAILABLE",
                    "AI로 정보를 읽지 못했어요. 잠시 후 다시 시도해주세요.", 0);
        }
        Outcome finalOutcome = outcome;
        return tx.execute(s -> {
            Performance performance = performanceRepository.findByIdForUpdate(performanceId)
                    .orElseThrow(() -> new NotFoundException(PerformanceService.NOT_FOUND_MESSAGE));
            PerformanceSource source = requireAdditional(performance, sourceId);
            Map<String, Object> before = summary(source, zoneRepository.countBySourceIds(List.of(sourceId)).stream()
                    .mapToLong(PerformanceZoneRepository.SourceZoneCount::getZoneCount).sum());
            applyLocked(performance, source, finalOutcome);
            Map<String, Object> after = summary(source, finalOutcome.zones().size());
            MapItem item = toMapItem(source, zoneRepository.findBySourceIds(List.of(sourceId)));
            if (afterLocked != null) {
                afterLocked.accept(new Reextracted(item, before, after));
            }
            return item;
        });
    }

    private String sourceUrlOf(Long performanceId, Long sourceId) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new NotFoundException(PerformanceService.NOT_FOUND_MESSAGE));
        return requireAdditional(performance, sourceId).getSourceUrl();
    }

    /** 이 공연의 링크이고 대표 링크가 아닌지 확인한다(아니면 404 / 400). */
    private PerformanceSource requireAdditional(Performance performance, Long sourceId) {
        PerformanceSource source = sourceRepository.findById(sourceId)
                .filter(s -> s.getPerformanceId().equals(performance.getId()))
                .orElseThrow(() -> new NotFoundException("링크를 찾을 수 없습니다."));
        if (source.getSourceKey().equals(performance.getSourceKey())) {
            throw new FieldValidationException("sourceId", "대표 링크의 지도는 공연 구역 다시 인식·구역 교체를 사용해 주세요.");
        }
        return source;
    }

    /**
     * 관리자 지도 삭제: 그 링크의 구역과 지도 정보를 지우고 상태를 NONE 으로 둔다(링크는 남는다). 공연 행을 잠근 트랜잭션 안에서 호출한다.
     * 조치 기록용으로 지우기 전 요약을 돌려준다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> clearLocked(Performance locked, Long sourceId) {
        PerformanceSource source = requireAdditional(locked, sourceId);
        long zones = zoneRepository.countBySourceIds(List.of(sourceId)).stream()
                .mapToLong(PerformanceZoneRepository.SourceZoneCount::getZoneCount).sum();
        Map<String, Object> before = summary(source, zones);
        zoneRepository.deleteBySourceId(sourceId);
        source.clearMap(now());
        zoneRepository.flush();
        sourceRepository.flush();
        return before;
    }

    static Map<String, Object> summary(PerformanceSource source, long zoneCount) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("sourceId", source.getId());
        m.put("sourceKey", source.getSourceKey());
        m.put("mapStatus", source.getMapStatus().name());
        m.put("zones", zoneCount);
        m.put("seatmapAspect", source.getSeatmapAspect() == null ? null : source.getSeatmapAspect().toPlainString());
        m.put("seatmapImageUrl", source.getSeatmapImageUrl());
        return m;
    }

    // ------------------------------------------------------------------ 조회

    /**
     * 추가 링크의 지도들(링크 추가 순): READY 이고 구역이 있거나 PENDING(zones 빈 배열)인 것만. 대표 지도는 호출한 쪽이 앞에 붙인다.
     */
    @Transactional(readOnly = true)
    public List<MapItem> additionalMaps(Performance performance) {
        List<PerformanceSource> rows = sourceRepository.findByPerformanceIdOrderByIdAsc(performance.getId()).stream()
                .filter(s -> !s.getSourceKey().equals(performance.getSourceKey()))
                .filter(s -> s.getMapStatus() == MapStatus.READY || s.getMapStatus() == MapStatus.PENDING)
                .toList();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PerformanceZone>> zonesBySource = zoneRepository
                .findBySourceIds(rows.stream().map(PerformanceSource::getId).toList()).stream()
                .collect(Collectors.groupingBy(PerformanceZone::getSourceId));
        List<MapItem> maps = new ArrayList<>();
        for (PerformanceSource s : rows) {
            List<PerformanceZone> zones = zonesBySource.getOrDefault(s.getId(), List.of());
            if (s.getMapStatus() == MapStatus.READY && zones.isEmpty()) {
                continue; // 읽었지만 구역이 없다: 보여 줄 지도가 없다
            }
            maps.add(toMapItem(s, zones));
        }
        return maps;
    }

    /** 추가 링크 지도 하나(READY 가 아니면 구역은 비운다). */
    MapItem toMapItem(PerformanceSource s, List<PerformanceZone> zones) {
        String site = PerformanceSourceService.siteOf(s.getSourceKey());
        boolean ready = s.getMapStatus() == MapStatus.READY;
        List<PerformanceDetailResponse.Zone> zs = ready
                ? zones.stream().map(z -> new PerformanceDetailResponse.Zone(z.getName(), z.getShapePolygons())).toList()
                : List.of();
        return new MapItem(s.getId(), site, PerformanceSourceService.siteNameOf(site), false, s.getMapStatus().name(),
                ready ? s.getSeatmapAspect() : null, ready ? seatmapImages.expose(s) : null, zs);
    }
}
