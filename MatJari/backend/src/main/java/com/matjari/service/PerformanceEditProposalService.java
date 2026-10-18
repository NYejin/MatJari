package com.matjari.service;

import com.matjari.domain.AdminReviewStatus;
import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceEditProposal;
import com.matjari.domain.PerformanceEditProposal.SessionChange;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.User;
import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.PerformanceEditProposalDtos.AdminEditProposal;
import com.matjari.dto.response.PerformanceEditProposalDtos.CreateRequest;
import com.matjari.dto.response.PerformanceEditProposalDtos.EditProposal;
import com.matjari.dto.response.PerformanceEditProposalDtos.SessionChangeRequest;
import com.matjari.dto.response.PerformanceEditProposalDtos.SessionChangeView;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.PerformanceEditProposalRepository;
import com.matjari.repository.PerformanceRepository;
import com.matjari.repository.PerformanceSessionRepository;
import com.matjari.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 공연 수정 제안: 사용자가 제목·공연장·회차(추가·시각 변경·삭제) 변경을 제안하고 관리자가 승인/거절한다(링크는 대상 아님, 알림 없음).
 *
 * 회차 변경 규칙(생성·승인 두 시점에 같은 검사): 이 공연의 회차만, 같은 회차는 한 번만, 최대 20개, 변경 뒤 회차가 1개 이상,
 * 새 시각은 10분 단위·지난 시각 불가(생성 때만)·남는 회차의 현재 시각과 겹치면 안 됨(시각 맞바꾸기는 하나씩 나눠 제안),
 * 삭제는 티켓·교환 희망·좌석 인증 요청이 걸리지 않은 회차만. 생성 때 위반은 400 {sessionChanges: ...} / 422 SESSION_IN_USE,
 * 승인 때 위반(그 사이 상태가 달라짐)은 409 SESSION_EDIT_CONFLICT(제안은 PENDING 으로 남아 거절할 수 있다).
 *
 * 오류: 없는 공연 404 / 형식·길이 400 {필드: 메시지} / 현재 값과 같음 422 EDIT_NO_CHANGE / 같은 공연에 내 PENDING 이 있음
 * 409 EDIT_PROPOSAL_ALREADY_PENDING / 내 PENDING 3건 이상 422 EDIT_PROPOSAL_LIMIT_REACHED / 이미 처리됨 409 PROPOSAL_ALREADY_REVIEWED.
 *
 * 잠금: 생성은 사용자 행 FOR UPDATE(첫 쿼리) -> 개수·중복 확인 -> INSERT (사용자별 직렬화로 상한이 동시 요청에 뚫리지 않는다).
 * 승인은 제안 행 FOR UPDATE(첫 쿼리) -> PENDING 확인 -> 공연 행 FOR UPDATE -> 공연 갱신 + 제안 APPROVED, 한 트랜잭션.
 * 같은 제안의 동시 승인/거절은 두 번째가 제안 잠금에서 기다렸다가 PENDING 이 아니라 409 가 된다. 같은 공연의 다른 PENDING 제안은 건드리지 않는다.
 */
@Service
public class PerformanceEditProposalService {

    static final int MAX_PENDING_PER_USER = 3;
    static final int MAX_SESSION_CHANGES = SessionChangePlanner.MAX_SESSION_CHANGES;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final PerformanceEditProposalRepository proposalRepository;
    private final PerformanceRepository performanceRepository;
    private final UserRepository userRepository;
    private final PerformanceSessionRepository sessionRepository;
    private final SessionChangePlanner planner;
    private final Clock clock;

    public PerformanceEditProposalService(PerformanceEditProposalRepository proposalRepository,
                                          PerformanceRepository performanceRepository,
                                          UserRepository userRepository,
                                          PerformanceSessionRepository sessionRepository,
                                          SessionChangePlanner planner, Clock clock) {
        this.proposalRepository = proposalRepository;
        this.performanceRepository = performanceRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.planner = planner;
        this.clock = clock;
    }

    @Transactional
    public EditProposal create(Long userId, Long performanceId, CreateRequest request) {
        // 입력 검증은 DB 를 건드리기 전에 한다(첫 쿼리는 사용자 행 잠금이어야 한다).
        String newTitle = clean(request.newTitle(), Performance.TITLE_MAX_LENGTH, "newTitle", "새 제목");
        String newVenue = clean(request.newVenueName(), Performance.VENUE_NAME_MAX_LENGTH, "newVenueName", "새 공연장 이름");
        String reason = clean(request.reason(), PerformanceEditProposal.REASON_MAX_LENGTH, "reason", "사유");
        List<SessionChange> sessionChanges = planner.parse(request.sessionChanges());
        if (newTitle == null && newVenue == null && sessionChanges.isEmpty()) {
            throw new FieldValidationException("newTitle", "새 제목, 새 공연장 이름, 회차 변경 중 하나는 입력해야 합니다.");
        }

        User user = userRepository.findByIdForUpdate(userId)
                .filter(u -> !u.isWithdrawn())
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new NotFoundException("공연을 찾을 수 없습니다."));
        SessionChangePlanner.ensureNotHidden(performance);

        // 현재 값과 같은 항목은 제안에서 뺀다. 모두 같으면 바꿀 것이 없다.
        if (newTitle != null && newTitle.equals(performance.getTitle())) {
            newTitle = null;
        }
        if (newVenue != null && newVenue.equals(performance.getVenueName())) {
            newVenue = null;
        }
        // 회차: 현재 시각과 같은 시각으로 바꾸는 항목은 뺀 뒤, 남은 변경이 지금 회차에 적용 가능한지 확인한다.
        List<PerformanceSession> sessions = sessionRepository.findByPerformance_IdOrderByStartsAtAsc(performanceId);
        sessionChanges = planner.dropNoOpChanges(sessionChanges, sessions);
        if (!sessionChanges.isEmpty()) {
            SessionChangePlanner.PlanError error = planner.checkSessionPlan(sessionChanges, sessions);
            if (error != null) {
                if (error.inUse()) {
                    throw new BusinessRuleException("SESSION_IN_USE", error.message());
                }
                throw new FieldValidationException("sessionChanges", error.message());
            }
        }
        if (newTitle == null && newVenue == null && sessionChanges.isEmpty()) {
            throw new BusinessRuleException("EDIT_NO_CHANGE", "현재 정보와 같아서 제안할 내용이 없어요.");
        }
        if (proposalRepository.existsPending(user.getId(), performanceId)) {
            throw alreadyPending();
        }
        if (proposalRepository.countPendingByProposerId(user.getId()) >= MAX_PENDING_PER_USER) {
            throw new BusinessRuleException("EDIT_PROPOSAL_LIMIT_REACHED",
                    "처리 대기 중인 수정 제안은 최대 " + MAX_PENDING_PER_USER + "건까지 보낼 수 있어요.",
                    Map.of("limit", MAX_PENDING_PER_USER));
        }
        try {
            PerformanceEditProposal saved = proposalRepository.saveAndFlush(
                    PerformanceEditProposal.create(performance, user.getId(), newTitle, newVenue, sessionChanges, reason));
            return toUser(saved, currentStarts(List.of(saved)));
        } catch (DataIntegrityViolationException e) {
            if (DataIntegrityViolations.isViolationOf(e, PerformanceEditProposal.UNIQUE_PENDING)) {
                throw alreadyPending();
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<EditProposal> listMine(Long userId, int page, int size) {
        Page<PerformanceEditProposal> result = proposalRepository.findMine(userId, pageRequest(page, size));
        Map<Long, LocalDateTime> starts = currentStarts(result.getContent());
        return new PageResponse<>(result.getContent().stream().map(p -> toUser(p, starts)).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /** status 가 null 이면 전체. 잘못된 값은 400 {status: ...}. */
    @Transactional(readOnly = true)
    public PageResponse<AdminEditProposal> listForAdmin(String status, int page, int size) {
        AdminReviewStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = AdminReviewStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new FieldValidationException("status", "status는 PENDING, APPROVED, REJECTED 중 하나여야 합니다.");
            }
        }
        Page<PerformanceEditProposal> result = proposalRepository.findForAdmin(filter, pageRequest(page, size));
        Map<Long, String> nicknames = nicknames(result.getContent());
        Map<Long, LocalDateTime> starts = currentStarts(result.getContent());
        return new PageResponse<>(result.getContent().stream().map(p -> toAdmin(p, nicknames, starts)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public AdminEditProposal approve(Long adminId, Long proposalId) {
        PerformanceEditProposal proposal = lockPending(proposalId);
        if (adminId.equals(proposal.getProposerId())) {
            throw new ConflictException("본인이 낸 제안은 직접 승인할 수 없습니다.", Map.of("code", "SELF_REVIEW"));
        }
        Long performanceId = proposal.getPerformance().getId();
        Performance performance = performanceRepository.findByIdForUpdate(performanceId)
                .orElseThrow(() -> new NotFoundException("공연을 찾을 수 없습니다."));
        // 회차 변경은 승인 시점의 회차에 다시 맞춰 본다(공연 행을 잠근 뒤라 그 사이 다른 승인은 끼어들 수 없다).
        List<PerformanceSession> sessions = sessionRepository.findByPerformance_IdOrderByStartsAtAsc(performanceId);
        List<SessionChange> changes = proposal.sessionChangesOrEmpty();
        if (!changes.isEmpty()) {
            SessionChangePlanner.PlanError error = planner.checkSessionPlan(changes, sessions);
            if (error != null) {
                throw SessionChangePlanner.sessionConflict(error.message());
            }
        }
        Map<Long, LocalDateTime> before = new HashMap<>();
        sessions.forEach(x -> before.put(x.getId(), x.getStartsAt()));
        // 승인 시점의 현재 값을 스냅샷한 뒤 반영한다.
        proposal.approve(adminId, performance.getTitle(), performance.getVenueName(), null, now());
        proposal.recordOldSessionTimes(before);
        if (proposal.getNewTitle() != null || proposal.getNewVenueName() != null) {
            performance.applyApprovedEdit(proposal.getNewTitle(), proposal.getNewVenueName());
        }
        try {
            planner.applySessionChanges(performance, changes, sessions);
            proposalRepository.saveAndFlush(proposal);
            performanceRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 삭제하려는 회차에 방금 티켓이 걸리는 등 DB 제약에 걸린 경우
            throw SessionChangePlanner.sessionConflict("승인하는 사이 회차 정보가 바뀌어 반영할 수 없어요. 이 제안은 거절해 주세요.");
        }
        Map<Long, LocalDateTime> starts = currentStarts(List.of(proposal));
        return toAdmin(proposal, nicknames(List.of(proposal)), starts);
    }

    @Transactional
    public AdminEditProposal reject(Long adminId, Long proposalId, String note) {
        String cleanNote = clean(note, PerformanceEditProposal.REVIEW_NOTE_MAX_LENGTH, "note", "거절 사유");
        PerformanceEditProposal proposal = lockPending(proposalId);
        proposal.reject(adminId, cleanNote, now());
        proposalRepository.saveAndFlush(proposal);
        return toAdmin(proposal, nicknames(List.of(proposal)), currentStarts(List.of(proposal)));
    }

    /** 제안 행을 FOR UPDATE 로 잠그고 PENDING 인지 확인한다. 트랜잭션의 첫 쿼리여야 한다. */
    private PerformanceEditProposal lockPending(Long proposalId) {
        PerformanceEditProposal proposal = proposalRepository.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new NotFoundException("수정 제안을 찾을 수 없습니다."));
        if (!proposal.isPending()) {
            throw new ConflictException("이미 처리된 제안입니다.", Map.of("code", "PROPOSAL_ALREADY_REVIEWED"));
        }
        return proposal;
    }

    private static ConflictException alreadyPending() {
        return new ConflictException("이 공연에 처리 대기 중인 수정 제안이 이미 있어요.",
                Map.of("code", "EDIT_PROPOSAL_ALREADY_PENDING"));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private static PageRequest pageRequest(int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(page, pageSize);
    }

    /** 공백 정리 후 빈 값은 null, 최대 길이 초과는 400 {필드: 메시지}. */
    private static String clean(String value, int max, String field, String label) {
        String cleaned = Performance.cleanDisplayText(value);
        if (cleaned == null || cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > max) {
            throw new FieldValidationException(field, label + "은(는) " + max + "자 이하여야 합니다.");
        }
        return cleaned;
    }

    private Map<Long, String> nicknames(List<PerformanceEditProposal> proposals) {
        Set<Long> ids = new HashSet<>();
        for (PerformanceEditProposal p : proposals) {
            ids.add(p.getProposerId());
            if (p.getReviewedById() != null) {
                ids.add(p.getReviewedById());
            }
        }
        Map<Long, String> map = new HashMap<>();
        userRepository.findAllById(ids).forEach(u -> map.put(u.getId(), u.getNickname()));
        return map;
    }

    private EditProposal toUser(PerformanceEditProposal p, Map<Long, LocalDateTime> starts) {
        Performance perf = p.getPerformance();
        return new EditProposal(p.getId(), perf.getId(), perf.getTitle(), perf.getTitle(), perf.getVenueName(),
                p.getNewTitle(), p.getNewVenueName(), p.getReason(), p.getStatus().name(), p.getReviewNote(),
                p.getCreatedAt(), p.getReviewedAt(), views(p, starts));
    }

    private AdminEditProposal toAdmin(PerformanceEditProposal p, Map<Long, String> nicknames,
                                      Map<Long, LocalDateTime> starts) {
        Performance perf = p.getPerformance();
        return new AdminEditProposal(p.getId(), perf.getId(), perf.getTitle(), perf.getTitle(), perf.getVenueName(),
                p.getNewTitle(), p.getNewVenueName(), p.getReason(), p.getStatus().name(), p.getReviewNote(),
                p.getCreatedAt(), p.getReviewedAt(), p.getProposerId(), nicknames.get(p.getProposerId()),
                p.getOldTitle(), p.getOldVenueName(),
                p.getReviewedById() == null ? null : nicknames.get(p.getReviewedById()), views(p, starts));
    }

    // ---- 회차 변경 ----

    /** 처리되지 않은 제안의 기존 시각을 보여 주려고 현재 회차 시각을 한 번에 읽는다(승인된 제안은 스냅샷을 쓴다). */
    private Map<Long, LocalDateTime> currentStarts(List<PerformanceEditProposal> proposals) {
        Set<Long> ids = new HashSet<>();
        for (PerformanceEditProposal p : proposals) {
            if (p.getStatus() != AdminReviewStatus.APPROVED) {
                p.sessionChangesOrEmpty().stream().map(SessionChange::sessionId).filter(x -> x != null).forEach(ids::add);
            }
        }
        Map<Long, LocalDateTime> map = new HashMap<>();
        if (!ids.isEmpty()) {
            sessionRepository.findAllById(ids).forEach(x -> map.put(x.getId(), x.getStartsAt()));
        }
        return map;
    }

    private static List<SessionChangeView> views(PerformanceEditProposal p, Map<Long, LocalDateTime> starts) {
        List<SessionChangeView> list = new ArrayList<>();
        for (SessionChange c : p.sessionChangesOrEmpty()) {
            LocalDateTime current = null;
            if (c.sessionId() != null) {
                current = c.oldStartsAt() != null ? LocalDateTime.parse(c.oldStartsAt()) : starts.get(c.sessionId());
            }
            list.add(new SessionChangeView(c.type(), c.sessionId(), c.startsAtTime(), current));
        }
        return list;
    }
}
