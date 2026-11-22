package com.matjari.service;

import com.matjari.exception.MatJariException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 채팅 사진 전송 하루 상한(V30): 사용자당 KST 달력 하루에 30장(설정 {@code matjari.chat.image-daily-limit}). 메모리 카운터라 서버 한 대 기준이고 재시작하면
 * 초기화된다. 새 사진 INSERT 직전에만 소모하므로 멱등 재전송·검증 실패는 세지 않는다. 초과하면 429 CHAT_IMAGE_RATE_LIMITED + Retry-After(다음 날 0시 KST까지 초).
 * 날짜가 바뀐 항목은 항목이 많아졌을 때 쓸어 낸다(메모리 누수 방지). 일반 텍스트 속도 제한({@link ChatRateLimiter})과는 별개로 함께 적용된다.
 */
@Component
public class ChatImageRateLimiter {

    static final String CODE = "CHAT_IMAGE_RATE_LIMITED";
    private static final int SWEEP_MIN_SIZE = 1_000;

    /** 429 CHAT_IMAGE_RATE_LIMITED. 응답 {code, message, retryAfterSeconds} 와 Retry-After 헤더는 GlobalExceptionHandler 가 만든다. */
    public static class ChatImageRateLimitedException extends MatJariException {
        private final long retryAfterSeconds;

        public ChatImageRateLimitedException(int limit, long retryAfterSeconds) {
            super("하루에 보낼 수 있는 사진은 " + limit + "장이에요. 내일 다시 시도해주세요.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getCode() {
            return CODE;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private record Day(LocalDate date, int count) {}

    private final Clock clock;
    private final int limit;
    private final ConcurrentHashMap<Long, Day> users = new ConcurrentHashMap<>();

    public ChatImageRateLimiter(Clock clock, @Value("${matjari.chat.image-daily-limit:30}") int limit) {
        this.clock = clock;
        this.limit = Math.max(1, limit);
    }

    /** 사진 1장을 시도한다. 상한을 넘으면 429. */
    public void acquire(Long userId) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (users.size() >= SWEEP_MIN_SIZE) {
            users.entrySet().removeIf(e -> e.getValue().date().isBefore(today));
        }
        boolean[] allowed = {true};
        users.compute(userId, (k, v) -> {
            int used = v != null && v.date().equals(today) ? v.count() : 0;
            if (used >= limit) {
                allowed[0] = false;
                return new Day(today, used);
            }
            return new Day(today, used + 1);
        });
        if (!allowed[0]) {
            long retry = java.time.Duration.between(now, today.plusDays(1).atStartOfDay(now.getZone())).toSeconds();
            throw new ChatImageRateLimitedException(limit, Math.max(1, retry));
        }
    }
}
