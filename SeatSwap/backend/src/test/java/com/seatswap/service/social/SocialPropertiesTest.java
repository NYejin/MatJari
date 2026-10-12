package com.seatswap.service.social;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialPropertiesTest {

    private static final String[] ORIGINS = {"http://localhost:5173"};

    private SocialProperties enabledGoogle() {
        SocialProperties p = new SocialProperties();
        p.getGoogle().setClientId("cid");
        p.getGoogle().setClientSecret("sec");
        p.setStateSecret(SocialTestSupport.SECRET);
        return p;
    }

    @Test
    void 제공자가_모두_비활성이면_검증을_건너뛴다_기존_환경_보호() {
        SocialProperties p = new SocialProperties();
        p.setFrontendBaseUrl("https://not-in-allowed.example.com"); // 위반 값이어도 통과
        p.setStateSecret("short");
        assertThat(p.anyEnabled()).isFalse();
        assertThatCode(() -> p.validate(ORIGINS)).doesNotThrowAnyException();
        assertThat(p.enabledProviderPaths()).isEmpty();
    }

    @Test
    void 활성이면_frontend_base_url이_allowed_origins에_있어야_기동한다() {
        SocialProperties p = enabledGoogle();
        assertThatCode(() -> p.validate(ORIGINS)).doesNotThrowAnyException();
        p.setFrontendBaseUrl("http://localhost:5173/"); // 끝 슬래시는 정리
        assertThatCode(() -> p.validate(ORIGINS)).doesNotThrowAnyException();
        p.setFrontendBaseUrl("http://localhost:3000");
        assertThatThrownBy(() -> p.validate(ORIGINS)).isInstanceOf(IllegalStateException.class).hasMessageContaining("allowed-origins");
        p.setFrontendBaseUrl("https://evil.example.com");
        assertThatThrownBy(() -> p.validate(ORIGINS)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 경로가_붙은_주소와_짧은_비밀키와_http_엔드포인트는_거부한다() {
        SocialProperties p1 = enabledGoogle();
        p1.setFrontendBaseUrl("http://localhost:5173/app");
        assertThatThrownBy(() -> p1.validate(new String[]{"http://localhost:5173/app"})).isInstanceOf(IllegalStateException.class);

        SocialProperties p2 = enabledGoogle();
        p2.setStateSecret("short");
        assertThatThrownBy(() -> p2.validate(ORIGINS)).hasMessageContaining("state-secret");

        SocialProperties p3 = enabledGoogle();
        p3.getGoogle().setTokenUri("http://accounts.example.com/token");
        assertThatThrownBy(() -> p3.validate(ORIGINS)).hasMessageContaining("token-uri");

        SocialProperties p4 = enabledGoogle();
        p4.getGoogle().setClientSecret("");
        assertThatThrownBy(() -> p4.validate(ORIGINS)).hasMessageContaining("client-secret");
    }

    @Test
    void 운영_설정에서는_개발용_기본_비밀키를_쓸_수_없다() {
        SocialProperties p = enabledGoogle();
        p.setStateSecret(SocialProperties.DEV_STATE_SECRET);
        p.setCookieSecure(true);
        p.setBackendBaseUrl("https://api.example.com");
        assertThatThrownBy(() -> p.validate(ORIGINS)).hasMessageContaining("개발용");
        p.setStateSecret(SocialTestSupport.SECRET);
        assertThatCode(() -> p.validate(ORIGINS)).doesNotThrowAnyException();
        p.setCookieSecure(false); // https 인데 Secure 쿠키가 아니면 거부
        assertThatThrownBy(() -> p.validate(ORIGINS)).hasMessageContaining("cookie-secure");
    }

    @Test
    void 검증_메시지와_toString에_secret이_실리지_않는다() {
        SocialProperties p = enabledGoogle();
        p.getGoogle().setClientSecret("TOP-SECRET-VALUE");
        p.setFrontendBaseUrl("http://other:1");
        assertThatThrownBy(() -> p.validate(ORIGINS)).satisfies(e -> assertThat(e.getMessage()).doesNotContain("TOP-SECRET-VALUE"));
        assertThat(p.getGoogle().toString()).doesNotContain("TOP-SECRET-VALUE");
    }

    @Test
    void 리다이렉트_URI는_백엔드_주소_기반이다() {
        SocialProperties p = new SocialProperties();
        p.setBackendBaseUrl("https://api.example.com/");
        assertThat(p.redirectUri(com.seatswap.domain.SocialProvider.KAKAO)).isEqualTo("https://api.example.com/api/auth/social/kakao/callback");
    }
}
