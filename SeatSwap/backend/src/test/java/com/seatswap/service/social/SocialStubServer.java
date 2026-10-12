package com.seatswap.service.social;

import com.seatswap.domain.SocialProvider;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/** 제공자 token/userinfo 흉내용 JDK HttpServer(무작위 포트). 경로: /token, /userinfo, /redirect(3xx), /redirected-target. */
public final class SocialStubServer implements AutoCloseable {

    /** 응답 설정: 상태, 본문, 지연(ms), 추가 헤더. */
    public record Reply(int status, String body, long delayMs, Map<String, String> headers) {
        public static Reply json(String body) {
            return new Reply(200, body, 0, Map.of("Content-Type", "application/json"));
        }

        public static Reply status(int status, String body) {
            return new Reply(status, body, 0, Map.of("Content-Type", "application/json"));
        }

        public Reply delayed(long ms) {
            return new Reply(status, body, ms, headers);
        }
    }

    /** 받은 요청 기록. */
    public record Received(String method, String path, String body, String authorization) {
    }

    private final HttpServer server;
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();

    public SocialStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "social-stub");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestMethod(), path, body, exchange.getRequestHeaders().getFirst("Authorization")));
            Reply reply = replies.getOrDefault(path, Reply.status(404, "{}"));
            try {
                if (reply.delayMs() > 0) {
                    Thread.sleep(reply.delayMs());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                } catch (IOException ignored) {
                    // 클라이언트가 먼저 끊음
                }
            }
            exchange.close();
        });
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void on(String path, Reply reply) {
        replies.put(path, reply);
    }

    public List<Received> received() {
        return received;
    }

    public long countTo(String path) {
        return received.stream().filter(r -> r.path().equals(path)).count();
    }

    public void reset() {
        replies.clear();
        received.clear();
    }

    /** 이 스텁을 가리키도록 제공자 설정을 채운다(client-id 가 있으면 활성). */
    public void point(SocialProperties.Provider cfg, String clientId, String clientSecret) {
        cfg.setClientId(clientId);
        cfg.setClientSecret(clientSecret);
        cfg.setAuthorizeUri(baseUrl() + "/authorize");
        cfg.setTokenUri(baseUrl() + "/token");
        cfg.setUserinfoUri(baseUrl() + "/userinfo");
    }

    /** 제공자별 userinfo 정상 응답 본문. */
    public static String userinfo(SocialProvider provider, String id, String email, boolean emailVerified, String nickname) {
        return switch (provider) {
            case GOOGLE -> "{\"sub\":\"" + id + "\"" + (email == null ? "" : ",\"email\":\"" + email + "\",\"email_verified\":" + emailVerified)
                    + (nickname == null ? "" : ",\"name\":\"" + nickname + "\"") + "}";
            case KAKAO -> "{\"id\":" + id + ",\"kakao_account\":{" + (email == null ? "" : "\"email\":\"" + email + "\",\"is_email_valid\":true,\"is_email_verified\":" + emailVerified + ",")
                    + "\"profile\":{\"nickname\":\"" + (nickname == null ? "" : nickname) + "\"}}}";
            case NAVER -> "{\"resultcode\":\"00\",\"response\":{\"id\":\"" + id + "\"" + (email == null ? "" : ",\"email\":\"" + email + "\"")
                    + (nickname == null ? "" : ",\"nickname\":\"" + nickname + "\"") + "}}";
        };
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
