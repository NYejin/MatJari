package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceEditProposal.SessionChange;
import com.matjari.domain.PerformanceSession;
import com.matjari.dto.response.PerformanceEditProposalDtos.SessionChangeRequest;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.repository.PerformanceSessionRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 회차 변경(추가·시각 변경·삭제) 규칙의 공용 구현. 공연 수정 제안(생성·승인)과 관리자 직접 회차 변경이 같은 코드를 쓴다.
 * 변경 목록 파싱(400 {sessionChanges}), 현재 시각과 같은 변경 제거, 지금 회차에 적용 가능한지 판정, 반영(삭제 -> 시각 변경 -> 추가)을 맡는다.
 * 반영은 호출자가 공연 행을 FOR UPDATE 로 잠근 트랜잭션 안에서 해야 한다.
 */
@Component
public class SessionChangePlanner {

    public static final int MAX_SESSION_CHANGES = 20;

    private final SessionTimePolicy sessionTimePolicy;
    private final PerformanceSessionRepository sessionRepository;

    public SessionChangePlanner(SessionTimePolicy sessionTimePolicy, PerformanceSessionRepository sessionRepository) {
        this.sessionTimePolicy = sessionTimePolicy;
        this.sessionRepository = sessionRepository;
    }

    public record PlanError(boolean inUse, String message) {}

    /** 요청의 회차 변경을 DB 를 보지 않고 가능한 만큼 검증·정리한다(400 {sessionChanges: 메시지}). */
    public List<SessionChange> parse(List<SessionChangeRequest> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        if (requested.size() > MAX_SESSION_CHANGES) {
            throw new FieldValidationException("sessionChanges", "회차 변경은 한 번에 " + MAX_SESSION_CHANGES + "개까지 제안할 수 있어요.");
        }
        List<SessionChange> result = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        for (SessionChangeRequest r : requested) {
            if (r == null || r.type() == null) {
                throw new FieldValidationException("sessionChanges", "회차 변경의 종류(ADD, CHANGE, REMOVE)가 필요해요.");
            }
            String type = r.type().strip().toUpperCase(Locale.ROOT);
            switch (type) {
                case SessionChange.ADD -> {
                    if (r.sessionId() != null) {
                        throw new FieldValidationException("sessionChanges", "추가할 회차에는 회차 번호를 넣을 수 없어요.");
                    }
                    result.add(new SessionChange(type, null,
                            sessionTimePolicy.normalize(r.startsAt(), "sessionChanges").toString(), null));
                }
                case SessionChange.CHANGE, SessionChange.REMOVE -> {
                    if (r.sessionId() == null) {
                        throw new FieldValidationException("sessionChanges", "바꾸거나 삭제할 회차를 골라 주세요.");
                    }
                    if (!seenIds.add(r.sessionId())) {
                        throw new FieldValidationException("sessionChanges", "같은 회차를 두 번 바꿀 수 없어요.");
                    }
                    String startsAt = null;
                    if (type.equals(SessionChange.CHANGE)) {
                        startsAt = sessionTimePolicy.normalize(r.startsAt(), "sessionChanges").toString();
                    }
                    result.add(new SessionChange(type, r.sessionId(), startsAt, null));
                }
                default -> throw new FieldValidationException("sessionChanges",
                        "회차 변경의 종류는 ADD, CHANGE, REMOVE 중 하나여야 해요.");
            }
        }
        return result;
    }

    /** 현재 시각과 같은 시각으로 바꾸는 CHANGE 는 바꿀 것이 없으니 뺀다(없는 회차는 그대로 두어 plan 검사가 잡는다). */
    public List<SessionChange> dropNoOpChanges(List<SessionChange> changes, List<PerformanceSession> sessions) {
        Map<Long, LocalDateTime> current = new HashMap<>();
        sessions.forEach(x -> current.put(x.getId(), x.getStartsAt()));
        List<SessionChange> kept = new ArrayList<>();
        for (SessionChange c : changes) {
            if (SessionChange.CHANGE.equals(c.type()) && c.startsAtTime().equals(current.get(c.sessionId()))) {
                continue;
            }
            kept.add(c);
        }
        return kept;
    }

    /** 변경 목록이 지금 회차 목록에 적용 가능한지 판정한다. 문제가 없으면 null. */
    public PlanError checkSessionPlan(List<SessionChange> changes, List<PerformanceSession> sessions) {
        Map<Long, PerformanceSession> byId = new HashMap<>();
        sessions.forEach(x -> byId.put(x.getId(), x));
        Set<Long> removed = new HashSet<>();
        List<LocalDateTime> newTimes = new ArrayList<>();
        int adds = 0;
        for (SessionChange c : changes) {
            if (!SessionChange.ADD.equals(c.type()) && !byId.containsKey(c.sessionId())) {
                return new PlanError(false, "이 공연에 없는 회차예요. 화면을 새로고침해 주세요.");
            }
            switch (c.type()) {
                case SessionChange.REMOVE -> removed.add(c.sessionId());
                case SessionChange.CHANGE -> newTimes.add(c.startsAtTime());
                default -> {
                    adds++;
                    newTimes.add(c.startsAtTime());
                }
            }
        }
        if (sessions.size() - removed.size() + adds < 1) {
            return new PlanError(false, "회차는 최소 1개가 남아야 해요.");
        }
        if (new HashSet<>(newTimes).size() != newTimes.size()) {
            return new PlanError(false, "같은 시각의 회차를 두 번 넣었어요.");
        }
        for (PerformanceSession x : sessions) {
            if (!removed.contains(x.getId()) && newTimes.contains(x.getStartsAt())) {
                return new PlanError(false, x.getStartsAt().toString().replace('T', ' ')
                        + " 회차가 이미 있어요. 시각을 맞바꾸려면 하나씩 나눠서 제안해 주세요.");
            }
        }
        if (!removed.isEmpty() && !sessionRepository.findInUseIds(removed).isEmpty()) {
            return new PlanError(true, "티켓이나 교환 희망 조건이 걸린 회차는 삭제를 제안할 수 없어요.");
        }
        return null;
    }

    /** 삭제 -> 시각 변경 -> 추가 순서로 반영한다(삭제한 시각을 다른 회차가 이어받을 수 있게 단계마다 flush). */
    public void applySessionChanges(Performance performance, List<SessionChange> changes,
                                    List<PerformanceSession> sessions) {
        if (changes.isEmpty()) {
            return;
        }
        Map<Long, PerformanceSession> byId = new HashMap<>();
        sessions.forEach(x -> byId.put(x.getId(), x));
        for (SessionChange c : changes) {
            if (SessionChange.REMOVE.equals(c.type())) {
                sessionRepository.delete(byId.get(c.sessionId()));
            }
        }
        sessionRepository.flush();
        for (SessionChange c : changes) {
            if (SessionChange.CHANGE.equals(c.type())) {
                byId.get(c.sessionId()).changeStartsAt(c.startsAtTime());
            }
        }
        sessionRepository.flush();
        for (SessionChange c : changes) {
            if (SessionChange.ADD.equals(c.type())) {
                sessionRepository.save(PerformanceSession.create(performance, c.startsAtTime()));
            }
        }
        sessionRepository.flush();
    }

    public static ConflictException sessionConflict(String message) {
        return new ConflictException(message, Map.of("code", "SESSION_EDIT_CONFLICT"));
    }

    /** 숨긴(내려간) 공연에 새로 만드는 경로를 막는 409 PERFORMANCE_HIDDEN. */
    public static void ensureNotHidden(Performance performance) {
        if (performance != null && performance.isHidden()) {
            throw new ConflictException("내려간 공연이에요.", Map.of("code", "PERFORMANCE_HIDDEN"));
        }
    }
}
