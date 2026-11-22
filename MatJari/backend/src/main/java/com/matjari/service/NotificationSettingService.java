package com.matjari.service;

import com.matjari.domain.User;
import com.matjari.domain.UserNotificationSetting;
import com.matjari.dto.NotificationSettingDtos;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.repository.UserNotificationSettingRepository;
import com.matjari.repository.UserRepository;
import com.matjari.service.mail.MailDeliveryPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

/**
 * 알림 설정(메일 알림 켜기/끄기, V29)과 계정 이메일 소유 확인. 행이 없으면 메일은 켜짐·이메일은 미확인으로 본다.
 * 설정 저장은 PK 한 행 upsert 뿐이고 users FOR UPDATE 는 잡지 않는다.
 * emailAvailable 은 메일을 받을 수 있는 이메일인지다: 소셜 가입의 자리표시 이메일(.invalid 예약 도메인)은 받을 수 없다.
 * emailVerified 는 이메일 소유가 확인됐는지다(가입 인증번호·소셜 검증 이메일·비밀번호 재설정·아래 확인 API). 알림 메일은 켜짐이고 확인된 사용자에게만 나간다.
 *
 * <p>확인 API: {@link #sendVerificationCode} 는 잠금 없이 읽기만 하고 인증번호를 보낸다. {@link #confirmVerification} 은 users 행을 FOR UPDATE 로 먼저 잡고
 * 코드를 검증한 뒤 email_verified_at 을 기록한다(가입·비밀번호 재설정과 같은 users 잠금 규칙).
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private final UserRepository userRepository;
    private final UserNotificationSettingRepository settingRepository;
    private final Clock clock;
    private final EmailVerificationService emailVerificationService;
    private final EmailOwnershipRecorder emailOwnershipRecorder;
    private final MailDeliveryPolicy mailDeliveryPolicy;

    @Transactional(readOnly = true)
    public NotificationSettingDtos.Response get(Long userId) {
        User user = loadUser(userId);
        UserNotificationSetting setting = settingRepository.findById(userId).orElse(null);
        return response(user, setting == null || setting.isEmailEnabled(), setting != null && setting.getEmailVerifiedAt() != null);
    }

    @Transactional
    public NotificationSettingDtos.Response update(Long userId, boolean emailEnabled) {
        User user = loadUser(userId);
        settingRepository.upsertEmailEnabled(userId, emailEnabled, LocalDateTime.now(clock));
        // upsert 가 영속성 컨텍스트를 비웠으므로 확인 여부는 새로 읽는다
        boolean verified = settingRepository.findById(userId).map(s -> s.getEmailVerifiedAt() != null).orElse(false);
        return response(user, emailEnabled, verified);
    }

    /**
     * 계정 이메일로 인증번호를 보낸다. 자리표시 이메일 400 email, 이미 확인됨 409 EMAIL_ALREADY_VERIFIED, 메일을 실제로 보낼 수 없는 설정 503 EMAIL_UNAVAILABLE,
     * 대기·제한 429 EMAIL_CODE_COOLDOWN/EMAIL_CODE_RATE_LIMITED, 발송 실패 503 EMAIL_SEND_FAILED. 트랜잭션·잠금 없음(메일 발송이 느릴 수 있다).
     */
    public void sendVerificationCode(Long userId, String ip) {
        User user = loadUser(userId);
        requireMailable(user);
        boolean verified = settingRepository.findById(userId).map(s -> s.getEmailVerifiedAt() != null).orElse(false);
        if (verified) {
            throw alreadyVerified();
        }
        if (!mailDeliveryPolicy.canSendRealMail()) {
            throw new EmailVerificationService.EmailCodeRateLimitedException("EMAIL_UNAVAILABLE",
                    "지금은 인증번호 메일을 보낼 수 없어요. 잠시 후 다시 시도하거나 문의해 주세요.", 0);
        }
        emailVerificationService.sendCodeForAccount(user.getEmail(), ip);
    }

    /** 인증번호를 확인하고 이메일 소유 확인을 기록한다. 이미 확인됐으면 그대로 200. 틀림·만료·횟수 초과는 400 code. */
    @Transactional
    public NotificationSettingDtos.Response confirmVerification(Long userId, String code) {
        User user = userRepository.findByIdForUpdate(userId).filter(u -> !u.isWithdrawn())
                .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
        requireMailable(user);
        UserNotificationSetting setting = settingRepository.findById(userId).orElse(null);
        boolean enabled = setting == null || setting.isEmailEnabled();
        if (setting != null && setting.getEmailVerifiedAt() != null) {
            return response(user, enabled, true);
        }
        emailVerificationService.verifyAndConsumeForAccount(user.getEmail(), code);
        emailOwnershipRecorder.markVerified(userId);
        return response(user, enabled, true);
    }

    private static void requireMailable(User user) {
        if (!isMailable(user.getEmail())) {
            throw new FieldValidationException("email", "메일을 받을 수 없는 계정(소셜 가입 자리표시 이메일)이에요.");
        }
    }

    private static ConflictException alreadyVerified() {
        return new ConflictException("이미 확인된 이메일이에요.", Map.of("code", "EMAIL_ALREADY_VERIFIED"));
    }

    private static NotificationSettingDtos.Response response(User user, boolean enabled, boolean verified) {
        return new NotificationSettingDtos.Response(enabled, isMailable(user.getEmail()), verified);
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> !u.isWithdrawn())
                .orElseThrow(() -> new InsufficientAuthenticationException("인증된 사용자를 찾을 수 없습니다."));
    }

    /** 메일을 보낼 수 있는 주소인가: 비어 있지 않고 예약 도메인(.invalid: 소셜 자리표시·탈퇴 익명화)이 아니다. */
    public static boolean isMailable(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        return !email.trim().toLowerCase(Locale.ROOT).endsWith(".invalid");
    }
}
