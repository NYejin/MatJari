package com.seatswap.service.social;

import com.seatswap.domain.SocialProvider;
import com.seatswap.service.social.SocialTestSupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SocialStateCookieTest {

    private final MutableClock clock = new MutableClock();
    private final SocialProperties props = new SocialProperties();
    private final SocialStateCookie cookie;

    SocialStateCookieTest() {
        props.setStateSecret(SocialTestSupport.SECRET);
        cookie = new SocialStateCookie(props, clock);
    }

    @Test
    void 서명된_쿠키는_같은_provider에서만_검증된다() {
        String v = cookie.sign(SocialProvider.GOOGLE, "st", "ver");
        assertThat(cookie.verify(v, SocialProvider.GOOGLE)).contains(new SocialStateCookie.Verified("st", "ver"));
        assertThat(cookie.verify(v, SocialProvider.KAKAO)).isEmpty();
    }

    @Test
    void 만료되면_거부한다() {
        String v = cookie.sign(SocialProvider.KAKAO, "st", "ver");
        clock.advance(599_000);
        assertThat(cookie.verify(v, SocialProvider.KAKAO)).isPresent();
        clock.advance(2_000);
        assertThat(cookie.verify(v, SocialProvider.KAKAO)).isEmpty();
    }

    @Test
    void 본문이나_서명을_변조하면_거부한다() {
        String v = cookie.sign(SocialProvider.NAVER, "st", "ver");
        String[] parts = v.split("\\.");
        // 본문의 state 를 바꾸고 서명은 그대로
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).replace("|st|", "|xx|").getBytes(StandardCharsets.UTF_8));
        assertThat(cookie.verify(forgedPayload + "." + parts[1], SocialProvider.NAVER)).isEmpty();
        // 서명 한 글자 변경
        String badSig = parts[1].substring(0, parts[1].length() - 1) + (parts[1].endsWith("A") ? "B" : "A");
        assertThat(cookie.verify(parts[0] + "." + badSig, SocialProvider.NAVER)).isEmpty();
    }

    @Test
    void 다른_비밀키로_서명한_쿠키는_거부한다() {
        SocialProperties other = new SocialProperties();
        other.setStateSecret("another-secret-0123456789-0123456789-0123456789");
        String v = new SocialStateCookie(other, clock).sign(SocialProvider.GOOGLE, "st", "ver");
        assertThat(cookie.verify(v, SocialProvider.GOOGLE)).isEmpty();
    }

    @Test
    void 쓰레기_값은_예외_없이_거부한다() {
        for (String bad : new String[]{null, "", ".", "a.b.c", "abc", "!!!.@@@", "x".repeat(2000)}) {
            assertThat(cookie.verify(bad, SocialProvider.GOOGLE)).isEmpty();
        }
    }

    @Test
    void 쿠키_헤더_속성과_상수시간_비교() {
        String h = cookie.setCookieHeader("VAL");
        assertThat(h).startsWith("ss_oauth=VAL").contains("HttpOnly", "SameSite=Lax", "Path=/api/auth/social", "Max-Age=600")
                .doesNotContain("Secure");
        props.setCookieSecure(true);
        assertThat(cookie.setCookieHeader("VAL")).contains("; Secure");
        assertThat(cookie.clearCookieHeader()).contains("Max-Age=0");
        assertThat(SocialStateCookie.constantTimeEquals("a", "a")).isTrue();
        assertThat(SocialStateCookie.constantTimeEquals("a", "b")).isFalse();
        assertThat(SocialStateCookie.constantTimeEquals(null, "b")).isFalse();
        assertThat(SocialStateCookie.constantTimeEquals("a", null)).isFalse();
    }
}
