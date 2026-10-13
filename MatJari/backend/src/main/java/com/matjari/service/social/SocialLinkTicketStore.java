package com.matjari.service.social;

import com.matjari.domain.SocialProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 소셜 계정 연결 티켓 저장소(서버 메모리). 로그인 코드 저장소({@link SocialLoginCodeStore})와 같은 방식이지만 별도 인스턴스다.
 * 마이페이지의 연결 시작 API 가 로그인한 사용자 id 와 제공자에 묶어 발급하고, authorize?link= 가 꺼내 쓴다(브라우저 이동에는 Authorization 헤더가 없어서).
 * 32바이트 랜덤, TTL(기본 60초), 꺼내면 즉시 삭제(1회용), 보관 상한(기본 1만) 초과 시 만료분 정리 후에도 가득 차면 발급 거부(503 SERVER_BUSY).
 * 서버를 재시작하면 사라진다. 티켓 값은 로그에 남기지 않는다.
 */
@Component
public class SocialLinkTicketStore {

    private record Entry(Long userId, SocialProvider provider, long expiresAtMillis) {
    }

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> tickets = new ConcurrentHashMap<>();
    private final Object issueLock = new Object();
    private final Clock clock;
    private final SocialProperties properties;

    public SocialLinkTicketStore(Clock clock, SocialProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /** 티켓을 발급한다. 보관 한도를 넘으면 SocialAuthException(503 SERVER_BUSY). */
    public String issue(Long userId, SocialProvider provider) {
        long now = clock.millis();
        synchronized (issueLock) {
            if (tickets.size() >= properties.getLoginCodeMax()) {
                tickets.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
                if (tickets.size() >= properties.getLoginCodeMax()) {
                    throw new SocialAuthException(HttpStatus.SERVICE_UNAVAILABLE, "SERVER_BUSY",
                            "요청이 몰려 있어요. 잠시 후 다시 시도해주세요.");
                }
            }
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            tickets.put(ticket, new Entry(userId, provider, now + properties.getLoginCodeTtlSeconds() * 1000L));
            return ticket;
        }
    }

    /** 티켓을 꺼낸다(성공이든 만료든 제공자 불일치든 삭제). 없거나 만료거나 발급 때의 제공자와 다르면 빈 값. 같은 티켓은 한 번만 성공한다. */
    public Optional<Long> consume(String ticket, SocialProvider provider) {
        if (ticket == null || ticket.isEmpty()) {
            return Optional.empty();
        }
        Entry entry = tickets.remove(ticket);
        if (entry == null || entry.expiresAtMillis() <= clock.millis() || entry.provider() != provider) {
            return Optional.empty();
        }
        return Optional.of(entry.userId());
    }

    int size() {
        return tickets.size();
    }
}
