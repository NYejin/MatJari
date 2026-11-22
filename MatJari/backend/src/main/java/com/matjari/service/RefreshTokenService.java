package com.matjari.service;

import com.matjari.domain.RefreshToken;
import com.matjari.domain.RefreshToken.RevokeReason;
import com.matjari.domain.User;
import com.matjari.repository.RefreshTokenRepository;
import com.matjari.repository.UserRepository;
import com.matjari.security.AccountSuspensionChecker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 리프레시 토큰 발급·회전·재사용 탐지·로그아웃(V27). 토큰은 무작위 32바이트 base64url 불투명 문자열이고 DB 에는 SHA-256 hex 만 둔다.
 * 잠금 순서(RefreshToken Javadoc): users FOR UPDATE -> 토큰 PK FOR UPDATE -> 새 행 INSERT -> 옛 행 ROTATED. 해시 인덱스에는 잠금을 걸지 않는다.
 * 상태를 바꾸는 결과(재사용 탐지 시 계열 폐기)는 예외 없이 결과값으로 돌려 커밋한 뒤 호출자가 401 을 던진다.
 * 모든 트랜잭션은 READ_COMMITTED 로 연다(갭 잠금 방지, 탈퇴와 같다).
 *
 * <p>한계: 같은 토큰으로 거의 동시에 온 두 재발급은 하나만 성공하고 다른 하나는 401 을 받는다(유예 시간 안이면 계열은 유지, 그 뒤의 재사용은 계열 폐기).
 */
@Slf4j
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final int MAX_RAW_LENGTH = 200;
    private static final int CLEANUP_BATCH = 1000;
    private static final int CLEANUP_MAX_BATCHES = 20;
    private static final Duration CLEANUP_INTERVAL = Duration.ofHours(1);

    /** 재발급 결과. OK 일 때만 user·newToken 이 있다. */
    public record Rotation(Outcome outcome, User user, String newToken) {
        public enum Outcome { OK, INVALID, REUSED }

        static Rotation of(Outcome outcome) {
            return new Rotation(outcome, null, null);
        }
    }

    private final RefreshTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final AccountSuspensionChecker suspensionChecker;
    private final Clock clock;
    private final long validityMs;
    private final Duration reuseGrace;
    private final Duration maxSession;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<LocalDateTime> lastCleanup = new AtomicReference<>();

    public RefreshTokenService(RefreshTokenRepository tokenRepository, UserRepository userRepository,
                               AccountSuspensionChecker suspensionChecker, Clock clock,
                               PlatformTransactionManager transactionManager,
                               @Value("${jwt.refresh-token-validity-ms}") long validityMs,
                               @Value("${matjari.auth.refresh-reuse-grace-seconds:20}") long reuseGraceSeconds,
                               @Value("${matjari.auth.refresh-max-session-days:30}") long maxSessionDays) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.suspensionChecker = suspensionChecker;
        this.clock = clock;
        this.validityMs = validityMs;
        this.reuseGrace = Duration.ofSeconds(reuseGraceSeconds);
        this.maxSession = Duration.ofDays(maxSessionDays);
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public long validityMs() {
        return validityMs;
    }

    /**
     * 새 계열의 첫 토큰(로그인·소셜 exchange). users 행을 먼저 잠그고 탈퇴 여부를 확인한다. 탈퇴했으면 null.
     * 반환값은 쿠키에 담을 원문 토큰이다.
     */
    public String issueNewFamily(Long userId) {
        String raw = generateRaw();
        Boolean ok = tx.execute(status -> {
            Optional<User> user = userRepository.findByIdForUpdate(userId).filter(u -> !u.isWithdrawn());
            if (user.isEmpty()) {
                return false;
            }
            suspensionChecker.assertNotSuspended(userId);   // 로그인 검사와 발급 사이에 걸린 정지를 막는다(403)
            LocalDateTime now = LocalDateTime.now(clock);
            tokenRepository.saveAndFlush(RefreshToken.issue(userId, hash(raw), UUID.randomUUID().toString(), now,
                    expiryFor(now, now)));
            return true;
        });
        maybeCleanup();
        return Boolean.TRUE.equals(ok) ? raw : null;
    }

    /**
     * 회전. 토큰이 없음·만료·폐기·세션 최대 수명 초과·사용자 탈퇴면 INVALID. 이미 ROTATED 된 토큰이 다시 오면: 폐기 후 유예 시간(기본 20초) 안이고
     * 대체 토큰이 살아 있으면 INVALID 만(계열 유지), 그 밖에는 계열 전체를 REUSE 로 폐기하고(커밋) REUSED.
     * 정지 중이면 AccountSuspendedException(403, 변경 없이 롤백).
     */
    public Rotation rotate(String rawToken) {
        Rotation result = rotateInternal(rawToken);
        maybeCleanup();
        return result;
    }

    private Rotation rotateInternal(String rawToken) {
        if (!plausible(rawToken)) {
            return Rotation.of(Rotation.Outcome.INVALID);
        }
        Long id = tokenRepository.findIdByTokenHash(hash(rawToken)).orElse(null);       // 잠금 없음
        Long userId = id == null ? null : tokenRepository.findUserIdById(id).orElse(null);
        if (userId == null) {
            return Rotation.of(Rotation.Outcome.INVALID);
        }
        String newRaw = generateRaw();
        return tx.execute(status -> {
            User user = userRepository.findByIdForUpdate(userId).filter(u -> !u.isWithdrawn()).orElse(null);
            if (user == null) {
                return Rotation.of(Rotation.Outcome.INVALID);
            }
            RefreshToken token = tokenRepository.findByIdForUpdate(id).orElse(null);
            if (token == null) {
                return Rotation.of(Rotation.Outcome.INVALID);
            }
            LocalDateTime now = LocalDateTime.now(clock);
            if (token.isRevoked()) {
                if (token.getRevokeReason() == RevokeReason.ROTATED) {
                    if (withinReuseGrace(token, now)) {
                        // 거의 동시에 온 재발급(탭 두 개 등): 새 토큰이 아직 살아 있으면 계열을 지키고 이 요청만 거절한다
                        return Rotation.of(Rotation.Outcome.INVALID);
                    }
                    int revoked = tokenRepository.revokeAllActiveByFamilyId(token.getFamilyId(), RevokeReason.REUSE, now);
                    log.warn("Refresh token reuse detected (userId={}, familyRevoked={})", userId, revoked);
                    return Rotation.of(Rotation.Outcome.REUSED);
                }
                return Rotation.of(Rotation.Outcome.INVALID);
            }
            if (!token.isUsableAt(now) || !now.isBefore(token.getFamilyStartedAt().plus(maxSession))) {
                return Rotation.of(Rotation.Outcome.INVALID);   // 만료 또는 세션 최대 수명 초과
            }
            suspensionChecker.assertNotSuspended(userId);   // 정지면 403 (롤백, 토큰은 그대로)
            RefreshToken next = tokenRepository.saveAndFlush(RefreshToken.issue(userId, hash(newRaw), token.getFamilyId(),
                    token.getFamilyStartedAt(), expiryFor(now, token.getFamilyStartedAt())));
            token.rotateTo(next.getId(), now);
            tokenRepository.saveAndFlush(token);
            return new Rotation(Rotation.Outcome.OK, user, newRaw);
        });
    }

    /** 로그아웃: 이 토큰의 계열을 LOGOUT 으로 폐기한다. 토큰이 없거나 모르는 값이면 아무것도 하지 않는다(멱등). */
    public void logout(String rawToken) {
        if (!plausible(rawToken)) {
            return;
        }
        Long id = tokenRepository.findIdByTokenHash(hash(rawToken)).orElse(null);
        Long userId = id == null ? null : tokenRepository.findUserIdById(id).orElse(null);
        if (userId == null) {
            return;
        }
        tx.executeWithoutResult(status -> {
            // 재발급과 같은 순서(users -> 토큰)로 잡아 회전 중 새로 생기는 행을 놓치지 않는다
            userRepository.findByIdForUpdate(userId);
            tokenRepository.findByIdForUpdate(id).ifPresent(token ->
                    tokenRepository.revokeAllActiveByFamilyId(token.getFamilyId(), RevokeReason.LOGOUT, LocalDateTime.now(clock)));
        });
    }

    /** 만료 행 배치 삭제를 가끔(마지막 정리 1시간 뒤) 별도 짧은 트랜잭션으로 한다. 실패는 삼킨다. */
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
            log.warn("Refresh token cleanup failed: {}", e.getClass().getSimpleName());
        }
    }

    /** 새 토큰 만료 = min(now + 수명, 계열 시작 + 세션 최대 수명). 회전으로 세션이 무한히 늘지 않는다. */
    private LocalDateTime expiryFor(LocalDateTime now, LocalDateTime familyStartedAt) {
        LocalDateTime byValidity = now.plus(Duration.ofMillis(validityMs));
        LocalDateTime byMax = familyStartedAt.plus(maxSession);
        return byValidity.isBefore(byMax) ? byValidity : byMax;
    }

    /** 회전으로 폐기된 지 유예 시간 이내이고 대체 토큰이 아직 살아 있으면(폐기·만료 아님) 정상적인 동시 재발급으로 본다. */
    private boolean withinReuseGrace(RefreshToken rotated, LocalDateTime now) {
        if (rotated.getRevokedAt() == null || rotated.getReplacedById() == null
                || now.isAfter(rotated.getRevokedAt().plus(reuseGrace))) {
            return false;
        }
        return tokenRepository.findById(rotated.getReplacedById()).map(next -> next.isUsableAt(now)).orElse(false);
    }

    private static boolean plausible(String raw) {
        return raw != null && !raw.isBlank() && raw.length() <= MAX_RAW_LENGTH;
    }

    private String generateRaw() {
        byte[] b = new byte[TOKEN_BYTES];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
