package com.seatswap.service;

import com.seatswap.domain.User;
import com.seatswap.dto.request.LoginRequest;
import com.seatswap.dto.request.SignupRequest;
import com.seatswap.dto.response.UserResponse;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.SeatSwapException;
import com.seatswap.repository.UserRepository;
import com.seatswap.security.JwtTokenProvider;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authService = new AuthService(userRepository, passwordEncoder, mock(JwtTokenProvider.class), mock(com.seatswap.security.AccountSuspensionChecker.class));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
    }

    private static DataIntegrityViolationException mysqlViolation(String sqlMessage, int errorCode,
                                                                  ConstraintViolationException.ConstraintKind kind,
                                                                  String constraintName) {
        SQLIntegrityConstraintViolationException sql =
                new SQLIntegrityConstraintViolationException(sqlMessage, "23000", errorCode);
        ConstraintViolationException cve =
                new ConstraintViolationException("could not execute statement", sql, "insert ...", kind, constraintName);
        return new DataIntegrityViolationException("could not execute statement", cve);
    }

    @Test
    void raceOnEmailUniqueMapsToEmailFieldError() {
        when(userRepository.existsByEmail("a@b.com")).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(mysqlViolation(
                "Duplicate entry 'a@b.com' for key 'users.UK6dotkott2kjsp8vw4d0m25fb7'", 1062,
                ConstraintViolationException.ConstraintKind.UNIQUE, "UK6dotkott2kjsp8vw4d0m25fb7"));

        assertThatThrownBy(() -> authService.signup(new SignupRequest("A@B.com", "password123", "닉네임")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("email");
                    assertThat(e.getMessage()).isEqualTo("이미 가입된 이메일입니다.");
                });
    }

    @Test
    void invalidTldEmailIsRejectedBecauseSocialPlaceholdersUseIt() {
        assertThatThrownBy(() -> authService.signup(new SignupRequest("kakao.123@social.seatswap.invalid", "password123", "닉네임")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("email"));
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    void namedEmailConstraintAlsoMapsToEmailFieldError() {
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(mysqlViolation(
                "Duplicate entry 'x' for key 'users.uk_users_email'", 1062,
                ConstraintViolationException.ConstraintKind.UNIQUE, "uk_users_email"));

        assertThatThrownBy(() -> authService.signup(new SignupRequest("a@b.com", "password123", "닉네임")))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void uniqueViolationOnOtherValueIsRethrown() {
        DataIntegrityViolationException other = mysqlViolation(
                "Duplicate entry 'zzz' for key 'users.UK_other'", 1062,
                ConstraintViolationException.ConstraintKind.UNIQUE, "UK_other");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(other);

        assertThatThrownBy(() -> authService.signup(new SignupRequest("a@b.com", "password123", "닉네임")))
                .isSameAs(other);
    }

    @Test
    void nonUniqueIntegrityViolationIsRethrown() {
        DataIntegrityViolationException notNull = mysqlViolation(
                "Column 'nickname' cannot be null", 1048,
                ConstraintViolationException.ConstraintKind.OTHER, null);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(notNull);

        assertThatThrownBy(() -> authService.signup(new SignupRequest("a@b.com", "password123", "닉네임")))
                .isSameAs(notNull);
    }

    @Test
    void integrityViolationWithoutHibernateCauseIsRethrown() {
        DataIntegrityViolationException plain = new DataIntegrityViolationException("boom");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(plain);

        assertThatThrownBy(() -> authService.signup(new SignupRequest("a@b.com", "password123", "닉네임")))
                .isSameAs(plain);
    }

    @Test
    void nullPasswordIsFieldErrorNotNpe() {
        assertThatThrownBy(() -> authService.signup(new SignupRequest("a@b.com", null, "닉네임")))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("password"));
    }

    @Test
    void nullNicknameAndEmailAreFieldErrors() {
        assertThatThrownBy(() -> authService.signup(new SignupRequest("a@b.com", "password123", null)))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("nickname"));
        assertThatThrownBy(() -> authService.signup(new SignupRequest(null, "password123", "닉네임")))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("email"));
    }

    @Test
    void invisibleNicknameIsRejected() {
        assertThatThrownBy(() -> authService.signup(new SignupRequest("a@b.com", "password123", "\u3164\u3164\u3164")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("nickname");
                    assertThat(e.getMessage()).isEqualTo("닉네임에 사용할 수 없는 문자가 포함되어 있습니다.");
                });
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void passwordOver72BytesRejectedOnSignup() {
        String korean26 = "가".repeat(26); // UTF-8 78 bytes
        assertThatThrownBy(() -> authService.signup(new SignupRequest("a@b.com", korean26, "닉네임")))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("password"));
    }

    @Test
    void loginWithPasswordOver72BytesFailsBeforeMatches() {
        assertThatThrownBy(() -> authService.login(new LoginRequest("a@b.com", "가".repeat(25))))
                .isInstanceOf(SeatSwapException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
        verifyNoInteractions(userRepository);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void signupStoresNormalizedValues() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        UserResponse res = authService.signup(new SignupRequest("  Mixed@Case.COM ", "password123", "  닉네임  "));
        assertThat(res.email()).isEqualTo("mixed@case.com");
        assertThat(res.nickname()).isEqualTo("닉네임");
        verify(userRepository).existsByEmail("mixed@case.com");
    }

    @Test
    void signupBodyWithRoleAdminIsIgnoredAndCreatesUser() throws Exception {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        // Spring Boot 기본 ObjectMapper와 같은 설정(알 수 없는 속성 무시)으로 요청 본문을 역직렬화
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
        SignupRequest request = mapper.readValue(
                "{\"email\":\"a@b.com\",\"password\":\"password123\",\"nickname\":\"닉네임\",\"role\":\"ADMIN\"}",
                SignupRequest.class);

        UserResponse res = authService.signup(request);

        assertThat(res.role()).isEqualTo(com.seatswap.domain.UserRole.USER);
        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(com.seatswap.domain.UserRole.USER);
    }

    @Test
    void signupAlwaysCreatesUserRole() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        UserResponse res = authService.signup(new SignupRequest("a@b.com", "password123", "닉네임"));
        assertThat(res.role()).isEqualTo(com.seatswap.domain.UserRole.USER);
    }
}
