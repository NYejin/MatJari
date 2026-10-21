package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.PerformanceZone;
import com.matjari.domain.User;
import com.matjari.dto.request.PerformanceCreateRequest;
import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.PerformanceDetailResponse;
import com.matjari.dto.response.PerformanceLookupResponse;
import com.matjari.dto.response.PerformanceSummaryResponse;
import com.matjari.dto.response.PerformanceZonesResponse;
import com.matjari.dto.response.SessionResponse;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.PerformanceRepository;
import com.matjari.repository.PerformanceSessionRepository;
import com.matjari.repository.PerformanceZoneRepository;
import com.matjari.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 공연 등록·조회 (FR-02). 조회·등록은 로그인 사용자 누구나. 등록 후 수정·삭제는 없다(추후 관리자 수정 제안으로만).
 * 링크에서 공연정보를 읽는 외부 URL 요청은 이 서비스의 책임이 아니다 (후속 작업에서 별도로 둔다)..
 */
@Slf4j
@Service
public class PerformanceService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    static final int QUERY_MAX_LENGTH = 100;
    static final String UNIQUE_SOURCE_KEY = "uk_performance_source_key";

    static final String DUPLICATE_PERFORMANCE_MESSAGE = "이미 등록된 공연입니다.";
    static final String NOT_FOUND_MESSAGE = "공연을 찾을 수 없습니다.";

    private final PerformanceRepository performanceRepository;
    private final PerformanceSessionRepository sessionRepository;
    private final PerformanceZoneRepository zoneRepository;
    private final UserRepository userRepository;
    private final SourceKeyResolver sourceKeyResolver;
    private final SessionTimePolicy sessionTimePolicy;
    private final TransactionTemplate transactionTemplate;
    private final TicketService ticketService;

    public PerformanceService(PerformanceRepository performanceRepository,
                              PerformanceSessionRepository sessionRepository,
                              PerformanceZoneRepository zoneRepository,
                              UserRepository userRepository,
                              SourceKeyResolver sourceKeyResolver,
                              SessionTimePolicy sessionTimePolicy,
                              PlatformTransactionManager transactionManager,
                              TicketService ticketService) {
        this.ticketService = ticketService;
        this.performanceRepository = performanceRepository;
        this.sessionRepository = sessionRepository;
        this.zoneRepository = zoneRepository;
        this.userRepository = userRepository;
        this.sourceKeyResolver = sourceKeyResolver;
        this.sessionTimePolicy = sessionTimePolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 링크로 이미 등록된 공연이 있는지 (등록 화면에서 입력 직후 확인용). 형식 오류는 400 {"sourceUrl": ...}. */
    @Transactional(readOnly = true)
    public PerformanceLookupResponse lookup(String sourceUrl) {
        String sourceKey = sourceKeyResolver.resolve(sourceUrl);
        return performanceRepository.findIdBySourceKey(sourceKey)
                .map(PerformanceLookupResponse::found)
                .orElseGet(PerformanceLookupResponse::notFound);
    }

    /**
     * 제목 부분 일치 검색. 다가오는 회차가 있는 공연 먼저(가까운 순). page는 0부터.
     * 대소문자 무시는 컬럼 collation(utf8mb4_0900_ai_ci)에 맡긴다 - lower()를 씌우지 않는다.
     * asOf: 정렬과 nextSessionStartsAt의 기준 시각. 페이지를 넘기는 동안 기준이 바뀌어 항목이 중복/누락되지
     * 않도록 프론트가 첫 페이지 시각을 고정해 넘긴다. 없으면 현재(KST).
     */
    @Transactional(readOnly = true)
    public PageResponse<PerformanceSummaryResponse> search(String query, int page, int size,
                                                           LocalDateTime asOf) {
        String q = Performance.cleanDisplayText(query);
        if (q != null && q.length() > QUERY_MAX_LENGTH) {
            throw new FieldValidationException("query", "검색어는 100자 이하로 입력해주세요.");
        }
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        LocalDateTime now = asOf != null ? PerformanceSession.truncate(asOf) : sessionTimePolicy.now();

        Page<Performance> result = performanceRepository.search(
                LikePatterns.contains(q),
                now,
                PageRequest.of(page, pageSize));

        Map<Long, PerformanceSessionRepository.SessionStats> stats = result.isEmpty()
                ? Map.of()
                : sessionRepository.findStats(result.map(Performance::getId).getContent(), now).stream()
                        .collect(Collectors.toMap(PerformanceSessionRepository.SessionStats::getPerformanceId,
                                Function.identity()));

        Map<Long, Long> zoneCounts = result.isEmpty()
                ? Map.of()
                : zoneRepository.countByPerformanceIds(result.map(Performance::getId).getContent()).stream()
                        .collect(Collectors.toMap(PerformanceZoneRepository.ZoneCount::getPerformanceId,
                                PerformanceZoneRepository.ZoneCount::getZoneCount));

        List<PerformanceSummaryResponse> content = result.getContent().stream()
                .map(p -> {
                    PerformanceSessionRepository.SessionStats s = stats.get(p.getId());
                    return new PerformanceSummaryResponse(
                            p.getId(),
                            p.getTitle(),
                            p.getVenueName(),
                            s == null ? null : s.getNextStartsAt(),
                            s == null || s.getSessionCount() == null ? 0 : s.getSessionCount(),
                            zoneCounts.getOrDefault(p.getId(), 0L));
                })
                .toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PerformanceDetailResponse get(Long performanceId, Long userId) {
        Performance performance = performanceRepository.findDetailById(performanceId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
        return toDetail(performance, sessionRepository.findByPerformance_IdOrderByStartsAtAsc(performanceId),
                zoneRepository.findByPerformanceIdOrderBySortOrderAscIdAsc(performanceId), userId);
    }

    /** 공연의 구역 목록(sortOrder 순)과 좌석표 비율. 없는 공연은 404. 구역이 없으면 zones=[]. */
    @Transactional(readOnly = true)
    public PerformanceZonesResponse getZones(Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND_MESSAGE));
        return new PerformanceZonesResponse(performance.getSeatmapAspect(),
                toZoneResponses(zoneRepository.findByPerformanceIdOrderBySortOrderAscIdAsc(performanceId)));
    }

    /**
     * 공연 + 회차 + 등록자 티켓 등록. 같은 링크(sourceKey)의 공연이 있으면 409 {"message","performanceId"}.
     * 공연은 티켓 등록과 함께만 만든다(API는 ticket 필수): 티켓이 실패하면(활성 티켓 상한 등) 공연도 롤백된다.
     * 티켓은 일반 티켓과 같고 공연에는 그 티켓을 가리키는 값을 두지 않는다. ticket 이 null 인 호출은 서비스 단위 시험뿐이다.
     * 티켓 필드 오류의 키는 ticket.zone·ticket.row·ticket.col·ticket.sessionStartsAt.
     * 동시 등록 레이스: insert에서 uk_performance_source_key 위반 → 트랜잭션 롤백 후 새 트랜잭션에서
     * 기존 공연 id를 조회해 같은 409로 응답한다 (메서드 전체를 @Transactional로 묶지 않는 이유).
     */
    public PerformanceDetailResponse create(Long userId, PerformanceCreateRequest request) {
        String sourceUrl = request.sourceUrl() == null ? null : request.sourceUrl().strip();
        String sourceKey = sourceKeyResolver.resolve(sourceUrl);
        // 같은 링크의 공연이 이미 있으면 제목/회차 검증보다 409를 먼저 - 프론트가 기존 공연으로 안내할 수 있게
        Long existingId = transactionTemplate.execute(
                status -> performanceRepository.findIdBySourceKey(sourceKey).orElse(null));
        if (existingId != null) {
            throw duplicatePerformance(existingId);
        }
        String title = cleanTitle(request.title());
        String venueName = cleanVenueName(request.venueName());
        List<LocalDateTime> startsAts = sessionTimePolicy.normalizeAll(request.sessions(), "sessions");
        List<PerformanceZoneSanitizer.Zone> zoneInputs = PerformanceZoneSanitizer.sanitize(request.zones());
        BigDecimal seatmapAspect = PerformanceZoneSanitizer.sanitizeAspect(request.seatmapAspect());
        PerformanceCreateRequest.TicketInput ticketInput = request.ticket();
        TicketService.SeatInput seat = ticketInput == null ? null : normalizeTicketSeat(ticketInput);
        LocalDateTime ticketStartsAt = ticketInput == null ? null : PerformanceSession.truncate(ticketInput.sessionStartsAt());
        if (ticketInput != null && !startsAts.contains(ticketStartsAt)) {
            throw new FieldValidationException("ticket.sessionStartsAt", "내 티켓의 회차를 등록할 회차 중에서 골라주세요.");
        }

        try {
            return transactionTemplate.execute(status -> {
                // 티켓 등록과 같은 규칙: users 행 FOR UPDATE 가 트랜잭션의 첫 쿼리(활성 티켓 상한 직렬화, 탈퇴 겹침 차단)
                User registrant = seat != null ? ticketService.lockActiveUser(userId) : null;
                // 사전 확인과 insert 사이에 등록된 경우 (unique 제약이 최종 방어)
                performanceRepository.findIdBySourceKey(sourceKey).ifPresent(id -> {
                    throw duplicatePerformance(id);
                });
                if (registrant == null) {
                    registrant = requireUser(userId);
                }

                Performance performance = performanceRepository.saveAndFlush(
                        Performance.create(venueName, title, sourceUrl, sourceKey, registrant, seatmapAspect));
                List<PerformanceSession> sessions = new ArrayList<>();
                for (LocalDateTime startsAt : startsAts) {
                    sessions.add(sessionRepository.save(PerformanceSession.create(performance, startsAt)));
                }
                sessionRepository.flush();
                // 구역은 공연 행 다음에만 쓴다(다른 잠금 없음). 같은 트랜잭션이라 실패하면 공연도 롤백된다.
                List<PerformanceZone> zones = new ArrayList<>();
                for (int i = 0; i < zoneInputs.size(); i++) {
                    PerformanceZoneSanitizer.Zone z = zoneInputs.get(i);
                    zones.add(PerformanceZone.create(performance, z.name(), z.nameKey(), i, z.shapePolygons()));
                }
                if (!zones.isEmpty()) {
                    zoneRepository.saveAll(zones);
                    zoneRepository.flush();
                }
                if (seat != null) {
                    PerformanceSession ticketSession = sessions.stream()
                            .filter(x -> x.getStartsAt().equals(ticketStartsAt))
                            .findFirst()
                            .orElseThrow();
                    ticketService.insertLocked(registrant, ticketSession, seat);
                }
                return toDetail(performance, sessions, zones, userId);
            });
        } catch (DataIntegrityViolationException e) {
            if (!DataIntegrityViolations.isViolationOf(e, UNIQUE_SOURCE_KEY)) {
                throw e;
            }
            // 실패한 insert 트랜잭션은 이미 롤백됐다. open-in-view=false라 아래 재조회는 새 트랜잭션/새 세션에서 돈다.
            log.info("Performance create race on sourceKey '{}'", sourceKey);
            Long winnerId = transactionTemplate.execute(
                    status -> performanceRepository.findIdBySourceKey(sourceKey).orElse(null));
            throw duplicatePerformance(winnerId);
        }
    }

    /** 티켓 좌석 정규화. 오류 키에 ticket. 을 붙여 공연 필드와 구분한다. */
    private TicketService.SeatInput normalizeTicketSeat(PerformanceCreateRequest.TicketInput input) {
        try {
            return ticketService.normalizeSeat(input.zone(), input.row(), input.col());
        } catch (FieldValidationException e) {
            Map<String, String> prefixed = new java.util.LinkedHashMap<>();
            e.getErrors().forEach((k, v) -> prefixed.put("ticket." + k, v));
            throw FieldValidationException.ofAll(prefixed);
        }
    }

    private static List<PerformanceDetailResponse.Zone> toZoneResponses(List<PerformanceZone> zones) {
        return zones.stream()
                .map(z -> new PerformanceDetailResponse.Zone(z.getName(), z.getShapePolygons()))
                .toList();
    }

    private PerformanceDetailResponse toDetail(Performance performance, List<PerformanceSession> sessions,
                                               List<PerformanceZone> zones, Long userId) {
        User registrant = performance.getRegistrant();
        return new PerformanceDetailResponse(
                performance.getId(),
                performance.getTitle(),
                performance.getSourceUrl(),
                performance.getVenueName(),
                performance.getSeatmapAspect(),
                toZoneResponses(zones),
                new PerformanceDetailResponse.Registrant(registrant.getId(), registrant.getNickname()),
                performance.isRegisteredBy(userId),
                sessions.stream().map(SessionResponse::from).toList(),
                performance.getCreatedAt(),
                performance.isHidden());
    }

    /** 토큰은 유효하나 사용자가 없는 경우 → 401 (spring-boot-conventions 예외 조항). */
    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
    }

    private static String cleanTitle(String title) {
        String cleaned = Performance.cleanDisplayText(title);
        if (cleaned == null || cleaned.isEmpty()) {
            throw new FieldValidationException("title", "공연 제목을 입력해주세요.");
        }
        if (cleaned.length() > Performance.TITLE_MAX_LENGTH) {
            throw new FieldValidationException("title", "공연 제목은 200자 이하로 입력해주세요.");
        }
        return cleaned;
    }

    private static String cleanVenueName(String venueName) {
        String cleaned = Performance.cleanDisplayText(venueName);
        if (cleaned == null || cleaned.isEmpty()) {
            throw new FieldValidationException("venueName", "공연장 이름을 입력해주세요.");
        }
        if (cleaned.length() > Performance.VENUE_NAME_MAX_LENGTH) {
            throw new FieldValidationException("venueName", "공연장 이름은 100자 이하로 입력해주세요.");
        }
        return cleaned;
    }

    private static ConflictException duplicatePerformance(Long existingId) {
        return new ConflictException(DUPLICATE_PERFORMANCE_MESSAGE,
                Collections.singletonMap("performanceId", existingId));
    }
}
