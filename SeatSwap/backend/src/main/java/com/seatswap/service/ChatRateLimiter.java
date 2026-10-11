package com.seatswap.service;

import com.seatswap.exception.SeatSwapException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채팅 전송 속도 제한: 사용자별 메모리 슬라이딩 윈도(최근 5초에 5건, 최근 60초에 60건)를 둘 다 통과해야 한다. 서버 한 대 기준이며 재시작하면 초기화된다.
 * 처음에는 토큰 버킷(용량 5 / 5초, 용량 60 / 60초)을 생각했지만 두 버킷의 지속 속도가 모두 초당 1개라 1분 한도가 사실상 한 번도 걸리지 않는다
 * (5초마다 5건씩 꾸준히 보내면 짧은 버킷만 쓰이고 1분 합계가 65건까지 갈 수 있다). 그래서 허용한 전송 시각을 최대 60개 보관하는
 * 슬라이딩 윈도로 "아무 5초에 5건, 아무 60초에 60건"을 정확히 지킨다. 한도를 넘은 시도는 기록하지 않는다(거절이 대기 시간을 늘리지 않는다).
 * 시각은 주입된 Clock 의 millis 만 쓴다. 오래 쉰 사용자 항목은 가끔 쓸어 낸다(메모리 누수 방지).
 */
@Component
public class ChatRateLimiter {

    public static final int SHORT_CAPACITY = 5;
    public static final long SHORT_WINDOW_MILLIS = 5_000;
    public static final int LONG_CAPACITY = 60;
    public static final long LONG_WINDOW_MILLIS = 60_000;
    static final String CODE = "CHAT_RATE_LIMITED";
    static final String MESSAGE = "메시지를 너무 빨리 보내고 있어요. 잠시 후 다시 시도해주세요.";

    private static final long SWEEP_INTERVAL_MILLIS = 60_000;
    private static final long IDLE_EVICT_MILLIS = 2 * LONG_WINDOW_MILLIS;
    private static final int SWEEP_MIN_SIZE = 1_000;

    /** 429 CHAT_RATE_LIMITED. 응답 {code, message, retryAfterSeconds} 와 Retry-After 헤더는 GlobalExceptionHandler 가 만든다. */
    public static class ChatRateLimitedException extends SeatSwapException {
        private final long retryAfterSeconds;

        public ChatRateLimitedException(long retryAfterSeconds) {
            super(MESSAGE);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getCode() {
            return CODE;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** 사용자 한 명의 최근 허용 전송 시각(오래된 것부터, 최대 LONG_CAPACITY 개). */
    private static final class Window {
        final java.util.ArrayDeque<Long> sent = new java.util.ArrayDeque<>();
        long lastMillis;
    }

    private final Clock clock;
    private final ConcurrentHashMap<Long, Window> users = new ConcurrentHashMap<>();
    private volatile long lastSweepMillis;

    public ChatRateLimiter(Clock clock) {
        this.clock = clock;
        this.lastSweepMillis = clock.millis();
    }

    /** 전송 1건을 시도한다. 한도를 넘으면 ChatRateLimitedException(429, retryAfterSeconds = 다음 전송이 가능해질 때까지). */
    public void acquire(Long userId) {
        long now = clock.millis();
        sweepIfNeeded(now);
        Window w = users.computeIfAbsent(userId, k -> new Window());
        synchronized (w) {
            while (!w.sent.isEmpty() && now - w.sent.peekFirst() >= LONG_WINDOW_MILLIS) {
                w.sent.pollFirst();
            }
            long waitMillis = 0;
            if (w.sent.size() >= LONG_CAPACITY) {
                waitMillis = w.sent.peekFirst() + LONG_WINDOW_MILLIS - now;
            }
            long inShort = w.sent.stream().filter(t -> now - t < SHORT_WINDOW_MILLIS).count();
            if (inShort >= SHORT_CAPACITY) {
                // 최근 5초 안의 기록 중 (inShort - SHORT_CAPACITY + 1)번째로 오래된 것이 창을 벗어나야 한다
                long idx = inShort - SHORT_CAPACITY;
                long boundary = w.sent.stream().filter(t -> now - t < SHORT_WINDOW_MILLIS).skip(idx).findFirst().orElse(now);
                waitMillis = Math.max(waitMillis, boundary + SHORT_WINDOW_MILLIS - now);
            }
            if (waitMillis > 0) {
                throw new ChatRateLimitedException(Math.max(1, (waitMillis + 999) / 1000));
            }
            w.sent.addLast(now);
            w.lastMillis = now;
        }
    }

    private void sweepIfNeeded(long now) {
        if (users.size() < SWEEP_MIN_SIZE || now - lastSweepMillis < SWEEP_INTERVAL_MILLIS) {
            return;
        }
        lastSweepMillis = now;
        users.entrySet().removeIf(e -> now - e.getValue().lastMillis > IDLE_EVICT_MILLIS);
    }
}
