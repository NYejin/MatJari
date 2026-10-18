package com.matjari.service.linkinfo;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 공개 HTML 페이지 한 장을 안전하게 받아 jsoup Document 로 만든다. 실패는 모두 {@link FetchFailedException}.
 *
 * SSRF 방어의 핵심은 호출자가 사용자 URL이 아니라 고정 템플릿으로 만든 URL만 넘기는 것이고(PerformancePreviewService),
 * 여기서는 한 번 더 막는다: https 만, 호스트는 코드에 고정된 허용 목록과 정확히 일치, 모든 해석 주소가 공인 주소,
 * 리다이렉트 따라가지 않음(3xx 는 실패), 2xx 중 200 만, text/html 계열만, 압축 없음(Accept-Encoding: identity),
 * 본문 최대 maxBytes, 전체 데드라인(헤더 대기 + 본문 읽기, 느린 본문은 워치독이 스트림을 닫아 끊는다).
 * 쿠키·인증 헤더는 보내지 않는다.
 *
 * 알려진 한계: 검증한 IP 로 연결을 고정(pinning)하지 않는다. 대상이 코드에 고정된 공개 도메인 3개라서
 * 공격자가 DNS 를 바꿀 수 없다는 전제다.
 */
@Component
public class PageFetcher {

    static final Set<String> ALLOWED_HOSTS = Set.of("ticket.yes24.com", "www.ticketlink.co.kr", "ticket.melon.com");
    private static final String USER_AGENT = "MatJariBot/0.1 (+portfolio project; contact: kidjin1016@gmail.com)";
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*\"?([A-Za-z0-9_.:-]+)", Pattern.CASE_INSENSITIVE);
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "linkinfo-watchdog");
        t.setDaemon(true);
        return t;
    });

    public static class FetchFailedException extends RuntimeException {
        public FetchFailedException(String message) {
            super(message);
        }

        public FetchFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final HttpClient client;
    private final Duration requestTimeout;
    private final long maxBytes;
    /** 테스트 전용: true 면 http·루프백·호스트 허용 목록 검사를 풀어 로컬 HttpServer 로 시험할 수 있다. 운영 빈은 항상 false. */
    private final boolean relaxedForTest;

    @Autowired
    public PageFetcher(@Value("${matjari.linkinfo.connect-timeout-ms:5000}") long connectTimeoutMs,
                       @Value("${matjari.linkinfo.request-timeout-ms:10000}") long requestTimeoutMs,
                       @Value("${matjari.linkinfo.max-bytes:2097152}") long maxBytes) {
        this(connectTimeoutMs, requestTimeoutMs, maxBytes, false);
    }

    PageFetcher(long connectTimeoutMs, long requestTimeoutMs, long maxBytes, boolean relaxedForTest) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.maxBytes = maxBytes;
        this.relaxedForTest = relaxedForTest;
    }

    public Document fetch(URI uri) {
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!relaxedForTest) {
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || !ALLOWED_HOSTS.contains(host)) {
                throw new FetchFailedException("host not allowed");
            }
        }
        long deadlineNanos = System.nanoTime() + requestTimeout.toNanos();
        if (!relaxedForTest) {
            checkAddresses(host);
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new FetchFailedException("request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchFailedException("interrupted", e);
        }
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new FetchFailedException("status " + response.statusCode());
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            String lower = contentType.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("text/html") && !lower.startsWith("application/xhtml+xml")) {
                throw new FetchFailedException("content-type");
            }
            String encoding = response.headers().firstValue("Content-Encoding").orElse("identity").trim();
            if (!encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) {
                throw new FetchFailedException("content-encoding"); // identity 요청을 무시한 압축 본문은 해제하지 않는다
            }
            long declared;
            try {
                declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            } catch (NumberFormatException e) {
                throw new FetchFailedException("content-length", e);
            }
            if (declared > maxBytes) {
                throw new FetchFailedException("too large");
            }
            byte[] bytes = readBounded(body, deadlineNanos);
            Matcher cs = CHARSET.matcher(contentType);
            String charset = cs.find() ? cs.group(1) : null;
            try {
                return Jsoup.parse(new ByteArrayInputStream(bytes), charset, uri.toString());
            } catch (IllegalArgumentException e) { // 알 수 없는 charset 이름
                return Jsoup.parse(new ByteArrayInputStream(bytes), null, uri.toString());
            }
        } catch (IOException e) {
            throw new FetchFailedException("read failed", e);
        }
    }

    private byte[] readBounded(InputStream in, long deadlineNanos) throws IOException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new FetchFailedException("deadline");
        }
        // 서버가 본문을 멈춰 세우면 read 가 블로킹되므로, 데드라인에 스트림을 닫아 끊는다.
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            try {
                in.close();
            } catch (IOException ignored) {
                // 이미 닫힘
            }
        }, remainingNanos, TimeUnit.NANOSECONDS);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > maxBytes) {
                    throw new FetchFailedException("too large");
                }
                out.write(buf, 0, n);
                if (System.nanoTime() > deadlineNanos) {
                    throw new FetchFailedException("deadline");
                }
            }
        } catch (IOException e) {
            throw new FetchFailedException(System.nanoTime() > deadlineNanos ? "deadline" : "read failed", e);
        } finally {
            watchdog.cancel(false);
        }
        return out.toByteArray();
    }

    private static void checkAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (IOException e) {
            throw new FetchFailedException("dns", e);
        }
        if (addresses.length == 0) {
            throw new FetchFailedException("dns");
        }
        for (InetAddress a : addresses) {
            if (isBlockedAddress(a)) {
                throw new FetchFailedException("blocked address");
            }
        }
    }

    /** 공인 인터넷 주소가 아니면 true (루프백·사설·링크로컬·CGNAT·멀티캐스트·0.0.0.0/8·예약·IPv6 ULA·IPv4-mapped 내부 주소). */
    public static boolean isBlockedAddress(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return blockedV4(b[0] & 0xFF, b[1] & 0xFF, b[2] & 0xFF);
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) { // fc00::/7 ULA
                return true;
            }
            if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B) { // 64:ff9b::/96 NAT64: 안의 IPv4로 판정
                boolean zero = true;
                for (int i = 4; i < 12; i++) {
                    zero &= b[i] == 0;
                }
                if (zero) {
                    return blockedV4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF);
                }
            }
            boolean zeroPrefix = true;
            for (int i = 0; i < 10; i++) {
                zeroPrefix &= b[i] == 0;
            }
            // ::ffff:a.b.c.d (IPv4-mapped) 와 ::a.b.c.d (IPv4-compatible) 는 안의 IPv4 로 판정
            if (zeroPrefix && ((b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) || (b[10] == 0 && b[11] == 0))) {
                return blockedV4(b[12] & 0xFF, b[13] & 0xFF, b[14] & 0xFF);
            }
            return false;
        }
        return true; // 알 수 없는 종류는 차단
    }

    private static boolean blockedV4(int b0, int b1, int b2) {
        return b0 == 0                                  // 0.0.0.0/8
                || b0 == 10                             // 사설
                || b0 == 127                            // 루프백
                || (b0 == 100 && (b1 & 0xC0) == 0x40)   // 100.64.0.0/10 CGNAT
                || (b0 == 169 && b1 == 254)             // 링크로컬(메타데이터 포함)
                || (b0 == 172 && (b1 & 0xF0) == 0x10)   // 172.16.0.0/12
                || (b0 == 192 && b1 == 168)             // 192.168.0.0/16
                || (b0 == 192 && b1 == 0 && b2 == 0)    // 192.0.0.0/24
                || (b0 == 198 && (b1 & 0xFE) == 18)     // 198.18.0.0/15 벤치마크
                || b0 >= 224;                           // 멀티캐스트·예약·브로드캐스트
    }
}
