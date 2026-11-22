package com.matjari.security;

import com.matjari.dto.request.AuthInputNormalizer;
import com.matjari.exception.MatJariException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 비밀번호 로그인·가입의 메모리 속도 제한(서버 한 대 기준, 재시작하면 초기화, 시계는 Clock 주입).
 * <ul>
 *   <li>계정(정규화한 이메일)별: 15분 창에서 실패 5회가 되면 마지막 실패로부터 15분 동안 잠근다(잠긴 동안의 시도는 세지 않는다). 성공하면 초기화.</li>
 *   <li>IP별: 10분 창에서 실패 30회가 되면 가장 오래된 실패가 창을 벗어날 때까지 막는다.</li>
 *   <li>가입: IP당 1시간 10회(시도마다 센다).</li>
 * </ul>
 * 없는 이메일도 똑같이 센다(가입 여부 노출 방지). 한도를 넘으면 429 {@code LOGIN_RATE_LIMITED}/{@code SIGNUP_RATE_LIMITED}.
 * 맵은 상한({@value #MAX_KEYS})을 두고, 꽉 차면 만료된 키를 쓸어 낸 뒤에도 남으면 가장 오래된 키를 버린다.
 * 한계: 특정 이메일에 일부러 실패를 쌓아 그 계정 로그인을 15분 막을 수 있다(계정 잠금의 일반적인 절충).
 */
@Component
public class AuthRateLimiter {

    public static final String LOGIN_CODE = "LOGIN_RATE_LIMITED";
    public static final String SIGNUP_CODE = "SIGNUP_RATE_LIMITED";
    static final int MAX_KEYS = 20_000;
    private static final int MAX_EMAIL_KEY_LENGTH = 320;

    private static final long ACCOUNT_WINDOW = 15 * 60_000L;
    private static final int ACCOUNT_MAX_FAILURES = 5;
    private static final long IP_WINDOW = 10 * 60_000L;
    private static final int IP_MAX_FAILURES = 30;
    private static final long SIGNUP_WINDOW = 60 * 60_000L;
    static final int DEFAULT_SIGNUP_MAX = 10;
    public static final String PASSWORD_RESET_CODE = "PASSWORD_RESET_RATE_LIMITED";
    private static final long RESET_IP_WINDOW = 10 * 60_000L;
    private static final int RESET_IP_MAX = 5;
    private static final long RESET_EMAIL_WINDOW = 60 * 60_000L;
    private static final int RESET_EMAIL_MAX = 3;
    private static final int RESET_CONFIRM_IP_MAX = 20;

    public static class AuthRateLimitedException extends MatJariException {
        private final String code;
        private final long retryAfterSeconds;

        public AuthRateLimitedException(String code, String message, long retryAfterSeconds) {
            super(message);
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getCode() {
            return code;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private static final class Bucket {
        final ArrayDeque<Long> hits = new ArrayDeque<>();
        long lockedUntil;
    }

    /** 삽입 순서 맵(오래 안 쓴 키가 앞). 모든 접근은 맵 자체를 잠근다. */
    private static final class Store {
        final Map<String, Bucket> map = new LinkedHashMap<>();
        final long window;

        Store(long window) {
            this.window = window;
        }
    }

    private final Clock clock;
    private final int signupMax;
    private final Store accounts = new Store(ACCOUNT_WINDOW);
    private final Store loginIps = new Store(IP_WINDOW);
    private final Store signupIps = new Store(SIGNUP_WINDOW);
    private final Store resetIps = new Store(RESET_IP_WINDOW);
    private final Store resetEmails = new Store(RESET_EMAIL_WINDOW);
    private final Store resetConfirmIps = new Store(RESET_IP_WINDOW);

    public AuthRateLimiter(Clock clock) {
        this(clock, DEFAULT_SIGNUP_MAX);
    }

    /** signupMax: IP당 1시간 가입 시도 상한(설정 {@code matjari.security.signup-per-hour}, 기본 10, 통합 테스트가 높인다). */
    @org.springframework.beans.factory.annotation.Autowired
    public AuthRateLimiter(Clock clock, @org.springframework.beans.factory.annotation.Value("${matjari.security.signup-per-hour:10}") int signupMax) {
        this.clock = clock;
        this.signupMax = signupMax;
    }

    /** 로그인 시도 전에 호출한다. 잠겨 있으면 429. */
    public void checkLogin(String email, String ip) {
        long now = clock.millis();
        long accountWait = waitMillis(accounts, accountKey(email), now, ACCOUNT_MAX_FAILURES, true);
        long ipWait = waitMillis(loginIps, ipKey(ip), now, IP_MAX_FAILURES, false);
        long wait = Math.max(accountWait, ipWait);
        if (wait > 0) {
            throw new AuthRateLimitedException(LOGIN_CODE,
                    "로그인 시도가 너무 많아요. 잠시 후 다시 시도해 주세요.", toSeconds(wait));
        }
    }

    /** 이메일·비밀번호가 틀렸을 때(없는 이메일 포함) 호출한다. */
    public void recordLoginFailure(String email, String ip) {
        long now = clock.millis();
        synchronized (accounts.map) {
            Bucket b = touch(accounts, accountKey(email));
            trim(b, accounts.window, now);
            b.hits.addLast(now);
            if (b.hits.size() >= ACCOUNT_MAX_FAILURES) {
                b.lockedUntil = now + ACCOUNT_WINDOW;
            }
        }
        synchronized (loginIps.map) {
            Bucket b = touch(loginIps, ipKey(ip));
            trim(b, loginIps.window, now);
            b.hits.addLast(now);
        }
    }

    /** 비밀번호가 맞았을 때 그 계정의 실패 횟수를 지운다(IP 실패는 그대로 둔다). */
    public void recordLoginSuccess(String email) {
        synchronized (accounts.map) {
            accounts.map.remove(accountKey(email));
        }
    }

    /** 가입 시도 1건을 센다. 한도를 넘으면 429. */
    public void acquireSignup(String ip) {
        long now = clock.millis();
        synchronized (signupIps.map) {
            Bucket b = touch(signupIps, ipKey(ip));
            trim(b, signupIps.window, now);
            if (b.hits.size() >= signupMax) {
                long wait = b.hits.peekFirst() + SIGNUP_WINDOW - now;
                throw new AuthRateLimitedException(SIGNUP_CODE,
                        "가입 시도가 너무 많아요. 잠시 후 다시 시도해 주세요.", toSeconds(wait));
            }
            b.hits.addLast(now);
        }
    }

    /**
     * 비밀번호 재설정 요청 1건을 센다(IP당 10분 5회, 이메일당 1시간 3회, 없는 이메일도 똑같이 센다). 한도를 넘으면 429 PASSWORD_RESET_RATE_LIMITED.
     * IP 를 먼저 확인하고, 이메일 쪽이 막히면 방금 센 IP 횟수는 돌려준다(막힌 요청이 다른 계정의 IP 몫을 쓰지 않게).
     */
    public void acquirePasswordResetRequest(String ip, String email) {
        long now = clock.millis();
        acquireWindow(resetIps, ipKey(ip), RESET_IP_MAX, now);
        try {
            acquireWindow(resetEmails, accountKey(email), RESET_EMAIL_MAX, now);
        } catch (AuthRateLimitedException e) {
            synchronized (resetIps.map) {
                Bucket b = resetIps.map.get(ipKey(ip));
                if (b != null && !b.hits.isEmpty()) {
                    b.hits.pollLast();
                }
            }
            throw e;
        }
    }

    /** 비밀번호 재설정 확정 시도 1건을 센다(IP당 10분 20회). 한도를 넘으면 429 PASSWORD_RESET_RATE_LIMITED. */
    public void acquirePasswordResetConfirm(String ip) {
        acquireWindow(resetConfirmIps, ipKey(ip), RESET_CONFIRM_IP_MAX, clock.millis());
    }

    private void acquireWindow(Store store, String key, int max, long now) {
        synchronized (store.map) {
            Bucket b = touch(store, key);
            trim(b, store.window, now);
            if (b.hits.size() >= max) {
                long wait = b.hits.peekFirst() + store.window - now;
                throw new AuthRateLimitedException(PASSWORD_RESET_CODE,
                        "요청이 너무 많아요. 잠시 후 다시 시도해 주세요.", toSeconds(wait));
            }
            b.hits.addLast(now);
        }
    }

    /** 잠겨 있으면 남은 시간(ms), 아니면 0. 조회만 하고 새 키를 만들지 않는다. */
    private long waitMillis(Store store, String key, long now, int max, boolean lockout) {
        synchronized (store.map) {
            Bucket b = store.map.get(key);
            if (b == null) {
                return 0;
            }
            trim(b, store.window, now);
            if (lockout && b.lockedUntil > now) {
                return b.lockedUntil - now;
            }
            if (!lockout && b.hits.size() >= max) {
                return b.hits.peekFirst() + store.window - now;
            }
            return 0;
        }
    }

    private static void trim(Bucket b, long window, long now) {
        while (!b.hits.isEmpty() && now - b.hits.peekFirst() >= window) {
            b.hits.pollFirst();
        }
    }

    /** 키의 버킷을 가져오고(없으면 만들고) 가장 최근 쪽으로 옮긴다. 호출자가 store.map 을 잠근 상태여야 한다. */
    private Bucket touch(Store store, String key) {
        Bucket b = store.map.remove(key);
        if (b == null) {
            if (store.map.size() >= MAX_KEYS) {
                sweep(store, clock.millis());
                if (store.map.size() >= MAX_KEYS) {
                    Iterator<String> it = store.map.keySet().iterator();
                    it.next();
                    it.remove();
                }
            }
            b = new Bucket();
        }
        store.map.put(key, b);
        return b;
    }

    private static void sweep(Store store, long now) {
        store.map.values().removeIf(b -> {
            trim(b, store.window, now);
            return b.hits.isEmpty() && b.lockedUntil <= now;
        });
    }

    private static String accountKey(String email) {
        String n = AuthInputNormalizer.normalizeEmail(email);
        if (n == null) {
            return "";
        }
        return n.length() > MAX_EMAIL_KEY_LENGTH ? n.substring(0, MAX_EMAIL_KEY_LENGTH) : n;
    }

    private static String ipKey(String ip) {
        return ip == null ? "" : ip;
    }

    private static long toSeconds(long millis) {
        return Math.max(1, (millis + 999) / 1000);
    }
}
