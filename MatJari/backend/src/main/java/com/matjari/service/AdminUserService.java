package com.matjari.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matjari.domain.AbuseReport;
import com.matjari.domain.AdminActionLog;
import com.matjari.domain.ExchangeMatchStatus;
import com.matjari.domain.TicketStatus;
import com.matjari.domain.User;
import com.matjari.domain.UserRole;
import com.matjari.domain.UserSanction;
import com.matjari.dto.response.DateTimeFormats;
import com.matjari.dto.response.PageResponse;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.AbuseReportRepository;
import com.matjari.repository.AdminActionLogRepository;
import com.matjari.repository.SocialAccountRepository;
import com.matjari.repository.TicketRepository;
import com.matjari.repository.UserRepository;
import com.matjari.repository.UserSanctionRepository;
import com.matjari.security.AccountSuspensionChecker;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 관리자 이용자 관리·조치 기록 (V22). /api/admin/** 는 SecurityConfig 가 ADMIN 만 허용한다.
 *
 * <h3>잠금·순서</h3>
 * <ul>
 *   <li>직접 정지: {@link AdminModerationService#createSanction} 공용 코어(대상 users 행은 잠그지 않는다, READ_COMMITTED, 티켓 id↑ -> 요청 -> 매칭 id↑ 취소 뒤
 *   user_sanction INSERT) 다음에 조치 기록. 신고가 없어 같은 대상 신고 행 직렬화가 없으므로 두 관리자가 정확히 동시에 같은 대상을 정지하면 활성 제재가 둘 생길 수 있다
 *   (해제는 활성 제재를 모두 해제하므로 무해하다).</li>
 *   <li>역할 변경: 탈퇴하지 않은 ADMIN 전부를 id 순으로 FOR UPDATE -> 대상 users 행 FOR UPDATE. 모든 역할 변경이 같은 순서라 교착하지 않고,
 *   '마지막 관리자' 판단은 잠근 집합으로 한다(서로를 동시에 내려 관리자가 0명이 되는 것을 막는다). 티켓·매칭은 잠그지 않는다.</li>
 *   <li>강제 탈퇴: {@link WithdrawService#forceWithdraw} (인증 요청 -> 티켓 -> 요청 -> 매칭 -> users, 알림 삭제) 같은 트랜잭션 맨 끝에서 조치 기록.
 *   이 서비스는 강제 탈퇴 경로에서 트랜잭션을 열지 않는다(WithdrawService 가 연다).</li>
 *   <li>조치 기록({@link AdminActionLogger})은 항상 각 조치 트랜잭션의 맨 끝이다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    static final int MAX_PAGE_SIZE = 100;
    static final int REASON_MAX_LENGTH = 300;
    public static final String FORCE_WITHDRAW_CONFIRM_TEXT = "강제 탈퇴";
    private static final String PLACEHOLDER_SUFFIX = "@social.matjari.invalid";

    // ------------------------------------------------------------------ DTO

    public record SuspendRequest(Integer days, String reason) {}

    public record UnsuspendRequest(String reason) {}

    public record RoleRequest(String role, String reason) {}

    public record ForceWithdrawRequest(String confirmText, String reason) {}

    public record AdminUserItem(
            Long id, String email, boolean emailPlaceholder, String nickname, String role,
            boolean suspended,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime suspendedUntil,
            boolean withdrawn,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime createdAt) {}

    public record AdminUserSanction(
            Long id, String note,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime startsAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime endsAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime revokedAt,
            String createdByNickname, Long reportId) {}

    public record AdminUserDetail(
            Long id, String email, boolean emailPlaceholder, String nickname, String role,
            boolean suspended,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime suspendedUntil,
            boolean withdrawn,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime createdAt,
            boolean passwordLoginEnabled, List<String> socialProviders,
            long activeTicketCount, long openMatchCount, long openReportsAgainstCount,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime withdrawnAt,
            List<AdminUserSanction> sanctions) {}

    public record AdminActionLogItem(
            Long id, Long adminId, String adminNickname, String action, String targetType, Long targetId, String reason, String detail,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.SECOND) LocalDateTime createdAt) {}

    private final UserRepository userRepository;
    private final UserSanctionRepository sanctionRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final TicketRepository ticketRepository;
    private final AbuseReportRepository reportRepository;
    private final AdminActionLogRepository actionLogRepository;
    private final AdminModerationService moderationService;
    private final WithdrawService withdrawService;
    private final AdminActionLogger actionLogger;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public PageResponse<AdminUserItem> list(String query, String status, String role, int page, int size) {
        String statusFilter = status == null || status.isBlank() ? "ALL" : status.strip().toUpperCase();
        if (!Set.of("ALL", "ACTIVE", "SUSPENDED", "WITHDRAWN").contains(statusFilter)) {
            throw new FieldValidationException("status", "status는 ALL, ACTIVE, SUSPENDED, WITHDRAWN 중 하나여야 합니다.");
        }
        String roleText = role == null || role.isBlank() ? "ALL" : role.strip().toUpperCase();
        UserRole roleFilter = switch (roleText) {
            case "ALL" -> null;
            case "USER" -> UserRole.USER;
            case "ADMIN" -> UserRole.ADMIN;
            default -> throw new FieldValidationException("role", "role은 ALL, USER, ADMIN 중 하나여야 합니다.");
        };
        String q = query == null ? "" : query.strip();
        String pattern = "%";
        long idQuery = -1;
        if (!q.isEmpty()) {
            pattern = "%" + q.toLowerCase().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            if (q.length() <= 18 && q.chars().allMatch(c -> c >= '0' && c <= '9')) {
                idQuery = Long.parseLong(q);
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Page<User> result = userRepository.adminSearch(pattern, idQuery, roleFilter, statusFilter, now, pageable(page, size));
        Map<Long, List<UserSanction>> active = activeByUser(result.getContent().stream().map(User::getId).toList(), now);
        List<AdminUserItem> content = result.getContent().stream().map(u -> item(u, active.get(u.getId()))).toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public AdminUserDetail detail(Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        LocalDateTime now = LocalDateTime.now(clock);
        List<UserSanction> sanctions = sanctionRepository.findByUserIdOrderByIdDesc(id);
        List<UserSanction> active = sanctions.stream().filter(s -> s.isActiveAt(now)).toList();
        Set<Long> creatorIds = new HashSet<>();
        sanctions.forEach(s -> creatorIds.add(s.getCreatedById()));
        Map<Long, String> nicknames = nicknames(creatorIds);
        long openReports = 0;
        for (Object[] row : reportRepository.countByReported(List.of(id), AbuseReport.Status.OPEN)) {
            openReports = row[2] == null ? 0 : ((Number) row[2]).longValue();
        }
        long openMatches = entityManager.createQuery(
                        "select count(m) from ExchangeMatch m where (m.userAId = :id or m.userBId = :id) and m.status in :open", Long.class)
                .setParameter("id", id)
                .setParameter("open", List.of(ExchangeMatchStatus.CHATTING, ExchangeMatchStatus.RESERVED))
                .getSingleResult();
        List<String> providers = socialAccountRepository.findByUserIdOrderByIdAsc(id).stream()
                .map(a -> a.getProvider().getPath()).toList();
        boolean suspended = !active.isEmpty();
        return new AdminUserDetail(u.getId(), u.getEmail(), isPlaceholder(u), u.getNickname(), u.getRole().name(),
                suspended, suspended ? AccountSuspensionChecker.untilOf(active) : null, u.isWithdrawn(), u.getCreatedAt(),
                u.isPasswordLoginEnabled(), providers,
                ticketRepository.countByUser_IdAndStatus(id, TicketStatus.ACTIVE), openMatches, openReports,
                u.getWithdrawnAt(),
                sanctions.stream().map(s -> new AdminUserSanction(s.getId(), s.getNote(), s.getStartsAt(), s.getEndsAt(),
                        s.getRevokedAt(), nicknames.get(s.getCreatedById()), s.getReportId())).toList());
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminActionLogItem> actionLogs(String action, String targetType, Long targetId, int page, int size) {
        AdminActionLog.Action parsedAction = null;
        if (action != null && !action.isBlank()) {
            try {
                parsedAction = AdminActionLog.Action.valueOf(action.strip());
            } catch (IllegalArgumentException e) {
                throw new FieldValidationException("action", "알 수 없는 action 입니다.");
            }
        }
        AdminActionLog.TargetType parsedType = null;
        if (targetType != null && !targetType.isBlank()) {
            try {
                parsedType = AdminActionLog.TargetType.valueOf(targetType.strip());
            } catch (IllegalArgumentException e) {
                throw new FieldValidationException("targetType", "targetType은 PERFORMANCE, USER 중 하나여야 합니다.");
            }
        }
        Page<AdminActionLog> result = actionLogRepository.search(parsedAction, parsedType, targetId, pageable(page, size));
        Set<Long> adminIds = new HashSet<>();
        result.getContent().forEach(l -> adminIds.add(l.getAdminId()));
        Map<Long, String> nicknames = nicknames(adminIds);
        List<AdminActionLogItem> content = result.getContent().stream()
                .map(l -> new AdminActionLogItem(l.getId(), l.getAdminId(), nicknames.get(l.getAdminId()), l.getAction().name(),
                        l.getTargetType().name(), l.getTargetId(), l.getReason(), l.getDetail(), l.getCreatedAt()))
                .toList();
        return new PageResponse<>(content, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    // ------------------------------------------------------------------ 조치

    /** 신고 없는 직접 이용 정지. 열린 매칭은 시스템 취소된다. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void suspend(Long adminId, Long targetId, SuspendRequest request) {
        Integer days = request == null ? null : request.days();
        if (days != null && days != 7 && days != 30) {
            throw new FieldValidationException("days", "정지 기간은 7일, 30일, 무기한 중에서 선택해주세요.");
        }
        String reason = cleanReason(request == null ? null : request.reason(), true);
        LocalDateTime now = LocalDateTime.now(clock);
        Long sanctionId = moderationService.createSanction(adminId, targetId, null, reason, days, now);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sanctionId", sanctionId);
        detail.put("days", days);
        actionLogger.log(adminId, AdminActionLog.Action.USER_SUSPEND, AdminActionLog.TargetType.USER, targetId, reason, json(detail));
    }

    /** 활성 제재를 모두 해제. 활성 제재가 없으면 멱등(기록 없음). 취소된 매칭은 복구하지 않는다. */
    @Transactional
    public void unsuspend(Long adminId, Long targetId, UnsuspendRequest request) {
        String reason = cleanReason(request == null ? null : request.reason(), false);
        if (!userRepository.existsById(targetId)) {
            throw new NotFoundException("사용자를 찾을 수 없습니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<UserSanction> active = new ArrayList<>(sanctionRepository.findActiveForUpdate(targetId, now));
        if (active.isEmpty()) {
            return;
        }
        active.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        List<Long> ids = new ArrayList<>();
        for (UserSanction s : active) {
            s.revoke(adminId, now);
            sanctionRepository.save(s);
            ids.add(s.getId());
        }
        sanctionRepository.flush();
        actionLogger.log(adminId, AdminActionLog.Action.USER_UNSUSPEND, AdminActionLog.TargetType.USER, targetId, reason,
                json(Map.of("sanctionIds", ids)));
    }

    /** 역할 변경. 규칙은 클래스 Javadoc 과 규약 문서(B절) 참고. */
    @Transactional
    public void changeRole(Long adminId, Long targetId, RoleRequest request) {
        String roleText = request == null || request.role() == null ? "" : request.role().strip().toUpperCase();
        UserRole newRole;
        if (roleText.equals("USER")) {
            newRole = UserRole.USER;
        } else if (roleText.equals("ADMIN")) {
            newRole = UserRole.ADMIN;
        } else {
            throw new FieldValidationException("role", "role은 USER 또는 ADMIN이어야 합니다.");
        }
        String reason = cleanReason(request.reason(), true);
        if (adminId.equals(targetId)) {
            throw new ConflictException("자기 자신의 역할은 바꿀 수 없습니다.", Map.of("code", "CANNOT_CHANGE_OWN_ROLE"));
        }
        // 1) 활성 ADMIN 전부를 id 순으로 잠그고 2) 대상 행을 잠근다(모든 역할 변경이 같은 순서).
        List<User> admins = userRepository.lockActiveAdmins();
        User target = userRepository.findByIdForUpdate(targetId).orElseThrow(() -> new NotFoundException("사용자를 찾을 수 없습니다."));
        if (target.isWithdrawn()) {
            throw new ConflictException("탈퇴한 사용자입니다.", Map.of("code", "TARGET_WITHDRAWN"));
        }
        UserRole oldRole = target.getRole();
        if (oldRole == newRole) {
            return;
        }
        if (newRole == UserRole.USER) {
            if (admins.stream().allMatch(a -> a.getId().equals(targetId))) {
                throw new ConflictException("마지막 관리자는 일반 사용자로 내릴 수 없습니다.", Map.of("code", "LAST_ADMIN"));
            }
        } else if (!sanctionRepository.findActive(targetId, LocalDateTime.now(clock)).isEmpty()) {
            throw new ConflictException("이용 정지 중인 사용자는 관리자로 올릴 수 없습니다. 먼저 정지를 해제해주세요.",
                    Map.of("code", "CANNOT_PROMOTE_SUSPENDED"));
        }
        target.changeRole(newRole);
        userRepository.saveAndFlush(target);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("from", oldRole.name());
        detail.put("to", newRole.name());
        actionLogger.log(adminId, AdminActionLog.Action.USER_ROLE_CHANGE, AdminActionLog.TargetType.USER, targetId, reason, json(detail));
    }

    /** 강제 탈퇴. 트랜잭션은 WithdrawService 가 연다(여기서는 열지 않는다). */
    public void forceWithdraw(Long adminId, Long targetId, ForceWithdrawRequest request) {
        String confirm = request == null ? null : request.confirmText();
        if (confirm == null || !FORCE_WITHDRAW_CONFIRM_TEXT.equals(confirm.strip())) {
            throw new FieldValidationException("confirmText", "확인 문구가 일치하지 않습니다. '" + FORCE_WITHDRAW_CONFIRM_TEXT + "'를 정확히 입력해주세요.");
        }
        String reason = cleanReason(request.reason(), true);
        if (adminId.equals(targetId)) {
            throw new ConflictException("자기 자신은 강제 탈퇴할 수 없습니다.", Map.of("code", "CANNOT_WITHDRAW_SELF"));
        }
        withdrawService.forceWithdraw(adminId, targetId, () -> actionLogger.log(adminId, AdminActionLog.Action.USER_FORCE_WITHDRAW,
                AdminActionLog.TargetType.USER, targetId, reason, null));
    }

    // ------------------------------------------------------------------ 보조

    private AdminUserItem item(User u, List<UserSanction> active) {
        boolean suspended = active != null && !active.isEmpty();
        return new AdminUserItem(u.getId(), u.getEmail(), isPlaceholder(u), u.getNickname(), u.getRole().name(),
                suspended, suspended ? AccountSuspensionChecker.untilOf(active) : null, u.isWithdrawn(), u.getCreatedAt());
    }

    private static boolean isPlaceholder(User u) {
        return u.getEmail() != null && u.getEmail().endsWith(PLACEHOLDER_SUFFIX);
    }

    private Map<Long, List<UserSanction>> activeByUser(Collection<Long> ids, LocalDateTime now) {
        Map<Long, List<UserSanction>> result = new HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        for (UserSanction s : sanctionRepository.findActiveByUserIds(ids, now)) {
            result.computeIfAbsent(s.getUserId(), k -> new ArrayList<>()).add(s);
        }
        return result;
    }

    private Map<Long, String> nicknames(Collection<Long> ids) {
        Map<Long, String> result = new HashMap<>();
        for (User u : userRepository.findAllById(ids.stream().filter(Objects::nonNull).toList())) {
            result.put(u.getId(), u.getNickname());
        }
        return result;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** 앞뒤 공백 제거. 필수인데 비면 400, 300자(코드 포인트) 초과는 400. 선택이 비면 null. */
    static String cleanReason(String value, boolean required) {
        String s = value == null ? "" : value.strip();
        if (s.isEmpty()) {
            if (required) {
                throw new FieldValidationException("reason", "사유를 입력해주세요.");
            }
            return null;
        }
        if (s.codePointCount(0, s.length()) > REASON_MAX_LENGTH) {
            throw new FieldValidationException("reason", "사유는 " + REASON_MAX_LENGTH + "자 이하로 입력해주세요.");
        }
        return s;
    }

    private static PageRequest pageable(int page, int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new FieldValidationException("size", "size는 1 이상이어야 합니다.");
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
    }
}
