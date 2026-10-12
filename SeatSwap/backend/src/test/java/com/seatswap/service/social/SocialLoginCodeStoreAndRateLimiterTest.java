package com.seatswap.service.social;

import com.seatswap.service.social.SocialTestSupport.MutableClock;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialLoginCodeStoreAndRateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final SocialProperties props = new SocialProperties();

    @Test
    void 코드는_한_번만_쓸_수_있다() {
        SocialLoginCodeStore store = new SocialLoginCodeStore(clock, props);
        String code = store.issue(7L);
        assertThat(store.consume(code)).contains(7L);
        assertThat(store.consume(code)).isEmpty();
    }

    @Test
    void 코드는_60초가_지나면_만료되고_만료_코드도_삭제된다() {
        SocialLoginCodeStore store = new SocialLoginCodeStore(clock, props);
        String code = store.issue(7L);
        clock.advance(59_000);
        String code2 = store.issue(8L);
        assertThat(store.consume(code2)).contains(8L);
        String code3 = store.issue(9L);
        clock.advance(60_000);
        assertThat(store.consume(code3)).isEmpty();
        assertThat(store.consume(code)).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void 코드는_32바이트_랜덤이라_충분히_길고_겹치지_않는다() {
        SocialLoginCodeStore store = new SocialLoginCodeStore(clock, props);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String c = store.issue(1L);
            assertThat(c).hasSize(43).matches("[A-Za-z0-9_-]+");
            assertThat(seen.add(c)).isTrue();
        }
        assertThat(store.consume("")).isEmpty();
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("nope")).isEmpty();
    }

    @Test
    void 보관_상한이_차면_만료분을_정리하고_그래도_가득이면_SERVER_BUSY() {
        props.setLoginCodeMax(3);
        SocialLoginCodeStore store = new SocialLoginCodeStore(clock, props);
        store.issue(1L);
        store.issue(2L);
        store.issue(3L);
        assertThatThrownBy(() -> store.issue(4L)).isInstanceOf(SocialLoginFailure.class)
                .extracting(e -> ((SocialLoginFailure) e).getError()).isEqualTo(SocialLoginFailure.SocialError.SERVER_BUSY);
        clock.advance(61_000); // 만료분 정리 후 다시 발급 가능
        assertThat(store.issue(5L)).isNotBlank();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void 동시에_같은_코드를_써도_한_번만_성공한다() throws Exception {
        SocialLoginCodeStore store = new SocialLoginCodeStore(clock, props);
        for (int round = 0; round < 50; round++) {
            String code = store.issue(1L);
            AtomicInteger ok = new AtomicInteger();
            Thread[] ts = new Thread[8];
            for (int i = 0; i < ts.length; i++) {
                ts[i] = new Thread(() -> {
                    if (store.consume(code).isPresent()) {
                        ok.incrementAndGet();
                    }
                });
                ts[i].start();
            }
            for (Thread t : ts) {
                t.join();
            }
            assertThat(ok.get()).isEqualTo(1);
        }
    }

    @Test
    void IP당_분당_30회를_넘으면_429이고_창이_지나면_풀린다() {
        SocialRateLimiter limiter = new SocialRateLimiter(clock, props);
        for (int i = 0; i < 30; i++) {
            limiter.acquire("1.1.1.1");
        }
        assertThatThrownBy(() -> limiter.acquire("1.1.1.1")).isInstanceOf(SocialAuthException.class)
                .satisfies(e -> {
                    SocialAuthException ex = (SocialAuthException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(429);
                    assertThat(ex.getCode()).isEqualTo("SOCIAL_RATE_LIMITED");
                    assertThat(ex.getRetryAfterSeconds()).isBetween(1L, 60L);
                });
        limiter.acquire("2.2.2.2"); // 다른 IP 는 영향 없음
        clock.advance(60_001);
        limiter.acquire("1.1.1.1");
    }

    @Test
    void 거절된_시도는_창을_늘리지_않는다() {
        props.setIpPerMinute(2);
        SocialRateLimiter limiter = new SocialRateLimiter(clock, props);
        limiter.acquire("ip");
        limiter.acquire("ip");
        for (int i = 0; i < 10; i++) {
            clock.advance(5_000);
            assertThatThrownBy(() -> limiter.acquire("ip")).isInstanceOf(SocialAuthException.class);
        }
        clock.advance(10_001); // 첫 기록 + 60초 지남
        limiter.acquire("ip");
    }
}
