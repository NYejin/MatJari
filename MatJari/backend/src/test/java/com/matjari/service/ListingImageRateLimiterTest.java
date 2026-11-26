package com.matjari.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 티켓·희망 조건 사진 올리기 속도 제한(V33): 분당 처리 시도와 KST 하루 장수. */
class ListingImageRateLimiterTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void 분당_처리_시도는_한도를_넘으면_LISTING_IMAGE_RATE_LIMITED_이고_1분이_지나면_풀린다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-26T03:00:00Z"));
        ListingImageRateLimiter limiter = new ListingImageRateLimiter(clock, 3, 30);
        for (int i = 0; i < 3; i++) {
            limiter.countAttempt(1L);
        }
        assertThatThrownBy(() -> limiter.countAttempt(1L))
                .isInstanceOfSatisfying(ListingImageRateLimiter.ListingImageRateLimitedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("LISTING_IMAGE_RATE_LIMITED");
                    assertThat(e.getRetryAfterSeconds()).isBetween(1L, 60L);
                });
        assertThatCode(() -> limiter.countAttempt(2L)).as("다른 사용자는 별개").doesNotThrowAnyException();
        clock.now = clock.now.plusSeconds(61);
        assertThatCode(() -> limiter.countAttempt(1L)).doesNotThrowAnyException();
    }

    @Test
    void 하루_30장까지_허용하고_다음_날_0시까지_Retry_After로_거절한다() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-26T14:00:00Z"));   // KST 23:00
        ListingImageRateLimiter limiter = new ListingImageRateLimiter(clock, 10, 30);
        for (int i = 0; i < 30; i++) {
            limiter.acquireDaily(1L);
        }
        assertThatThrownBy(() -> limiter.acquireDaily(1L))
                .isInstanceOfSatisfying(ListingImageRateLimiter.ListingImageRateLimitedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("LISTING_IMAGE_RATE_LIMITED");
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(3600);
                });
        clock.now = clock.now.plusSeconds(3600);   // KST 다음 날 0시
        assertThatCode(() -> limiter.acquireDaily(1L)).doesNotThrowAnyException();
    }
}
