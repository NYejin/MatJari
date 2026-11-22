package com.matjari.service;

import com.matjari.repository.PasswordResetTokenRepository;
import com.matjari.repository.RefreshTokenRepository;
import com.matjari.repository.UserRepository;
import com.matjari.security.AuthRateLimiter;
import com.matjari.security.JwtTokenProvider;
import com.matjari.service.mail.EmailSender;
import com.matjari.service.social.SocialProperties;
import com.matjari.websocket.WebSocketSessionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 메일을 보낼 수 없는 설정에서의 재설정 요청 거절(503 PASSWORD_RESET_UNAVAILABLE) 단위 테스트. */
class PasswordResetServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AuthRateLimiter limiter = mock(AuthRateLimiter.class);

    private PasswordResetService service(String mailMode, String backendBaseUrl) {
        SocialProperties social = new SocialProperties();
        social.setBackendBaseUrl(backendBaseUrl);
        return new PasswordResetService(users, mock(PasswordResetTokenRepository.class), mock(RefreshTokenRepository.class),
                mock(PasswordEncoder.class), mock(EmailSender.class), social, limiter, mock(JwtTokenProvider.class),
                mock(WebSocketSessionRegistry.class), Clock.systemUTC(), mock(PlatformTransactionManager.class), 20, 30, mailMode);
    }

    @Test
    void log_모드인데_백엔드_주소가_루프백이_아니면_계정_조회_전에_모든_요청을_503으로_거절한다() {
        PasswordResetService s = service("log", "https://matjari.example.com");
        assertThatThrownBy(() -> s.requestReset("a@b.com", "1.1.1.1"))
                .isInstanceOf(PasswordResetService.PasswordResetUnavailableException.class)
                .hasMessage("지금은 비밀번호 재설정 메일을 보낼 수 없어요. 잠시 후 다시 시도하거나 문의해 주세요.");
        verifyNoInteractions(users, limiter);
    }

    @Test
    void 개발_루프백_log_모드와_smtp_모드는_거절하지_않는다() {
        when(users.findByEmail("a@b.com")).thenReturn(Optional.empty());
        assertThatCode(() -> service("log", "http://localhost:8080").requestReset("a@b.com", "1.1.1.1")).doesNotThrowAnyException();
        assertThatCode(() -> service("smtp", "https://matjari.example.com").requestReset("a@b.com", "1.1.1.1")).doesNotThrowAnyException();
    }
}
