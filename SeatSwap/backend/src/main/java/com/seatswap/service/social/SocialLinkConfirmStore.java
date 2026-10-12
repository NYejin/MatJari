package com.seatswap.service.social;

import com.seatswap.domain.SocialProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 소셜 계정 '연결 확정 코드' 저장소(서버 메모리). link 모드 콜백이 제공자 프로필을 읽은 뒤 DB 에 저장하지 않고
 * (연결 대상 사용자, 제공자, 제공자 사용자 id)를 이 코드에 묶어 프래그먼트로 프런트에 넘기고,
 * 로그인한 본인이 인증된 API 로 코드를 확정할 때만 저장한다(강제 연결 CSRF 방어).
 * 32바이트 랜덤, TTL(로그인 코드와 같은 설정), 꺼내면 즉시 삭제(1회용), 상한(login-code-max 공유) 초과 시 SERVER_BUSY.
 * 코드 값은 로그에 남기지 않는다.
 */
@Component
public class SocialLinkConfirmStore {

    /** 확정 대상. */
    public record Payload(Long userId, SocialProvider provider, String providerUserId) {
        @Override
        public String toString() {
            return "Payload[***]";
        }
    }

    private record Entry(Payload payload, long expiresAtMillis) {
    }

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> codes = new ConcurrentHashMap<>();
    private final Object issueLock = new Object();
    private final Clock clock;
    private final SocialProperties properties;

    public SocialLinkConfirmStore(Clock clock, SocialProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    public String issue(Long userId, SocialProvider provider, String providerUserId) {
        long now = clock.millis();
        synchronized (issueLock) {
            if (codes.size() >= properties.getLoginCodeMax()) {
                codes.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
                if (codes.size() >= properties.getLoginCodeMax()) {
                    throw new SocialAuthException(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_BUSY",
                            "요청이 몰려 있어요. 잠시 후 다시 시도해주세요.");
                }
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            codes.put(code, new Entry(new Payload(userId, provider, providerUserId), now + properties.getLoginCodeTtlSeconds() * 1000L));
            return code;
        }
    }

    /** 원자적으로 꺼낸다(성공이든 만료든 삭제). 없거나 만료면 빈 값. */
    public Optional<Payload> consume(String code) {
        if (code == null || code.isEmpty()) {
            return Optional.empty();
        }
        Entry entry = codes.remove(code);
        if (entry == null || entry.expiresAtMillis() <= clock.millis()) {
            return Optional.empty();
        }
        return Optional.of(entry.payload());
    }

    int size() {
        return codes.size();
    }
}
