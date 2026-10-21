package com.matjari.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matjari.domain.AdminActionLog;
import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceEditProposal.SessionChange;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.PerformanceZone;
import com.matjari.dto.request.PerformanceCreateRequest;
import com.matjari.dto.response.DateTimeFormats;
import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.PerformanceEditProposalDtos.SessionChangeRequest;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.PerformanceRepository;
import com.matjari.repository.PerformanceSessionRepository;
import com.matjari.repository.PerformanceZoneRepository;
import com.matjari.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 관리자 공연 관리: 목록·상세, 내리기(숨김)·다시 올리기, 제목·공연장 직접 수정, 회차 변경, 구역 전체 교체.
 *
 * 모든 변경 조치는 공연 행을 FOR UPDATE 로 잠근 것이 트랜잭션의 첫 쿼리이고, 변경 뒤 맨 끝에서 {@link AdminActionLogger} 로 기록한다.
 * (입력 검증은 DB 를 건드리기 전에 한다.) 잠금은 공연 행 하나뿐이라 티켓 -> 요청 -> 매칭 사슬에 끼지 않는다.
 * 회차 규칙은 공연 수정 제안 승인과 같은 {@link SessionChangePlanner} 를 쓴다. 사유(reason)는 1~200자(unhide 는 선택).
 * 숨김의 효과는 공개 목록 제외와 새 티켓 등록·새 교환 요청·새 제안·좌석 인증 요청·수정 제안 생성 409 PERFORMANCE_HIDDEN 이다.
 */
@Service
public class AdminPerformanceService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;
    static final int QUERY_MAX_LENGTH = 100;
    static final int REASON_MAX_LENGTH = Performance.HIDDEN_REASON_MAX_LENGTH;
    static final int MAX_ZONES = 1000; // 구역 개수 상한은 없고 비정상 요청만 막는 안전 한도

    public record ListItem(Long id, String title, String venueName, String sourceUrl, String registrantNickname,
                           long sessionCount, long zoneCount, boolean hidden,
                           @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime hiddenAt,
                           @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime createdAt) {}

    public record Registrant(Long id, String nickname) {}

    public record SessionItem(Long id,
                              @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE) LocalDateTime startsAt,
                              boolean inUse) {}

    public record ZoneItem(String name, List<List<List<Integer>>> shapePolygons) {}

    public record Detail(Long id, String title, String venueName, String sourceUrl, boolean hidden,
                         @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime hiddenAt,
                         String hiddenReason, String hiddenByNickname, Registrant registrant,
                         List<SessionItem> sessions, List<ZoneItem> zones, BigDecimal seatmapAspect,
                         @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime createdAt) {}

    public record ReasonRequest(String reason) {}

    public record EditRequest(String title, String venueName, String reason) {}

    public record SessionChangesRequest(List<SessionChangeRequest> changes, String reason) {}

    public record ZonesRequest(List<PerformanceCreateRequest.ZoneInput> zones, BigDecimal seatmapAspect, String reason) {}

    private final PerformanceRepository performanceRepository;
    private final PerformanceSessionRepository sessionRepository;
    private final PerformanceZoneRepository zoneRepository;
    private final UserRepository userRepository;
    private final SessionChangePlanner planner;
    private final AdminActionLogger actionLogger;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public AdminPerformanceService(PerformanceRepository performanceRepository,
                                   PerformanceSessionRepository sessionRepository,
                                   PerformanceZoneRepository zoneRepository, UserRepository userRepository,
                                   SessionChangePlanner planner, AdminActionLogger actionLogger,
                                   ObjectMapper objectMapper, Clock clock, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.performanceRepository = performanceRepository;
        this.sessionRepository = sessionRepository;
        this.zoneRepository = zoneRepository;
        this.userRepository = userRepository;
        this.planner = planner;
        this.actionLogger = actionLogger;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public PageResponse<ListItem> list(String query, String hidden, int page, int size) {
        String q = Performance.cleanDisplayText(query);
        if (q != null && q.length() > QUERY_MAX_LENGTH) {
            throw new FieldValidationException("query", "검색어는 100자 이하로 입력해주세요.");
        }
        String mode = hidden == null || hidden.isBlank() ? "ALL" : hidden.strip().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "VISIBLE", "HIDDEN").contains(mode)) {
            throw new FieldValidationException("hidden", "hidden은 ALL, VISIBLE, HIDDEN 중 하나여야 합니다.");
        }
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<Performance> result = performanceRepository.findForAdmin(LikePatterns.contains(q), mode,
                PageRequest.of(page, pageSize));
        List<Long> ids = result.getContent().stream().map(Performance::getId).toList();
        Map<Long, Long> sessionCounts = ids.isEmpty() ? Map.of()
                : sessionRepository.findStats(ids, LocalDateTime.now(clock)).stream().collect(Collectors.toMap(
                        PerformanceSessionRepository.SessionStats::getPerformanceId,
                        s -> s.getSessionCount() == null ? 0L : s.getSessionCount()));
        Map<Long, Long> zoneCounts = ids.isEmpty() ? Map.of()
                : zoneRepository.countByPerformanceIds(ids).stream().collect(Collectors.toMap(
                        PerformanceZoneRepository.ZoneCount::getPerformanceId,
                        PerformanceZoneRepository.ZoneCount::getZoneCount));
        List<ListItem> content = result.getContent().stream()
                .map(p -> new ListItem(p.getId(), p.getTitle(), p.getVenueName(), p.getSourceUrl(),
                        p.getRegistrant().getNickname(), sessionCounts.getOrDefault(p.getId(), 0L),
                        zoneCounts.getOrDefault(p.getId(), 0L), p.isHidden(), p.getHiddenAt(), p.getCreatedAt()))
                .toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Detail get(Long id) {
        Performance performance = performanceRepository.findDetailById(id)
                .orElseThrow(() -> new NotFoundException(PerformanceService.NOT_FOUND_MESSAGE));
        return toDetail(performance);
    }

    // ------------------------------------------------------------------ 조치

    @Transactional
    public void hide(Long adminId, Long id, String reason) {
        String cleaned = requiredReason(reason);
        Performance performance = lock(id);
        if (!performance.hide(adminId, cleaned, LocalDateTime.now(clock))) {
            return; // 이미 숨김: 멱등, 기록 없음
        }
        performanceRepository.flush();
        actionLogger.log(adminId, AdminActionLog.Action.PERFORMANCE_HIDE, AdminActionLog.TargetType.PERFORMANCE, id,
                cleaned, json(Map.of("title", performance.getTitle(), "before", Map.of("hidden", false),
                        "after", Map.of("hidden", true))));
    }

    @Transactional
    public void unhide(Long adminId, Long id, String reason) {
        String cleaned = optionalReason(reason);
        Performance performance = lock(id);
        String previousReason = performance.getHiddenReason();
        if (!performance.unhide()) {
            return; // 숨김 아님: 멱등, 기록 없음
        }
        performanceRepository.flush();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("hidden", true);
        before.put("hiddenReason", previousReason);
        actionLogger.log(adminId, AdminActionLog.Action.PERFORMANCE_UNHIDE, AdminActionLog.TargetType.PERFORMANCE, id,
                cleaned, json(Map.of("title", performance.getTitle(), "before", before, "after", Map.of("hidden", false))));
    }

    /**
     * 숨긴 공연 완전 삭제(사용자 결정 2026-10-21). 숨김 상태가 아니면 409 PERFORMANCE_NOT_HIDDEN, 티켓(상태 무관)·교환 희망 회차·
     * 좌석 인증 요청·교환 이력이 하나라도 있으면 409 PERFORMANCE_IN_USE(종류별 개수). 공연 행을 잠근 뒤 검사하고
     * 공연 수정 제안 → 구역 → 회차 → 공연 순으로 지운다. 조치 기록(대상은 FK 아님)은 맨 끝에 남긴다.
     */
    @Transactional
    public void delete(Long adminId, Long id, String reason) {
        String cleaned = requiredReason(reason);
        Performance performance = lock(id);
        if (!performance.isHidden()) {
            throw new BusinessRuleException("PERFORMANCE_NOT_HIDDEN", "숨긴 공연만 삭제할 수 있어요. 먼저 공연을 내려 주세요.");
        }
        Map<String, Object> inUse = jdbc.queryForMap("""
                SELECT (SELECT COUNT(*) FROM ticket t JOIN performance_session s ON s.id = t.performance_session_id
                        WHERE s.performance_id = ?) tickets,
                       (SELECT COUNT(*) FROM exchange_want_session w JOIN performance_session s ON s.id = w.performance_session_id
                        WHERE s.performance_id = ?) wantSessions,
                       (SELECT COUNT(*) FROM seat_claim c JOIN performance_session s ON s.id = c.performance_session_id
                        WHERE s.performance_id = ?) seatClaims,
                       (SELECT COUNT(*) FROM exchange_history h WHERE h.performance_id = ?) histories
                """, id, id, id, id);
        long total = inUse.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
        if (total > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            inUse.forEach((k, v) -> details.put(k, ((Number) v).longValue()));
            throw new com.matjari.exception.ConflictException(
                    "이 공연에 연결된 기록이 있어 삭제할 수 없어요(티켓 " + details.get("tickets") + "·교환 희망 회차 "
                            + details.get("wantSessions") + "·좌석 인증 " + details.get("seatClaims") + "·교환 이력 "
                            + details.get("histories") + "). 숨김 상태로 두세요.",
                    Map.of("code", "PERFORMANCE_IN_USE", "inUse", details));
        }
        int proposals = jdbc.update("DELETE FROM performance_edit_proposal WHERE performance_id = ?", id);
        int zones = jdbc.update("DELETE FROM performance_zone WHERE performance_id = ?", id);
        int sessions = jdbc.update("DELETE FROM performance_session WHERE performance_id = ?", id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("title", performance.getTitle());
        detail.put("venueName", performance.getVenueName());
        detail.put("sourceUrl", performance.getSourceUrl());
        detail.put("hiddenReason", performance.getHiddenReason());
        detail.put("deleted", Map.of("sessions", sessions, "zones", zones, "editProposals", proposals));
        jdbc.update("DELETE FROM performance WHERE id = ?", id); // 엔티티는 바꾸지 않았으므로 영속성 컨텍스트가 다시 쓰지 않는다
        actionLogger.log(adminId, AdminActionLog.Action.PERFORMANCE_DELETE, AdminActionLog.TargetType.PERFORMANCE, id,
                cleaned, json(detail));
    }

    @Transactional
    public Detail edit(Long adminId, Long id, EditRequest request) {
        String title = cleanText(request.title(), Performance.TITLE_MAX_LENGTH, "title", "제목");
        String venue = cleanText(request.venueName(), Performance.VENUE_NAME_MAX_LENGTH, "venueName", "공연장 이름");
        String reason = requiredReason(request.reason());
        if (title == null && venue == null) {
            throw new FieldValidationException("title", "제목이나 공연장 이름 중 하나는 입력해야 합니다.");
        }
        Performance performance = lock(id);
        if (title != null && title.equals(performance.getTitle())) {
            title = null;
        }
        if (venue != null && venue.equals(performance.getVenueName())) {
            venue = null;
        }
        if (title == null && venue == null) {
            throw new BusinessRuleException("EDIT_NO_CHANGE", "현재 정보와 같아서 바꿀 내용이 없어요.");
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (title != null) {
            before.put("title", performance.getTitle());
            after.put("title", title);
        }
        if (venue != null) {
            before.put("venueName", performance.getVenueName());
            after.put("venueName", venue);
        }
        performance.applyApprovedEdit(title, venue);
        performanceRepository.flush();
        Detail detail = toDetail(performance);
        actionLogger.log(adminId, AdminActionLog.Action.PERFORMANCE_EDIT, AdminActionLog.TargetType.PERFORMANCE, id,
                reason, json(Map.of("before", before, "after", after)));
        return detail;
    }

    @Transactional
    public Detail changeSessions(Long adminId, Long id, SessionChangesRequest request) {
        List<SessionChange> changes = planner.parse(request.changes());
        String reason = requiredReason(request.reason());
        if (changes.isEmpty()) {
            throw new FieldValidationException("changes", "바꿀 회차를 하나 이상 입력해주세요.");
        }
        Performance performance = lock(id);
        List<PerformanceSession> sessions = sessionRepository.findByPerformance_IdOrderByStartsAtAsc(id);
        List<String> beforeTimes = sessions.stream().map(s -> s.getStartsAt().toString()).toList();
        changes = planner.dropNoOpChanges(changes, sessions);
        if (changes.isEmpty()) {
            throw new BusinessRuleException("EDIT_NO_CHANGE", "현재 정보와 같아서 바꿀 내용이 없어요.");
        }
        SessionChangePlanner.PlanError error = planner.checkSessionPlan(changes, sessions);
        if (error != null) {
            if (error.inUse()) {
                throw new BusinessRuleException("SESSION_IN_USE", error.message());
            }
            throw new FieldValidationException("sessionChanges", error.message());
        }
        Map<Long, LocalDateTime> oldTimes = new LinkedHashMap<>();
        sessions.forEach(s -> oldTimes.put(s.getId(), s.getStartsAt()));
        try {
            planner.applySessionChanges(performance, changes, sessions);
        } catch (DataIntegrityViolationException e) {
            // 삭제하려는 회차에 방금 티켓이 걸리는 등 DB 제약에 걸린 경우
            throw SessionChangePlanner.sessionConflict("그 사이 회차 정보가 바뀌어 반영할 수 없어요. 화면을 새로고침해 주세요.");
        }
        List<Map<String, Object>> changeSummary = new ArrayList<>();
        for (SessionChange c : changes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", c.type());
            if (c.sessionId() != null) {
                item.put("sessionId", c.sessionId());
                item.put("oldStartsAt", String.valueOf(oldTimes.get(c.sessionId())));
            }
            if (c.startsAt() != null) {
                item.put("startsAt", c.startsAt());
            }
            changeSummary.add(item);
        }
        Detail detail = toDetail(performance);
        List<String> afterTimes = detail.sessions().stream().map(s -> s.startsAt().toString()).toList();
        actionLogger.log(adminId, AdminActionLog.Action.PERFORMANCE_SESSIONS, AdminActionLog.TargetType.PERFORMANCE, id,
                reason, json(Map.of("changes", changeSummary, "before", beforeTimes, "after", afterTimes)));
        return detail;
    }

    @Transactional
    public Detail replaceZones(Long adminId, Long id, ZonesRequest request) {
        String reason = requiredReason(request.reason());
        if (request.zones() != null && request.zones().size() > MAX_ZONES) {
            throw new FieldValidationException("zones", "구역은 한 번에 1,000개까지 입력할 수 있어요.");
        }
        List<PerformanceZoneSanitizer.Zone> inputs = PerformanceZoneSanitizer.sanitize(request.zones());
        BigDecimal aspect = PerformanceZoneSanitizer.sanitizeAspect(request.seatmapAspect());
        Performance performance = lock(id);
        List<PerformanceZone> existing = zoneRepository.findByPerformanceIdOrderBySortOrderAscIdAsc(id);
        List<String> beforeNames = existing.stream().map(PerformanceZone::getName).toList();
        BigDecimal beforeAspect = performance.getSeatmapAspect();

        zoneRepository.deleteAll(existing);
        zoneRepository.flush(); // 같은 이름 구역을 다시 넣을 수 있게 먼저 지운다(uk_performance_zone_name_key)
        List<PerformanceZone> created = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            PerformanceZoneSanitizer.Zone z = inputs.get(i);
            created.add(PerformanceZone.create(performance, z.name(), z.nameKey(), i, z.shapePolygons()));
        }
        if (!created.isEmpty()) {
            zoneRepository.saveAll(created);
        }
        performance.changeSeatmapAspect(aspect);
        zoneRepository.flush();
        performanceRepository.flush();

        Detail detail = toDetail(performance);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("zones", beforeNames);
        before.put("seatmapAspect", beforeAspect == null ? null : beforeAspect.toPlainString());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("zones", inputs.stream().map(PerformanceZoneSanitizer.Zone::name).toList());
        after.put("withShape", inputs.stream().filter(z -> z.shapePolygons() != null).count());
        after.put("seatmapAspect", aspect == null ? null : aspect.toPlainString());
        actionLogger.log(adminId, AdminActionLog.Action.PERFORMANCE_ZONES, AdminActionLog.TargetType.PERFORMANCE, id,
                reason, json(Map.of("before", before, "after", after)));
        return detail;
    }

    // ------------------------------------------------------------------ 내부

    /** 공연 행 FOR UPDATE. 변경 조치 트랜잭션의 첫 쿼리여야 한다. */
    private Performance lock(Long id) {
        return performanceRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException(PerformanceService.NOT_FOUND_MESSAGE));
    }

    private Detail toDetail(Performance performance) {
        Long id = performance.getId();
        List<PerformanceSession> sessions = sessionRepository.findByPerformance_IdOrderByStartsAtAsc(id);
        Set<Long> inUse = sessions.isEmpty() ? Set.of()
                : Set.copyOf(sessionRepository.findInUseIds(sessions.stream().map(PerformanceSession::getId).toList()));
        List<ZoneItem> zones = zoneRepository.findByPerformanceIdOrderBySortOrderAscIdAsc(id).stream()
                .map(z -> new ZoneItem(z.getName(), z.getShapePolygons())).toList();
        String hiddenBy = performance.getHiddenById() == null ? null
                : userRepository.findById(performance.getHiddenById()).map(u -> u.getNickname()).orElse(null);
        var registrant = performance.getRegistrant();
        return new Detail(id, performance.getTitle(), performance.getVenueName(), performance.getSourceUrl(),
                performance.isHidden(), performance.getHiddenAt(), performance.getHiddenReason(), hiddenBy,
                new Registrant(registrant.getId(), registrant.getNickname()),
                sessions.stream().map(s -> new SessionItem(s.getId(), s.getStartsAt(), inUse.contains(s.getId()))).toList(),
                zones, performance.getSeatmapAspect(), performance.getCreatedAt());
    }

    private static String requiredReason(String reason) {
        String cleaned = Performance.cleanDisplayText(reason);
        if (cleaned == null || cleaned.isEmpty()) {
            throw new FieldValidationException("reason", "사유를 입력해주세요.");
        }
        if (cleaned.length() > REASON_MAX_LENGTH) {
            throw new FieldValidationException("reason", "사유는 " + REASON_MAX_LENGTH + "자 이하로 입력해주세요.");
        }
        return cleaned;
    }

    private static String optionalReason(String reason) {
        String cleaned = Performance.cleanDisplayText(reason);
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > REASON_MAX_LENGTH) {
            throw new FieldValidationException("reason", "사유는 " + REASON_MAX_LENGTH + "자 이하로 입력해주세요.");
        }
        return cleaned;
    }

    private static String cleanText(String value, int max, String field, String label) {
        String cleaned = Performance.cleanDisplayText(value);
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > max) {
            throw new FieldValidationException(field, label + "은(는) " + max + "자 이하여야 합니다.");
        }
        return cleaned;
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null; // 기록의 보조 정보일 뿐이라 직렬화 실패로 조치를 막지 않는다
        }
    }
}
