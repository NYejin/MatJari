package com.matjari.service;

import com.matjari.domain.User;
import com.matjari.domain.UserNotificationSetting;
import com.matjari.dto.NotificationSettingDtos;
import com.matjari.repository.UserNotificationSettingRepository;
import com.matjari.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 알림 설정: 행이 없으면 켜짐, 자리표시 이메일이면 emailAvailable=false, 저장은 upsert 한 번. */
class NotificationSettingServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 12, 0);

    private UserRepository users;
    private UserNotificationSettingRepository settings;
    private NotificationSettingService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        settings = mock(UserNotificationSettingRepository.class);
        service = new NotificationSettingService(users, settings, Clock.fixed(NOW.atZone(KST).toInstant(), KST));
    }

    private void user(long id, String email) {
        User u = User.create(email, "enc", "닉");
        ReflectionTestUtils.setField(u, "id", id);
        when(users.findById(id)).thenReturn(Optional.of(u));
    }

    @Test
    void 설정_행이_없으면_켜짐이다() {
        user(1L, "a@b.com");
        when(settings.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.get(1L)).isEqualTo(new NotificationSettingDtos.Response(true, true));
    }

    @Test
    void 저장된_꺼짐을_돌려준다() {
        user(1L, "a@b.com");
        UserNotificationSetting off = new UserNotificationSetting();
        ReflectionTestUtils.setField(off, "emailEnabled", false);
        when(settings.findById(1L)).thenReturn(Optional.of(off));
        assertThat(service.get(1L)).isEqualTo(new NotificationSettingDtos.Response(false, true));
    }

    @Test
    void 자리표시_이메일_계정은_메일을_받을_수_없다() {
        user(2L, "google.abcdef0123456789@social.matjari.invalid");
        when(settings.findById(2L)).thenReturn(Optional.empty());
        assertThat(service.get(2L)).isEqualTo(new NotificationSettingDtos.Response(true, false));
        assertThat(NotificationSettingService.isMailable("withdrawn.3@withdrawn.matjari.invalid")).isFalse();
        assertThat(NotificationSettingService.isMailable("  ")).isFalse();
        assertThat(NotificationSettingService.isMailable(null)).isFalse();
        assertThat(NotificationSettingService.isMailable("X@Y.COM")).isTrue();
    }

    @Test
    void 수정은_upsert를_한_번_하고_바뀐_값을_돌려준다() {
        user(1L, "a@b.com");
        assertThat(service.update(1L, false)).isEqualTo(new NotificationSettingDtos.Response(false, true));
        verify(settings).upsertEmailEnabled(1L, false, NOW);
    }

    @Test
    void 없거나_탈퇴한_사용자는_인증_오류다() {
        when(users.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(9L)).isInstanceOf(InsufficientAuthenticationException.class);
        User gone = User.create("withdrawn.8@withdrawn.matjari.invalid", "enc", "탈퇴한 회원");
        ReflectionTestUtils.setField(gone, "id", 8L);
        ReflectionTestUtils.setField(gone, "withdrawnAt", NOW);
        when(users.findById(8L)).thenReturn(Optional.of(gone));
        assertThatThrownBy(() -> service.update(8L, true)).isInstanceOf(InsufficientAuthenticationException.class);
    }
}
