package com.matjari.service;

import com.matjari.domain.User;
import com.matjari.dto.request.LoginRequest;
import com.matjari.dto.request.SignupRequest;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.MatJariException;
import com.matjari.repository.UserRepository;
import com.matjari.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CVE-2025-22228 회귀 테스트 — 실제 BCryptPasswordEncoder를 사용한다 (AuthServiceTest는 encoder를 mock).
 *
 * Spring Security 6.3.8+ 부터 BCrypt는 72바이트 초과 비밀번호를 조용히 잘라 쓰지 않고
 * IllegalArgumentException을 던진다. 이 예외가 서비스까지 올라오면 GlobalExceptionHandler에서 500이 되므로,
 * AuthService의 72바이트 사전 차단이 encoder 호출보다 먼저 걸리는지 확인한다.
 */
class AuthServiceBcryptLimitTest {

    private static final String ASCII_72 = "a".repeat(72);
    private static final String ASCII_73 = "a".repeat(73);
    private static final String KOREAN_25 = "가".repeat(25); // 25자 = UTF-8 75바이트 (64자 제한은 통과)

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        // strength 4: 테스트 속도용. 72바이트 검사는 strength와 무관하다.
        passwordEncoder = spy(new BCryptPasswordEncoder(4));
        JwtTokenProvider jwt = mock(JwtTokenProvider.class);
        when(jwt.generateAccessToken(any(), anyString())).thenReturn("access");
        when(jwt.generateRefreshToken(any(), anyString())).thenReturn("refresh");
        authService = new AuthService(userRepository, passwordEncoder, jwt, mock(com.matjari.security.AccountSuspensionChecker.class), mock(com.matjari.service.EmailVerificationService.class));
    }

    @Test
    void bcryptEncodeRejectsOver72Bytes() {
        assertThat(KOREAN_25.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThan(72);
        BCryptPasswordEncoder raw = new BCryptPasswordEncoder(4);

        // 72바이트까지는 정상
        assertThat(raw.matches(ASCII_72, raw.encode(ASCII_72))).isTrue();
        // 72바이트 초과 encode는 예외 (6.3.0에서는 조용히 잘라서 해시했음)
        assertThatThrownBy(() -> raw.encode(ASCII_73)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> raw.encode(KOREAN_25)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 주의: CVE-2025-22228 수정(6.3.8+)은 encode(해시 생성) 경로에만 적용된다.
     * BCrypt.hashpw의 검사는 {@code !for_check && passwordb.length > 72}라서, matches(checkpw) 경로는
     * 기존 해시 호환을 위해 예외 없이 앞 72바이트만 비교한다 (6.3.10 소스 기준).
     * 따라서 로그인 시 AuthService의 72바이트 사전 차단은 업그레이드 후에도 제거하면 안 된다.
     * 라이브러리 동작이 바뀌면(false 반환 또는 예외) 이 테스트가 깨지므로 그때 사전 차단 정책을 다시 검토한다.
     */
    @Test
    void bcryptMatchesStillTruncatesAt72Bytes() {
        BCryptPasswordEncoder raw = new BCryptPasswordEncoder(4);
        String hashOf72 = raw.encode(ASCII_72);
        assertThat(raw.matches(ASCII_73, hashOf72)).isTrue();
    }

    @Test
    void signupOver72BytesIsFieldErrorBeforeEncoder() {
        assertThatThrownBy(() -> authService.signup(new SignupRequest("a@b.com", KOREAN_25, "닉네임")))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("password"));
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void loginOver72BytesIsLoginFailureNotIllegalArgument() {
        // 72바이트 앞부분이 같은 계정이 실제로 존재해도 encoder까지 가지 않고 일반 로그인 실패로 끝나야 한다
        User user = User.create("a@b.com", new BCryptPasswordEncoder(4).encode(ASCII_72), "닉네임");
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));

        for (String over : new String[]{ASCII_73, KOREAN_25, "a".repeat(1000)}) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", over)))
                    .isExactlyInstanceOf(MatJariException.class)
                    .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        verifyNoInteractions(userRepository);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void signupAndLoginAtExactly72BytesStillWork() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        // 64자 제한(DTO)과 72바이트 제한을 모두 통과하는 경계값: 한글 24자 = 72바이트
        String korean24 = "가".repeat(24);
        authService.signup(new SignupRequest("a@b.com", korean24, "닉네임"));

        User user = User.create("a@b.com", passwordEncoder.encode(korean24), "닉네임");
        when(userRepository.findByEmail("a@b.com")).thenReturn(Optional.of(user));
        assertThat(authService.login(new LoginRequest("a@b.com", korean24)).accessToken()).isEqualTo("access");
    }
}
