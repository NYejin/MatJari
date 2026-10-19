package com.matjari.service;

import com.matjari.domain.User;
import com.matjari.domain.UserRole;
import com.matjari.exception.ConflictException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ExchangeRequestRepository;
import com.matjari.repository.NotificationRepository;
import com.matjari.repository.SocialAccountRepository;
import com.matjari.repository.TicketRepository;
import com.matjari.repository.UserRepository;
import com.matjari.service.social.SocialLinkConfirmStore;
import com.matjari.service.social.SocialProperties;
import com.matjari.websocket.WebSocketSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 관리자 강제 탈퇴 코어: 본인 탈퇴와 같은 정리·익명화를 쓰되 비밀번호 없이, 처리자는 관리자, 조치 기록은 맨 끝. */
class ForceWithdrawServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final ExchangeRequestRepository requestRepository = mock(ExchangeRequestRepository.class);
    private final SocialAccountRepository socialRepository = mock(SocialAccountRepository.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final OpenMatchCanceller canceller = mock(OpenMatchCanceller.class);
    private final SeatClaimService claimService = mock(SeatClaimService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), KST);
    private final SocialProperties socialProps = new SocialProperties();
    private WithdrawService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new WithdrawService(userRepository, ticketRepository, requestRepository, socialRepository, notificationRepository,
                canceller, claimService, new SocialLinkConfirmStore(clock, socialProps), socialProps, mock(WebSocketSessionRegistry.class),
                new BCryptPasswordEncoder(4), new SessionTimePolicy(clock), clock, tm);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));
    }

    private User user(long id, UserRole role) {
        User u = User.create("a@b.com", "pw", "닉네임");
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "role", role);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void 없는_대상은_404_관리자_대상은_ADMIN_CANNOT_WITHDRAW_이미_탈퇴는_TARGET_WITHDRAWN() {
        assertThatThrownBy(() -> service.forceWithdraw(1L, 99L, () -> {})).isInstanceOf(NotFoundException.class);
        user(5L, UserRole.ADMIN);
        assertThatThrownBy(() -> service.forceWithdraw(1L, 5L, () -> {})).isInstanceOf(ConflictException.class)
                .hasMessageContaining("관리자 계정은 탈퇴할 수 없습니다");
        User gone = user(6L, UserRole.USER);
        gone.anonymizeForWithdrawal("x", java.time.LocalDateTime.now(clock));
        assertThatThrownBy(() -> service.forceWithdraw(1L, 6L, () -> {})).isInstanceOf(ConflictException.class)
                .hasMessage("이미 탈퇴한 사용자입니다.");
        verifyNoInteractions(canceller, claimService, notificationRepository);
    }

    @Test
    void 강제_탈퇴는_처리자를_관리자로_넘기고_조치_기록은_알림_삭제_뒤_맨_끝이다() {
        User u = user(5L, UserRole.USER);
        when(notificationRepository.findAllIdsByUserId(5L)).thenReturn(List.of(1L));
        AtomicInteger logged = new AtomicInteger();

        service.forceWithdraw(1L, 5L, logged::incrementAndGet);

        InOrder o = inOrder(claimService, canceller, userRepository, socialRepository, notificationRepository);
        o.verify(claimService).rejectPendingForWithdrawal(5L, 1L);
        o.verify(canceller).cancelAllOf(5L);
        o.verify(userRepository).findByIdForUpdate(5L);   // users 잠금은 정리 뒤
        o.verify(claimService).rejectPendingForWithdrawal(5L, 1L);
        o.verify(socialRepository).deleteAllByUserId(5L);
        o.verify(notificationRepository).deleteAllByIds(List.of(1L), 5L);
        verify(claimService, never()).rejectPendingForWithdrawal(5L);
        assertThat(logged).hasValue(1);
        assertThat(u.isWithdrawn()).isTrue();
        assertThat(u.getEmail()).isEqualTo("withdrawn.5@withdrawn.matjari.invalid");
    }

    @Test
    void 정리_중_실패하면_조치_기록을_남기지_않는다() {
        user(5L, UserRole.USER);
        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(canceller).cancelAllOf(5L);
        AtomicInteger logged = new AtomicInteger();
        assertThatThrownBy(() -> service.forceWithdraw(1L, 5L, logged::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        assertThat(logged).hasValue(0);
    }
}
