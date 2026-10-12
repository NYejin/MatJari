package com.seatswap.service.social;

import com.seatswap.domain.SocialAccount;
import com.seatswap.domain.SocialProvider;
import com.seatswap.domain.User;
import com.seatswap.dto.response.SocialAccountsResponse;
import com.seatswap.dto.response.SocialLinkResponse;
import com.seatswap.exception.AccountSuspendedException;
import com.seatswap.repository.SocialAccountRepository;
import com.seatswap.repository.UserRepository;
import com.seatswap.security.AccountSuspensionChecker;
import com.seatswap.service.social.SocialLoginFailure.SocialError;
import com.seatswap.service.social.SocialTestSupport.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 소셜 계정 목록·연결 시작·해제(마지막 로그인 수단 보호)와 연결 저장({@link SocialAccountLinker}) 규칙. 저장소는 mock. */
class SocialAccountServiceTest {

    private final MutableClock clock = new MutableClock();
    private SocialProperties props;
    private SocialAccountRepository accountRepository;
    private UserRepository userRepository;
    private SocialLinkTicketStore ticketStore;
    private SocialAccountService service;
    private AccountSuspensionChecker suspensionChecker;
    private SocialAccountLinker linker;

    @BeforeEach
    void setUp() {
        props = new SocialProperties();
        props.setStateSecret(SocialTestSupport.SECRET);
        props.getGoogle().setClientId("g-client");
        props.getKakao().setClientId("k-client");
        accountRepository = mock(SocialAccountRepository.class);
        userRepository = mock(UserRepository.class);
        ticketStore = new SocialLinkTicketStore(clock, props);
        suspensionChecker = mock(AccountSuspensionChecker.class);
        service = new SocialAccountService(props, accountRepository, userRepository, ticketStore, new SocialRateLimiter(clock, props));
        linker = new SocialAccountLinker(userRepository, accountRepository, suspensionChecker);
    }

    private static SocialAccount account(long userId, SocialProvider provider, String puid) {
        return SocialAccount.create(userId, provider, puid);
    }

    private User user(boolean passwordLogin) {
        User u = passwordLogin ? User.create("a@b.com", "pw", "닉네임") : User.createSocial("a@b.com", "pw", "닉네임");
        ReflectionTestUtils.setField(u, "id", 1L);
        return u;
    }

    // ---------------- 도메인 플래그 ----------------

    @Test
    void 일반_가입은_비밀번호_로그인_가능_소셜_가입은_불가() {
        assertThat(User.create("a@b.com", "pw", "닉네임").isPasswordLoginEnabled()).isTrue();
        assertThat(User.createSocial("a@b.com", "pw", "닉네임").isPasswordLoginEnabled()).isFalse();
    }

    // ---------------- 목록 / canUnlink ----------------

    @Test
    void 목록은_연결_계정_활성_제공자_canUnlink를_돌려준다() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(false)));
        when(accountRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(account(1L, SocialProvider.KAKAO, "k1")));
        SocialAccountsResponse r = service.list(1L);
        assertThat(r.accounts()).extracting(SocialAccountsResponse.Item::provider).containsExactly("kakao");
        assertThat(r.providers()).containsExactly("google", "kakao");
        assertThat(r.canUnlink()).isFalse(); // 비밀번호 로그인 불가 + 연결 1개
    }

    @Test
    void canUnlink는_비밀번호_로그인_가능_또는_연결_2개_이상이다() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(true)));
        when(accountRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(account(1L, SocialProvider.KAKAO, "k1")));
        assertThat(service.list(1L).canUnlink()).isTrue();

        when(userRepository.findById(2L)).thenReturn(Optional.of(user(false)));
        when(accountRepository.findByUserIdOrderByIdAsc(2L)).thenReturn(List.of(
                account(2L, SocialProvider.KAKAO, "k1"), account(2L, SocialProvider.GOOGLE, "g1")));
        assertThat(service.list(2L).canUnlink()).isTrue();

        when(userRepository.findById(3L)).thenReturn(Optional.of(user(false)));
        when(accountRepository.findByUserIdOrderByIdAsc(3L)).thenReturn(List.of());
        assertThat(service.list(3L).canUnlink()).isFalse();
    }

    @Test
    void 없는_사용자의_목록_조회는_인증_실패() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(9L)).isInstanceOf(AuthenticationException.class);
    }

    // ---------------- 연결 시작 ----------------

    @Test
    void 연결_시작은_userId에_묶인_티켓으로_상대경로_authorizeUrl을_돌려준다() {
        SocialLinkResponse r = service.startLink(1L, "google");
        assertThat(r.authorizeUrl()).startsWith("/api/auth/social/google/authorize?link=");
        String ticket = r.authorizeUrl().substring(r.authorizeUrl().indexOf("link=") + 5);
        assertThat(ticket).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(ticketStore.consume(ticket)).contains(1L);
        assertThat(r.toString()).doesNotContain(ticket); // 로그에 티켓이 남지 않는다
    }

    @Test
    void 이미_연결한_제공자는_409_SOCIAL_ALREADY_LINKED이고_티켓을_만들지_않는다() {
        when(accountRepository.existsByUserIdAndProvider(1L, SocialProvider.GOOGLE)).thenReturn(true);
        assertThatThrownBy(() -> service.startLink(1L, "google")).isInstanceOfSatisfying(SocialAuthException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(409);
            assertThat(e.getCode()).isEqualTo("SOCIAL_ALREADY_LINKED");
        });
        assertThat(ticketStore.size()).isZero();
    }

    @Test
    void 비활성_또는_모르는_제공자는_404_SOCIAL_PROVIDER_DISABLED() {
        for (String p : new String[]{"naver", "facebook", "GOOGLE", ""}) {
            assertThatThrownBy(() -> service.startLink(1L, p)).isInstanceOfSatisfying(SocialAuthException.class, e -> {
                assertThat(e.getStatus().value()).isEqualTo(404);
                assertThat(e.getCode()).isEqualTo("SOCIAL_PROVIDER_DISABLED");
            });
        }
        assertThat(ticketStore.size()).isZero();
    }

    @Test
    void 연결_시작은_사용자당_분당_10회로_제한되고_다른_사용자는_영향이_없다() {
        for (int i = 0; i < 10; i++) {
            service.startLink(1L, "google");
        }
        assertThatThrownBy(() -> service.startLink(1L, "google")).isInstanceOfSatisfying(SocialAuthException.class,
                e -> assertThat(e.getCode()).isEqualTo("SOCIAL_RATE_LIMITED"));
        service.startLink(2L, "google");
        clock.advance(60_001);
        service.startLink(1L, "google");
    }

    // ---------------- 해제 ----------------

    @Test
    void 마지막_로그인_수단이면_409_LAST_LOGIN_METHOD이고_삭제하지_않는다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(false)));
        SocialAccount only = account(1L, SocialProvider.GOOGLE, "g1");
        when(accountRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(only));
        assertThatThrownBy(() -> service.unlink(1L, "google")).isInstanceOfSatisfying(SocialAuthException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(409);
            assertThat(e.getCode()).isEqualTo("LAST_LOGIN_METHOD");
        });
        verify(accountRepository, never()).delete(any(SocialAccount.class));
    }

    @Test
    void 비밀번호_로그인이_가능하거나_다른_연결이_남으면_해제한다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(true)));
        SocialAccount only = account(1L, SocialProvider.GOOGLE, "g1");
        when(accountRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(only));
        service.unlink(1L, "google");
        verify(accountRepository).delete(only);

        when(userRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(user(false)));
        SocialAccount g = account(2L, SocialProvider.GOOGLE, "g2");
        when(accountRepository.findByUserIdOrderByIdAsc(2L)).thenReturn(List.of(g, account(2L, SocialProvider.KAKAO, "k2")));
        service.unlink(2L, "google");
        verify(accountRepository).delete(g);
    }

    @Test
    void 연결이_없으면_멱등으로_아무것도_하지_않는다_users_행은_먼저_잠근다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(false)));
        when(accountRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(account(1L, SocialProvider.KAKAO, "k1")));
        service.unlink(1L, "google"); // google 연결 없음 -> 마지막 수단 검사 없이 204
        verify(accountRepository, never()).delete(any(SocialAccount.class));
        verify(userRepository).findByIdForUpdate(1L);
    }

    @Test
    void 해제는_제공자가_지금_비활성이어도_가능하고_모르는_제공자는_404() {
        props.getGoogle().setClientId("");
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(true)));
        SocialAccount g = account(1L, SocialProvider.GOOGLE, "g1");
        when(accountRepository.findByUserIdOrderByIdAsc(1L)).thenReturn(List.of(g));
        service.unlink(1L, "google");
        verify(accountRepository).delete(g);
        assertThatThrownBy(() -> service.unlink(1L, "facebook")).isInstanceOf(SocialAuthException.class);
    }

    // ---------------- 연결 저장(SocialAccountLinker) ----------------

    @Test
    void 연결은_이메일과_무관하게_social_account만_저장한다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(true)));
        when(accountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub-1")).thenReturn(Optional.empty());
        when(accountRepository.existsByUserIdAndProvider(1L, SocialProvider.GOOGLE)).thenReturn(false);
        linker.link(1L, SocialProvider.GOOGLE, "sub-1");
        verify(accountRepository).saveAndFlush(any(SocialAccount.class));
        verify(userRepository, never()).existsByEmail(any());
        verify(suspensionChecker).assertNotSuspended(1L);
    }

    @Test
    void 그_제공자_계정이_다른_사용자에_연결돼_있으면_ALREADY_LINKED_OTHER() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(true)));
        when(accountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.of(account(2L, SocialProvider.GOOGLE, "sub-1")));
        assertThatThrownBy(() -> linker.link(1L, SocialProvider.GOOGLE, "sub-1")).isInstanceOf(SocialLoginFailure.class)
                .extracting(e -> ((SocialLoginFailure) e).getError()).isEqualTo(SocialError.ALREADY_LINKED_OTHER);
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void 내가_이미_그_제공자를_가졌으면_ALREADY_LINKED() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(true)));
        when(accountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.of(account(1L, SocialProvider.GOOGLE, "sub-1")));
        assertThatThrownBy(() -> linker.link(1L, SocialProvider.GOOGLE, "sub-1")).extracting(e -> ((SocialLoginFailure) e).getError())
                .isEqualTo(SocialError.ALREADY_LINKED);

        // 다른 제공자 계정 id 로 같은 제공자를 또 연결하려는 경우
        when(accountRepository.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub-2")).thenReturn(Optional.empty());
        when(accountRepository.existsByUserIdAndProvider(1L, SocialProvider.GOOGLE)).thenReturn(true);
        assertThatThrownBy(() -> linker.link(1L, SocialProvider.GOOGLE, "sub-2")).extracting(e -> ((SocialLoginFailure) e).getError())
                .isEqualTo(SocialError.ALREADY_LINKED);
    }

    @Test
    void 사용자가_없으면_INVALID_LINK_정지면_AccountSuspendedException() {
        when(userRepository.findByIdForUpdate(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> linker.link(5L, SocialProvider.GOOGLE, "s")).extracting(e -> ((SocialLoginFailure) e).getError())
                .isEqualTo(SocialError.INVALID_LINK);
        verifyNoInteractions(suspensionChecker);

        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(true)));
        doThrow(new AccountSuspendedException(null)).when(suspensionChecker).assertNotSuspended(1L);
        assertThatThrownBy(() -> linker.link(1L, SocialProvider.GOOGLE, "s")).isInstanceOf(AccountSuspendedException.class);
        verify(accountRepository, never()).saveAndFlush(any());
    }

    @Test
    void INSERT의_UNIQUE_위반은_삼키지_않고_던진다_구분은_콜백이_한다() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(true)));
        when(accountRepository.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        DataIntegrityViolationException dup = new DataIntegrityViolationException(
                "Duplicate entry for key 'social_account.uk_social_account_user_provider'");
        when(accountRepository.saveAndFlush(any(SocialAccount.class))).thenThrow(dup);
        assertThatThrownBy(() -> linker.link(1L, SocialProvider.GOOGLE, "s")).isSameAs(dup);
        assertThat(SocialAuthService.linkConflict(dup)).isEqualTo(SocialError.ALREADY_LINKED);
        assertThat(SocialAuthService.linkConflict(new DataIntegrityViolationException(
                "Duplicate entry for key 'social_account.uk_social_account_provider_user'"))).isEqualTo(SocialError.ALREADY_LINKED_OTHER);
        assertThat(SocialAuthService.linkConflict(new DataIntegrityViolationException("unknown"))).isEqualTo(SocialError.ALREADY_LINKED_OTHER);
    }
}
