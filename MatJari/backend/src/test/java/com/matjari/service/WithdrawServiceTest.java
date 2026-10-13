package com.matjari.service;

import com.matjari.domain.SocialAccount;
import com.matjari.domain.SocialProvider;
import com.matjari.domain.Ticket;
import com.matjari.domain.User;
import com.matjari.domain.UserRole;
import com.matjari.dto.request.WithdrawRequest;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.MatJariException;
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
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WithdrawServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final TicketRepository ticketRepository = mock(TicketRepository.class);
    private final ExchangeRequestRepository requestRepository = mock(ExchangeRequestRepository.class);
    private final SocialAccountRepository socialRepository = mock(SocialAccountRepository.class);
    private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
    private final OpenMatchCanceller canceller = mock(OpenMatchCanceller.class);
    private final SeatClaimService claimService = mock(SeatClaimService.class);
    private final WebSocketSessionRegistry registry = mock(WebSocketSessionRegistry.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), KST);
    private final SocialProperties socialProps = new SocialProperties();
    private final SocialLinkConfirmStore reauthStore = new SocialLinkConfirmStore(clock, socialProps);
    private WithdrawService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new WithdrawService(userRepository, ticketRepository, requestRepository, socialRepository, notificationRepository,
                canceller, claimService, reauthStore, socialProps, registry, encoder, new SessionTimePolicy(clock), clock, tm);
    }

    private User passwordUser(long id) {
        User u = User.create("a@b.com", encoder.encode("password123"), "닉네임");
        ReflectionTestUtils.setField(u, "id", id);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.of(u));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));
        return u;
    }

    private User socialUser(long id) {
        User u = User.createSocial("s@social.matjari.invalid", encoder.encode("random"), "소셜");
        ReflectionTestUtils.setField(u, "id", id);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        when(userRepository.findByIdForUpdate(id)).thenReturn(Optional.of(u));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(i -> i.getArgument(0));
        return u;
    }

    @Test
    void 비밀번호_계정은_비밀번호가_맞아야_하고_틀리면_아무것도_바꾸지_않는다() {
        User u = passwordUser(5L);
        for (WithdrawRequest bad : new WithdrawRequest[] {null, new WithdrawRequest(null, "탈퇴합니다"), new WithdrawRequest("", null),
                new WithdrawRequest("x".repeat(80), null)}) {
            assertThatThrownBy(() -> service.withdraw(5L, bad)).isInstanceOf(MatJariException.class)
                    .isNotInstanceOf(ConflictException.class).hasMessage(WithdrawService.PASSWORD_MISMATCH_MESSAGE);
        }
        assertThat(u.isWithdrawn()).isFalse();
        verifyNoInteractions(canceller, claimService, socialRepository, notificationRepository);
    }

    @Test
    void 소셜_전용_계정은_확인_문구가_정확히_일치해야_한다() {
        User u = socialUser(6L);
        for (String bad : new String[] {null, "", "탈퇴합니다 ", "탈퇴 합니다"}) {
            assertThatThrownBy(() -> service.withdraw(6L, new WithdrawRequest("whatever", bad)))
                    .isInstanceOf(FieldValidationException.class);
        }
        assertThat(u.isWithdrawn()).isFalse();
        service.withdraw(6L, new WithdrawRequest(null, "탈퇴합니다"));
        assertThat(u.isWithdrawn()).isTrue();
        assertThat(u.isPasswordLoginEnabled()).isFalse();
    }

    /** 구글이 켜져 있고 이 사용자가 구글 계정(puid)을 연결한 상태를 만든다. */
    private void linkedGoogle(long userId, String puid) {
        socialProps.getGoogle().setClientId("g-client");
        when(socialRepository.findByUserIdOrderByIdAsc(userId)).thenReturn(List.of(SocialAccount.create(userId, SocialProvider.GOOGLE, puid)));
    }

    @Test
    void 연결된_제공자가_켜져_있으면_소셜_전용_계정은_재인증_코드도_있어야_한다() {
        User u = socialUser(6L);
        linkedGoogle(6L, "puid-1");
        for (String bad : new String[] {null, "", "  ", "unknown-code"}) {
            assertThatThrownBy(() -> service.withdraw(6L, new WithdrawRequest(null, "탈퇴합니다", bad)))
                    .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("reauthCode"));
        }
        assertThat(u.isWithdrawn()).isFalse();
        verifyNoInteractions(canceller, claimService);
    }

    @Test
    void 재인증_코드가_맞으면_소셜_전용_계정이_탈퇴하고_코드는_한_번만_쓴다() {
        User u = socialUser(6L);
        linkedGoogle(6L, "puid-1");
        when(socialRepository.existsByUserIdAndProviderAndProviderUserId(6L, SocialProvider.GOOGLE, "puid-1")).thenReturn(true);
        String code = reauthStore.issue(6L, SocialProvider.GOOGLE, "puid-1");

        service.withdraw(6L, new WithdrawRequest(null, "탈퇴합니다", code));

        assertThat(u.isWithdrawn()).isTrue();
        assertThat(reauthStore.consume(code)).as("한 번 쓴 코드는 다시 쓸 수 없다").isEmpty();
    }

    @Test
    void 다른_사용자의_코드나_연결되지_않은_계정의_코드는_거절하고_코드는_소진된다() {
        User u = socialUser(6L);
        linkedGoogle(6L, "puid-1");
        String others = reauthStore.issue(7L, SocialProvider.GOOGLE, "puid-1");
        assertThatThrownBy(() -> service.withdraw(6L, new WithdrawRequest(null, "탈퇴합니다", others)))
                .isInstanceOf(FieldValidationException.class);
        assertThat(reauthStore.consume(others)).isEmpty();

        // 내 코드이지만 이 사용자에게 연결된 계정(puid)이 아니다: existsBy... 가 false
        String unlinked = reauthStore.issue(6L, SocialProvider.GOOGLE, "other-puid");
        when(socialRepository.existsByUserIdAndProviderAndProviderUserId(6L, SocialProvider.GOOGLE, "other-puid")).thenReturn(false);
        assertThatThrownBy(() -> service.withdraw(6L, new WithdrawRequest(null, "탈퇴합니다", unlinked)))
                .isInstanceOf(FieldValidationException.class);
        assertThat(u.isWithdrawn()).isFalse();
    }

    @Test
    void 재인증이_맞아도_확인_문구가_틀리면_거절하고_코드는_쓰지_않는다() {
        socialUser(6L);
        linkedGoogle(6L, "puid-1");
        String code = reauthStore.issue(6L, SocialProvider.GOOGLE, "puid-1");

        assertThatThrownBy(() -> service.withdraw(6L, new WithdrawRequest(null, "탈퇴", code)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("confirmText"));
        assertThat(reauthStore.consume(code)).as("문구 오류에서는 코드를 소비하지 않는다").isPresent();
    }

    @Test
    void 연결된_제공자가_모두_꺼져_있으면_확인_문구만으로_탈퇴한다() {
        User u = socialUser(6L);
        // 구글 계정이 연결돼 있지만 지금은 꺼진 제공자(client-id 없음) -> 재인증 수단이 없다
        when(socialRepository.findByUserIdOrderByIdAsc(6L)).thenReturn(List.of(SocialAccount.create(6L, SocialProvider.GOOGLE, "puid-1")));

        service.withdraw(6L, new WithdrawRequest(null, "탈퇴합니다", null));

        assertThat(u.isWithdrawn()).isTrue();
    }

    @Test
    void 비밀번호_계정은_재인증_코드를_보지_않는다() {
        User u = passwordUser(5L);
        socialProps.getGoogle().setClientId("g-client");
        service.withdraw(5L, new WithdrawRequest("password123", null, null));
        assertThat(u.isWithdrawn()).isTrue();
    }

    @Test
    void 관리자는_409_ADMIN_CANNOT_WITHDRAW이고_정리를_시작하지_않는다() {
        User admin = passwordUser(1L);
        ReflectionTestUtils.setField(admin, "role", UserRole.ADMIN);
        assertThatThrownBy(() -> service.withdraw(1L, new WithdrawRequest("password123", null)))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "ADMIN_CANNOT_WITHDRAW"));
        verifyNoInteractions(canceller, claimService);
    }

    @Test
    void 이미_탈퇴했거나_없는_계정은_401_예외이고_잠금_뒤_재확인도_한다() {
        assertThatThrownBy(() -> service.withdraw(9L, new WithdrawRequest("x", null))).isInstanceOf(InsufficientAuthenticationException.class);

        User u = passwordUser(7L);
        // 사전 확인은 통과했지만 users 잠금을 얻고 보니 다른 탈퇴가 먼저 끝난 상황
        User done = User.create("a@b.com", "x", "닉");
        ReflectionTestUtils.setField(done, "id", 7L);
        done.anonymizeForWithdrawal("h", LocalDateTime.now(clock));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(done));
        assertThatThrownBy(() -> service.withdraw(7L, new WithdrawRequest("password123", null)))
                .isInstanceOf(InsufficientAuthenticationException.class);
        assertThat(u.isWithdrawn()).isFalse();
        verify(socialRepository, never()).deleteAllByUserId(anyLong());
    }

    @Test
    void 성공_흐름은_정리_2회_후_익명화하고_알림을_맨_끝에_지우고_순서를_지킨다() {
        User u = passwordUser(5L);
        Ticket t = mock(Ticket.class);
        when(t.isActive()).thenReturn(true);
        when(ticketRepository.findActiveIdsByUserIds(List.of(5L))).thenReturn(List.of(12L, 11L));
        when(ticketRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(t));
        when(notificationRepository.findAllIdsByUserId(5L)).thenReturn(List.of(1L, 2L, 3L));

        service.withdraw(5L, new WithdrawRequest("password123", null));

        InOrder o = inOrder(claimService, canceller, ticketRepository, requestRepository, userRepository, socialRepository, notificationRepository);
        o.verify(claimService).rejectPendingForWithdrawal(5L);
        o.verify(canceller).cancelAllOf(5L);
        o.verify(ticketRepository).findActiveIdsByUserIds(List.of(5L));
        o.verify(ticketRepository).findByIdForUpdate(11L);   // id 오름차순
        o.verify(requestRepository).closeByTicketId(eq(11L), any());
        o.verify(ticketRepository).findByIdForUpdate(12L);
        o.verify(requestRepository).closeByTicketId(eq(12L), any());
        o.verify(userRepository).findByIdForUpdate(5L);        // users 잠금은 티켓·매칭 정리 뒤
        o.verify(claimService).rejectPendingForWithdrawal(5L);   // 2차 정리
        o.verify(canceller).cancelAllOf(5L);
        o.verify(socialRepository).deleteAllByUserId(5L);
        o.verify(userRepository).saveAndFlush(u);
        o.verify(notificationRepository).deleteAllByIds(List.of(1L, 2L, 3L), 5L);   // 알림은 맨 끝

        assertThat(u.getEmail()).isEqualTo("withdrawn.5@withdrawn.matjari.invalid");
        assertThat(u.getNickname()).isEqualTo("탈퇴한 회원");
        assertThat(u.isPasswordLoginEnabled()).isFalse();
        assertThat(u.getRole()).isEqualTo(UserRole.USER);
        assertThat(u.getWithdrawnAt()).isEqualTo(LocalDateTime.of(2026, 10, 13, 12, 0));
        assertThat(encoder.matches("password123", u.getPassword())).as("원래 비밀번호는 더 이상 통하지 않는다").isFalse();
    }

    @Test
    void 알림은_500개씩_나눠_id_목록으로만_지운다() {
        passwordUser(5L);
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 1203).boxed().toList();
        when(notificationRepository.findAllIdsByUserId(5L)).thenReturn(ids);
        service.withdraw(5L, new WithdrawRequest("password123", null));
        verify(notificationRepository).deleteAllByIds(ids.subList(0, 500), 5L);
        verify(notificationRepository).deleteAllByIds(ids.subList(500, 1000), 5L);
        verify(notificationRepository).deleteAllByIds(ids.subList(1000, 1203), 5L);
        verify(notificationRepository, never()).deleteAllByIds(anyCollection(), eq(99L));
    }

    @Test
    void 사용자당_분당_5회를_넘으면_429이고_다른_사용자는_영향이_없다() {
        passwordUser(5L);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> service.withdraw(5L, new WithdrawRequest("wrong", null))).isNotInstanceOf(WithdrawService.WithdrawRateLimitedException.class);
        }
        assertThatThrownBy(() -> service.withdraw(5L, new WithdrawRequest("password123", null)))
                .isInstanceOfSatisfying(WithdrawService.WithdrawRateLimitedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WITHDRAW_RATE_LIMITED");
                    assertThat(e.getRetryAfterSeconds()).isBetween(1L, 60L);
                });
        // 다른 사용자는 영향 없음
        passwordUser(6L);
        assertThatThrownBy(() -> service.withdraw(6L, new WithdrawRequest("wrong", null))).isNotInstanceOf(WithdrawService.WithdrawRateLimitedException.class);
    }
}
