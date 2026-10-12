package com.seatswap.service.social;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 소셜 로그인 authorize/exchange 의 IP당 속도 제한: 메모리 슬라이딩 윈도(60초에 N건, 기본 30). 서버 한 대 기준이며 재시작하면 초기화된다.
 * 키는 request.getRemoteAddr() 이다(X-Forwarded-For 는 위조할 수 있어 신뢰하지 않는다 — 프록시 뒤에서는 프록시 주소로 합쳐지는 한계).
 * 한도를 넘은 시도는 기록하지 않는다. 오래 쉰 키는 가끔 쓸어 낸다.
 */
@Component
public class SocialRateLimiter {

    private static final long WINDOW_MILLIS = 60_000;
    private static final long SWEEP_INTERVAL_MILLIS = 60_000;
    private static final int SWEEP_MIN_SIZE = 1_000;

    private static final class Window {
        final ArrayDeque<Long> hits = new ArrayDeque<>();
        long lastMillis;
    }

    private final Clock clock;
    private final SocialProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long lastSweepMillis;

    public SocialRateLimiter(Clock clock, SocialProperties properties) {
        this.clock = clock;
        this.properties = properties;
        this.lastSweepMillis = clock.millis();
    }

    /** 1건을 시도한다. 한도를 넘으면 429 SOCIAL_RATE_LIMITED. */
    public void acquire(String key) {
        acquire(key, properties.getIpPerMinute());
    }

    /** 키별 한도를 직접 정해 1건을 시도한다(계정 연결 시작처럼 사용자 단위 버킷에 쓴다). */
    public void acquire(String key, int limitPerMinute) {
        long now = clock.millis();
        sweepIfNeeded(now);
        Window w = windows.computeIfAbsent(key == null ? "" : key, k -> new Window());
        synchronized (w) {
            while (!w.hits.isEmpty() && now - w.hits.peekFirst() >= WINDOW_MILLIS) {
                w.hits.pollFirst();
            }
            if (w.hits.size() >= limitPerMinute) {
                long wait = w.hits.peekFirst() + WINDOW_MILLIS - now;
                throw SocialAuthException.rateLimited(Math.max(1, (wait + 999) / 1000));
            }
            w.hits.addLast(now);
            w.lastMillis = now;
        }
    }

    private void sweepIfNeeded(long now) {
        if (windows.size() < SWEEP_MIN_SIZE || now - lastSweepMillis < SWEEP_INTERVAL_MILLIS) {
            return;
        }
        lastSweepMillis = now;
        windows.entrySet().removeIf(e -> now - e.getValue().lastMillis > 2 * WINDOW_MILLIS);
    }
}
