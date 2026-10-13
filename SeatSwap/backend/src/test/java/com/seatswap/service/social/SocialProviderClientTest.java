package com.seatswap.service.social;

import com.seatswap.domain.SocialProvider;
import com.seatswap.service.social.SocialProviderClient.ProviderException;
import com.seatswap.service.social.SocialStubServer.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialProviderClientTest {

    private SocialStubServer stub;
    private SocialProperties props;
    private SocialProviderClient client;

    @BeforeEach
    void setUp() {
        stub = new SocialStubServer();
        props = SocialTestSupport.properties(stub);
        props.setReadTimeoutMs(700);
        props.setMaxBodyBytes(2048);
        client = new SocialProviderClient(props);
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new TreeMap<>();
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return m;
    }

    @Test
    void 구글_토큰_교환_요청_형식과_PKCE() {
        stub.on("/token", Reply.json("{\"access_token\":\"AT-1\",\"token_type\":\"Bearer\"}"));
        String token = client.exchangeCode(SocialProvider.GOOGLE, "the-code", "the-verifier", "the-state");
        assertThat(token).isEqualTo("AT-1");
        var req = stub.received().get(0);
        assertThat(req.method()).isEqualTo("POST");
        Map<String, String> f = form(req.body());
        assertThat(f).containsEntry("grant_type", "authorization_code").containsEntry("code", "the-code")
                .containsEntry("redirect_uri", "http://localhost:8080/api/auth/social/google/callback")
                .containsEntry("client_id", "g-client").containsEntry("client_secret", "g-secret-VALUE")
                .containsEntry("code_verifier", "the-verifier").doesNotContainKey("state");
    }

    @Test
    void 네이버는_state를_보내고_PKCE_verifier는_보내지_않는다() {
        stub.on("/token", Reply.json("{\"access_token\":\"AT-N\"}"));
        client.exchangeCode(SocialProvider.NAVER, "c", "v", "st-1");
        Map<String, String> f = form(stub.received().get(0).body());
        assertThat(f).containsEntry("state", "st-1").doesNotContainKey("code_verifier");
    }

    @Test
    void 카카오_secret이_비어_있으면_보내지_않는다() {
        props.getKakao().setClientSecret("");
        stub.on("/token", Reply.json("{\"access_token\":\"AT-K\"}"));
        client.exchangeCode(SocialProvider.KAKAO, "c", "v", "s");
        assertThat(form(stub.received().get(0).body())).doesNotContainKey("client_secret").containsEntry("code_verifier", "v");
    }

    @Test
    void userinfo는_Bearer로_호출하고_세_제공자_응답을_해석한다() {
        stub.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "g-1", "A@B.com", true, "홍길동")));
        SocialProfile g = client.fetchProfile(SocialProvider.GOOGLE, "AT-1");
        assertThat(g.providerUserId()).isEqualTo("g-1");
        assertThat(g.email()).isEqualTo("a@b.com");
        assertThat(g.nickname()).isEqualTo("홍길동");
        assertThat(stub.received().get(0).authorization()).isEqualTo("Bearer AT-1");

        stub.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.KAKAO, "12345", "k@k.com", true, "카톡")));
        SocialProfile k = client.fetchProfile(SocialProvider.KAKAO, "AT");
        assertThat(k.providerUserId()).isEqualTo("12345");
        assertThat(k.email()).isEqualTo("k@k.com");

        stub.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.NAVER, "nid", "n@n.com", true, "네이버닉")));
        SocialProfile n = client.fetchProfile(SocialProvider.NAVER, "AT");
        assertThat(n.providerUserId()).isEqualTo("nid");
        assertThat(n.email()).isNull(); // 네이버 이메일은 검증 여부를 알 수 없어 쓰지 않는다
        assertThat(n.nickname()).isEqualTo("네이버닉");
    }

    @Test
    void 미검증_이메일은_버린다() {
        stub.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "g-1", "a@b.com", false, "x")));
        assertThat(client.fetchProfile(SocialProvider.GOOGLE, "AT").email()).isNull();
        stub.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.KAKAO, "1", "a@b.com", false, "x")));
        assertThat(client.fetchProfile(SocialProvider.KAKAO, "AT").email()).isNull();
        // 이메일 값이 이상하면(골뱅이 없음) 없는 것으로
        stub.on("/userinfo", Reply.json(SocialStubServer.userinfo(SocialProvider.GOOGLE, "g-1", "not-an-email", true, "x")));
        assertThat(client.fetchProfile(SocialProvider.GOOGLE, "AT").email()).isNull();
    }

    @Test
    void 식별자가_없거나_너무_길면_실패한다() {
        stub.on("/userinfo", Reply.json("{\"email\":\"a@b.com\",\"email_verified\":true}"));
        assertThatThrownBy(() -> client.fetchProfile(SocialProvider.GOOGLE, "AT")).isInstanceOf(ProviderException.class);
        stub.on("/userinfo", Reply.json("{\"sub\":\"" + "x".repeat(101) + "\"}"));
        assertThatThrownBy(() -> client.fetchProfile(SocialProvider.GOOGLE, "AT")).isInstanceOf(ProviderException.class);
        stub.on("/userinfo", Reply.json("{\"sub\":{\"a\":1}}"));
        assertThatThrownBy(() -> client.fetchProfile(SocialProvider.GOOGLE, "AT")).isInstanceOf(ProviderException.class);
    }

    @Test
    void 오류_응답과_잘못된_JSON과_토큰_누락은_ProviderException이다() {
        stub.on("/token", Reply.status(400, "{\"error\":\"invalid_grant\"}"));
        assertThatThrownBy(() -> client.exchangeCode(SocialProvider.GOOGLE, "c", "v", "s")).isInstanceOf(ProviderException.class);
        stub.on("/token", Reply.status(500, "oops"));
        assertThatThrownBy(() -> client.exchangeCode(SocialProvider.GOOGLE, "c", "v", "s")).isInstanceOf(ProviderException.class);
        stub.on("/token", Reply.json("this is not json"));
        assertThatThrownBy(() -> client.exchangeCode(SocialProvider.GOOGLE, "c", "v", "s")).isInstanceOf(ProviderException.class);
        stub.on("/token", Reply.json("[1,2]"));
        assertThatThrownBy(() -> client.exchangeCode(SocialProvider.GOOGLE, "c", "v", "s")).isInstanceOf(ProviderException.class);
        stub.on("/token", Reply.json("{\"error\":\"x\"}")); // 네이버처럼 200 + error
        assertThatThrownBy(() -> client.exchangeCode(SocialProvider.NAVER, "c", "v", "s")).isInstanceOf(ProviderException.class);
        stub.on("/userinfo", Reply.status(401, "{}"));
        assertThatThrownBy(() -> client.fetchProfile(SocialProvider.GOOGLE, "AT")).isInstanceOf(ProviderException.class);
    }

    @Test
    void 리다이렉트는_따라가지_않는다() {
        stub.on("/token", new Reply(302, "", 0, Map.of("Location", stub.baseUrl() + "/redirected-target")));
        stub.on("/redirected-target", Reply.json("{\"access_token\":\"STOLEN\"}"));
        assertThatThrownBy(() -> client.exchangeCode(SocialProvider.GOOGLE, "c", "v", "s")).isInstanceOf(ProviderException.class);
        assertThat(stub.countTo("/redirected-target")).isZero();
    }

    @Test
    void 느린_응답은_타임아웃으로_실패한다() {
        stub.on("/token", Reply.json("{\"access_token\":\"AT\"}").delayed(2_500));
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.exchangeCode(SocialProvider.GOOGLE, "c", "v", "s")).isInstanceOf(ProviderException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void 본문이_상한을_넘으면_실패한다() {
        stub.on("/userinfo", Reply.json("{\"sub\":\"a\",\"pad\":\"" + "x".repeat(5000) + "\"}"));
        assertThatThrownBy(() -> client.fetchProfile(SocialProvider.GOOGLE, "AT")).isInstanceOf(ProviderException.class);
    }

    @Test
    void 연결할_수_없어도_ProviderException이고_메시지에_비밀이_없다() {
        props.getGoogle().setTokenUri("http://127.0.0.1:1/token");
        assertThatThrownBy(() -> client.exchangeCode(SocialProvider.GOOGLE, "SECRET-CODE", "v", "s"))
                .isInstanceOf(ProviderException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage()) + e.getCause())
                        .doesNotContain("SECRET-CODE").doesNotContain("g-secret-VALUE"));
    }
}
