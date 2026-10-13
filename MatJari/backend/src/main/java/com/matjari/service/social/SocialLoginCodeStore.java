package com.matjari.service.social;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 일회용 로그인 코드 저장소(서버 메모리). 콜백이 JWT 대신 이 코드를 프런트 URL 프래그먼트로 넘기고, 프런트가 exchange 로 토큰과 바꾼다.
 * 32바이트 랜덤, TTL(기본 60초), 꺼내면 즉시 삭제(1회용), 최대 보관 수(기본 1만) 초과 시 만료분 정리 후에도 가득 차면 발급 거부(SERVER_BUSY).
 * 서버를 재시작하면 사라진다(TTL 이 60초라 허용). 코드 값은 로그에 남기지 않는다.
 */
@Component
public class SocialLoginCodeStore {

    private record Entry(Long userId, long expiresAtMillis) {
    }

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> codes = new ConcurrentHashMap<>();
    private final Object issueLock = new Object();
    private final Clock clock;
    private final SocialProperties properties;

    public SocialLoginCodeStore(Clock clock, SocialProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /** 코드를 발급한다. 보관 한도를 넘으면 SocialLoginFailure(SERVER_BUSY). */
    public String issue(Long userId) {
        long now = clock.millis();
        synchronized (issueLock) {
            if (codes.size() >= properties.getLoginCodeMax()) {
                codes.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
                if (codes.size() >= properties.getLoginCodeMax()) {
                    throw new SocialLoginFailure(SocialLoginFailure.SocialError.SERVER_BUSY);
                }
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            codes.put(code, new Entry(userId, now + properties.getLoginCodeTtlSeconds() * 1000L));
            return code;
        }
    }

    /** 코드를 꺼낸다(성공이든 만료든 삭제). 없거나 만료면 빈 값. 같은 코드는 한 번만 성공한다. */
    public Optional<Long> consume(String code) {
        if (code == null || code.isEmpty()) {
            return Optional.empty();
        }
        Entry entry = codes.remove(code);
        if (entry == null || entry.expiresAtMillis() <= clock.millis()) {
            return Optional.empty();
        }
        return Optional.of(entry.userId());
    }

    int size() {
        return codes.size();
    }
}
