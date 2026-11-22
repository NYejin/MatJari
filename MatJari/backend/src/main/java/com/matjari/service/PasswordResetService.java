package com.matjari.service;

import com.matjari.domain.PasswordResetToken;
import com.matjari.domain.RefreshToken;
import com.matjari.domain.RefreshToken.RevokeReason;
import com.matjari.domain.User;
import com.matjari.dto.request.AuthInputNormalizer;
import com.matjari.dto.response.TokenResponse;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.MatJariException;
import com.matjari.repository.PasswordResetTokenRepository;
import com.matjari.repository.RefreshTokenRepository;
import com.matjari.repository.UserRepository;
import com.matjari.security.AuthRateLimiter;
import com.matjari.security.JwtTokenProvider;
import com.matjari.service.mail.EmailSender;
import com.matjari.service.mail.EmailSenderConfig;
import com.matjari.service.social.SocialProperties;
import com.matjari.websocket.WebSocketSessionRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 비밀번호 재설정(메일 링크)과 로그인 상태의 비밀번호 변경(V28 password_reset_token). 잠금 순서는 {@link PasswordResetToken} Javadoc 을 따른다.
 *
 * <ul>
 *   <li><b>요청</b>: 존재·미탈퇴·비밀번호 로그인 가능 계정에만 링크를 만들고(users FOR UPDATE -&gt; 이전 링크 무효화 -&gt; INSERT) 메일은 커밋 뒤
 *       별도 스레드로 보낸다(SMTP 지연이 응답 시간으로 새지 않게). 그 외 계정은 아무것도 하지 않는다. 정지 계정에도 보낸다.
 *       호출한 쪽(컨트롤러)이 항상 204 를 준다. 발송 실패는 삼키고 로그만 남긴다(토큰·이메일은 남기지 않는다).</li>
 *   <li><b>확정</b>: 없음·만료·사용됨·무효·탈퇴·소셜 전용은 모두 {@link InvalidResetTokenException}(400 INVALID_RESET_TOKEN, 구분하지 않는다).
 *       성공하면 비밀번호 변경 + 링크 사용 처리 + 그 사용자의 리프레시 토큰 전부 폐기(PASSWORD_RESET)를 한 트랜잭션에서 한다.</li>
 *   <li><b>변경</b>: users FOR UPDATE 후 현재 비밀번호를 확인하고, 이미 보낸 재설정 링크를 무효화하며, 현재 요청의 리프레시 쿠키 계열을 뺀 나머지 계열을 폐기한다.
 *       쿠키(mj_rt)는 Path=/api/auth 라서 {@code /api/users/me/...} 요청에는 브라우저가 보내지 않는다. 그래서 쿠키를 읽을 수 있는
 *       {@code PATCH /api/auth/password} 를 함께 둔다. 쿠키를 알 수 없으면(null) 모든 계열을 폐기한다(현재 기기도 다음 재발급 때 다시 로그인).</li>
 * </ul>
 * 모든 트랜잭션은 READ_COMMITTED(갭 잠금 방지, 리프레시 토큰·탈퇴와 같다). BCrypt 인코딩은 잠금 밖에서 한다.
 *
 * <p>메일을 보낼 수 없는 설정(log 모드 + 비루프백 백엔드)이면 요청은 계정 조회 전에 모두 503 PASSWORD_RESET_UNAVAILABLE 이다.
 * 비밀번호가 바뀌면 users.credentials_changed_at 을 기록해 그 전에 발급된 액세스 토큰을 필터·STOMP 가 거부하고 기존 STOMP 연결은 커밋 뒤 끊는다.
 *
 * <p>한계: 요청 응답 시간은 계정이 있을 때(DB 쓰기 몇 번)가 없을 때보다 수 ms 길다. 속도 제한은 메모리·단일 인스턴스 기준이다.
 * 같은 초에 발급된 옛 액세스 토큰은 변경 직후 새 토큰과 구분할 수 없어 허용한다(최대 1초 창).
 */
@Slf4j
@Service
public class PasswordResetService {

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_RAW_LENGTH = 200;
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final int CLEANUP_BATCH = 1000;
    private static final int CLEANUP_MAX_BATCHES = 20;
    private static final Duration CLEANUP_INTERVAL = Duration.ofHours(1);
    private static final Pattern IP_FORMAT = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final String SUBJECT = "[맞자리] 비밀번호 재설정";
    private static final String PASSWORD_BYTES_MESSAGE = "비밀번호가 너무 깁니다. 영문 기준 64자 이하로 입력해주세요.";

    /** 400 INVALID_RESET_TOKEN. 링크가 없거나 만료·사용·무효라는 사실을 구분해 알리지 않는다. */
    public static class InvalidResetTokenException extends MatJariException {
        public InvalidResetTokenException() {
            super("재설정 링크가 올바르지 않거나 만료되었어요. 비밀번호 재설정을 다시 요청해주세요.");
        }
    }

    /** 503 PASSWORD_RESET_UNAVAILABLE. 메일을 실제로 보낼 수 없는 설정(log 모드 + 비루프백 백엔드)이라 모든 요청을 똑같이 거절한다. */
    public static class PasswordResetUnavailableException extends MatJariException {
        public PasswordResetUnavailableException() {
            super("지금은 비밀번호 재설정 메일을 보낼 수 없어요. 잠시 후 다시 시도하거나 문의해 주세요.");
        }
    }

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailOwnershipRecorder emailOwnershipRecorder;
    private final EmailSender emailSender;
    private final SocialProperties socialProperties;
    private final AuthRateLimiter rateLimiter;
    private final JwtTokenProvider jwtTokenProvider;
    private final WebSocketSessionRegistry sessionRegistry;
    private final Duration reuseGrace;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final Duration ttl;
    private final boolean linkLoggingForbidden;
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<LocalDateTime> lastCleanup = new AtomicReference<>();
    private final ThreadPoolExecutor mailExecutor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(200), r -> {
                Thread t = new Thread(r, "password-reset-mail");
                t.setDaemon(true);
                return t;
            });

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository tokenRepository,
                                RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
                                EmailSender emailSender, SocialProperties socialProperties, AuthRateLimiter rateLimiter,
                                JwtTokenProvider jwtTokenProvider, WebSocketSessionRegistry sessionRegistry,
                                Clock clock, PlatformTransactionManager transactionManager,
                                @Value("${matjari.auth.refresh-reuse-grace-seconds:20}") long reuseGraceSeconds,
                                @Value("${matjari.password-reset.ttl-minutes:30}") long ttlMinutes,
                                @Value("${matjari.mail.mode:log}") String mailMode,
                                EmailOwnershipRecorder emailOwnershipRecorder) {
        this.emailOwnershipRecorder = emailOwnershipRecorder;
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.socialProperties = socialProperties;
        this.rateLimiter = rateLimiter;
        this.jwtTokenProvider = jwtTokenProvider;
        this.sessionRegistry = sessionRegistry;
        this.reuseGrace = Duration.ofSeconds(reuseGraceSeconds);
        this.clock = clock;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.linkLoggingForbidden = "log".equalsIgnoreCase(mailMode == null ? "log" : mailMode.trim())
                && !EmailSenderConfig.isLoopback(socialProperties.getBackendBaseUrl());
    }

    @PreDestroy
    void shutdown() {
        mailExecutor.shutdown();
    }

    // ------------------------------------------------------------------ 요청

    /** 재설정 링크 요청. 항상 조용히 끝난다(가입 여부를 드러내지 않는다). 속도 제한은 이 안에서 센다(초과 429). */
    public void requestReset(String rawEmail, String ip) {
        if (linkLoggingForbidden) {
            // 계정 조회 전에 모든 요청을 똑같이 거절한다(가입 여부를 드러내지 않고, 보낼 수 없는데 성공처럼 보이지 않게)
            throw new PasswordResetUnavailableException();
        }
        String email = AuthInputNormalizer.normalizeEmail(rawEmail);
        if (email == null || email.isEmpty()) {
            return;
        }
        rateLimiter.acquirePasswordResetRequest(ip, email);

        User found = userRepository.findByEmail(email).orElse(null);   // 잠금 없음
        if (found != null && eligible(found)) {
            String raw = generateRaw();
            String hash = RefreshTokenService.hash(raw);
            Long userId = found.getId();
            Boolean issued = tx.execute(status -> {
                User user = userRepository.findByIdForUpdate(userId).orElse(null);   // 탈퇴·소셜 전환을 잠금 안에서 다시 확인
                if (user == null || !eligible(user)) {
                    return false;
                }
                LocalDateTime now = LocalDateTime.now(clock);
                tokenRepository.invalidateAllActiveByUserId(userId, now);
                tokenRepository.saveAndFlush(PasswordResetToken.issue(userId, hash,
                        now.plus(ttl).truncatedTo(ChronoUnit.MICROS), safeIp(ip)));
                return true;
            });
            if (Boolean.TRUE.equals(issued)) {
                sendAfterCommit(email, raw);   // tx.execute 가 반환했으므로 커밋된 뒤다
            }
        }
        maybeCleanup();
    }

    private static boolean eligible(User u) {
        return !u.isWithdrawn() && u.isPasswordLoginEnabled();
    }

    private void sendAfterCommit(String email, String rawToken) {
        String body = mailBody(rawToken);
        try {
            mailExecutor.execute(() -> {
                try {
                    emailSender.send(email, SUBJECT, body);
                } catch (RuntimeException e) {
                    // 수신 주소·토큰은 남기지 않는다
                    log.warn("Password reset mail send failed: {}", e.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Password reset mail dropped: send queue is full");
        }
    }

    private String mailBody(String rawToken) {
        String link = socialProperties.getFrontendBaseUrl() + "/reset-password#token=" + rawToken;
        return "맞자리 비밀번호 재설정 링크입니다.\n\n"
                + link + "\n\n"
                + ttl.toMinutes() + "분 안에 링크를 열어 새 비밀번호를 정해주세요. 링크는 한 번만 쓸 수 있습니다.\n"
                + "본인이 요청하지 않았다면 이 메일을 무시하세요.";
    }

    // ------------------------------------------------------------------ 확정

    /**
     * 링크로 새 비밀번호를 저장한다. 성공하면 그 계정의 이메일(로그인 실패 제한 카운터 초기화용)을 돌려준다.
     * 호출 전에 컨트롤러가 IP 속도 제한을 센다.
     *
     * @throws InvalidResetTokenException 없음·만료·사용됨·무효·탈퇴·소셜 전용
     */
    public String confirmReset(String rawToken, String newPassword) {
        if (newPassword == null || exceedsBcryptLimit(newPassword)) {
            throw new FieldValidationException("newPassword", PASSWORD_BYTES_MESSAGE);
        }
        String encoded = passwordEncoder.encode(newPassword);   // 잠금 밖. 링크가 틀려도 같은 비용을 써 응답 시간 차이를 줄인다
        if (!plausible(rawToken)) {
            throw new InvalidResetTokenException();
        }
        Long id = tokenRepository.findIdByTokenHash(RefreshTokenService.hash(rawToken)).orElse(null);   // 잠금 없음
        Long userId = id == null ? null : tokenRepository.findUserIdById(id).orElse(null);              // 잠금 없음
        if (userId == null) {
            throw new InvalidResetTokenException();
        }
        String email = tx.execute(status -> {
            User user = userRepository.findByIdForUpdate(userId).orElse(null);   // users 먼저
            if (user == null || !eligible(user)) {
                return null;
            }
            PasswordResetToken token = tokenRepository.findByIdForUpdate(id).orElse(null);   // 그다음 토큰 PK
            LocalDateTime now = LocalDateTime.now(clock);
            if (token == null || !userId.equals(token.getUserId()) || !token.isUsableAt(now)) {
                return null;
            }
            user.changePassword(encoded, now);
            userRepository.saveAndFlush(user);
            token.markUsed(now);
            tokenRepository.saveAndFlush(token);
            refreshTokenRepository.revokeAllActiveByUserId(userId, RevokeReason.PASSWORD_RESET, now);
            closeSocketsAfterCommit(userId);
            emailOwnershipRecorder.markVerified(userId);   // 재설정 링크를 메일함에서 열었으므로 이메일 소유가 확인된 것이다(users 잠금 안)
            return user.getEmail();
        });
        if (email == null) {
            throw new InvalidResetTokenException();
        }
        return email;
    }

    // ------------------------------------------------------------------ 변경(로그인 상태)

    /**
     * 현재 비밀번호를 확인하고 새 비밀번호로 바꾼다. 현재 요청의 리프레시 쿠키 계열(currentRefreshToken, 없으면 null)을 뺀 나머지 계열을 폐기한다.
     * 로그인 실패 제한을 함께 쓴다: 계정(email)이 잠겨 있으면 429 LOGIN_RATE_LIMITED, 현재 비밀번호가 틀리면 실패로 센다, 바꾸면 초기화.
     *
     * @throws ConflictException        409 PASSWORD_LOGIN_DISABLED (소셜 전용 계정)
     * @throws FieldValidationException 400 currentPassword(틀림) / newPassword(현재와 같음·너무 김)
     */
    public TokenResponse changePassword(Long userId, String email, String ip, String currentPassword, String newPassword,
                                        String currentRefreshToken) {
        rateLimiter.checkLogin(email, ip);
        if (exceedsBcryptLimit(newPassword)) {
            throw new FieldValidationException("newPassword", PASSWORD_BYTES_MESSAGE);
        }
        String encoded = passwordEncoder.encode(newPassword);   // 잠금 밖
        try {
            tx.executeWithoutResult(status -> doChange(userId, currentPassword, newPassword, encoded, currentRefreshToken));
        } catch (FieldValidationException e) {
            if ("currentPassword".equals(e.getField())) {
                rateLimiter.recordLoginFailure(email, ip);
            }
            throw e;
        }
        rateLimiter.recordLoginSuccess(email);
        // 변경 시각(초)과 같거나 늦은 iat 로 새 액세스 토큰을 준다(옛 토큰은 필터가 거부한다)
        return TokenResponse.of(jwtTokenProvider.generateAccessToken(userId, email));
    }

    private void doChange(Long userId, String currentPassword, String newPassword, String encoded, String refreshRaw) {
        User user = userRepository.findByIdForUpdate(userId).filter(u -> !u.isWithdrawn())
                .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
        if (!user.isPasswordLoginEnabled()) {
            throw new ConflictException("비밀번호로 로그인하는 계정이 아니라 비밀번호를 바꿀 수 없어요.",
                    Map.of("code", "PASSWORD_LOGIN_DISABLED"));
        }
        // 72바이트를 넘는 값은 가입에서 거부되므로 일치할 수 없다(앞부분만 비교되는 일을 막는다)
        if (exceedsBcryptLimit(currentPassword) || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new FieldValidationException("currentPassword", "현재 비밀번호가 올바르지 않아요.");
        }
        if (currentPassword.equals(newPassword)) {
            throw new FieldValidationException("newPassword", "현재 비밀번호와 다른 비밀번호를 입력해주세요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        user.changePassword(encoded, now);
        userRepository.saveAndFlush(user);
        tokenRepository.invalidateAllActiveByUserId(userId, now);   // 이미 메일로 나간 재설정 링크를 못 쓰게 한다
        String keepFamily = familyOf(userId, refreshRaw);
        if (keepFamily == null) {
            refreshTokenRepository.revokeAllActiveByUserId(userId, RevokeReason.PASSWORD_RESET, now);
        } else {
            refreshTokenRepository.revokeAllActiveByUserIdExceptFamily(userId, keepFamily, RevokeReason.PASSWORD_RESET, now);
        }
        closeSocketsAfterCommit(userId);
    }

    /** 커밋 뒤 그 사용자의 기존 STOMP 연결을 끊는다(탈퇴와 같은 방식, 실패는 삼킨다 — 토큰은 이미 거부된다). */
    private void closeSocketsAfterCommit(Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    sessionRegistry.closeAllOf(userId);
                } catch (RuntimeException e) {
                    log.warn("Closing STOMP sessions after password change failed: {}", e.getClass().getSimpleName());
                }
            }
        });
    }

    /** 쿠키 토큰이 이 사용자의 살아 있는 계열이면 계열 id, 아니면 null. users 잠금 안에서 잠금 없이 읽는다. */
    private String familyOf(Long userId, String refreshRaw) {
        if (!plausible(refreshRaw)) {
            return null;
        }
        Long tokenId = refreshTokenRepository.findIdByTokenHash(RefreshTokenService.hash(refreshRaw)).orElse(null);
        if (tokenId == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        return refreshTokenRepository.findById(tokenId)
                .filter(t -> userId.equals(t.getUserId()) && (t.isUsableAt(now) || rotatedWithinGrace(t, now)))
                .map(RefreshToken::getFamilyId).orElse(null);
    }

    /**
     * 방금 회전돼 ROTATED 가 된 쿠키 토큰(재발급이 막 끝나 브라우저가 새 쿠키를 아직 못 받은 경우)이 재사용 유예 안이고 대체 토큰이 살아 있으면
     * 같은 계열로 본다(현재 기기 로그아웃 방지, RefreshTokenService.withinReuseGrace 와 같은 기준).
     */
    private boolean rotatedWithinGrace(RefreshToken t, LocalDateTime now) {
        if (t.getRevokeReason() != RevokeReason.ROTATED || t.getRevokedAt() == null || t.getReplacedById() == null
                || now.isAfter(t.getRevokedAt().plus(reuseGrace))) {
            return false;
        }
        return refreshTokenRepository.findById(t.getReplacedById()).map(next -> next.isUsableAt(now)).orElse(false);
    }

    // ------------------------------------------------------------------ 정리·도우미

    /** 만료 행 배치 삭제를 가끔(마지막 정리 1시간 뒤) users 잠금 밖의 별도 짧은 트랜잭션으로 한다. 실패는 삼킨다. */
    void maybeCleanup() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime last = lastCleanup.get();
        if (last != null && now.isBefore(last.plus(CLEANUP_INTERVAL))) {
            return;
        }
        if (!lastCleanup.compareAndSet(last, now)) {
            return;
        }
        try {
            for (int i = 0; i < CLEANUP_MAX_BATCHES; i++) {
                Integer n = tx.execute(status -> tokenRepository.deleteExpiredBatch(now, CLEANUP_BATCH));
                if (n == null || n < CLEANUP_BATCH) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Password reset token cleanup failed: {}", e.getClass().getSimpleName());
        }
    }

    private static boolean plausible(String raw) {
        return raw != null && !raw.isBlank() && raw.length() <= MAX_RAW_LENGTH;
    }

    private static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }

    /** DB CHECK(2~45자 [0-9A-Fa-f:.])에 맞지 않는 값은 NULL 로 둔다. */
    private static String safeIp(String ip) {
        return ip != null && IP_FORMAT.matcher(ip).matches() ? ip.toLowerCase(Locale.ROOT) : null;
    }

    private String generateRaw() {
        byte[] b = new byte[TOKEN_BYTES];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
