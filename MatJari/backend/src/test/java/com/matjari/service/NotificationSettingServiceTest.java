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
    private EmailVerificationService verification;
    private EmailOwnershipRecorder recorder;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        settings = mock(UserNotificationSettingRepository.class);
        verification = mock(EmailVerificationService.class);
        recorder = mock(EmailOwnershipRecorder.class);
        service = newService("smtp");
    }

    private NotificationSettingService newService(String mailMode) {
        return new NotificationSettingService(users, settings, Clock.fixed(NOW.atZone(KST).toInstant(), KST), verification, recorder,
                new com.matjari.service.mail.MailDeliveryPolicy(mailMode, "https://matjari.example.com"));
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
        assertThat(service.get(1L)).isEqualTo(new NotificationSettingDtos.Response(true, true, false));
    }

    @Test
    void 저장된_꺼짐을_돌려준다() {
        user(1L, "a@b.com");
        UserNotificationSetting off = new UserNotificationSetting();
        ReflectionTestUtils.setField(off, "emailEnabled", false);
        when(settings.findById(1L)).thenReturn(Optional.of(off));
        assertThat(service.get(1L)).isEqualTo(new NotificationSettingDtos.Response(false, true, false));
    }

    @Test
    void 자리표시_이메일_계정은_메일을_받을_수_없다() {
        user(2L, "google.abcdef0123456789@social.matjari.invalid");
        when(settings.findById(2L)).thenReturn(Optional.empty());
        assertThat(service.get(2L)).isEqualTo(new NotificationSettingDtos.Response(true, false, false));
        assertThat(NotificationSettingService.isMailable("withdrawn.3@withdrawn.matjari.invalid")).isFalse();
        assertThat(NotificationSettingService.isMailable("  ")).isFalse();
        assertThat(NotificationSettingService.isMailable(null)).isFalse();
        assertThat(NotificationSettingService.isMailable("X@Y.COM")).isTrue();
    }

    @Test
    void 수정은_upsert를_한_번_하고_바뀐_값을_돌려준다() {
        user(1L, "a@b.com");
        assertThat(service.update(1L, false)).isEqualTo(new NotificationSettingDtos.Response(false, true, false));
        verify(settings).upsertEmailEnabled(1L, false, NOW);
    }

    private static UserNotificationSetting verified() {
        UserNotificationSetting s = new UserNotificationSetting();
        ReflectionTestUtils.setField(s, "emailVerifiedAt", NOW.minusDays(1));
        return s;
    }

    @Test
    void 확인된_이메일이면_emailVerified_true를_돌려준다() {
        user(1L, "a@b.com");
        when(settings.findById(1L)).thenReturn(Optional.of(verified()));
        assertThat(service.get(1L)).isEqualTo(new NotificationSettingDtos.Response(true, true, true));
    }

    @Test
    void 인증번호_발송_자리표시_이메일은_400_이미_확인됨은_409_보낼_수_없는_설정은_503() {
        user(2L, "google.abcdef0123456789@social.matjari.invalid");
        assertThatThrownBy(() -> service.sendVerificationCode(2L, "1.1.1.1"))
                .isInstanceOf(com.matjari.exception.FieldValidationException.class);
        user(1L, "a@b.com");
        when(settings.findById(1L)).thenReturn(Optional.of(verified()));
        assertThatThrownBy(() -> service.sendVerificationCode(1L, "1.1.1.1"))
                .isInstanceOf(com.matjari.exception.ConflictException.class);
        when(settings.findById(1L)).thenReturn(Optional.empty());
        NotificationSettingService logOnProd = newService("log");
        assertThatThrownBy(() -> logOnProd.sendVerificationCode(1L, "1.1.1.1"))
                .isInstanceOfSatisfying(EmailVerificationService.EmailCodeRateLimitedException.class,
                        e -> assertThat(e.getCode()).isEqualTo("EMAIL_UNAVAILABLE"));
        org.mockito.Mockito.verifyNoInteractions(verification);
    }

    @Test
    void 인증번호_발송은_계정_이메일로_보낸다() {
        user(1L, "a@b.com");
        when(settings.findById(1L)).thenReturn(Optional.empty());
        service.sendVerificationCode(1L, "1.1.1.1");
        verify(verification).sendCodeForAccount("a@b.com", "1.1.1.1");
    }

    @Test
    void 확인은_users를_잠그고_코드를_검증한_뒤_소유_확인을_기록한다() {
        User u = User.create("a@b.com", "enc", "닉");
        ReflectionTestUtils.setField(u, "id", 1L);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(u));
        when(settings.findById(1L)).thenReturn(Optional.empty());
        assertThat(service.confirmVerification(1L, "123456")).isEqualTo(new NotificationSettingDtos.Response(true, true, true));
        verify(verification).verifyAndConsumeForAccount("a@b.com", "123456");
        verify(recorder).markVerified(1L);
    }

    @Test
    void 이미_확인됐으면_확인_API는_코드_검증_없이_200() {
        User u = User.create("a@b.com", "enc", "닉");
        ReflectionTestUtils.setField(u, "id", 1L);
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(u));
        when(settings.findById(1L)).thenReturn(Optional.of(verified()));
        assertThat(service.confirmVerification(1L, "000000").emailVerified()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(verification, recorder);
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
