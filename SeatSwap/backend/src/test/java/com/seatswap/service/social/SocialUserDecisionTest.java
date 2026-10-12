package com.seatswap.service.social;

import com.seatswap.domain.SocialAccount;
import com.seatswap.domain.SocialProvider;
import com.seatswap.domain.User;
import com.seatswap.exception.AccountSuspendedException;
import com.seatswap.repository.SocialAccountRepository;
import com.seatswap.repository.UserRepository;
import com.seatswap.security.AccountSuspensionChecker;
import com.seatswap.service.social.SocialLoginFailure.SocialError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocialUserDecisionTest {

    private UserRepository userRepository;
    private SocialAccountRepository socialRepo;
    private PasswordEncoder encoder;
    private SocialUserRegistrar registrar;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        socialRepo = mock(SocialAccountRepository.class);
        encoder = new BCryptPasswordEncoder(4);
        registrar = new SocialUserRegistrar(userRepository, socialRepo, encoder);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", 100L);
            return u;
        });
    }

    // ---------------- 가입 ----------------

    @Test
    void 검증된_이메일로_가입하면_users와_social_account를_만든다() {
        Long id = registrar.register(SocialProvider.GOOGLE, new SocialProfile("sub-1", "a@b.com", "홍길동"));
        assertThat(id).isEqualTo(100L);
        ArgumentCaptor<User> uc = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(uc.capture());
        assertThat(uc.getValue().getEmail()).isEqualTo("a@b.com");
        assertThat(uc.getValue().getNickname()).isEqualTo("홍길동");
        assertThat(uc.getValue().getRole().name()).isEqualTo("USER");
        // 비밀번호는 BCrypt 해시(평문 아님), 아무도 모르는 값
        assertThat(uc.getValue().getPassword()).startsWith("$2");
        ArgumentCaptor<SocialAccount> ac = ArgumentCaptor.forClass(SocialAccount.class);
        verify(socialRepo).saveAndFlush(ac.capture());
        assertThat(ac.getValue().getUserId()).isEqualTo(100L);
        assertThat(ac.getValue().getProvider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(ac.getValue().getProviderUserId()).isEqualTo("sub-1");
    }

    @Test
    void 이메일이_없으면_자리표시_이메일을_쓴다() {
        registrar.register(SocialProvider.KAKAO, new SocialProfile("12345", null, "카톡"));
        ArgumentCaptor<User> uc = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(uc.capture());
        assertThat(uc.getValue().getEmail()).matches("kakao\\.[0-9a-f]{16}@social\\.seatswap\\.invalid");
    }

    @Test
    void 식별자에_이상한_문자가_있으면_SHA256_앞_16자로_대체한다() {
        String email = SocialUserRegistrar.placeholderEmail(SocialProvider.NAVER, "ab+c/=@x");
        assertThat(email).matches("naver\\.[0-9a-f]{16}@social\\.seatswap\\.invalid");
        assertThat(SocialUserRegistrar.placeholderEmail(SocialProvider.NAVER, "ab+c/=@x")).isEqualTo(email);
        assertThat(SocialUserRegistrar.placeholderEmail(SocialProvider.NAVER, "x".repeat(80))).matches("naver\\.[0-9a-f]{16}@.*");
        // 대소문자만 다른 식별자는 서로 다른 자리표시 이메일이 된다(DB 는 대소문자를 구분해 저장한다)
        assertThat(SocialUserRegistrar.placeholderEmail(SocialProvider.GOOGLE, "AbC-_9"))
                .isNotEqualTo(SocialUserRegistrar.placeholderEmail(SocialProvider.GOOGLE, "abc-_9"));
    }

    @Test
    void 같은_이메일의_기존_계정이_있으면_EMAIL_EXISTS이고_아무것도_저장하지_않는다() {
        when(userRepository.existsByEmail("a@b.com")).thenReturn(true);
        assertThatThrownBy(() -> registrar.register(SocialProvider.GOOGLE, new SocialProfile("sub-1", "a@b.com", "닉네임")))
                .isInstanceOf(SocialLoginFailure.class)
                .extracting(e -> ((SocialLoginFailure) e).getError()).isEqualTo(SocialError.EMAIL_EXISTS);
        verify(userRepository, never()).saveAndFlush(any());
        verify(socialRepo, never()).saveAndFlush(any());
    }

    @Test
    void 비밀번호는_매번_다른_무작위_해시이고_어떤_이메일_비밀번호로도_맞지_않는다() {
        registrar.register(SocialProvider.GOOGLE, new SocialProfile("s1", null, null));
        registrar.register(SocialProvider.GOOGLE, new SocialProfile("s2", null, null));
        ArgumentCaptor<User> uc = ArgumentCaptor.forClass(User.class);
        verify(userRepository, org.mockito.Mockito.times(2)).saveAndFlush(uc.capture());
        String h1 = uc.getAllValues().get(0).getPassword();
        String h2 = uc.getAllValues().get(1).getPassword();
        assertThat(h1).isNotEqualTo(h2);
        for (String guess : new String[]{"", "password", "s1", "google.s1@social.seatswap.invalid", "12345678"}) {
            assertThat(encoder.matches(guess, h1)).isFalse();
        }
    }

    // ---------------- 닉네임 ----------------

    @Test
    void 닉네임은_정리하고_20자로_자르고_짧으면_대체한다() {
        assertThat(registrar.nicknameFor(SocialProvider.GOOGLE, "  홍길동  ")).isEqualTo("홍길동");
        assertThat(registrar.nicknameFor(SocialProvider.GOOGLE, "가​나‮다")).isEqualTo("가나다"); // 보이지 않는 문자 제거
        assertThat(registrar.nicknameFor(SocialProvider.GOOGLE, "a".repeat(50))).hasSize(20);
        // 서로게이트 쌍(이모지)을 가운데서 자르지 않는다
        String cut = registrar.nicknameFor(SocialProvider.GOOGLE, "a".repeat(19) + "😀");
        assertThat(cut).isEqualTo("a".repeat(19));
        assertThat(registrar.nicknameFor(SocialProvider.KAKAO, "가")).matches("카카오사용자\\d{4}");
        assertThat(registrar.nicknameFor(SocialProvider.NAVER, null)).matches("네이버사용자\\d{4}");
        assertThat(registrar.nicknameFor(SocialProvider.GOOGLE, "​ㅤ")).matches("Google사용자\\d{4}");
        assertThat(registrar.nicknameFor(SocialProvider.GOOGLE, "   ")).matches("Google사용자\\d{4}");
    }

    // ---------------- 로그인 서비스 ----------------

    private SocialLoginService loginService(AccountSuspensionChecker checker) {
        return new SocialLoginService(socialRepo, registrar, userRepository, checker);
    }

    @Test
    void 이미_연결된_소셜_계정은_가입하지_않고_그_사용자로_로그인한다_이메일은_보지_않는다() {
        when(socialRepo.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.of(SocialAccount.create(7L, SocialProvider.GOOGLE, "sub-1")));
        AccountSuspensionChecker checker = mock(AccountSuspensionChecker.class);
        Long id = loginService(checker).loginOrSignup(SocialProvider.GOOGLE, new SocialProfile("sub-1", "changed@new.com", "다른닉"));
        assertThat(id).isEqualTo(7L);
        verify(userRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).existsByEmail(any());
        verify(checker).assertNotSuspended(7L);
    }

    @Test
    void 정지된_기존_사용자는_AccountSuspendedException() {
        when(socialRepo.findByProviderAndProviderUserId(any(), any()))
                .thenReturn(Optional.of(SocialAccount.create(7L, SocialProvider.KAKAO, "1")));
        AccountSuspensionChecker checker = mock(AccountSuspensionChecker.class);
        doThrow(new AccountSuspendedException(null)).when(checker).assertNotSuspended(7L);
        assertThatThrownBy(() -> loginService(checker).loginOrSignup(SocialProvider.KAKAO, new SocialProfile("1", null, null)))
                .isInstanceOf(AccountSuspendedException.class);
    }

    @Test
    void 동시_첫_로그인에서_UNIQUE_위반이면_먼저_만들어진_사용자로_진행한다() {
        when(socialRepo.findByProviderAndProviderUserId(SocialProvider.GOOGLE, "sub-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(SocialAccount.create(55L, SocialProvider.GOOGLE, "sub-1")));
        when(socialRepo.saveAndFlush(any(SocialAccount.class))).thenThrow(new DataIntegrityViolationException("dup"));
        AccountSuspensionChecker checker = mock(AccountSuspensionChecker.class);
        assertThat(loginService(checker).loginOrSignup(SocialProvider.GOOGLE, new SocialProfile("sub-1", null, null))).isEqualTo(55L);
        verify(checker).assertNotSuspended(55L);
    }

    @Test
    void 이메일_UNIQUE_경합이면_EMAIL_EXISTS() {
        when(socialRepo.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup email"));
        when(userRepository.existsByEmail("a@b.com")).thenReturn(false, true); // register 검사 때는 없었고 경합 뒤에 생김
        assertThatThrownBy(() -> loginService(mock(AccountSuspensionChecker.class))
                .loginOrSignup(SocialProvider.GOOGLE, new SocialProfile("sub-1", "a@b.com", null)))
                .isInstanceOf(SocialLoginFailure.class)
                .extracting(e -> ((SocialLoginFailure) e).getError()).isEqualTo(SocialError.EMAIL_EXISTS);
    }

    @Test
    void 알_수_없는_무결성_위반은_그대로_던진다() {
        when(socialRepo.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("other"));
        assertThatThrownBy(() -> loginService(mock(AccountSuspensionChecker.class))
                .loginOrSignup(SocialProvider.GOOGLE, new SocialProfile("sub-1", null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(socialRepo, never()).saveAndFlush(any());
    }
}
