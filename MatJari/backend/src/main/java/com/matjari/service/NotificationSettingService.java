package com.matjari.service;

import com.matjari.domain.User;
import com.matjari.domain.UserNotificationSetting;
import com.matjari.dto.NotificationSettingDtos;
import com.matjari.repository.UserNotificationSettingRepository;
import com.matjari.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 알림 설정(메일 알림 켜기/끄기, V29). 행이 없으면 켜짐으로 본다. 저장은 PK 한 행 upsert 뿐이고 users FOR UPDATE 는 잡지 않는다.
 * emailAvailable 은 메일을 받을 수 있는 이메일인지다: 소셜 가입의 자리표시 이메일(.invalid 예약 도메인)은 받을 수 없다.
 */
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private final UserRepository userRepository;
    private final UserNotificationSettingRepository settingRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public NotificationSettingDtos.Response get(Long userId) {
        User user = loadUser(userId);
        boolean enabled = settingRepository.findById(userId).map(UserNotificationSetting::isEmailEnabled).orElse(true);
        return new NotificationSettingDtos.Response(enabled, isMailable(user.getEmail()));
    }

    @Transactional
    public NotificationSettingDtos.Response update(Long userId, boolean emailEnabled) {
        User user = loadUser(userId);
        settingRepository.upsertEmailEnabled(userId, emailEnabled, LocalDateTime.now(clock));
        return new NotificationSettingDtos.Response(emailEnabled, isMailable(user.getEmail()));
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
