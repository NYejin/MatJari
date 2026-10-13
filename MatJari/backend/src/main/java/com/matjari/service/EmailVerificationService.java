package com.matjari.service;

import com.matjari.dto.request.AuthInputNormalizer;
import com.matjari.dto.response.EmailCodeResponse;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.MatJariException;
import com.matjari.repository.UserRepository;
import com.matjari.service.mail.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 일반 회원가입 전 이메일 인증번호(V19 email_verification).
 * - 발송: 이메일로 6자리 숫자 코드를 보내고 HMAC 해시만 저장한다(이메일당 행 1개, 다시 보내면 덮어씀).
 *   재발송 대기·이메일/IP/전체 속도 제한을 둔다. 이미 가입된 이메일은 400 email(가입 화면의 중복 안내와 같은 응답).
 * - 검증: 가입 트랜잭션 안에서 호출한다. 먼저 시도 횟수를 별도 트랜잭션으로 올려(가입이 롤백돼도 남는다) 병렬 추측을 막고,
 *   맞으면 같은 가입 트랜잭션에서 행을 지운다(1회용). 가입이 이후 단계에서 실패하면 행은 되살아난다.
 */
@Slf4j
@Service
public class EmailVerificationService {

    private static final Pattern CODE_PATTERN = Pattern.compile("\\d{6}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long TEN_MIN_MILLIS = 10 * 60_000L;
    private static final long HOUR_MILLIS = 60 * 60_000L;
    private static final String SUBJECT = "[맞자리] 이메일 인증번호";

    private final EmailVerificationProperties properties;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;
    private final UserRepository userRepository;
    private final EmailSender emailSender;
    private final Clock clock;

    private final Limiter ipLimiter = new Limiter();
    private final Limiter emailLimiter = new Limiter();
    private final Limiter globalLimiter = new Limiter();

    public EmailVerificationService(EmailVerificationProperties properties, JdbcTemplate jdbc,
                                    PlatformTransactionManager txManager, UserRepository userRepository,
                                    EmailSender emailSender, Clock clock) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.userRepository = userRepository;
        this.emailSender = emailSender;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** 인증번호를 만들어 메일로 보낸다. ip 는 속도 제한 키다(request.getRemoteAddr()). */
    public EmailCodeResponse sendCode(String rawEmail, String ip) {
        String email = AuthInputNormalizer.normalizeEmail(rawEmail);
        if (email == null || email.isEmpty()) {
            throw new FieldValidationException("email", "이메일을 입력해주세요.");
        }
        if (email.endsWith(".invalid")) {
            throw new FieldValidationException("email", "올바른 이메일 형식이 아닙니다.");
        }
        if (!properties.isEnabled()) {
            throw new MatJariException("이메일 인증을 사용하지 않는 환경입니다.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new FieldValidationException("email", "이미 가입된 이메일입니다.");
        }

        long now = clock.millis();
        LocalDateTime nowTime = LocalDateTime.now(clock);

        // 대기시간: 마지막 발송 뒤 N초 안에는 다시 보내지 않는다(속도 제한 횟수를 쓰기 전에 확인)
        List<LocalDateTime> lastSent = jdbc.query("SELECT sent_at FROM email_verification WHERE email = ?",
                (rs, i) -> rs.getTimestamp(1).toLocalDateTime(), email);
        if (!lastSent.isEmpty()) {
            long wait = Duration.between(nowTime, lastSent.get(0).plusSeconds(properties.getResendCooldownSeconds())).toMillis();
            if (wait > 0) {
                throw new EmailCodeRateLimitedException("EMAIL_CODE_COOLDOWN",
                        "인증번호를 방금 보냈어요. 잠시 후 다시 요청해주세요.", (wait + 999) / 1000);
            }
        }
        ipLimiter.acquire("ip:" + ip, properties.getIpPer10Min(), TEN_MIN_MILLIS, now);
        emailLimiter.acquire("email:" + email, properties.getEmailPer10Min(), TEN_MIN_MILLIS, now);
        globalLimiter.acquire("all", properties.getGlobalPerHour(), HOUR_MILLIS, now);

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        LocalDateTime expiresAt = nowTime.plusSeconds(properties.getCodeTtlSeconds());
        // 오래 지난 만료 행은 보낼 때 가끔 쓸어 낸다(스케줄러 없음)
        jdbc.update("DELETE FROM email_verification WHERE expires_at < ?", nowTime.minusHours(1));
        jdbc.update("INSERT INTO email_verification (email, code_hash, attempts, sent_at, expires_at) VALUES (?, ?, 0, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE code_hash = VALUES(code_hash), attempts = 0, sent_at = VALUES(sent_at), expires_at = VALUES(expires_at)",
                email, hash(email, code), nowTime, expiresAt);
        try {
            emailSender.send(email, SUBJECT, mailBody(code));
        } catch (EmailSender.EmailSendException e) {
            // 보내지 못했으면 대기시간에 걸리지 않도록 행을 지운다
            jdbc.update("DELETE FROM email_verification WHERE email = ?", email);
            log.warn("Email verification send failed: {}", e.getMessage());
            throw new EmailCodeRateLimitedException("EMAIL_SEND_FAILED",
                    "인증번호 메일을 보내지 못했어요. 잠시 후 다시 시도해주세요.", 0);
        }
        return new EmailCodeResponse(properties.getCodeTtlSeconds(), properties.getResendCooldownSeconds());
    }

    /**
     * 가입 트랜잭션 안에서 호출한다. 인증이 꺼져 있으면 아무것도 하지 않는다.
     * 실패하면 400 emailCode 필드 오류. 성공하면 행을 지운다(호출 트랜잭션이 롤백되면 되살아난다).
     */
    public void verifyAndConsume(String rawEmail, String code) {
        if (!properties.isEnabled()) {
            return;
        }
        String email = AuthInputNormalizer.normalizeEmail(rawEmail);
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.isEmpty()) {
            throw new FieldValidationException("emailCode", "이메일로 받은 인증번호를 입력해주세요.");
        }
        if (!CODE_PATTERN.matcher(trimmed).matches()) {
            throw new FieldValidationException("emailCode", "인증번호는 숫자 6자리예요.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // 시도 횟수를 먼저, 호출 트랜잭션과 별개로 올린다(남은 횟수가 없거나 만료면 0행)
        Integer counted = requiresNew.execute(status -> jdbc.update(
                "UPDATE email_verification SET attempts = attempts + 1 WHERE email = ? AND expires_at > ? AND attempts < ?",
                email, now, properties.getMaxAttempts()));
        if (counted == null || counted == 0) {
            throw new FieldValidationException("emailCode",
                    "인증번호가 만료되었거나 입력 횟수를 넘었어요. 인증번호를 다시 받아 주세요.");
        }
        int consumed = jdbc.update("DELETE FROM email_verification WHERE email = ? AND code_hash = ? AND expires_at > ?",
                email, hash(email, trimmed), now);
        if (consumed == 0) {
            throw new FieldValidationException("emailCode", "인증번호가 일치하지 않아요.");
        }
    }

    private String hash(String email, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((email + "\n" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String mailBody(String code) {
        return "맞자리 회원가입 인증번호: " + code + "\n\n"
                + (properties.getCodeTtlSeconds() / 60) + "분 안에 가입 화면에 입력해주세요.\n"
                + "본인이 요청하지 않았다면 이 메일을 무시하세요.";
    }

    /** 429/503 응답 예외. 응답 {code, message, retryAfterSeconds}(retryAfterSeconds > 0 이면 Retry-After 헤더)는 GlobalExceptionHandler 가 만든다. */
    public static class EmailCodeRateLimitedException extends MatJariException {
        private final String code;
        private final long retryAfterSeconds;

        public EmailCodeRateLimitedException(String code, String message, long retryAfterSeconds) {
            super(message);
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getCode() {
            return code;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** 메모리 슬라이딩 윈도(서버 한 대 기준, 재시작하면 초기화). 한도를 넘은 시도는 기록하지 않는다. */
    static final class Limiter {
        private static final class Window {
            final ArrayDeque<Long> hits = new ArrayDeque<>();
            long lastMillis;
        }

        private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

        void acquire(String key, int limit, long windowMillis, long now) {
            if (windows.size() > 5_000) {
                windows.entrySet().removeIf(e -> now - e.getValue().lastMillis > 2 * windowMillis);
            }
            Window w = windows.computeIfAbsent(key, k -> new Window());
            synchronized (w) {
                while (!w.hits.isEmpty() && now - w.hits.peekFirst() >= windowMillis) {
                    w.hits.pollFirst();
                }
                if (w.hits.size() >= limit) {
                    long wait = w.hits.peekFirst() + windowMillis - now;
                    throw new EmailCodeRateLimitedException("EMAIL_CODE_RATE_LIMITED",
                            "인증번호 요청이 너무 많아요. 잠시 후 다시 시도해주세요.", Math.max(1, (wait + 999) / 1000));
                }
                w.hits.addLast(now);
                w.lastMillis = now;
            }
        }
    }
}
