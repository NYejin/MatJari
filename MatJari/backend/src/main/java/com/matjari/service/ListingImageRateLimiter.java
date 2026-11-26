package com.matjari.service;

import com.matjari.exception.MatJariException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 티켓·희망 조건 사진 올리기 속도 제한 (V33). 채팅 사진({@link ChatImageProcessingGate}, {@link ChatImageRateLimiter})과 같은 방식이지만 세는 칸이 따로다.
 * <ul>
 *   <li>{@link #countAttempt}: 처리 시도(검증 실패 포함)를 사용자당 1분 슬라이딩 윈도 {@code process-per-minute}(기본 10)회로 센다.</li>
 *   <li>{@link #acquireDaily}: 새 INSERT 직전에만 사용자당 KST 하루 {@code daily-limit}(기본 30)장을 소모한다(실패·롤백은 돌려주지 않는다).</li>
 * </ul>
 * 둘 다 초과하면 429 LISTING_IMAGE_RATE_LIMITED + Retry-After. 메모리 카운터라 서버 한 대 기준이고 재시작하면 초기화된다.
 * 전체 동시 처리 수(503 SERVER_BUSY)는 채팅 사진과 같은 세마포어({@link ChatImageProcessingGate#runWithPermit})를 쓴다.
 */
@Component
public class ListingImageRateLimiter {

    static final String CODE = "LISTING_IMAGE_RATE_LIMITED";
    static final long WINDOW_MILLIS = 60_000L;
    private static final int SWEEP_MIN_SIZE = 1_000;

    /** 429 LISTING_IMAGE_RATE_LIMITED. 응답 {code, message, retryAfterSeconds} 와 Retry-After 헤더는 GlobalExceptionHandler 가 만든다. */
    public static class ListingImageRateLimitedException extends MatJariException {
        private final long retryAfterSeconds;

        public ListingImageRateLimitedException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getCode() {
            return CODE;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private static final class Window {
        final ArrayDeque<Long> hits = new ArrayDeque<>();
        long lastMillis;
    }

    private record Day(LocalDate date, int count) {}

    private final Clock clock;
    private final int perMinute;
    private final int dailyLimit;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Day> days = new ConcurrentHashMap<>();

    public ListingImageRateLimiter(Clock clock,
                                   @Value("${matjari.listing-image.process-per-minute:10}") int perMinute,
                                   @Value("${matjari.listing-image.daily-limit:30}") int dailyLimit) {
        this.clock = clock;
        this.perMinute = Math.max(1, perMinute);
        this.dailyLimit = Math.max(1, dailyLimit);
    }

    /** 처리 시도 1회를 센다. 1분 안에 {@code perMinute}회를 넘으면 429. */
    public void countAttempt(Long userId) {
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
                throw new ListingImageRateLimitedException(
                        "사진을 너무 자주 올리고 있어요. 잠시 후 다시 시도해주세요.", Math.max(1, (wait + 999) / 1000));
            }
            w.hits.addLast(now);
            w.lastMillis = now;
        }
    }

    /** 새 사진 1장을 소모한다. 하루 상한을 넘으면 429. */
    public void acquireDaily(Long userId) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (days.size() >= SWEEP_MIN_SIZE) {
            days.entrySet().removeIf(e -> e.getValue().date().isBefore(today));
        }
        boolean[] allowed = {true};
        days.compute(userId, (k, v) -> {
            int used = v != null && v.date().equals(today) ? v.count() : 0;
            if (used >= dailyLimit) {
                allowed[0] = false;
                return new Day(today, used);
            }
            return new Day(today, used + 1);
        });
        if (!allowed[0]) {
            long retry = Duration.between(now, today.plusDays(1).atStartOfDay(now.getZone())).toSeconds();
            throw new ListingImageRateLimitedException(
                    "하루에 올릴 수 있는 사진은 " + dailyLimit + "장이에요. 내일 다시 시도해주세요.", Math.max(1, retry));
        }
    }
}
