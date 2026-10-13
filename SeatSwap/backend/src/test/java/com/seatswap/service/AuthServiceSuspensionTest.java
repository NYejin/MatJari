package com.seatswap.service;

import com.seatswap.domain.User;
import com.seatswap.dto.request.LoginRequest;
import com.seatswap.dto.request.RefreshRequest;
import com.seatswap.exception.AccountSuspendedException;
import com.seatswap.exception.SeatSwapException;
import com.seatswap.repository.UserRepository;
import com.seatswap.security.AccountSuspensionChecker;
import com.seatswap.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 이용 정지: 로그인·토큰 재발급이 비밀번호/토큰 검증 뒤 403 ACCOUNT_SUSPENDED 를 내고 토큰을 발급하지 않는다. */
class AuthServiceSuspensionTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwt;
    private AccountSuspensionChecker checker;
    private AuthService authService;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwt = mock(JwtTokenProvider.class);
        checker = mock(AccountSuspensionChecker.class);
        authService = new AuthService(userRepository, passwordEncoder, jwt, checker);
        user = User.create("a@b.com", "encoded", "닉네임");
        ReflectionTestUtils.setField(user, "id", 5L);
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
    }

    @Test
    void 정지_중이면_비밀번호가_맞아도_로그인은_403_예외이고_토큰을_만들지_않는다() {
        LocalDateTime until = LocalDateTime.of(2026, 11, 1, 9, 0, 0);
        when(passwordEncoder.matches("password123", "encoded")).thenReturn(true);
        doThrow(new AccountSuspendedException(until)).when(checker).assertNotSuspended(5L);

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "password123")))
                .isInstanceOfSatisfying(AccountSuspendedException.class, e -> assertThat(e.getUntil()).isEqualTo(until));
        verify(jwt, never()).generateAccessToken(5L, "a@b.com");
    }

    @Test
    void 정지_중_로그인은_비밀번호가_맞았을_때만_탈퇴_전용_토큰을_응답에_싣는다() {
        when(passwordEncoder.matches("password123", "encoded")).thenReturn(true);
        doThrow(new AccountSuspendedException(null)).when(checker).assertNotSuspended(5L);
        when(jwt.generateWithdrawToken(5L, "a@b.com")).thenReturn("wd-token");

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "password123")))
                .isInstanceOfSatisfying(AccountSuspendedException.class,
                        e -> assertThat(e.toBody()).containsEntry("withdrawToken", "wd-token").containsEntry("code", "ACCOUNT_SUSPENDED"));
        verify(jwt, never()).generateAccessToken(5L, "a@b.com");
        verify(jwt, never()).generateRefreshToken(5L, "a@b.com");
    }

    @Test
    void 정지_예외_본문은_토큰이_없으면_withdrawToken_키가_없다() {
        assertThat(new AccountSuspendedException(null).toBody()).doesNotContainKey("withdrawToken");
    }

    @Test
    void 비밀번호가_틀리면_정지_여부를_드러내지_않고_일반_로그인_실패다() {
        when(passwordEncoder.matches("password123", "encoded")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "password123")))
                .isExactlyInstanceOf(SeatSwapException.class);
        verify(checker, never()).assertNotSuspended(5L);
    }

    @Test
    void 정지_중이면_토큰_재발급도_거부한다() {
        when(jwt.validateToken("r")).thenReturn(true);
        when(jwt.isRefreshToken("r")).thenReturn(true);
        when(jwt.getUserId("r")).thenReturn(5L);
        doThrow(new AccountSuspendedException(null)).when(checker).assertNotSuspended(5L);

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("r"))).isInstanceOf(AccountSuspendedException.class);
        verify(jwt, never()).generateAccessToken(5L, "a@b.com");
    }
}
