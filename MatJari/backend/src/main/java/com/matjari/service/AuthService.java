package com.matjari.service;

import com.matjari.domain.User;
import com.matjari.dto.request.AuthInputNormalizer;
import com.matjari.dto.request.LoginRequest;
import com.matjari.dto.request.RefreshRequest;
import com.matjari.dto.request.SignupRequest;
import com.matjari.dto.response.TokenResponse;
import com.matjari.dto.response.UserResponse;
import com.matjari.exception.AccountSuspendedException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.MatJariException;
import com.matjari.repository.UserRepository;
import com.matjari.security.AccountSuspensionChecker;
import com.matjari.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 회원가입/로그인/JWT 발급·재발급 (FR-01) */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String LOGIN_FAIL_MESSAGE = "이메일 또는 비밀번호가 올바르지 않습니다.";
    private static final String DUPLICATE_EMAIL_MESSAGE = "이미 가입된 이메일입니다.";
    private static final String EMAIL_REQUIRED_MESSAGE = "이메일을 입력해주세요.";
    private static final String PASSWORD_REQUIRED_MESSAGE = "비밀번호를 입력해주세요.";
    private static final String NICKNAME_REQUIRED_MESSAGE = "닉네임을 입력해주세요.";
    private static final String NICKNAME_INVALID_CHAR_MESSAGE = "닉네임에 사용할 수 없는 문자가 포함되어 있습니다.";
    private static final String NICKNAME_LENGTH_MESSAGE = "닉네임은 2~20자로 입력해주세요.";
    private static final String PASSWORD_BYTES_MESSAGE = "비밀번호가 너무 깁니다. 영문 기준 64자 이하로 입력해주세요.";
    private static final int NICKNAME_MIN = 2;
    private static final int NICKNAME_MAX = 20;
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AccountSuspensionChecker suspensionChecker;
    private final EmailVerificationService emailVerificationService;

    @Transactional
    public UserResponse signup(SignupRequest request) {
        // DTO에서 이미 정규화되지만, 서비스가 다른 경로로 호출돼도 같은 규칙이 보장되도록 한 번 더 적용 (멱등)
        String email = AuthInputNormalizer.normalizeEmail(request.email());
        String nickname = AuthInputNormalizer.normalizeNickname(request.nickname());

        String password = request.password();

        // @Valid를 거치지 않는 호출 경로에서도 NPE 대신 필드 오류가 나가도록 null/blank를 직접 확인
        if (email == null || email.isEmpty()) {
            throw new FieldValidationException("email", EMAIL_REQUIRED_MESSAGE);
        }
        // @NotBlank와 같은 기준(Java trim)으로 공백만으로 된 비밀번호 거부 (정책 M3)
        if (password == null || password.trim().isEmpty()) {
            throw new FieldValidationException("password", PASSWORD_REQUIRED_MESSAGE);
        }
        // 닉네임 길이는 공백 제거 후 기준 (DB 컬럼 255 초과로 인한 500 방지도 겸함)
        if (nickname == null || nickname.isEmpty()) {
            throw new FieldValidationException("nickname", NICKNAME_REQUIRED_MESSAGE);
        }
        if (nickname.length() < NICKNAME_MIN || nickname.length() > NICKNAME_MAX) {
            throw new FieldValidationException("nickname", NICKNAME_LENGTH_MESSAGE);
        }
        if (AuthInputNormalizer.containsInvisibleChar(nickname)) {
            throw new FieldValidationException("nickname", NICKNAME_INVALID_CHAR_MESSAGE);
        }
        // BCrypt는 72바이트 이후를 무시하므로, 멀티바이트 문자로 64자 이내라도 72바이트를 넘으면 거부
        if (exceedsBcryptLimit(password)) {
            throw new FieldValidationException("password", PASSWORD_BYTES_MESSAGE);
        }
        // `.invalid` 는 예약된 가짜 도메인(소셜 가입의 자리표시 이메일이 쓴다) — 일반 가입으로 선점하지 못하게 한다
        if (email.endsWith(".invalid")) {
            throw new FieldValidationException("email", "올바른 이메일 형식이 아닙니다.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new FieldValidationException("email", DUPLICATE_EMAIL_MESSAGE);
        }

        // 이메일 인증번호(꺼져 있으면 건너뜀). 마지막 검증 단계로 두어 다른 입력 오류로 시도 횟수를 쓰지 않게 한다.
        // 성공하면 인증 행을 지우며, 아래 저장이 실패해 롤백되면 되살아난다
        emailVerificationService.verifyAndConsume(email, request.emailCode());

        User user = User.create(email, passwordEncoder.encode(password), nickname);
        try {
            // flush까지 즉시 수행해 unique 제약 위반을 이 메서드 안에서 잡는다 (동시 가입 레이스)
            return UserResponse.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            // 주의: 여기서 예외를 삼키고 값을 반환하도록 바꾸면 안 된다. saveAndFlush 실패 시 트랜잭션이
            // 이미 rollback-only로 표시되어 있어 커밋 시점에 UnexpectedRollbackException(→500)이 난다.
            // 반드시 예외를 던져 롤백시킬 것.
            if (isDuplicateEmailViolation(e, email)) {
                log.warn("Signup race: duplicate email detected at insert (cause: {})",
                        e.getMostSpecificCause().getMessage());
                throw new FieldValidationException("email", DUPLICATE_EMAIL_MESSAGE);
            }
            log.warn("Signup insert failed with non-email integrity violation (cause: {})",
                    e.getMostSpecificCause().getMessage());
            throw e;
        }
    }

    public TokenResponse login(LoginRequest request) {
        String password = request.password();
        // BCrypt 72바이트 초과 비밀번호는 가입 단계에서 거부되므로 일치할 수 없다.
        // 앞 72바이트만 비교되는 truncation 매칭을 막기 위해 matches 호출 전에 실패 처리.
        if (password == null || exceedsBcryptLimit(password)) {
            throw new MatJariException(LOGIN_FAIL_MESSAGE);
        }

        User user = userRepository.findByEmail(AuthInputNormalizer.normalizeEmail(request.email()))
                .orElseThrow(() -> new MatJariException(LOGIN_FAIL_MESSAGE));

        if (user.isWithdrawn() || !passwordEncoder.matches(password, user.getPassword())) {
            throw new MatJariException(LOGIN_FAIL_MESSAGE);
        }
        // 비밀번호가 맞은 뒤에만 정지 여부를 알려 준다(정지 여부로 계정 존재를 추측하지 못하게). 403 ACCOUNT_SUSPENDED
        try {
            suspensionChecker.assertNotSuspended(user.getId());
        } catch (AccountSuspendedException e) {
            // 비밀번호까지 맞았으므로 정지 중에도 회원탈퇴만 할 수 있는 단기 토큰을 함께 준다(그 외 API 에는 쓸 수 없다)
            throw e.withWithdrawToken(jwtTokenProvider.generateWithdrawToken(user.getId(), user.getEmail()));
        }

        return issueTokens(user);
    }

    public TokenResponse refresh(RefreshRequest request) {
        String token = request.refreshToken();

        if (!jwtTokenProvider.validateToken(token) || !jwtTokenProvider.isRefreshToken(token)) {
            throw new MatJariException("유효하지 않거나 만료된 refresh token 입니다.");
        }

        Long userId = jwtTokenProvider.getUserId(token);
        User user = userRepository.findById(userId)
                .filter(u -> !u.isWithdrawn())
                .orElseThrow(() -> new MatJariException("사용자를 찾을 수 없습니다."));
        suspensionChecker.assertNotSuspended(user.getId());

        return issueTokens(user);
    }

    private static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }

    /**
     * DataIntegrityViolationException이 "이메일 unique 제약 위반"인지 판별한다.
     * - cause가 Hibernate ConstraintViolationException이어야 하고
     * - unique 위반(ConstraintKind.UNIQUE 또는 MySQL 1062 Duplicate entry)이어야 하며
     * - 제약명에 'email'이 들어 있거나(이름 붙은 제약) MySQL 메시지의 중복 값이 가입 이메일과 같아야 한다.
     *   (현재 users.email unique 제약명은 Hibernate 자동 생성 UK... 이라 이름만으로는 판별 불가)
     * 그 외(NOT NULL, 다른 컬럼 등)는 이메일 중복으로 위장하지 않고 호출 측에서 rethrow한다.
     */
    private static boolean isDuplicateEmailViolation(DataIntegrityViolationException e, String email) {
        Throwable cause = e.getCause();
        while (cause != null && !(cause instanceof ConstraintViolationException)) {
            cause = cause.getCause();
        }
        if (!(cause instanceof ConstraintViolationException cve)) {
            return false;
        }
        boolean unique = cve.getKind() == ConstraintViolationException.ConstraintKind.UNIQUE
                || cve.getErrorCode() == MYSQL_DUPLICATE_ENTRY;
        if (!unique || !"23000".equals(cve.getSQLState())) {
            return false;
        }
        String constraintName = cve.getConstraintName();
        if (constraintName != null && constraintName.toLowerCase(Locale.ROOT).contains("email")) {
            return true;
        }
        String sqlMessage = cve.getSQLException() != null ? cve.getSQLException().getMessage() : null;
        return sqlMessage != null && sqlMessage.contains("Duplicate entry '" + email + "'");
    }

    /** JWT 발급. 소셜 로그인 코드 교환(SocialAuthService)도 같은 발급 경로를 쓴다. */
    public TokenResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getEmail());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId(), user.getEmail());
        return TokenResponse.of(accessToken, refreshToken);
    }
}
