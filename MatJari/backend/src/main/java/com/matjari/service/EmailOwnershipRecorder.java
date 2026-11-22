package com.matjari.service;

import com.matjari.repository.UserNotificationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 이메일 소유가 확인된 시각(user_notification_setting.email_verified_at, V29)을 기록한다. 메일 알림은 이 값이 있는 사용자에게만 나간다
 * (남의 주소로 선점 가입한 계정에 알림 메일이 가는 것을 막는다). 호출 트랜잭션 안에서 PK 한 행 upsert 만 한다(이미 확인됐으면 처음 시각을 유지한다).
 * 호출자는 users 행을 이미 잠갔거나(비밀번호 재설정·내 이메일 인증) 방금 INSERT 한(가입) 트랜잭션이다. 설정 행이 없으면 만든다(켜짐 기본값).
 */
@Component
@RequiredArgsConstructor
public class EmailOwnershipRecorder {

    private final UserNotificationSettingRepository settingRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public void markVerified(Long userId) {
        settingRepository.upsertEmailVerified(userId, LocalDateTime.now(clock));
    }
}
