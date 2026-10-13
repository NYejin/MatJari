package com.matjari.service;

import com.matjari.domain.AdminReviewStatus;
import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceEditProposal;
import com.matjari.domain.User;
import com.matjari.dto.response.PageResponse;
import com.matjari.dto.response.PerformanceEditProposalDtos.AdminEditProposal;
import com.matjari.dto.response.PerformanceEditProposalDtos.CreateRequest;
import com.matjari.dto.response.PerformanceEditProposalDtos.EditProposal;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.PerformanceEditProposalRepository;
import com.matjari.repository.PerformanceRepository;
import com.matjari.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 공연 수정 제안: 사용자가 제목·공연장 변경을 제안하고 관리자가 승인/거절한다(회차·링크는 대상 아님, 알림 없음).
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
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 50;

    private final PerformanceEditProposalRepository proposalRepository;
    private final PerformanceRepository performanceRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public PerformanceEditProposalService(PerformanceEditProposalRepository proposalRepository,
                                          PerformanceRepository performanceRepository,
                                          UserRepository userRepository, Clock clock) {
        this.proposalRepository = proposalRepository;
        this.performanceRepository = performanceRepository;
        this.userRepository = userRepository;
        this.clock = clock;
    }

    @Transactional
    public EditProposal create(Long userId, Long performanceId, CreateRequest request) {
        // 입력 검증은 DB 를 건드리기 전에 한다(첫 쿼리는 사용자 행 잠금이어야 한다).
        String newTitle = clean(request.newTitle(), Performance.TITLE_MAX_LENGTH, "newTitle", "새 제목");
        String newVenue = clean(request.newVenueName(), Performance.VENUE_NAME_MAX_LENGTH, "newVenueName", "새 공연장 이름");
        String reason = clean(request.reason(), PerformanceEditProposal.REASON_MAX_LENGTH, "reason", "사유");
        if (newTitle == null && newVenue == null) {
            throw new FieldValidationException("newTitle", "새 제목이나 새 공연장 이름 중 하나는 입력해야 합니다.");
        }

        User user = userRepository.findByIdForUpdate(userId)
                .filter(u -> !u.isWithdrawn())
                .orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new NotFoundException("공연을 찾을 수 없습니다."));

        // 현재 값과 같은 항목은 제안에서 뺀다. 모두 같으면 바꿀 것이 없다.
        if (newTitle != null && newTitle.equals(performance.getTitle())) {
            newTitle = null;
        }
        if (newVenue != null && newVenue.equals(performance.getVenueName())) {
            newVenue = null;
        }
        if (newTitle == null && newVenue == null) {
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
                    PerformanceEditProposal.create(performance, user.getId(), newTitle, newVenue, reason));
            return toUser(saved);
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
        return new PageResponse<>(result.getContent().stream().map(this::toUser).toList(), result.getNumber(),
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
        return new PageResponse<>(result.getContent().stream().map(p -> toAdmin(p, nicknames)).toList(),
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
        // 승인 시점의 현재 값을 스냅샷한 뒤 반영한다.
        proposal.approve(adminId, performance.getTitle(), performance.getVenueName(), null, now());
        performance.applyApprovedEdit(proposal.getNewTitle(), proposal.getNewVenueName());
        proposalRepository.saveAndFlush(proposal);
        performanceRepository.flush();
        return toAdmin(proposal, nicknames(List.of(proposal)));
    }

    @Transactional
    public AdminEditProposal reject(Long adminId, Long proposalId, String note) {
        String cleanNote = clean(note, PerformanceEditProposal.REVIEW_NOTE_MAX_LENGTH, "note", "거절 사유");
        PerformanceEditProposal proposal = lockPending(proposalId);
        proposal.reject(adminId, cleanNote, now());
        proposalRepository.saveAndFlush(proposal);
        return toAdmin(proposal, nicknames(List.of(proposal)));
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

    private EditProposal toUser(PerformanceEditProposal p) {
        Performance perf = p.getPerformance();
        return new EditProposal(p.getId(), perf.getId(), perf.getTitle(), perf.getTitle(), perf.getVenueName(),
                p.getNewTitle(), p.getNewVenueName(), p.getReason(), p.getStatus().name(), p.getReviewNote(),
                p.getCreatedAt(), p.getReviewedAt());
    }

    private AdminEditProposal toAdmin(PerformanceEditProposal p, Map<Long, String> nicknames) {
        Performance perf = p.getPerformance();
        return new AdminEditProposal(p.getId(), perf.getId(), perf.getTitle(), perf.getTitle(), perf.getVenueName(),
                p.getNewTitle(), p.getNewVenueName(), p.getReason(), p.getStatus().name(), p.getReviewNote(),
                p.getCreatedAt(), p.getReviewedAt(), p.getProposerId(), nicknames.get(p.getProposerId()),
                p.getOldTitle(), p.getOldVenueName(),
                p.getReviewedById() == null ? null : nicknames.get(p.getReviewedById()));
    }
}
