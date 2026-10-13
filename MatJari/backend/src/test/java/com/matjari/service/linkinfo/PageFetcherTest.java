package com.matjari.service.linkinfo;

import com.sun.net.httpserver.HttpServer;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 로컬 HttpServer(루프백)만 사용 — 외부 인터넷 접속 없음. 완화 모드 생성자로 http·루프백을 허용해 시험한다. */
class PageFetcherTest {

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI url(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void respond(String path, int status, String contentType, byte[] body, String location) {
        server.createContext(path, ex -> {
            if (contentType != null) {
                ex.getResponseHeaders().add("Content-Type", contentType);
            }
            if (location != null) {
                ex.getResponseHeaders().add("Location", location);
            }
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
            ex.close();
        });
    }

    private PageFetcher relaxed(long requestTimeoutMs, long maxBytes) {
        return new PageFetcher(2000, requestTimeoutMs, maxBytes, true);
    }

    @Test
    void readsHtmlWithDeclaredCharset() {
        respond("/ok", 200, "text/html; charset=UTF-8", "<html><body><p id=a>한글 제목</p></body></html>"
                .getBytes(StandardCharsets.UTF_8), null);
        Document doc = relaxed(3000, 1 << 20).fetch(url("/ok"));
        assertEquals("한글 제목", doc.selectFirst("#a").text());
    }

    @Test
    void redirectIsNotFollowed() {
        respond("/redir", 302, "text/html", new byte[0], "http://127.0.0.1:1/secret");
        assertThrows(PageFetcher.FetchFailedException.class, () -> relaxed(3000, 1 << 20).fetch(url("/redir")));
    }

    @Test
    void errorStatusFails() {
        respond("/err", 500, "text/html", "x".getBytes(StandardCharsets.UTF_8), null);
        assertThrows(PageFetcher.FetchFailedException.class, () -> relaxed(3000, 1 << 20).fetch(url("/err")));
    }

    @Test
    void wrongContentTypeFails() {
        respond("/json", 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8), null);
        assertThrows(PageFetcher.FetchFailedException.class, () -> relaxed(3000, 1 << 20).fetch(url("/json")));
    }

    @Test
    void oversizedBodyFails() {
        respond("/big", 200, "text/html", "a".repeat(5000).getBytes(StandardCharsets.UTF_8), null);
        assertThrows(PageFetcher.FetchFailedException.class, () -> relaxed(3000, 1000).fetch(url("/big")));
    }

    @Test
    void slowResponseTimesOut() {
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.close();
        });
        assertThrows(PageFetcher.FetchFailedException.class, () -> relaxed(400, 1 << 20).fetch(url("/slow")));
    }

    @Test
    void stalledBodyIsCutAtDeadline() {
        server.createContext("/stall", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/html");
            ex.sendResponseHeaders(200, 0); // chunked
            try {
                OutputStream os = ex.getResponseBody();
                os.write("<html>".getBytes(StandardCharsets.UTF_8));
                os.flush();
                Thread.sleep(3000);
            } catch (Exception ignored) {
                // 클라이언트가 끊음
            }
            ex.close();
        });
        long start = System.nanoTime();
        assertThrows(PageFetcher.FetchFailedException.class, () -> relaxed(600, 1 << 20).fetch(url("/stall")));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2500, "데드라인 안에 끊겨야 한다");
    }

    @Test
    void productionModeRejectsUserSuppliedTargets() {
        PageFetcher strict = new PageFetcher(1000, 1000, 1 << 20);
        for (String u : new String[]{
                "http://ticket.yes24.com/Perf/1",              // http
                "https://127.0.0.1/Perf/1",                    // 허용 목록 밖
                "https://evil.example.com/Perf/1",
                "https://ticket.yes24.com:8443/Perf/1",        // 포트
                "https://user:pw@ticket.yes24.com/Perf/1"}) { // userinfo
            assertThrows(PageFetcher.FetchFailedException.class, () -> strict.fetch(URI.create(u)), u);
        }
    }

    @Test
    void blockedAddressClassification() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1",
                "169.254.169.254", "100.64.0.1", "100.127.255.255", "0.0.0.0", "0.1.2.3", "224.0.0.1",
                "255.255.255.255", "::1", "::", "fc00::1", "fd12:3456::1", "fe80::1", "::ffff:10.0.0.1",
                "::ffff:127.0.0.1", "::ffff:169.254.169.254", "ff02::1"}) {
            assertTrue(PageFetcher.isBlockedAddress(InetAddress.getByName(ip)), ip);
        }
        for (String ip : new String[]{"8.8.8.8", "172.32.0.1", "100.128.0.1", "1.1.1.1", "::ffff:8.8.8.8",
                "2606:4700:4700::1111"}) {
            assertFalse(PageFetcher.isBlockedAddress(InetAddress.getByName(ip)), ip);
        }
    }
}
