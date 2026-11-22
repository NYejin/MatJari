package com.matjari.repository;

import com.matjari.domain.UserNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * 알림 설정 저장소 (V29). 모두 PK(user_id) 한 행만 다루는 문장이다(범위 UPDATE/DELETE 없음).
 * upsert 는 users 행에 FK 공유 잠금만 걸고 users FOR UPDATE 를 잡지 않는다(설정 저장은 다른 잠금과 얽히지 않는다).
 */
public interface UserNotificationSettingRepository extends JpaRepository<UserNotificationSetting, Long> {

    /** 메일 알림 켜기/끄기. 행이 없으면 만든다(last_email_at 은 건드리지 않는다). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "INSERT INTO user_notification_setting (user_id, email_enabled, updated_at) "
            + "VALUES (:userId, :enabled, :now) ON DUPLICATE KEY UPDATE email_enabled = :enabled, updated_at = :now")
    int upsertEmailEnabled(@Param("userId") Long userId, @Param("enabled") boolean enabled, @Param("now") LocalDateTime now);

    /** 회원 탈퇴: 설정 행 삭제(PK 한 행, 없어도 0행으로 정상). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = "DELETE FROM user_notification_setting WHERE user_id = :userId")
    int deleteByUserIdNative(@Param("userId") Long userId);
}
