package com.matjari.service.social;

import com.matjari.domain.SocialProvider;
import com.matjari.repository.SocialAccountRepository;
import com.matjari.repository.UserRepository;
import com.matjari.security.AccountSuspensionChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SocialLoginServiceRetryTest {

    private SocialUserRegistrar registrar;
    private UserRepository userRepository;
    private SocialLoginService service;
    private final SocialProfile profile = new SocialProfile("sub-1", "a@b.com", "홍길동");

    @BeforeEach
    void setUp() {
        SocialAccountRepository socialRepo = mock(SocialAccountRepository.class);
        registrar = mock(SocialUserRegistrar.class);
        userRepository = mock(UserRepository.class);
        service = new SocialLoginService(socialRepo, registrar, userRepository, mock(AccountSuspensionChecker.class));
        when(socialRepo.findByProviderAndProviderUserId(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void 교착_한_번_뒤_재시도가_성공하면_그_사용자로_진행한다() {
        when(registrar.register(any(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(7L);
        assertThat(service.loginOrSignup(SocialProvider.GOOGLE, profile)).isEqualTo(7L);
        verify(registrar, times(2)).register(any(), any());
    }

    @Test
    void 교착_뒤_재시도가_중복_키이고_이메일이_있으면_EMAIL_EXISTS다() {
        when(registrar.register(any(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenThrow(new DataIntegrityViolationException("dup"));
        when(userRepository.existsByEmail("a@b.com")).thenReturn(true);
        assertThatThrownBy(() -> service.loginOrSignup(SocialProvider.GOOGLE, profile))
                .isInstanceOf(SocialLoginFailure.class);
        verify(registrar, times(2)).register(any(), any());
    }

    @Test
    void 재시도도_교착이고_판단할_수_없으면_다시_던진다() {
        when(registrar.register(any(), any())).thenThrow(new CannotAcquireLockException("deadlock"));
        assertThatThrownBy(() -> service.loginOrSignup(SocialProvider.GOOGLE, profile))
                .isInstanceOf(CannotAcquireLockException.class);
        verify(registrar, times(2)).register(any(), any());
    }
}
