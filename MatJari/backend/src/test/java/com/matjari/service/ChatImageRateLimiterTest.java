package com.matjari.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatImageRateLimiterTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 테스트가 시각을 움직일 수 있는 Clock. */
    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void 하루_30장까지_허용하고_31번째는_다음_날_0시까지_Retry_After로_거절한다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-22T14:00:00Z"));   // KST 23:00
        ChatImageRateLimiter limiter = new ChatImageRateLimiter(clock, 30);
        for (int i = 0; i < 30; i++) {
            limiter.acquire(1L);
        }

        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOfSatisfying(ChatImageRateLimiter.ChatImageRateLimitedException.class, e -> {
            assertThat(e.getCode()).isEqualTo("CHAT_IMAGE_RATE_LIMITED");
            assertThat(e.getRetryAfterSeconds()).isEqualTo(3600);   // 23:00 -> 24:00
        });
        assertThatCode(() -> limiter.acquire(2L)).as("다른 사용자는 별개").doesNotThrowAnyException();
    }

    @Test
    void KST_날짜가_바뀌면_다시_센다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-22T14:59:00Z"));   // KST 23:59
        ChatImageRateLimiter limiter = new ChatImageRateLimiter(clock, 2);
        limiter.acquire(1L);
        limiter.acquire(1L);
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(ChatImageRateLimiter.ChatImageRateLimitedException.class);

        clock.now = Instant.parse("2026-11-22T15:00:01Z");   // KST 다음 날 00:00:01
        assertThatCode(() -> limiter.acquire(1L)).doesNotThrowAnyException();
    }

    @Test
    void 거절된_시도는_횟수를_늘리지_않는다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-22T03:00:00Z"));
        ChatImageRateLimiter limiter = new ChatImageRateLimiter(clock, 1);
        limiter.acquire(1L);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(ChatImageRateLimiter.ChatImageRateLimitedException.class);
        }
        clock.now = Instant.parse("2026-11-23T03:00:00Z");
        assertThatCode(() -> limiter.acquire(1L)).doesNotThrowAnyException();
    }
}
