package com.seatswap.service.social;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 소셜 로그인 테스트 공용 도우미. */
public final class SocialTestSupport {

    private SocialTestSupport() {
    }

    public static final String SECRET = "test-state-secret-0123456789-0123456789-abcdef";

    /** 테스트가 시간을 직접 움직이는 Clock. */
    public static final class MutableClock extends Clock {
        private volatile long millis = 1_800_000_000_000L;

        public void advance(long ms) {
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

    /** 세 제공자를 모두 스텁으로 가리키게 한 설정(client-secret 은 SECRET_OF 로 확인 가능). */
    public static SocialProperties properties(SocialStubServer stub) {
        SocialProperties p = new SocialProperties();
        p.setStateSecret(SECRET);
        p.setBackendBaseUrl("http://localhost:8080");
        p.setFrontendBaseUrl("http://localhost:5173");
        stub.point(p.getGoogle(), "g-client", "g-secret-VALUE");
        stub.point(p.getKakao(), "k-client", "k-secret-VALUE");
        stub.point(p.getNaver(), "n-client", "n-secret-VALUE");
        return p;
    }
}
