package com.matjari.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.matjari.domain.AdminActionLog;
import com.matjari.domain.User;
import com.matjari.domain.UserRole;
import com.matjari.domain.UserSanction;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.repository.AbuseReportRepository;
import com.matjari.repository.AdminActionLogRepository;
import com.matjari.repository.SocialAccountRepository;
import com.matjari.repository.TicketRepository;
import com.matjari.repository.UserRepository;
import com.matjari.repository.UserSanctionRepository;
import com.matjari.service.AdminUserService.ForceWithdrawRequest;
import com.matjari.service.AdminUserService.RoleRequest;
import com.matjari.service.AdminUserService.SuspendRequest;
import com.matjari.service.AdminUserService.UnsuspendRequest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 관리자 이용자 조치: 검증·거부 규칙(자기 자신·마지막 관리자·ADMIN 대상)과 조치 기록(맨 끝, 실패·멱등이면 없음). */
class AdminUserServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0, 0);

    private UserRepository userRepository;
    private UserSanctionRepository sanctionRepository;
    private AdminModerationService moderationService;
    private WithdrawService withdrawService;
    private AdminActionLogger logger;
    private AdminUserService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        sanctionRepository = mock(UserSanctionRepository.class);
        moderationService = mock(AdminModerationService.class);
        withdrawService = mock(WithdrawService.class);
        logger = mock(AdminActionLogger.class);
        service = new AdminUserService(userRepository, sanctionRepository, mock(SocialAccountRepository.class), mock(TicketRepository.class),
                mock(AbuseReportRepository.class), mock(AdminActionLogRepository.class), moderationService, withdrawService, logger,
                mock(EntityManager.class), new ObjectMapper(), Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));
    }

    private User user(long id, UserRole role) {
        User u = User.create("u" + id + "@t.com", "pw", "닉" + id);
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "role", role);
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.of(u));
        return u;
    }

    private static void assertCode(Throwable t, String code) {
        assertThat(t).isInstanceOf(ConflictException.class);
        assertThat(((ConflictException) t).getDetails()).containsEntry("code", code);
    }

    // ------------------------------------------------------------------ 정지

    @Test
    void 직접_정지는_기간과_사유를_검증한다() {
        assertThatThrownBy(() -> service.suspend(1L, 2L, new SuspendRequest(14, "사유"))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.suspend(1L, 2L, new SuspendRequest(7, "  "))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.suspend(1L, 2L, new SuspendRequest(7, "x".repeat(301)))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.suspend(1L, 2L, null)).isInstanceOf(FieldValidationException.class);
        verifyNoInteractions(moderationService, logger);
    }

    @Test
    void 직접_정지는_신고_없이_공용_코어를_부르고_기록은_그_뒤에_남긴다() {
        when(moderationService.createSanction(eq(1L), eq(2L), isNull(), eq("사기"), eq(30), any())).thenReturn(77L);
        service.suspend(1L, 2L, new SuspendRequest(30, " 사기 "));
        InOrder o = inOrder(moderationService, logger);
        o.verify(moderationService).createSanction(eq(1L), eq(2L), isNull(), eq("사기"), eq(30), eq(NOW));
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        o.verify(logger).log(eq(1L), eq(AdminActionLog.Action.USER_SUSPEND), eq(AdminActionLog.TargetType.USER), eq(2L), eq("사기"), detail.capture());
        assertThat(detail.getValue()).contains("\"sanctionId\":77").contains("\"days\":30");
    }

    @Test
    void 정지_규칙_위반이면_기록을_남기지_않는다() {
        when(moderationService.createSanction(anyLong(), anyLong(), any(), any(), any(), any()))
                .thenThrow(new ConflictException("관리자", Map.of("code", "CANNOT_SANCTION_ADMIN")));
        assertThatThrownBy(() -> service.suspend(1L, 2L, new SuspendRequest(null, "사유"))).isInstanceOf(ConflictException.class);
        verifyNoInteractions(logger);
    }

    @Test
    void 정지_해제는_활성_제재를_모두_해제하고_없으면_멱등이다() {
        when(userRepository.existsById(2L)).thenReturn(true);
        when(sanctionRepository.findActiveForUpdate(eq(2L), any())).thenReturn(List.of());
        service.unsuspend(1L, 2L, new UnsuspendRequest(null));
        verifyNoInteractions(logger);

        UserSanction a = UserSanction.suspend(2L, null, "a", NOW.minusDays(1), null, 1L);
        UserSanction b = UserSanction.suspend(2L, null, "b", NOW.minusDays(1), 30, 1L);
        ReflectionTestUtils.setField(a, "id", 5L);
        ReflectionTestUtils.setField(b, "id", 4L);
        when(sanctionRepository.findActiveForUpdate(eq(2L), any())).thenReturn(List.of(a, b));
        service.unsuspend(1L, 2L, new UnsuspendRequest("오해"));
        assertThat(a.getRevokedAt()).isEqualTo(NOW);
        assertThat(b.getRevokedById()).isEqualTo(1L);
        verify(logger).log(eq(1L), eq(AdminActionLog.Action.USER_UNSUSPEND), eq(AdminActionLog.TargetType.USER), eq(2L), eq("오해"),
                eq("{\"sanctionIds\":[4,5]}"));
    }

    // ------------------------------------------------------------------ 역할

    @Test
    void 자기_자신의_역할은_바꿀_수_없다() {
        assertThatThrownBy(() -> service.changeRole(1L, 1L, new RoleRequest("USER", "사유")))
                .satisfies(t -> assertCode(t, "CANNOT_CHANGE_OWN_ROLE"));
        verify(userRepository, never()).lockActiveAdmins();
    }

    @Test
    void 마지막_관리자는_내릴_수_없고_다른_관리자가_있으면_내린다() {
        User target = user(2L, UserRole.ADMIN);
        when(userRepository.lockActiveAdmins()).thenReturn(List.of(target));
        assertThatThrownBy(() -> service.changeRole(1L, 2L, new RoleRequest("USER", "사유"))).satisfies(t -> assertCode(t, "LAST_ADMIN"));
        assertThat(target.getRole()).isEqualTo(UserRole.ADMIN);
        verifyNoInteractions(logger);

        User me = user(1L, UserRole.ADMIN);
        when(userRepository.lockActiveAdmins()).thenReturn(List.of(me, target));
        service.changeRole(1L, 2L, new RoleRequest("user", "퇴임"));
        assertThat(target.getRole()).isEqualTo(UserRole.USER);
        verify(logger).log(eq(1L), eq(AdminActionLog.Action.USER_ROLE_CHANGE), eq(AdminActionLog.TargetType.USER), eq(2L), eq("퇴임"),
                eq("{\"from\":\"ADMIN\",\"to\":\"USER\"}"));
    }

    @Test
    void 관리자로_올리기는_정지_중이거나_탈퇴한_사용자면_거부한다() {
        User target = user(2L, UserRole.USER);
        when(userRepository.lockActiveAdmins()).thenReturn(List.of());
        when(sanctionRepository.findActive(eq(2L), any())).thenReturn(List.of(UserSanction.suspend(2L, null, "a", NOW.minusDays(1), null, 1L)));
        assertThatThrownBy(() -> service.changeRole(1L, 2L, new RoleRequest("ADMIN", "사유")))
                .satisfies(t -> assertCode(t, "CANNOT_PROMOTE_SUSPENDED"));

        when(sanctionRepository.findActive(eq(2L), any())).thenReturn(List.of());
        service.changeRole(1L, 2L, new RoleRequest("ADMIN", "사유"));
        assertThat(target.getRole()).isEqualTo(UserRole.ADMIN);

        User gone = user(3L, UserRole.USER);
        gone.anonymizeForWithdrawal("x", NOW);
        assertThatThrownBy(() -> service.changeRole(1L, 3L, new RoleRequest("ADMIN", "사유"))).satisfies(t -> assertCode(t, "TARGET_WITHDRAWN"));
    }

    @Test
    void 같은_역할이면_멱등이고_기록하지_않으며_사유와_역할은_필수다() {
        user(2L, UserRole.USER);
        when(userRepository.lockActiveAdmins()).thenReturn(List.of());
        service.changeRole(1L, 2L, new RoleRequest("USER", "사유"));
        verifyNoInteractions(logger);
        assertThatThrownBy(() -> service.changeRole(1L, 2L, new RoleRequest("USER", null))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.changeRole(1L, 2L, new RoleRequest("SUPER", "사유"))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.changeRole(1L, 2L, null)).isInstanceOf(FieldValidationException.class);
    }

    // ------------------------------------------------------------------ 강제 탈퇴

    @Test
    void 강제_탈퇴는_확인_문구와_사유가_필요하고_자기_자신은_안_된다() {
        assertThatThrownBy(() -> service.forceWithdraw(1L, 2L, new ForceWithdrawRequest("탈퇴", "사유"))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.forceWithdraw(1L, 2L, new ForceWithdrawRequest("강제 탈퇴", " "))).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.forceWithdraw(1L, 1L, new ForceWithdrawRequest("강제 탈퇴", "사유"))).satisfies(t -> assertCode(t, "CANNOT_WITHDRAW_SELF"));
        verifyNoInteractions(withdrawService);
    }

    @Test
    void 강제_탈퇴는_탈퇴_코어에_맡기고_맨_끝_콜백으로_기록한다() {
        service.forceWithdraw(1L, 2L, new ForceWithdrawRequest("강제 탈퇴", "사기 반복"));
        ArgumentCaptor<Runnable> atEnd = ArgumentCaptor.forClass(Runnable.class);
        verify(withdrawService).forceWithdraw(eq(1L), eq(2L), atEnd.capture());
        verifyNoInteractions(logger);   // 코어가 끝까지 정리한 뒤에만 호출된다
        atEnd.getValue().run();
        verify(logger).log(1L, AdminActionLog.Action.USER_FORCE_WITHDRAW, AdminActionLog.TargetType.USER, 2L, "사기 반복", null);
    }

    // ------------------------------------------------------------------ 조회

    @Test
    void 목록_필터_값을_검증하고_검색어를_이스케이프한다() {
        assertThatThrownBy(() -> service.list(null, "BAD", null, 0, 20)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.list(null, null, "BAD", 0, 20)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.list(null, null, null, -1, 20)).isInstanceOf(FieldValidationException.class);
        when(userRepository.adminSearch(any(), anyLong(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of()));
        service.list(" 50%_a! ", "suspended", "admin", 0, 500);
        verify(userRepository).adminSearch(eq("%50!%!_a!!%"), eq(-1L), eq(UserRole.ADMIN), eq("SUSPENDED"), eq(NOW),
                org.mockito.ArgumentMatchers.argThat(p -> p.getPageSize() == 100));
        service.list("123", null, null, 0, 20);
        verify(userRepository).adminSearch(eq("%123%"), eq(123L), any(), eq("ALL"), any(), any());
    }
}
