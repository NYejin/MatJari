package com.seatswap.service;

import com.seatswap.domain.AbuseReport;
import com.seatswap.domain.ChatMessage;
import com.seatswap.domain.User;
import com.seatswap.domain.UserBlock;
import com.seatswap.domain.UserRole;
import com.seatswap.domain.UserSanction;
import com.seatswap.dto.response.ModerationDtos.ActiveSanctionRef;
import com.seatswap.dto.response.ModerationDtos.AdminBlockResponse;
import com.seatswap.dto.response.ModerationDtos.AdminReport;
import com.seatswap.dto.response.ModerationDtos.AdminReportMessage;
import com.seatswap.dto.response.ModerationDtos.AdminReportMessagePage;
import com.seatswap.dto.response.ModerationDtos.AdminSanctionResponse;
import com.seatswap.dto.response.ModerationDtos.ReportedUser;
import com.seatswap.dto.response.ModerationDtos.ResolveRequest;
import com.seatswap.dto.response.ModerationDtos.SanctionInput;
import com.seatswap.dto.response.ModerationDtos.UserRef;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.AbuseReportRepository;
import com.seatswap.repository.ChatMessageRepository;
import com.seatswap.repository.UserRepository;
import com.seatswap.repository.UserSanctionRepository;
import com.seatswap.security.AccountSuspensionChecker;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import com.seatswap.websocket.WebSocketSessionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 관리자 신고 처리·이용 정지·차단 현황 (V13). 모든 경로는 /api/admin/** 라 SecurityConfig 가 ADMIN 만 통과시킨다.
 *
 * <h3>처리(resolve/dismiss)와 잠금 순서</h3>
 * ①<b>신고 행 FOR UPDATE 가 첫 잠금</b>(resolve 는 같은 대상의 신고 행 전체를 id 순으로 잠근다. 같은 신고의 동시 처리는 여기서 직렬화, 먼저 커밋한 쪽만 성공하고 나머지는 409 REPORT_ALREADY_HANDLED)
 * -> (제재를 함께 걸 때) ②대상 ADMIN 여부 읽기(409 CANNOT_SANCTION_ADMIN) -> ③활성 제재 중복 잠금 읽기(409 SANCTION_ALREADY_ACTIVE)
 * -> ④{@link OpenMatchCanceller#cancelAllOf}(티켓 id↑ -> 요청 id↑ -> 매칭 id↑) -> ⑤user_sanction INSERT -> ⑥신고 행 갱신(sanction_id).
 * <ul>
 *   <li><b>대상 users 행은 잠그지 않는다.</b> canceller 가 티켓을 잠그므로 그 앞에서 users 행을 X 로 잡으면 "users 는 티켓 뒤"
 *   규약(wiki 잠금 함정 1절)을 깨고, 티켓 -> users 순으로 잡는 기존 경로와 교착할 수 있다. 활성 제재 중복은 대신 ③의 잠금 읽기가 막는다:
 *   user_sanction(user_id) 인덱스 범위를 잠그므로 같은 대상에 대한 두 제재 생성이 겹치면 한쪽이 교착(503 BUSY)으로 밀려나 중복 제재가 생기지 않는다.</li>
 *   <li>user_sanction INSERT 는 canceller 뒤(트랜잭션 끝쪽)다. INSERT 의 FK 검사가 users 행 S 잠금을 거는데, 이를 canceller 앞에 두면
 *   users S 를 쥔 채 티켓을 잠그는 순서가 되기 때문이다. 같은 이유로 신고 행(자식 쪽 FK 가 없는 sanction_id)은 마지막에 갱신한다.</li>
 *   <li>신고 행을 잠근 뒤 티켓·요청·매칭을 잠그지만, 그 반대(티켓·매칭을 잠근 채 신고 행을 기다리는) 경로는 없다(신고 INSERT 는 아무것도 잠그지 않는다).</li>
 * </ul>
 * 알려진 한계: ③은 "활성 제재가 있는 사용자"를 보호할 뿐, 처음 제재를 거는 두 관리자가 정확히 동시에 서로 다른 신고로 같은 대상을 처리하면
 * 한쪽이 503 으로 재시도 안내를 받는다.
 */
@Service
@RequiredArgsConstructor
public class AdminModerationService {

    static final int MAX_PAGE_SIZE = 100;
    static final int DEFAULT_MESSAGE_SIZE = 50;
    static final int MAX_MESSAGE_SIZE = 200;
    static final String HANDLED_MESSAGE = "이미 처리된 신고입니다.";

    private final AbuseReportRepository reportRepository;
    private final UserSanctionRepository sanctionRepository;
    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;
    /** 구현체(BE-A)가 없는 환경에서도 컨텍스트가 뜨도록 호출 시점에 꺼낸다. */
    private final ObjectProvider<OpenMatchCanceller> cancellerProvider;
    private final EntityManager entityManager;
    private final Clock clock;
    /** 이용 정지 커밋 뒤 대상의 열려 있는 STOMP 연결을 닫는다. */
    private final WebSocketSessionRegistry sessionRegistry;

    // ------------------------------------------------------------------ 신고

    @Transactional(readOnly = true)
    public PageResponse<AdminReport> listReports(String status, String reason, int page, int size) {
        AbuseReport.Status parsedStatus = null;
        AbuseReport.Reason parsedReason = null;
        if (status != null && !status.isBlank()) {
            try {
                parsedStatus = AbuseReport.Status.valueOf(status.strip());
            } catch (IllegalArgumentException e) {
                throw new FieldValidationException("status", "status는 OPEN, RESOLVED, DISMISSED 중 하나여야 합니다.");
            }
        }
        if (reason != null && !reason.isBlank()) {
            try {
                parsedReason = AbuseReport.Reason.valueOf(reason.strip());
            } catch (IllegalArgumentException e) {
                throw new FieldValidationException("reason", "reason은 ABUSE, FRAUD, NO_SHOW, FAKE_SEAT, OTHER 중 하나여야 합니다.");
            }
        }
        Page<AbuseReport> result = reportRepository.search(parsedStatus, parsedReason, pageable(page, size));
        List<AdminReport> content = toAdminReports(result.getContent());
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminReport getReport(Long id) {
        AbuseReport report = reportRepository.findById(id).orElseThrow(AdminModerationService::reportNotFound);
        return toAdminReports(List.of(report)).get(0);
    }

    /** 채팅 근거: 그 신고의 매칭 메시지만, 최신부터 위로(before = 그보다 오래된 것). size 기본 50, 최대 200. */
    @Transactional(readOnly = true)
    public AdminReportMessagePage messages(Long id, Long before, Integer size) {
        int limit = size == null ? DEFAULT_MESSAGE_SIZE : size;
        if (limit < 1) {
            throw new FieldValidationException("size", "size는 1 이상이어야 합니다.");
        }
        limit = Math.min(limit, MAX_MESSAGE_SIZE);
        AbuseReport report = reportRepository.findById(id).orElseThrow(AdminModerationService::reportNotFound);
        List<ChatMessage> rows = before == null
                ? chatMessageRepository.findLatest(report.getMatchId(), limit + 1)
                : chatMessageRepository.findBefore(report.getMatchId(), before, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<ChatMessage> page = hasMore ? rows.subList(0, limit) : rows;
        Map<Long, String> nicknames = nicknames(List.of(report.getReporterId(), report.getReportedId()));
        List<AdminReportMessage> messages = page.stream()
                .map(m -> new AdminReportMessage(m.getId(), m.getSenderId(), nicknames.get(m.getSenderId()), m.getContent(), m.getCreatedAt()))
                .toList();
        return new AdminReportMessagePage(messages, hasMore);
    }

    /** READ_COMMITTED: OpenMatchCanceller 가 티켓 잠금 뒤 매칭을 다시 읽으므로 트랜잭션 스냅샷이 낡으면 안 된다(그리고 호출 전에 ExchangeMatch 를 읽지 않는다). */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminReport resolve(Long adminId, Long reportId, ResolveRequest request) {
        String note = cleanNote(request == null ? null : request.note(), "note", false);
        SanctionInput sanctionInput = request == null ? null : request.sanction();
        String sanctionNote = null;
        if (sanctionInput != null) {
            Integer days = sanctionInput.days();
            if (days != null && days != 7 && days != 30) {
                throw new FieldValidationException("sanction.days", "정지 기간은 7일, 30일, 무기한 중에서 선택해주세요.");
            }
            sanctionNote = cleanNote(sanctionInput.note(), "sanction.note", true);
        }

        // 제재를 걸 때만 같은 대상의 신고 행 전체를 잠가 직렬화한다(제재 없는 처리는 그 신고 행만 잠근다)
        AbuseReport report = sanctionInput == null ? lockOpenReport(reportId) : lockOpenReportOfTarget(reportId);
        LocalDateTime now = LocalDateTime.now(clock);
        Long sanctionId = null;
        if (sanctionInput != null) {
            Long targetId = report.getReportedId();
            User target = userRepository.findById(targetId)
                    .orElseThrow(() -> new NotFoundException("대상 사용자를 찾을 수 없습니다."));
            if (target.getRole() == UserRole.ADMIN || targetId.equals(adminId)) {
                throw new ConflictException("관리자 계정은 제재할 수 없습니다.", Map.of("code", "CANNOT_SANCTION_ADMIN"));
            }
            if (!sanctionRepository.findActiveForUpdate(targetId, now).isEmpty()) {
                throw new ConflictException("이미 이용 정지 중인 사용자입니다.", Map.of("code", "SANCTION_ALREADY_ACTIVE"));
            }
            // 열린 매칭 시스템 취소(티켓 -> 요청 -> 매칭 잠금)는 users 행 잠금 없이, user_sanction INSERT 보다 먼저.
            cancellerProvider.getObject().cancelAllOf(targetId);
            UserSanction sanction = sanctionRepository.saveAndFlush(
                    UserSanction.suspend(targetId, report.getId(), sanctionNote, now, sanctionInput.days(), adminId));
            sanctionId = sanction.getId();
            closeSocketsAfterCommit(targetId);
        }
        report.resolve(adminId, note, sanctionId, now);
        reportRepository.saveAndFlush(report);
        return toAdminReports(List.of(report)).get(0);
    }

    /** 커밋된 뒤에만 대상의 STOMP 연결을 닫는다(롤백이면 닫지 않는다). 실패해도 처리 결과에는 영향이 없다. */
    private void closeSocketsAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return; // 트랜잭션 밖(단위 테스트)에서는 닫지 않는다
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    sessionRegistry.closeAllOf(userId);
                } catch (RuntimeException e) {
                    // 연결이 남아도 REST 는 이미 403 이고 열린 매칭은 취소됐다. 토큰 만료 때 정리된다.
                }
            }
        });
    }

    @Transactional
    public AdminReport dismiss(Long adminId, Long reportId, String rawNote) {
        String note = cleanNote(rawNote, "note", false);
        AbuseReport report = lockOpenReport(reportId);
        report.dismiss(adminId, note, LocalDateTime.now(clock));
        reportRepository.saveAndFlush(report);
        return toAdminReports(List.of(report)).get(0);
    }

    /** 첫 쿼리: 신고 행 잠금. 이미 처리된 신고는 409 REPORT_ALREADY_HANDLED. */
    private AbuseReport lockOpenReport(Long reportId) {
        AbuseReport report = reportRepository.findByIdForUpdate(reportId).orElseThrow(AdminModerationService::reportNotFound);
        if (!report.isOpen()) {
            throw new ConflictException(HANDLED_MESSAGE, Map.of("code", "REPORT_ALREADY_HANDLED"));
        }
        return report;
    }

    /**
     * resolve 의 첫 쿼리: 같은 대상(reported_id)의 신고 행을 모두 id 순으로 잠근다. READ_COMMITTED 에는 갭 잠금이 없어
     * 활성 제재 중복 검사(잠금 읽기)만으로는 새 제재 INSERT 두 건을 막지 못하므로, 같은 대상을 처리하는 모든 resolve 가
     * 같은 행 집합을 같은 순서로 잠가 직렬화한다(대상이 다르면 집합이 겹치지 않아 서로 기다리지 않는다).
     */
    private AbuseReport lockOpenReportOfTarget(Long reportId) {
        Long reportedId = reportRepository.findReportedIdById(reportId).orElseThrow(AdminModerationService::reportNotFound);
        AbuseReport report = reportRepository.lockAllByReportedId(reportedId).stream()
                .filter(r -> r.getId().equals(reportId)).findFirst().orElseThrow(AdminModerationService::reportNotFound);
        if (!report.isOpen()) {
            throw new ConflictException(HANDLED_MESSAGE, Map.of("code", "REPORT_ALREADY_HANDLED"));
        }
        return report;
    }

    // ------------------------------------------------------------------ 제재

    /** active: "true" 활성만, "false" 비활성(해제·만료)만, 그 외(null·빈 값·전체·all)는 전체. */
    @Transactional(readOnly = true)
    public PageResponse<AdminSanctionResponse> listSanctions(String active, int page, int size) {
        LocalDateTime now = LocalDateTime.now(clock);
        Pageable pageable = pageable(page, size);
        String filter = active == null ? "" : active.strip();
        Page<UserSanction> result;
        if ("true".equalsIgnoreCase(filter)) {
            result = sanctionRepository.findPageActive(now, pageable);
        } else if ("false".equalsIgnoreCase(filter)) {
            result = sanctionRepository.findPageInactive(now, pageable);
        } else if (filter.isEmpty() || "all".equalsIgnoreCase(filter) || "전체".equals(filter)) {
            result = sanctionRepository.findPageAll(pageable);
        } else {
            throw new FieldValidationException("active", "active는 true, false 또는 생략(전체)이어야 합니다.");
        }
        List<AdminSanctionResponse> content = toSanctionResponses(result.getContent(), now);
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    /** 해제. 이미 해제됐거나 만료된 제재는 409 SANCTION_NOT_ACTIVE. 취소된 매칭은 복구하지 않는다. */
    @Transactional
    public AdminSanctionResponse revoke(Long adminId, Long sanctionId) {
        UserSanction sanction = sanctionRepository.findByIdForUpdate(sanctionId)
                .orElseThrow(() -> new NotFoundException("제재를 찾을 수 없습니다."));
        LocalDateTime now = LocalDateTime.now(clock);
        if (!sanction.isActiveAt(now)) {
            throw new ConflictException("이미 해제되었거나 기간이 끝난 제재입니다.", Map.of("code", "SANCTION_NOT_ACTIVE"));
        }
        sanction.revoke(adminId, now);
        sanctionRepository.saveAndFlush(sanction);
        return toSanctionResponses(List.of(sanction), now).get(0);
    }

    // ------------------------------------------------------------------ 차단 현황(읽기 전용)

    @Transactional(readOnly = true)
    public PageResponse<AdminBlockResponse> listBlocks(int page, int size) {
        Pageable pageable = pageable(page, size);
        List<UserBlock> rows = entityManager.createQuery("select b from UserBlock b order by b.id desc", UserBlock.class)
                .setFirstResult((int) pageable.getOffset()).setMaxResults(pageable.getPageSize()).getResultList();
        long total = entityManager.createQuery("select count(b) from UserBlock b", Long.class).getSingleResult();
        Set<Long> ids = new HashSet<>();
        rows.forEach(b -> {
            ids.add(b.getBlockerId());
            ids.add(b.getBlockedId());
        });
        Map<Long, String> nicknames = nicknames(ids);
        List<AdminBlockResponse> content = rows.stream()
                .map(b -> new AdminBlockResponse(b.getId(), new UserRef(b.getBlockerId(), nicknames.get(b.getBlockerId())),
                        new UserRef(b.getBlockedId(), nicknames.get(b.getBlockedId())), b.getCreatedAt()))
                .toList();
        int totalPages = (int) Math.ceil(total / (double) pageable.getPageSize());
        return new PageResponse<>(content, pageable.getPageNumber(), pageable.getPageSize(), total, totalPages);
    }

    // ------------------------------------------------------------------ 변환

    private List<AdminReport> toAdminReports(List<AbuseReport> reports) {
        if (reports.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Set<Long> userIds = new HashSet<>();
        Set<Long> reportedIds = new HashSet<>();
        for (AbuseReport r : reports) {
            userIds.add(r.getReporterId());
            userIds.add(r.getReportedId());
            reportedIds.add(r.getReportedId());
            if (r.getHandledById() != null) {
                userIds.add(r.getHandledById());
            }
        }
        Map<Long, String> nicknames = nicknames(userIds);
        Map<Long, long[]> counts = new HashMap<>();
        for (Object[] row : reportRepository.countByReported(reportedIds, AbuseReport.Status.OPEN)) {
            counts.put((Long) row[0], new long[]{((Number) row[1]).longValue(), row[2] == null ? 0 : ((Number) row[2]).longValue()});
        }
        Map<Long, List<UserSanction>> active = new HashMap<>();
        for (UserSanction s : sanctionRepository.findActiveByUserIds(reportedIds, now)) {
            active.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(s);
        }
        List<AdminReport> result = new ArrayList<>(reports.size());
        for (AbuseReport r : reports) {
            long[] c = counts.getOrDefault(r.getReportedId(), new long[]{0, 0});
            List<UserSanction> list = active.get(r.getReportedId());
            ActiveSanctionRef activeRef = list == null ? null : new ActiveSanctionRef(
                    list.stream().map(UserSanction::getId).max(Long::compare).orElse(null), AccountSuspensionChecker.untilOf(list));
            result.add(new AdminReport(r.getId(), r.getMatchId(), r.getReason().name(), r.getDetail(), r.getStatus().name(),
                    r.getCreatedAt(), r.getHandledAt(),
                    r.getHandledById() == null ? null : nicknames.get(r.getHandledById()), r.getHandlerNote(),
                    new UserRef(r.getReporterId(), nicknames.get(r.getReporterId())),
                    new ReportedUser(r.getReportedId(), nicknames.get(r.getReportedId()), c[0], c[1], activeRef)));
        }
        return result;
    }

    private List<AdminSanctionResponse> toSanctionResponses(List<UserSanction> sanctions, LocalDateTime now) {
        Set<Long> ids = new HashSet<>();
        for (UserSanction s : sanctions) {
            ids.add(s.getUserId());
            ids.add(s.getCreatedById());
        }
        Map<Long, String> nicknames = nicknames(ids);
        return sanctions.stream()
                .map(s -> new AdminSanctionResponse(s.getId(), s.getUserId(), nicknames.get(s.getUserId()), s.getType().name(), s.getNote(),
                        s.getStartsAt(), s.getEndsAt(), s.getRevokedAt(), s.isActiveAt(now), nicknames.get(s.getCreatedById()), s.getReportId()))
                .toList();
    }

    private Map<Long, String> nicknames(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        for (User u : userRepository.findAllById(ids.stream().filter(Objects::nonNull).toList())) {
            result.put(u.getId(), u.getNickname());
        }
        return result;
    }

    private static NotFoundException reportNotFound() {
        return new NotFoundException(AbuseReportService.REPORT_NOT_FOUND_MESSAGE);
    }

    /** 앞뒤 공백 제거, 비면 null(required 면 400), 300자(코드 포인트) 초과는 400. */
    private static String cleanNote(String value, String field, boolean required) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            if (required) {
                throw new FieldValidationException(field, "사유를 입력해주세요.");
            }
            return null;
        }
        if (stripped.codePointCount(0, stripped.length()) > AbuseReport.HANDLER_NOTE_MAX_LENGTH) {
            throw new FieldValidationException(field, "메모는 " + AbuseReport.HANDLER_NOTE_MAX_LENGTH + "자 이하로 입력해주세요.");
        }
        return stripped;
    }

    private static Pageable pageable(int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new FieldValidationException("size", "size는 1 이상이어야 합니다.");
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
    }
}
