package com.seatswap.websocket;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 열린 WebSocket 세션 추적. STOMP 세션 id 는 WebSocketSession id 와 같다.
 * - 핸드셰이크 직후 {@link #opened} 로 등록(미인증), CONNECT 인증 성공 시 {@link #authenticate} 로 userId·토큰 만료를 붙인다.
 * - 사용자당 동시 연결 수를 제한한다(인증된 세션만 센다). 한도를 넘으면 {@link ConnectionLimitExceededException}.
 * - 주기 정리: 토큰이 만료된 세션과 CONNECT 하지 않고 오래 둔 세션을 close 1008 로 닫는다.
 * 단일 인스턴스 메모리 기준이다(다중 인스턴스가 되면 사용자당 제한은 인스턴스별이 된다).
 */
@Slf4j
@Component
public class WebSocketSessionRegistry {

    /** 사용자당 동시 연결 한도 초과 */
    public static class ConnectionLimitExceededException extends RuntimeException {
        public ConnectionLimitExceededException(String message) {
            super(message);
        }
    }

    private static final Duration REJECT_CLOSE_DELAY = Duration.ofMillis(300);

    private static final class Entry {
        final WebSocketSession socket;
        final Instant openedAt;
        volatile Long userId;
        volatile Instant expiresAt;
        /** 이 세션의 유일한 구독 id(없으면 null). */
        String subscriptionId;

        Entry(WebSocketSession socket, Instant openedAt) {
            this.socket = socket;
            this.openedAt = openedAt;
        }
    }

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private final ThreadPoolTaskScheduler scheduler;
    private final long sweepIntervalMs;
    private final long connectTimeoutMs;
    private final int maxConnectionsPerUser;
    private ScheduledFuture<?> sweeper;

    public WebSocketSessionRegistry(Clock clock,
                                    @Qualifier("wsHeartbeatScheduler") ThreadPoolTaskScheduler scheduler,
                                    @Value("${seatswap.websocket.sweep-interval-ms:30000}") long sweepIntervalMs,
                                    @Value("${seatswap.websocket.connect-timeout-ms:10000}") long connectTimeoutMs,
                                    @Value("${seatswap.websocket.max-connections-per-user:5}") int maxConnectionsPerUser) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.sweepIntervalMs = sweepIntervalMs;
        this.connectTimeoutMs = connectTimeoutMs;
        this.maxConnectionsPerUser = maxConnectionsPerUser;
    }

    @PostConstruct
    void start() {
        if (scheduler != null && sweepIntervalMs > 0) {
            sweeper = scheduler.scheduleWithFixedDelay(this::sweep, Duration.ofMillis(sweepIntervalMs));
        }
    }

    @PreDestroy
    void stop() {
        if (sweeper != null) {
            sweeper.cancel(false);
        }
    }

    /** 핸드셰이크 직후(아직 미인증). */
    public void opened(WebSocketSession socket) {
        sessions.put(socket.getId(), new Entry(socket, clock.instant()));
    }

    public void closed(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * CONNECT 인증 성공 후 호출. 사용자당 한도를 원자적으로 확인하고 userId·만료 시각을 붙인다.
     * 등록되지 않은 세션이거나 이미 인증된 세션(CONNECT 중복)이면 IllegalStateException.
     */
    public synchronized void authenticate(String sessionId, long userId, Instant expiresAt) {
        Entry entry = sessions.get(sessionId);
        if (entry == null) {
            throw new IllegalStateException("unknown session");
        }
        if (entry.userId != null) {
            throw new IllegalStateException("already connected");
        }
        long count = sessions.values().stream().filter(e -> e.userId != null && e.userId == userId).count();
        if (count >= maxConnectionsPerUser) {
            throw new ConnectionLimitExceededException("connection limit exceeded");
        }
        entry.expiresAt = expiresAt;
        entry.userId = userId;
    }

    /**
     * 세션당 구독은 1개다. 이미 구독 중이면 false(호출자가 거부), 아니면 subscriptionId 를 기록하고 true.
     * 같은 사용자 큐를 한 세션이 여러 번 구독해 푸시가 중복·증폭되는 것을 막는다.
     */
    public boolean registerSubscription(String sessionId, String subscriptionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || subscriptionId == null) {
            return false;
        }
        synchronized (entry) {
            if (entry.subscriptionId != null) {
                return false;
            }
            entry.subscriptionId = subscriptionId;
            return true;
        }
    }

    /** UNSUBSCRIBE: 기록된 구독 id 와 일치할 때만 비운다(다른 id 의 UNSUBSCRIBE 로는 비워지지 않는다). */
    public void clearSubscription(String sessionId, String subscriptionId) {
        Entry entry = sessions.get(sessionId);
        if (entry == null || subscriptionId == null) {
            return;
        }
        synchronized (entry) {
            if (subscriptionId.equals(entry.subscriptionId)) {
                entry.subscriptionId = null;
            }
        }
    }

    /** 인증됐고 토큰이 만료되지 않은 세션인가. */
    public boolean isActive(String sessionId) {
        Entry entry = sessions.get(sessionId);
        return entry != null && entry.userId != null && entry.expiresAt.isAfter(clock.instant());
    }

    /** 세션에 붙은 사용자 id(미인증이면 null). */
    public Long userIdOf(String sessionId) {
        Entry entry = sessions.get(sessionId);
        return entry == null ? null : entry.userId;
    }

    /** 사용자의 인증된 연결 수(테스트·진단용). */
    public long connectionCount(long userId) {
        return sessions.values().stream().filter(e -> e.userId != null && e.userId == userId).count();
    }

    /** 즉시 close 1008. */
    public void closePolicyViolation(String sessionId) {
        Entry entry = sessions.remove(sessionId);
        if (entry != null) {
            closeQuietly(entry.socket, "policy violation");
        }
    }

    /** ERROR 프레임이 먼저 나가도록 잠깐 뒤에 close 1008. 스케줄러가 없으면 즉시. */
    public void closePolicyViolationSoon(String sessionId) {
        if (sessionId == null) {
            return;
        }
        if (scheduler == null) {
            closePolicyViolation(sessionId);
            return;
        }
        scheduler.schedule(() -> closePolicyViolation(sessionId), clock.instant().plus(REJECT_CLOSE_DELAY));
    }

    /** 만료·미인증 방치 세션 정리(스케줄러가 주기 호출). */
    public void sweep() {
        Instant now = clock.instant();
        for (Map.Entry<String, Entry> e : sessions.entrySet()) {
            Entry entry = e.getValue();
            boolean expired = entry.userId != null && !entry.expiresAt.isAfter(now);
            boolean neverConnected = entry.userId == null && now.isAfter(entry.openedAt.plusMillis(connectTimeoutMs));
            if ((expired || neverConnected) && sessions.remove(e.getKey(), entry)) {
                closeQuietly(entry.socket, expired ? "token expired" : "connect timeout");
            }
        }
    }

    private void closeQuietly(WebSocketSession socket, String reason) {
        try {
            socket.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
        } catch (Exception ex) {
            log.debug("websocket close failed: {}", ex.toString());
        }
    }
}
