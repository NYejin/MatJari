package com.matjari.service.aiextract;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * AI 호출 제한: 사용자별 하루(KST) 상한, 전체 하루 상한, 동시 실행 수(대기 없이 초과 시 거절).
 * 메모리 기반 단일 인스턴스 구조라 서버 재시작 때 횟수가 초기화된다. 캐시 적중·AI 호출이 없는 경우는 호출하지 않는다.
 * 호출을 허락받으면(거절되지 않으면) 그 시점에 횟수 1을 센다(호출이 실패해도 돌려주지 않는다).
 */
@Component
public class AiUsageLimiter {

    /** 허락 한 건. close()로 동시 실행 자리를 반납한다. */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    private final Clock clock;
    private final AiProperties properties;
    private final Semaphore concurrency;
    private final Map<Long, int[]> perUser = new HashMap<>();
    private LocalDate day;
    private int global;

    public AiUsageLimiter(Clock clock, AiProperties properties) {
        this.clock = clock;
        this.properties = properties;
        this.concurrency = new Semaphore(Math.max(1, properties.getMaxConcurrent()));
    }

    /** 허락하면 Permit, 아니면 429 AI_RATE_LIMITED 예외. */
    public Permit acquire(Long userId) {
        if (!concurrency.tryAcquire()) {
            throw DetailExtractionService.rateLimited(10);
        }
        boolean ok = false;
        try {
            synchronized (this) {
                ZonedDateTime now = ZonedDateTime.now(clock);
                LocalDate today = now.toLocalDate();
                if (!today.equals(day)) {
                    day = today;
                    global = 0;
                    perUser.clear();
                }
                long untilMidnight = Math.max(1, Duration.between(now, today.plusDays(1).atStartOfDay(now.getZone())).toSeconds());
                int[] used = perUser.computeIfAbsent(userId, k -> new int[1]);
                if (used[0] >= properties.getDailyPerUser() || global >= properties.getDailyGlobal()) {
                    throw DetailExtractionService.rateLimited(untilMidnight);
                }
                used[0]++;
                global++;
            }
            ok = true;
        } finally {
            if (!ok) {
                concurrency.release();
            }
        }
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                concurrency.release();
            }
        };
    }
}
