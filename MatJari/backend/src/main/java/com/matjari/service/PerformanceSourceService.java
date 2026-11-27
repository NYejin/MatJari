package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSource;
import com.matjari.dto.response.PerformancePreviewResponse;
import com.matjari.dto.response.PerformanceSourceDtos.Candidate;
import com.matjari.dto.response.PerformanceSourceDtos.PreviewInfo;
import com.matjari.dto.response.PerformanceSourceDtos.SimilarResponse;
import com.matjari.dto.response.PerformanceSourceDtos.SourceItem;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.PerformanceRepository;
import com.matjari.repository.PerformanceSessionRepository;
import com.matjari.repository.PerformanceSourceRepository;
import com.matjari.repository.PerformanceZoneRepository;
import com.matjari.service.linkinfo.PerformancePreviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 한 공연을 여러 예매처 링크로 묶기 (V32 performance_source).
 *
 * 규칙: 링크 키(source_key)는 공연 전체에서 하나(같은 링크는 한 공연에만), 공연당 링크는 최대 {@value #MAX_SOURCES}개,
 * 지원 사이트(멜론·인터파크(NOL)·티켓링크·YES24) 링크만 추가할 수 있다. 일반 사용자는 링크를 추가할 때 서버가 "같은 공연인지"를
 * 다시 검증한다(공연장이 같고 링크 기간이 공연 회차 범위와 겹침, 기간이 없으면 제목 유사도). 관리자는 이 검증 없이 추가한다.
 *
 * 외부 요청: 링크 미리보기({@link PerformancePreviewService})는 기존 속도 제한·캐시를 그대로 쓰고 사용자 URL을 직접 요청하지 않는다(고정 템플릿).
 * 미리보기는 항상 잠금 밖에서 먼저 읽는다. 잠금 순서: users FOR UPDATE(탈퇴 확인, 티켓 등록 경로와 같음) → 공연 행 FOR UPDATE → INSERT.
 * 이 테이블은 티켓 → 요청 → 매칭 잠금 사슬 밖이다.
 */
@Slf4j
@Service
public class PerformanceSourceService {

    public static final int MAX_SOURCES = 6;
    static final int MAX_CANDIDATES = 3;
    static final int CANDIDATE_SCAN_LIMIT = 300;
    private static final LocalDateTime FAR_FUTURE = LocalDateTime.of(2999, 1, 1, 0, 0);
    private static final Pattern SUPPORTED_KEY = Pattern.compile("^(yes24|ticketlink|melon|interpark):\\d{1,20}$");

    static final String NOT_SUPPORTED_MESSAGE = "멜론티켓·인터파크(NOL)·티켓링크·YES24 상품 링크만 추가할 수 있어요.";
    static final String NOT_SAME_MESSAGE = "이 링크는 같은 공연으로 확인되지 않아요. 공연장과 공연 기간이 같은 링크만 추가할 수 있어요.";

    /** 링크 추가 결과. created=false 는 이미 이 공연에 달려 있어 아무것도 하지 않은 멱등 응답. added 는 새로 만든 행(없으면 null). */
    public record AddResult(List<SourceItem> sources, boolean created, PerformanceSource added) {}

    private final PerformanceRepository performanceRepository;
    private final PerformanceSessionRepository sessionRepository;
    private final PerformanceSourceRepository sourceRepository;
    private final SourceKeyResolver sourceKeyResolver;
    private final PerformancePreviewService previewService;
    private final TicketService ticketService;
    private final SessionTimePolicy sessionTimePolicy;
    private final TransactionTemplate transactionTemplate;
    private final PerformanceZoneRepository zoneRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PerformanceSourceService(PerformanceRepository performanceRepository,
                                    PerformanceSessionRepository sessionRepository,
                                    PerformanceSourceRepository sourceRepository,
                                    SourceKeyResolver sourceKeyResolver,
                                    PerformancePreviewService previewService,
                                    TicketService ticketService,
                                    SessionTimePolicy sessionTimePolicy,
                                    PlatformTransactionManager transactionManager,
                                    PerformanceZoneRepository zoneRepository,
                                    ApplicationEventPublisher eventPublisher) {
        this.zoneRepository = zoneRepository;
        this.eventPublisher = eventPublisher;
        this.performanceRepository = performanceRepository;
        this.sessionRepository = sessionRepository;
        this.sourceRepository = sourceRepository;
        this.sourceKeyResolver = sourceKeyResolver;
        this.previewService = previewService;
        this.ticketService = ticketService;
        this.sessionTimePolicy = sessionTimePolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------ 사이트 표기

    /** 링크 키의 사이트 id. 지원 사이트가 아닌 일반 링크(url:...)는 etc. */
    static String siteOf(String sourceKey) {
        int i = sourceKey == null ? -1 : sourceKey.indexOf(':');
        String prefix = i < 0 ? "" : sourceKey.substring(0, i);
        return switch (prefix) {
            case "melon", "interpark", "ticketlink", "yes24" -> prefix;
            default -> "etc";
        };
    }

    static String siteNameOf(String site) {
        return switch (site) {
            case "melon" -> "멜론티켓";
            case "interpark" -> "인터파크(NOL)";
            case "ticketlink" -> "티켓링크";
            case "yes24" -> "YES24";
            default -> "기타";
        };
    }

    static SourceItem toItem(Long id, String sourceKey, String url, boolean primary) {
        return toItem(id, sourceKey, url, primary, "NONE", 0L);
    }

    static SourceItem toItem(Long id, String sourceKey, String url, boolean primary, String mapStatus, long zoneCount) {
        String site = siteOf(sourceKey);
        return new SourceItem(id, site, siteNameOf(site), url, primary, mapStatus, zoneCount);
    }

    /** 저장된 링크 행이 없을 때(단위 시험 등)의 합성 응답: 대표 링크만. */
    public static List<SourceItem> primaryOnly(Performance performance) {
        return List.of(toItem(null, performance.getSourceKey(), performance.getSourceUrl(), true));
    }

    // ------------------------------------------------------------------ 조회

    /** 링크 키로 그 링크가 달린 공연 id. */
    @Transactional(readOnly = true)
    public Optional<Long> findPerformanceIdBySourceKey(String sourceKey) {
        return sourceRepository.findBySourceKey(sourceKey).map(PerformanceSource::getPerformanceId);
    }

    /** 공연의 링크 목록: 대표 먼저, 그다음 추가 순. 행이 하나도 없으면 대표 링크만 합성한다. */
    @Transactional(readOnly = true)
    public List<SourceItem> list(Performance performance) {
        return toItems(performance, sourceRepository.findByPerformanceIdOrderByIdAsc(performance.getId()));
    }

    private List<SourceItem> toItems(Performance performance, List<PerformanceSource> rows) {
        List<SourceItem> items = new ArrayList<>();
        PerformanceSource primary = rows.stream()
                .filter(s -> s.getSourceKey().equals(performance.getSourceKey())).findFirst().orElse(null);
        // 대표 링크: 대표 지도(source_id NULL)의 구역 수. 추가 링크: 링크별 지도 구역 수와 상태
        long primaryZones = performance.getId() == null ? 0L : zoneRepository.countByPerformanceId(performance.getId());
        String primaryStatus = primaryZones > 0 ? "READY" : "NONE";
        if (primary != null) {
            items.add(toItem(primary.getId(), primary.getSourceKey(), primary.getSourceUrl(), true, primaryStatus, primaryZones));
        } else {
            items.add(toItem(null, performance.getSourceKey(), performance.getSourceUrl(), true, primaryStatus, primaryZones));
        }
        List<Long> extraIds = rows.stream().filter(s -> s != primary).map(PerformanceSource::getId).toList();
        Map<Long, Long> counts = new LinkedHashMap<>();
        if (!extraIds.isEmpty()) {
            for (PerformanceZoneRepository.SourceZoneCount c : zoneRepository.countBySourceIds(extraIds)) {
                counts.put(c.getSourceId(), c.getZoneCount());
            }
        }
        for (PerformanceSource s : rows) {
            if (s != primary) {
                items.add(toItem(s.getId(), s.getSourceKey(), s.getSourceUrl(), false, s.getMapStatus().name(),
                        counts.getOrDefault(s.getId(), 0L)));
            }
        }
        return items;
    }

    // ------------------------------------------------------------------ 같은 공연 후보

    /**
     * GET /similar. 링크를 형식 검증하고(400) 이미 등록됐는지 알린 뒤, 링크 미리보기로 읽은 제목·공연장·기간으로 같은 공연 후보를 찾는다.
     * 미리보기를 못 읽으면(OK 아님) 후보는 비어 있다. 속도 제한(429)은 미리보기와 같다.
     */
    public SimilarResponse similar(Long userId, String sourceUrl) {
        String url = sourceUrl == null ? null : sourceUrl.strip();
        String key = sourceKeyResolver.resolve(url);
        Long existingId = findPerformanceIdBySourceKey(key).orElse(null);
        PerformancePreviewResponse preview = previewService.preview(userId, url);
        PreviewInfo info = new PreviewInfo(preview.status(), preview.title(), preview.venueName(),
                preview.periodStart(), preview.periodEnd());
        List<Candidate> candidates = existingId != null || !isOk(preview) ? List.of()
                : findSimilar(preview.title(), preview.venueName(), preview.periodStart(), preview.periodEnd());
        return new SimilarResponse(info, existingId, candidates);
    }

    private static boolean isOk(PerformancePreviewResponse preview) {
        return preview != null && "OK".equals(preview.status());
    }

    /**
     * 같은 공연일 수 있는 숨기지 않은 공연을 최대 {@value #MAX_CANDIDATES}개: 공연장이 같고(SamePerformancePolicy.sameVenue) 기간이 회차 범위와 겹침
     * (기간이 없으면 제목 유사도 {@value SamePerformancePolicy#TITLE_THRESHOLD} 이상, 그때는 아직 끝나지 않은 공연만 본다). 제목 유사도 내림차순, 같으면 최근 등록 먼저.
     */
    @Transactional(readOnly = true)
    public List<Candidate> findSimilar(String title, String venueName, LocalDate periodStart, LocalDate periodEnd) {
        if (venueName == null || venueName.isBlank()) {
            return List.of();
        }
        boolean period = SamePerformancePolicy.hasPeriod(periodStart, periodEnd);
        LocalDateTime from;
        LocalDateTime toExclusive;
        if (period) {
            LocalDate s = periodStart != null ? periodStart : periodEnd;
            LocalDate e = periodEnd != null ? periodEnd : periodStart;
            if (e.isBefore(s)) {
                LocalDate t = s;
                s = e;
                e = t;
            }
            from = s.atStartOfDay();
            toExclusive = e.plusDays(1).atStartOfDay();
        } else {
            from = sessionTimePolicy.now().toLocalDate().atStartOfDay();
            toExclusive = FAR_FUTURE;
        }
        List<Performance> performances = performanceRepository.findVisibleWithSessionsOverlapping(
                from, toExclusive, PageRequest.of(0, CANDIDATE_SCAN_LIMIT));
        if (performances.isEmpty()) {
            return List.of();
        }
        List<Long> ids = performances.stream().map(Performance::getId).toList();
        Map<Long, PerformanceSessionRepository.SessionRange> ranges = sessionRepository.findRanges(ids).stream()
                .collect(Collectors.toMap(PerformanceSessionRepository.SessionRange::getPerformanceId, Function.identity()));

        record Scored(Performance performance, PerformanceSessionRepository.SessionRange range, double similarity) {}
        List<Scored> scored = new ArrayList<>();
        for (Performance p : performances) {
            PerformanceSessionRepository.SessionRange r = ranges.get(p.getId());
            if (r == null || !SamePerformancePolicy.sameVenue(venueName, p.getVenueName())) {
                continue;
            }
            double sim = SamePerformancePolicy.titleSimilarity(title, p.getTitle());
            boolean same = period
                    ? SamePerformancePolicy.periodOverlaps(periodStart, periodEnd, r.getFirstStartsAt(), r.getLastStartsAt())
                    : sim >= SamePerformancePolicy.TITLE_THRESHOLD;
            if (same) {
                scored.add(new Scored(p, r, sim));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::similarity).reversed()
                .thenComparing(s -> s.performance().getId(), Comparator.reverseOrder()));
        List<Scored> top = scored.stream().limit(MAX_CANDIDATES).toList();
        if (top.isEmpty()) {
            return List.of();
        }
        Map<Long, List<PerformanceSource>> sourcesByPerformance = sourceRepository
                .findByPerformanceIdIn(top.stream().map(s -> s.performance().getId()).toList()).stream()
                .collect(Collectors.groupingBy(PerformanceSource::getPerformanceId));
        List<Candidate> result = new ArrayList<>();
        for (Scored s : top) {
            Performance p = s.performance();
            Set<String> sites = new LinkedHashSet<>();
            sites.add(siteOf(p.getSourceKey())); // 대표 링크 먼저
            sourcesByPerformance.getOrDefault(p.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(PerformanceSource::getId))
                    .forEach(src -> sites.add(siteOf(src.getSourceKey())));
            result.add(new Candidate(p.getId(), p.getTitle(), p.getVenueName(), s.range().getFirstStartsAt(),
                    s.range().getLastStartsAt(), s.range().getSessionCount() == null ? 0 : s.range().getSessionCount().intValue(),
                    List.copyOf(sites)));
        }
        return result;
    }

    /** 이 링크의 미리보기가 그 공연과 같은 공연인지(링크 추가의 서버 재검증). 공연장이 같고, 기간이 있으면 회차 범위와 겹침, 없으면 제목 유사도. */
    private boolean isSamePerformance(PerformancePreviewResponse preview, Performance performance) {
        if (!isOk(preview) || !SamePerformancePolicy.sameVenue(preview.venueName(), performance.getVenueName())) {
            return false;
        }
        PerformanceSessionRepository.SessionRange range = sessionRepository.findRanges(List.of(performance.getId())).stream()
                .findFirst().orElse(null);
        if (range == null) {
            return false;
        }
        if (SamePerformancePolicy.hasPeriod(preview.periodStart(), preview.periodEnd())) {
            return SamePerformancePolicy.periodOverlaps(preview.periodStart(), preview.periodEnd(),
                    range.getFirstStartsAt(), range.getLastStartsAt());
        }
        return SamePerformancePolicy.titleSimilarity(preview.title(), performance.getTitle()) >= SamePerformancePolicy.TITLE_THRESHOLD;
    }

    // ------------------------------------------------------------------ 링크 추가 (일반 사용자)

    /**
     * 링크 키가 지원 사이트(멜론·NOL·티켓링크·YES24 상품 번호)인지 확인하고 키를 돌려준다. 형식 오류·미지원이면 400 {"sourceUrl": ...}.
     */
    public String requireSupportedKey(String sourceUrl) {
        String key = sourceKeyResolver.resolve(sourceUrl); // 형식 오류는 FieldValidationException(400)
        if (!SUPPORTED_KEY.matcher(key).matches()) {
            throw new FieldValidationException("sourceUrl", NOT_SUPPORTED_MESSAGE);
        }
        return key;
    }

    /**
     * POST /api/performances/{id}/sources. 순서: 형식·지원 사이트(400) → 공연 없음(404) → 이미 등록된 링크(다른 공연 409 SOURCE_ALREADY_REGISTERED,
     * 같은 공연은 멱등) → 숨긴 공연 409 → [잠금 밖 외부 미리보기] → 트랜잭션(users FOR UPDATE → 공연 행 FOR UPDATE → 같은 검사 다시 → 같은 공연 검증 422
     * SOURCE_NOT_SAME_PERFORMANCE → 상한 422 SOURCE_LIMIT_REACHED → INSERT).
     */
    public AddResult addByUser(Long userId, Long performanceId, String sourceUrl) {
        String url = sourceUrl == null ? null : sourceUrl.strip();
        String key = requireSupportedKey(url);
        AddResult early = transactionTemplate.execute(s -> {
            Performance p = performanceRepository.findById(performanceId)
                    .orElseThrow(() -> new NotFoundException(PerformanceService.NOT_FOUND_MESSAGE));
            AddResult existing = existingResult(p, key);
            if (existing == null) {
                SessionChangePlanner.ensureNotHidden(p);
            }
            return existing;
        });
        if (early != null) {
            return early;
        }
        PerformancePreviewResponse preview = previewService.preview(userId, url); // 외부 요청은 잠금 밖에서
        try {
            return transactionTemplate.execute(s -> {
                ticketService.lockActiveUser(userId); // 트랜잭션의 첫 쿼리
                Performance p = performanceRepository.findByIdForUpdate(performanceId)
                        .orElseThrow(() -> new NotFoundException(PerformanceService.NOT_FOUND_MESSAGE));
                return addLocked(p, url, key, userId, preview, true);
            });
        } catch (DataIntegrityViolationException e) {
            if (!DataIntegrityViolations.isViolationOf(e, PerformanceSource.UNIQUE_SOURCE_KEY)) {
                throw e;
            }
            // 동시에 같은 링크가 다른 곳에 달렸다. 실패한 트랜잭션은 롤백됐으니 새 트랜잭션에서 승자를 읽는다.
            log.info("Performance source add race on key '{}'", key);
            AddResult winner = transactionTemplate.execute(s -> {
                Performance p = performanceRepository.findById(performanceId)
                        .orElseThrow(() -> new NotFoundException(PerformanceService.NOT_FOUND_MESSAGE));
                return existingResult(p, key);
            });
            if (winner == null) {
                throw e;
            }
            return winner;
        }
    }

    /**
     * 공연 행을 잠근 트랜잭션 안에서 링크를 만든다(관리자 경로도 같은 코드). verifySame=true 이면 preview 로 같은 공연인지 검증한다(관리자는 false).
     * 이미 이 공연에 달린 링크면 아무것도 하지 않고 created=false 로 돌려준다.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public AddResult addLocked(Performance locked, String url, String key, Long addedById,
                               PerformancePreviewResponse preview, boolean verifySame) {
        AddResult existing = existingResult(locked, key);
        if (existing != null) {
            return existing;
        }
        SessionChangePlanner.ensureNotHidden(locked);
        if (verifySame && !isSamePerformance(preview, locked)) {
            throw new BusinessRuleException("SOURCE_NOT_SAME_PERFORMANCE", NOT_SAME_MESSAGE);
        }
        if (sourceRepository.countByPerformanceId(locked.getId()) >= MAX_SOURCES) {
            throw new BusinessRuleException("SOURCE_LIMIT_REACHED",
                    "한 공연에 달 수 있는 링크는 최대 " + MAX_SOURCES + "개예요.", Map.of("limit", MAX_SOURCES));
        }
        // 추가 링크는 지도를 읽는 중(PENDING)으로 INSERT 하고, 커밋 뒤 SourceMapService 가 읽는다(롤백이면 이벤트도 버려진다)
        PerformanceSource saved = sourceRepository.saveAndFlush(
                PerformanceSource.createAdditional(locked.getId(), key, url, addedById, sessionTimePolicy.now()));
        eventPublisher.publishEvent(new SourceMapRequestedEvent(locked.getId(), saved.getId()));
        return new AddResult(list(locked), true, saved);
    }

    /** 이미 그 키가 달려 있으면: 같은 공연이면 멱등 결과, 다른 공연이면 409 SOURCE_ALREADY_REGISTERED. 없으면 null. */
    private AddResult existingResult(Performance performance, String key) {
        Optional<PerformanceSource> existing = sourceRepository.findBySourceKey(key);
        if (existing.isEmpty()) {
            return null;
        }
        if (existing.get().getPerformanceId().equals(performance.getId())) {
            return new AddResult(list(performance), false, null);
        }
        throw sourceAlreadyRegistered(existing.get().getPerformanceId());
    }

    static ConflictException sourceAlreadyRegistered(Long performanceId) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", "SOURCE_ALREADY_REGISTERED");
        details.put("performanceId", performanceId);
        return new ConflictException("이미 다른 공연에 등록된 링크입니다.", details);
    }

    // ------------------------------------------------------------------ 대표 링크·관리자 삭제

    /** 공연 등록 트랜잭션에서 방금 저장한 공연의 대표 링크 행을 함께 만든다. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void insertPrimary(Performance performance, Long registrantId) {
        sourceRepository.saveAndFlush(PerformanceSource.create(performance.getId(), performance.getSourceKey(),
                performance.getSourceUrl(), registrantId));
    }

    /** 관리자 삭제. 공연 행을 잠근 트랜잭션 안에서 호출한다. 이 공연의 링크가 아니면 404, 대표 링크면 409 PRIMARY_SOURCE. 지운 행을 돌려준다. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public PerformanceSource removeLocked(Performance locked, Long sourceId) {
        PerformanceSource source = sourceRepository.findById(sourceId)
                .filter(s -> s.getPerformanceId().equals(locked.getId()))
                .orElseThrow(() -> new NotFoundException("링크를 찾을 수 없습니다."));
        if (source.getSourceKey().equals(locked.getSourceKey())) {
            throw new ConflictException("대표 링크는 삭제할 수 없어요.", Map.of("code", "PRIMARY_SOURCE"));
        }
        zoneRepository.deleteBySourceId(source.getId()); // 그 링크의 지도 구역을 먼저 지운다(FK)
        sourceRepository.delete(source);
        sourceRepository.flush();
        return source;
    }
}
