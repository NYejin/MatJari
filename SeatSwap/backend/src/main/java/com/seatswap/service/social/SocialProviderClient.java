package com.seatswap.service.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatswap.domain.SocialProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 제공자 token / userinfo 엔드포인트 호출. 요청 대상은 설정(SocialProperties)의 고정 URL 뿐이고 사용자 입력 URL 은 쓰지 않는다.
 * 리다이렉트 비추종(3xx 는 실패), 연결 타임아웃, 응답 전체(헤더+본문) 데드라인(워치독이 스트림을 닫음), 본문 상한, 압축 요청 안 함.
 * 모든 실패는 {@link ProviderException}(PROVIDER_ERROR). 메시지에는 응답·토큰·code·secret 을 싣지 않는다(실패 종류만).
 */
@Component
public class SocialProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "social-watchdog");
        t.setDaemon(true);
        return t;
    });

    /** 제공자 호출 실패(타임아웃·4xx/5xx·JSON 파싱 불가·본문 초과 등). */
    public static class ProviderException extends RuntimeException {
        public ProviderException(String kind) {
            super(kind, null, false, false);
        }
    }

    private final SocialProperties properties;
    private final HttpClient client;

    public SocialProviderClient(SocialProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 인가 코드를 access_token 으로 교환한다. verifier 는 PKCE 제공자만 보낸다. */
    public String exchangeCode(SocialProvider provider, String code, String verifier, String state) {
        SocialProperties.Provider cfg = properties.of(provider);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", properties.redirectUri(provider));
        form.put("client_id", cfg.getClientId());
        if (cfg.getClientSecret() != null && !cfg.getClientSecret().isBlank()) {
            form.put("client_secret", cfg.getClientSecret());
        }
        if (provider.supportsPkce()) {
            form.put("code_verifier", verifier);
        }
        if (provider == SocialProvider.NAVER) {
            form.put("state", state); // 네이버 token 엔드포인트는 state 를 요구한다
        }
        HttpRequest request = builder(cfg.getTokenUri())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)))
                .build();
        JsonNode json = send(request);
        JsonNode token = json.get("access_token");
        if (token == null || !token.isTextual() || token.asText().isBlank()) {
            throw new ProviderException("token-missing");
        }
        return token.asText();
    }

    /** access_token 으로 userinfo 를 읽어 (식별자, 이메일, 닉네임)을 뽑는다. */
    public SocialProfile fetchProfile(SocialProvider provider, String accessToken) {
        SocialProperties.Provider cfg = properties.of(provider);
        HttpRequest request = builder(cfg.getUserinfoUri())
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        return SocialProfileParser.parse(provider, send(request));
    }

    private JsonNode send(HttpRequest request) {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getReadTimeoutMs());
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ProviderException("io"); // 원인(URL 등)은 싣지 않는다
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("interrupted");
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new ProviderException("status-" + response.statusCode());
            }
            String encoding = response.headers().firstValue("Content-Encoding").orElse("identity").trim();
            if (!encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) {
                throw new ProviderException("content-encoding");
            }
            long declared;
            try {
                declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            } catch (NumberFormatException e) {
                throw new ProviderException("content-length");
            }
            if (declared > properties.getMaxBodyBytes()) {
                throw new ProviderException("too-large");
            }
            byte[] bytes = readBounded(body, deadlineNanos);
            try {
                JsonNode node = MAPPER.readTree(bytes);
                if (node == null || !node.isObject()) {
                    throw new ProviderException("json-shape");
                }
                return node;
            } catch (IOException e) {
                throw new ProviderException("json");
            }
        } catch (IOException e) {
            throw new ProviderException("read");
        }
    }

    private byte[] readBounded(InputStream in, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new ProviderException("deadline");
        }
        // 서버가 본문을 멈춰 세우면 read 가 블로킹되므로 데드라인에 스트림을 닫아 끊는다
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            try {
                in.close();
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }, remaining, TimeUnit.NANOSECONDS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > properties.getMaxBodyBytes()) {
                    throw new ProviderException("too-large");
                }
                out.write(buf, 0, n);
                if (System.nanoTime() > deadlineNanos) {
                    throw new ProviderException("deadline");
                }
            }
        } catch (IOException e) {
            throw new ProviderException(System.nanoTime() > deadlineNanos ? "deadline" : "read");
        } finally {
            watchdog.cancel(false);
        }
        return out.toByteArray();
    }

    /** 요청 전체 타임아웃(헤더 대기)을 붙인 빌더. 본문 읽기 데드라인은 send 의 워치독이 맡는다. */
    private HttpRequest.Builder builder(String uri) {
        return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofMillis(properties.getReadTimeoutMs()));
    }

    private static String encodeForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return sb.toString();
    }
}
