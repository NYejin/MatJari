package com.matjari.security;

import com.matjari.dto.request.LoginRequest;
import com.matjari.exception.MatJariException;
import com.matjari.repository.UserRepository;
import com.matjari.security.AuthRateLimiter.AuthRateLimitedException;
import com.matjari.service.AuthService;
import com.matjari.service.EmailVerificationService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 인증 보안 보강: 로그인·가입 속도 제한, 클라이언트 IP 해석, 기본 비밀값 기동 거부, 없는 계정 BCrypt 비교. */
class AuthHardeningTest {

    private static final class MutableClock extends Clock {
        private long millis = 1_000_000_000L;

        void advanceSeconds(long s) {
            millis += s * 1000;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    @Nested
    class LoginLimit {
        private final MutableClock clock = new MutableClock();
        private final AuthRateLimiter limiter = new AuthRateLimiter(clock);

        private void fail(String email, String ip, int n) {
            for (int i = 0; i < n; i++) {
                limiter.checkLogin(email, ip);
                limiter.recordLoginFailure(email, ip);
            }
        }

        @Test
        void 계정_실패_5회_뒤에는_429_코드와_대기시간() {
            fail("a@b.com", "1.1.1.1", 5);
            assertThatThrownBy(() -> limiter.checkLogin("a@b.com", "1.1.1.1"))
                    .isInstanceOfSatisfying(AuthRateLimitedException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("LOGIN_RATE_LIMITED");
                        assertThat(e.getRetryAfterSeconds()).isBetween(1L, 900L);
                    });
        }

        @Test
        void 이메일은_정규화해서_센다_다른_IP로도_계정_잠금() {
            fail("A@B.com ", "1.1.1.1", 5);
            assertThatThrownBy(() -> limiter.checkLogin("a@b.com", "9.9.9.9"))
                    .isInstanceOf(AuthRateLimitedException.class);
        }

        @Test
        void 성공하면_그_계정_실패_횟수가_초기화된다() {
            fail("a@b.com", "1.1.1.1", 4);
            limiter.recordLoginSuccess("a@b.com");
            fail("a@b.com", "1.1.1.1", 4);
            assertThatCode(() -> limiter.checkLogin("a@b.com", "1.1.1.1")).doesNotThrowAnyException();
        }

        @Test
        void 잠금은_15분_뒤_풀린다() {
            fail("a@b.com", "1.1.1.1", 5);
            clock.advanceSeconds(899);
            assertThatThrownBy(() -> limiter.checkLogin("a@b.com", "1.1.1.1")).isInstanceOf(AuthRateLimitedException.class);
            clock.advanceSeconds(2);
            assertThatCode(() -> limiter.checkLogin("a@b.com", "1.1.1.1")).doesNotThrowAnyException();
        }

        @Test
        void 창을_벗어난_실패는_세지_않는다() {
            fail("a@b.com", "1.1.1.1", 4);
            clock.advanceSeconds(16 * 60);
            fail("a@b.com", "1.1.1.1", 4);
            assertThatCode(() -> limiter.checkLogin("a@b.com", "1.1.1.1")).doesNotThrowAnyException();
        }

        @Test
        void 없는_이메일도_같은_방식으로_센다_IP는_10분_30회() {
            for (int i = 0; i < 30; i++) {
                String email = "nobody" + i + "@x.com"; // 계정별로는 한도 아래
                limiter.checkLogin(email, "2.2.2.2");
                limiter.recordLoginFailure(email, "2.2.2.2");
            }
            assertThatThrownBy(() -> limiter.checkLogin("other@x.com", "2.2.2.2"))
                    .isInstanceOfSatisfying(AuthRateLimitedException.class,
                            e -> assertThat(e.getCode()).isEqualTo("LOGIN_RATE_LIMITED"));
            assertThatCode(() -> limiter.checkLogin("other@x.com", "3.3.3.3")).doesNotThrowAnyException();
            clock.advanceSeconds(10 * 60 + 1);
            assertThatCode(() -> limiter.checkLogin("other@x.com", "2.2.2.2")).doesNotThrowAnyException();
        }

        @Test
        void 키가_상한을_넘어도_맵이_무한히_커지지_않는다() {
            for (int i = 0; i < AuthRateLimiter.MAX_KEYS + 500; i++) {
                limiter.recordLoginFailure("u" + i + "@x.com", "10.0." + (i / 250) + "." + (i % 250));
            }
            assertThatCode(() -> limiter.checkLogin("u1@x.com", "1.1.1.1")).doesNotThrowAnyException();
        }
    }

    @Nested
    class SignupLimit {
        private final MutableClock clock = new MutableClock();
        private final AuthRateLimiter limiter = new AuthRateLimiter(clock);

        @Test
        void IP당_1시간_10회_초과는_429_SIGNUP_RATE_LIMITED() {
            for (int i = 0; i < 10; i++) {
                limiter.acquireSignup("1.1.1.1");
            }
            assertThatThrownBy(() -> limiter.acquireSignup("1.1.1.1"))
                    .isInstanceOfSatisfying(AuthRateLimitedException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("SIGNUP_RATE_LIMITED");
                        assertThat(e.getRetryAfterSeconds()).isBetween(1L, 3600L);
                    });
            assertThatCode(() -> limiter.acquireSignup("2.2.2.2")).doesNotThrowAnyException();
            clock.advanceSeconds(3601);
            assertThatCode(() -> limiter.acquireSignup("1.1.1.1")).doesNotThrowAnyException();
        }
    }

    @Nested
    class ClientIp {
        private final ClientIpResolver resolver = new ClientIpResolver(
                "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16".split(","));

        private MockHttpServletRequest req(String remote, String realIp, String xff) {
            MockHttpServletRequest r = new MockHttpServletRequest();
            r.setRemoteAddr(remote);
            if (realIp != null) {
                r.addHeader("X-Real-IP", realIp);
            }
            if (xff != null) {
                r.addHeader("X-Forwarded-For", xff);
            }
            return r;
        }

        @Test
        void 신뢰_프록시는_X_Real_IP를_쓴다() {
            assertThat(resolver.resolve(req("172.18.0.3", "203.0.113.7", null))).isEqualTo("203.0.113.7");
        }

        @Test
        void 신뢰되지_않은_직접_연결은_헤더를_무시한다() {
            assertThat(resolver.resolve(req("198.51.100.9", "203.0.113.7", "203.0.113.8"))).isEqualTo("198.51.100.9");
        }

        @Test
        void X_Real_IP가_없으면_XFF의_가장_오른쪽_비신뢰_주소() {
            // 왼쪽 1.2.3.4 는 클라이언트가 넣은 위조 값, 오른쪽 203.0.113.7 이 프록시가 본 실제 주소
            assertThat(resolver.resolve(req("10.0.0.5", null, "1.2.3.4, 203.0.113.7, 10.0.0.9")))
                    .isEqualTo("203.0.113.7");
        }

        @Test
        void XFF가_모두_신뢰_주소거나_없으면_직접_연결_주소() {
            assertThat(resolver.resolve(req("10.0.0.5", null, "192.168.1.1, 10.0.0.9"))).isEqualTo("10.0.0.5");
            assertThat(resolver.resolve(req("10.0.0.5", null, null))).isEqualTo("10.0.0.5");
        }

        @Test
        void IP가_아닌_헤더_값은_무시한다() {
            assertThat(resolver.resolve(req("10.0.0.5", "evil.example.com", "not-an-ip"))).isEqualTo("10.0.0.5");
            assertThat(resolver.resolve(req("10.0.0.5", null, "1.2.3.4, garbage, 203.0.113.7")))
                    .isEqualTo("203.0.113.7");
        }

        @Test
        void IPv6_루프백과_CIDR_경계() {
            assertThat(resolver.resolve(req("0:0:0:0:0:0:0:1", "203.0.113.7", null))).isEqualTo("203.0.113.7");
            assertThat(resolver.resolve(req("172.15.0.1", "203.0.113.7", null))).isEqualTo("172.15.0.1");
            assertThat(resolver.resolve(req("172.31.255.255", "203.0.113.7", null))).isEqualTo("203.0.113.7");
            assertThat(resolver.resolve(req("172.32.0.1", "203.0.113.7", null))).isEqualTo("172.32.0.1");
        }

        @Test
        void 잘못된_CIDR_설정은_기동_실패() {
            assertThatThrownBy(() -> new ClientIpResolver(new String[]{"10.0.0.0/99"})).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> new ClientIpResolver(new String[]{"proxy.local"})).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class SecretsGuard {
        private static final String JWT_DEFAULT = "dev-only-insecure-default-secret-please-override-with-JWT-SECRET-env";
        private static final String EMAIL_DEFAULT = "dev-only-insecure-email-code-secret-please-override";

        @Test
        void 루프백이면_개발_기본값을_허용한다() {
            assertThatCode(() -> InsecureSecretsGuard.check(JWT_DEFAULT, EMAIL_DEFAULT, true, "http://localhost:8080"))
                    .doesNotThrowAnyException();
        }

        @Test
        void 운영_주소에서_JWT_기본값과_예시값은_거부() {
            assertThatThrownBy(() -> InsecureSecretsGuard.check(JWT_DEFAULT, "real", true, "https://api.example.com"))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(JWT_DEFAULT);
            assertThatThrownBy(() -> InsecureSecretsGuard.check("changeme-dev-secret", "real", false, "https://api.example.com"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void 운영_주소에서_이메일_키_기본값은_인증이_켜져_있을_때만_거부() {
            assertThatThrownBy(() -> InsecureSecretsGuard.check("real-jwt-secret-value-0123456789abcdef", EMAIL_DEFAULT, true, "https://api.example.com"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatCode(() -> InsecureSecretsGuard.check("real-jwt-secret-value-0123456789abcdef", EMAIL_DEFAULT, false, "https://api.example.com"))
                    .doesNotThrowAnyException();
        }

        @Test
        void 운영_주소에서_새_비밀값은_통과() {
            assertThatCode(() -> InsecureSecretsGuard.check("real-jwt-secret-value-0123456789abcdef", "real-email-secret", true, "https://api.example.com"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class DummyHash {
        @Test
        void 없는_이메일도_BCrypt_비교를_한_번_한_뒤_같은_메시지로_실패() {
            UserRepository users = mock(UserRepository.class);
            PasswordEncoder encoder = mock(PasswordEncoder.class);
            when(encoder.encode(anyString())).thenReturn("$2a$10$dummydummydummydummydummydummydummydummydummydummydummy");
            when(users.findByEmail(anyString())).thenReturn(java.util.Optional.empty());
            AuthService service = new AuthService(users, encoder, mock(JwtTokenProvider.class),
                    mock(AccountSuspensionChecker.class), mock(EmailVerificationService.class));

            assertThatThrownBy(() -> service.login(new LoginRequest("nobody@x.com", "password1!")))
                    .isInstanceOf(MatJariException.class).hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
            verify(encoder).matches(anyString(), any());
        }
    }
}
