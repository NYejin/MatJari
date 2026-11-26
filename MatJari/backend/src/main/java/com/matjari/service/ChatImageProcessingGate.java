package com.matjari.service;

import com.matjari.exception.MatJariException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 채팅 사진 디코드·재인코딩(CPU·메모리가 큰 일)의 입구. 두 가지로 서버 메모리 고갈을 막는다.
 * <ol>
 *   <li><b>처리 시도 자체를 센다</b>: 사용자당 1분 슬라이딩 윈도에 {@code image-process-per-minute}(기본 10)회. 검증에 실패하거나 깨진 사진도 시도로 센다
 *       (하루 전송 상한 {@link ChatImageRateLimiter}은 새 INSERT 직전에만 소모하므로 실패 시도를 막지 못한다). 초과 429 CHAT_IMAGE_RATE_LIMITED + Retry-After.</li>
 *   <li><b>전역 동시 처리 수</b>: 세마포어 {@code image-process-concurrency}(기본 2). 자리가 없으면 기다리지 않고 바로 503 SERVER_BUSY + Retry-After(3초).</li>
 * </ol>
 * 세마포어는 try/finally 로 반드시 풀린다(처리 중 어떤 예외든). 메모리 카운터라 서버 한 대 기준이고 재시작하면 초기화된다.
 */
@Component
public class ChatImageProcessingGate {

    static final long WINDOW_MILLIS = 60_000L;
    static final long BUSY_RETRY_SECONDS = 3;
    private static final int SWEEP_MIN_SIZE = 1_000;

    /** 503 SERVER_BUSY: 동시 사진 처리 한도에 닿았다. 응답 {code, message, retryAfterSeconds} 와 Retry-After 는 GlobalExceptionHandler 가 만든다. */
    public static class ChatImageBusyException extends MatJariException {
        private final long retryAfterSeconds;

        public ChatImageBusyException(long retryAfterSeconds) {
            super("지금 사진을 처리하는 요청이 많아요. 잠시 후 다시 시도해주세요.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getCode() {
            return "SERVER_BUSY";
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private static final class Window {
        final ArrayDeque<Long> hits = new ArrayDeque<>();
        long lastMillis;
    }

    private final Clock clock;
    private final int perMinute;
    private final Semaphore permits;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    public ChatImageProcessingGate(Clock clock,
                                   @Value("${matjari.chat.image-process-per-minute:10}") int perMinute,
                                   @Value("${matjari.chat.image-process-concurrency:2}") int concurrency) {
        this.clock = clock;
        this.perMinute = Math.max(1, perMinute);
        this.permits = new Semaphore(Math.max(1, concurrency));
    }

    /** 시도 1회를 세고 동시 처리 자리를 잡아 {@code work} 를 실행한다. 한도를 넘으면 work 는 실행되지 않는다. */
    public <T> T run(Long userId, Supplier<T> work) {
        countAttempt(userId);
        return runWithPermit(work);
    }

    /**
     * 사용자별 시도 수는 세지 않고 전역 동시 처리 자리만 잡아 {@code work} 를 실행한다(티켓·희망 조건 사진도 같은 세마포어를 공유한다, V33).
     * 자리가 없으면 기다리지 않고 바로 503 SERVER_BUSY.
     */
    public <T> T runWithPermit(Supplier<T> work) {
        if (!permits.tryAcquire()) {
            throw new ChatImageBusyException(BUSY_RETRY_SECONDS);
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    private void countAttempt(Long userId) {
        long now = clock.millis();
        if (windows.size() >= SWEEP_MIN_SIZE) {
            windows.entrySet().removeIf(e -> now - e.getValue().lastMillis > WINDOW_MILLIS);
        }
        Window w = windows.computeIfAbsent(userId, k -> new Window());
        synchronized (w) {
            while (!w.hits.isEmpty() && now - w.hits.peekFirst() >= WINDOW_MILLIS) {
                w.hits.pollFirst();
            }
            if (w.hits.size() >= perMinute) {
                long wait = w.hits.peekFirst() + WINDOW_MILLIS - now;
                throw new ChatImageRateLimiter.ChatImageRateLimitedException(
                        "사진을 너무 자주 보내고 있어요. 잠시 후 다시 시도해주세요.", Math.max(1, (wait + 999) / 1000));
            }
            w.hits.addLast(now);
            w.lastMillis = now;
        }
    }

    /** 테스트용: 지금 비어 있는 동시 처리 자리 수. */
    int availablePermits() {
        return permits.availablePermits();
    }
}
