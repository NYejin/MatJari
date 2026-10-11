package com.seatswap.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatRateLimiterTest {

    /** 테스트가 시간을 직접 움직이는 Clock. */
    private static final class MutableClock extends Clock {
        private long millis = 1_000_000;

        void advance(long ms) {
            millis += ms;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    @Test
    void 짧은_구간은_5초에_5건까지고_여섯번째는_429다() {
        MutableClock clock = new MutableClock();
        ChatRateLimiter limiter = new ChatRateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            limiter.acquire(1L);
        }
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOfSatisfying(ChatRateLimiter.ChatRateLimitedException.class, e -> {
            assertThat(e.getCode()).isEqualTo("CHAT_RATE_LIMITED");
            assertThat(e.getRetryAfterSeconds()).isEqualTo(5L);
        });
        limiter.acquire(2L);   // 다른 사용자는 별개
        clock.advance(4_999);
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(ChatRateLimiter.ChatRateLimitedException.class);
        clock.advance(1);      // 첫 전송이 5초 창을 벗어난다
        limiter.acquire(1L);
    }

    @Test
    void 긴_구간은_1분에_60건이고_5초마다_5건씩_보내도_60건에서_막힌다() {
        MutableClock clock = new MutableClock();
        ChatRateLimiter limiter = new ChatRateLimiter(clock);
        int sent = 0;
        // 5초마다 5건: 짧은 구간은 늘 통과하지만 12번(=60건) 뒤 같은 1분 안의 다음 전송은 막힌다
        for (int window = 0; window < 12; window++) {
            for (int i = 0; i < 5; i++) {
                limiter.acquire(1L);
                sent++;
            }
            clock.advance(window == 11 ? 4_000 : 5_000);   // 마지막 묶음 뒤에는 첫 전송 시각으로부터 59초째
        }
        assertThat(sent).isEqualTo(60);
        assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOfSatisfying(ChatRateLimiter.ChatRateLimitedException.class,
                e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(1L));
        clock.advance(1_000);   // 첫 묶음이 60초 창을 벗어난다
        limiter.acquire(1L);
    }

    @Test
    void 거절된_시도는_기록되지_않아_대기_시간을_늘리지_않는다() {
        MutableClock clock = new MutableClock();
        ChatRateLimiter limiter = new ChatRateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            limiter.acquire(1L);
        }
        for (int i = 0; i < 20; i++) {
            assertThatThrownBy(() -> limiter.acquire(1L)).isInstanceOf(ChatRateLimiter.ChatRateLimitedException.class);
        }
        clock.advance(5_000);
        limiter.acquire(1L);
    }
}
