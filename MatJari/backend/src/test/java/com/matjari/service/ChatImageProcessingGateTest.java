package com.matjari.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 채팅 사진 처리 입구: 시도 횟수(실패 포함)를 사용자별로 세고, 동시 처리 수를 제한하며, 어떤 예외에서도 자리를 반납한다. */
class ChatImageProcessingGateTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 시각을 직접 움직일 수 있는 시계. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-22T03:00:00Z"));

        void advanceSeconds(long s) {
            now.updateAndGet(i -> i.plusSeconds(s));
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
            return now.get();
        }
    }

    @Test
    void 처리_시도는_실패해도_사용자별로_센다_분당_한도를_넘으면_429_코드이고_작업은_실행되지_않는다() {
        MutableClock clock = new MutableClock();
        ChatImageProcessingGate gate = new ChatImageProcessingGate(clock, 3, 2);
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> gate.run(1L, () -> {
                ran.incrementAndGet();
                throw new IllegalStateException("깨진 사진");
            })).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> gate.run(1L, () -> ran.incrementAndGet()))
                .isInstanceOfSatisfying(ChatImageRateLimiter.ChatImageRateLimitedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CHAT_IMAGE_RATE_LIMITED");
                    assertThat(e.getRetryAfterSeconds()).isBetween(1L, 60L);
                });
        assertThat(ran.get()).isEqualTo(3);
        assertThat(gate.run(2L, () -> "다른 사용자는 독립")).isEqualTo("다른 사용자는 독립");
        clock.advanceSeconds(61);
        assertThat(gate.run(1L, () -> "창이 지나면 다시 가능")).isEqualTo("창이 지나면 다시 가능");
    }

    @Test
    void 예외가_나도_동시_처리_자리를_반납한다() {
        ChatImageProcessingGate gate = new ChatImageProcessingGate(new MutableClock(), 1000, 2);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> gate.run((long) 1, () -> {
                throw new IllegalArgumentException("x");
            })).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(gate.availablePermits()).isEqualTo(2);
    }

    @Test
    void 동시_처리_자리가_없으면_기다리지_않고_바로_503_SERVER_BUSY() throws Exception {
        ChatImageProcessingGate gate = new ChatImageProcessingGate(new MutableClock(), 1000, 2);
        CountDownLatch inside = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable hold = () -> gate.run(7L, () -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        Thread a = new Thread(hold);
        Thread b = new Thread(hold);
        a.start();
        b.start();
        assertThat(inside.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        assertThatThrownBy(() -> gate.run(8L, () -> "들어오면 안 된다"))
                .isInstanceOfSatisfying(ChatImageProcessingGate.ChatImageBusyException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("SERVER_BUSY");
                    assertThat(e.getRetryAfterSeconds()).isPositive();
                });
        assertThat((System.nanoTime() - started) / 1_000_000).as("기다리지 않는다").isLessThan(1000);

        release.countDown();
        a.join(5000);
        b.join(5000);
        assertThat(gate.availablePermits()).isEqualTo(2);
        assertThat(gate.run(8L, () -> "자리가 비면 다시 가능")).isEqualTo("자리가 비면 다시 가능");
    }
}
