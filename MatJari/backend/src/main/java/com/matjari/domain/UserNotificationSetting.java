package com.matjari.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자별 알림 설정 (V29). 행이 없으면 메일 알림은 켜짐으로 본다. 쓰기는 UserNotificationSettingRepository 의 upsert 로만 하므로
 * 엔티티에 setter 가 없다(이 엔티티는 읽기용이다). last_email_at 은 메일 쿨다운 기준이다.
 */
@Entity
@Table(name = "user_notification_setting")
@Getter
@NoArgsConstructor
public class UserNotificationSetting {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "email_enabled", nullable = false, insertable = false, updatable = false)
    private boolean emailEnabled = true;

    @Column(name = "last_email_at", insertable = false, updatable = false)
    private LocalDateTime lastEmailAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
