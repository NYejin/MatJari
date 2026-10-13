package com.matjari.service.social;

import com.matjari.domain.SocialProvider;
import com.matjari.service.social.SocialTestSupport.MutableClock;
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
    void 연결_모드_쿠키는_linkUserId를_담고_로그인_모드는_null이다() {
        String link = cookie.sign(SocialProvider.GOOGLE, "st", "ver", 42L);
        assertThat(cookie.verify(link, SocialProvider.GOOGLE)).contains(new SocialStateCookie.Verified("st", "ver", 42L));
        String login = cookie.sign(SocialProvider.GOOGLE, "st", "ver");
        assertThat(cookie.verify(login, SocialProvider.GOOGLE).orElseThrow().linkUserId()).isNull();
        assertThat(cookie.verify(link, SocialProvider.KAKAO)).isEmpty();
        clock.advance(601_000);
        assertThat(cookie.verify(link, SocialProvider.GOOGLE)).isEmpty();
    }

    @Test
    void linkUserId를_바꾸거나_떼어_내면_서명이_맞지_않아_거부한다() {
        String v = cookie.sign(SocialProvider.NAVER, "st", "ver", 42L);
        String[] parts = v.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        for (String forged : new String[]{payload.replace("|42|", "|7|"), payload.replace("|42|", "||")}) {
            String enc = Base64.getUrlEncoder().withoutPadding().encodeToString(forged.getBytes(StandardCharsets.UTF_8));
            assertThat(cookie.verify(enc + "." + parts[1], SocialProvider.NAVER)).isEmpty();
        }
    }

    @Test
    void 서명이_맞아도_4칸_구_포맷이나_잘못된_linkUserId는_무효이다() {
        // 같은 비밀키로 서명했지만 칸 수가 다른(구 포맷) 쿠키
        long exp = clock.millis() + 60_000;
        assertThat(cookie.verify(signedRaw("GOOGLE|st|ver|" + exp), SocialProvider.GOOGLE)).isEmpty();
        assertThat(cookie.verify(signedRaw("GOOGLE|st|ver|abc|" + exp), SocialProvider.GOOGLE)).isEmpty();
        assertThat(cookie.verify(signedRaw("GOOGLE|st|ver|-5|" + exp), SocialProvider.GOOGLE)).isEmpty();
        assertThat(cookie.verify(signedRaw("GOOGLE|st|ver|0|" + exp), SocialProvider.GOOGLE)).isEmpty();
        assertThat(cookie.verify(signedRaw("GOOGLE|st|ver||" + exp), SocialProvider.GOOGLE)).isPresent();
    }

    /** 테스트용: 임의 payload 를 같은 비밀키로 서명한다. */
    private String signedRaw(String payload) {
        try {
            String enc = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SocialTestSupport.SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return enc + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(enc.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
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

    @Test
    void looksLikeLinkMode는_서명과_무관하게_5칸_중_linkUserId_칸이_양의_정수인지만_본다() {
        assertThat(SocialStateCookie.looksLikeLinkMode(cookie.sign(SocialProvider.GOOGLE, "st", "ver", 42L))).isTrue();
        assertThat(SocialStateCookie.looksLikeLinkMode(cookie.sign(SocialProvider.GOOGLE, "st", "ver"))).isFalse();
        // 서명이 틀려도 본문 모양만 본다
        String v = cookie.sign(SocialProvider.GOOGLE, "st", "ver", 42L);
        assertThat(SocialStateCookie.looksLikeLinkMode(v.substring(0, v.indexOf('.')) + ".bad")).isTrue();
        String zero = Base64.getUrlEncoder().withoutPadding().encodeToString("GOOGLE|a|b|0|1".getBytes(StandardCharsets.UTF_8));
        String four = Base64.getUrlEncoder().withoutPadding().encodeToString("GOOGLE|a|b|7".getBytes(StandardCharsets.UTF_8));
        String nan = Base64.getUrlEncoder().withoutPadding().encodeToString("GOOGLE|a|b|x|1".getBytes(StandardCharsets.UTF_8));
        for (String bad : new String[]{null, "", "garbage", "!!!.x", zero + ".x", four + ".x", nan + ".x", "A".repeat(2000)}) {
            assertThat(SocialStateCookie.looksLikeLinkMode(bad)).isFalse();
        }
    }
}
