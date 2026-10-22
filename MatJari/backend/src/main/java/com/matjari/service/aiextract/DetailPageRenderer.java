package com.matjari.service.aiextract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 티켓링크 상품 페이지는 공개 HTML 이 빈 껍데기이고 상세 설명·상세 이미지·회차가 화면이 뜬 뒤 채워진다. 그래서 별도 renderer 컨테이너의
 * 헤드리스 크롬(DevTools 프로토콜)으로 페이지를 끝까지 띄운 뒤, 사람이 브라우저로 볼 때와 같은 화면 글자(innerText)와 이미지 주소를 가져온다
 * (사용자 지시 2026-10-22). 사이트 내부 API 를 직접 부르지 않는다.
 *
 * 안전: 여는 주소는 고정 템플릿(https://www.ticketlink.co.kr/product/{숫자 id})뿐이고, 최종 주소의 호스트가 www.ticketlink.co.kr 일 때만 쓴다.
 * User-Agent 는 다른 요청과 같은 MatJariBot(사람 브라우저로 위장하지 않음, 봇 차단을 우회하지 않음). 동시에 한 페이지만, 탭은 항상 닫는다.
 * 실패는 모두 null(링크 HTML 만으로 진행). renderer 는 백엔드와만 닿는 내부 네트워크에 두고 포트를 열지 않는다(compose).
 * 크롬은 Host 헤더가 IP·localhost 가 아니면 DevTools 요청을 거절하므로 서비스 이름을 IP 로 바꿔 접속한다.
 * (YES24 는 봇 차단(STCLab)이 자동 브라우저를 막아 쓰지 않는다, 2026-10-22 시험.)
 */
@Component
public class DetailPageRenderer {

    private static final Logger log = LoggerFactory.getLogger(DetailPageRenderer.class);
    private static final String USER_AGENT = "MatJariBot/0.1 (+portfolio project; contact: kidjin1016@gmail.com)";
    private static final Pattern ID = Pattern.compile("\\d{1,20}");
    private static final Pattern TARGET_ID = Pattern.compile("[A-Za-z0-9]{16,64}");
    private static final int MAX_TEXT_CHARS = 60_000;
    private static final long POLL_MS = 500;
    /** 화면이 다 채워졌으면 {text, imgs}, 아직이면 null. 티켓링크가 아니면(첫 화면으로 넘어간 지난 공연 등) "OFF". */
    private static final String READ = "(() => { if (location.protocol === 'about:' || document.readyState === 'loading') return null;"
            + " if (location.hostname !== 'www.ticketlink.co.kr' || !location.pathname.startsWith('/product/')) return 'OFF';"
            + " const t = document.body ? document.body.innerText : '';"
            + " const loaded = [...document.images].filter(i => i.complete && i.naturalHeight > 0);"
            // 세로로 아주 긴 이미지(상세 이미지)만 넘긴다: 높이가 폭의 2배 이상이고 2000px 이상
            + " const imgs = loaded.filter(i => i.naturalHeight >= 2000 && i.naturalHeight >= 2 * i.naturalWidth).map(i => i.currentSrc || i.src);"
            + " const ready = t.includes('회차') || t.includes('상품 상세') || loaded.length > 6;"
            + " return ready ? JSON.stringify({ text: t.slice(0, " + MAX_TEXT_CHARS + "), imgs: imgs.slice(0, 60) }) : null; })()";

    /** 그린 화면의 글자와, 화면의 이미지 주소만 담은 문서(DetailImageFetcher 가 같은 규칙으로 고른다). */
    public record Rendered(String text, Document document) {}

    private final AiProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Semaphore slot = new Semaphore(1);

    @Autowired
    public DetailPageRenderer(AiProperties properties) {
        this.properties = properties;
    }

    public boolean available() {
        return properties.getRendererUrl() != null && !properties.getRendererUrl().isBlank();
    }

    public Rendered renderTicketlink(String id) {
        if (!available() || id == null || !ID.matcher(id).matches()) {
            return null;
        }
        boolean acquired = false;
        String targetId = null;
        URI base = null;
        Cdp cdp = null;
        long started = System.nanoTime();
        try {
            acquired = slot.tryAcquire(3, TimeUnit.SECONDS);
            if (!acquired) {
                log.info("티켓링크 화면 그리기 건너뜀: 다른 요청 처리 중");
                return null;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getRenderTimeoutMs());
            base = baseUri();
            HttpResponse<String> created = http.send(HttpRequest.newBuilder(base.resolve("/json/new?about:blank"))
                    .timeout(Duration.ofSeconds(3)).PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            if (created.statusCode() != 200) {
                log.info("티켓링크 화면 그리기 실패: 탭 열기 {}", created.statusCode());
                return null;
            }
            targetId = mapper.readTree(created.body()).path("id").asText("");
            if (!TARGET_ID.matcher(targetId).matches()) {
                targetId = null;
                return null;
            }
            cdp = Cdp.open(http, URI.create("ws://" + base.getRawAuthority() + "/devtools/page/" + targetId), mapper, deadline);
            cdp.call("Network.setUserAgentOverride", Map.of("userAgent", USER_AGENT), deadline);
            cdp.call("Emulation.setDeviceMetricsOverride", Map.of("width", 1280, "height", 2000, "deviceScaleFactor", 1, "mobile", false), deadline);
            cdp.call("Page.navigate", Map.of("url", "https://www.ticketlink.co.kr/product/" + id), deadline);
            Thread.sleep(1500);
            while (System.nanoTime() < deadline) {
                JsonNode value = cdp.call("Runtime.evaluate", Map.of("expression", READ, "returnByValue", true), deadline)
                        .path("result").path("value");
                if (value.isTextual()) {
                    if ("OFF".equals(value.asText())) {
                        log.info("티켓링크 화면 그리기: 상품 페이지가 아님(지난 공연 등)");
                        return null;
                    }
                    JsonNode data = mapper.readTree(value.asText());
                    Thread.sleep(1500); // 아래쪽 상세 이미지가 마저 뜰 시간
                    JsonNode again = cdp.call("Runtime.evaluate", Map.of("expression", READ, "returnByValue", true), deadline)
                            .path("result").path("value");
                    if (again.isTextual() && !"OFF".equals(again.asText())) {
                        data = mapper.readTree(again.asText());
                    }
                    StringBuilder html = new StringBuilder("<html><body>");
                    for (JsonNode src : data.path("imgs")) {
                        html.append("<img src=\"").append(org.jsoup.nodes.Entities.escape(src.asText())).append("\">");
                    }
                    html.append("</body></html>");
                    String text = data.path("text").asText("");
                    log.info("티켓링크 화면 그리기 완료: {}ms, 글자 {}자, 이미지 {}장",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), text.length(), data.path("imgs").size());
                    return new Rendered(text, Jsoup.parse(html.toString(), "https://www.ticketlink.co.kr/"));
                }
                Thread.sleep(POLL_MS);
            }
            log.info("티켓링크 화면 그리기 시간 초과");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.info("티켓링크 화면 그리기 실패: {}", e.getClass().getSimpleName());
            return null;
        } finally {
            if (cdp != null) {
                cdp.close();
            }
            if (targetId != null && base != null) {
                try {
                    http.send(HttpRequest.newBuilder(base.resolve("/json/close/" + targetId)).timeout(Duration.ofSeconds(3))
                            .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                } catch (Exception e) {
                    log.debug("renderer 탭 닫기 실패: {}", e.getClass().getSimpleName());
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (acquired) {
                slot.release();
            }
        }
    }

    /** http://서비스이름:포트 → http://IP:포트 (크롬 DevTools 의 Host 헤더 검사). */
    private URI baseUri() throws Exception {
        URI configured = URI.create(properties.getRendererUrl().trim());
        if (!"http".equals(configured.getScheme()) || configured.getHost() == null || configured.getPort() <= 0) {
            throw new IllegalStateException("renderer-url");
        }
        String host = InetAddress.getByName(configured.getHost()).getHostAddress();
        if (host.contains(":")) {
            host = "[" + host + "]";
        }
        return URI.create("http://" + host + ":" + configured.getPort());
    }

    /** DevTools 웹소켓 한 개: id 로 요청과 응답을 짝짓는다(이벤트는 버린다). */
    static final class Cdp implements WebSocket.Listener {
        private final ObjectMapper mapper;
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
        private final AtomicInteger nextId = new AtomicInteger();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket socket;

        private Cdp(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        static Cdp open(HttpClient http, URI ws, ObjectMapper mapper, long deadlineNanos) throws Exception {
            Cdp cdp = new Cdp(mapper);
            cdp.socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).buildAsync(ws, cdp)
                    .get(remaining(deadlineNanos), TimeUnit.NANOSECONDS);
            return cdp;
        }

        JsonNode call(String method, Map<String, Object> params, long deadlineNanos) throws Exception {
            int id = nextId.incrementAndGet();
            CompletableFuture<JsonNode> future = new CompletableFuture<>();
            pending.put(id, future);
            ObjectNode msg = mapper.createObjectNode().put("id", id).put("method", method);
            msg.set("params", mapper.valueToTree(params));
            socket.sendText(mapper.writeValueAsString(msg), true).get(remaining(deadlineNanos), TimeUnit.NANOSECONDS);
            JsonNode reply = future.get(remaining(deadlineNanos), TimeUnit.NANOSECONDS);
            if (reply.has("error")) {
                throw new IllegalStateException("cdp error");
            }
            return reply.path("result");
        }

        private static long remaining(long deadlineNanos) {
            return Math.max(1, deadlineNanos - System.nanoTime());
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (partial.length() > MAX_TEXT_CHARS * 6) { // 비정상적으로 큰 메시지는 끊는다
                partial.setLength(0);
                webSocket.abort();
                return null;
            }
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    JsonNode node = mapper.readTree(text);
                    if (node.has("id")) {
                        CompletableFuture<JsonNode> f = pending.remove(node.get("id").asInt());
                        if (f != null) {
                            f.complete(node);
                        }
                    }
                } catch (Exception ignored) {
                    // 형식이 깨진 메시지는 버린다
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            pending.values().forEach(f -> f.completeExceptionally(error));
        }

        void close() {
            if (socket != null) {
                socket.abort();
            }
        }
    }
}
