package com.seatswap.security;

import com.seatswap.websocket.WebSocketSessionRegistry;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * clientInboundChannel 인터셉터: STOMP 프레임 인증·인가 (spring-security-messaging 미사용).
 * - CONNECT: 네이티브 헤더 {@code Authorization: Bearer <access>} 만 본다(쿼리스트링 토큰은 읽지 않는다). 서명·만료 검증,
 *   refresh 토큰 거부, 사용자 존재 확인 뒤 Principal(이름 = userId 문자열)을 세팅하고 토큰 만료 시각을 세션에 붙인다.
 *   사용자당 동시 연결 한도를 넘으면 거부한다.
 * - 그 외 프레임은 인증된 세션이고 토큰이 만료되지 않았을 때만 통과(만료면 거부 + close 1008).
 * - SUBSCRIBE 는 {@code /user/queue/chat}·{@code /user/queue/notifications} 만(목적지당 1개, 같은 목적지 두 번째는 거부), UNSUBSCRIBE·DISCONNECT 는 허용, 클라이언트 SEND 를 포함한 나머지는 모두 거부한다.
 * 거부하면 예외로 ERROR 프레임이 나가고, 세션은 곧 close 1008 로 닫는다. 토큰 값은 로그·오류 메시지에 남기지 않는다.
 */
@Slf4j
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    public static final String USER_CHAT_DESTINATION = "/user/queue/chat";
    public static final String USER_NOTIFICATION_DESTINATION = "/user/queue/notifications";
    private static final Set<String> ALLOWED_DESTINATIONS = Set.of(USER_CHAT_DESTINATION, USER_NOTIFICATION_DESTINATION);
    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER = "Bearer ";

    /** ERROR 프레임의 message 로 나가는 거부 사유(고정 코드). */
    public static class StompRejectedException extends MessagingException {
        public StompRejectedException(String code) {
            super(code);
        }
    }

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final WebSocketSessionRegistry registry;

    public StompAuthChannelInterceptor(JwtTokenProvider jwtTokenProvider,
                                       CustomUserDetailsService userDetailsService,
                                       WebSocketSessionRegistry registry) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDetailsService = userDetailsService;
        this.registry = registry;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // 하트비트 등 STOMP 명령이 없는 메시지
        }
        StompCommand command = accessor.getCommand();
        String sessionId = accessor.getSessionId();

        if (command == StompCommand.DISCONNECT) {
            return message; // 세션 정리용(연결이 끊길 때 서버가 스스로 보내는 DISCONNECT 포함)이라 항상 통과
        }
        if (command == StompCommand.CONNECT || command == StompCommand.STOMP) {
            return connect(message, accessor, sessionId);
        }

        if (sessionId == null || !registry.isActive(sessionId)) {
            throw reject(sessionId, "unauthorized");
        }
        Long registeredUser = registry.userIdOf(sessionId);
        if (accessor.getUser() == null || !String.valueOf(registeredUser).equals(accessor.getUser().getName())) {
            throw reject(sessionId, "unauthorized");
        }
        switch (command) {
            case SUBSCRIBE -> {
                String destination = accessor.getDestination();
                if (destination == null || !ALLOWED_DESTINATIONS.contains(destination)) {
                    throw reject(sessionId, "forbidden destination");
                }
                // 목적지당 구독은 1개: 같은 목적지의 두 번째 SUBSCRIBE(또는 id 없는 SUBSCRIBE)는 거부한다
                if (!registry.registerSubscription(sessionId, destination, accessor.getSubscriptionId())) {
                    throw reject(sessionId, "duplicate subscription");
                }
            }
            case UNSUBSCRIBE -> registry.clearSubscription(sessionId, accessor.getSubscriptionId());
            default -> throw reject(sessionId, "forbidden frame"); // SEND 포함: 전송은 REST 로만 한다
        }
        return message;
    }

    private Message<?> connect(Message<?> message, StompHeaderAccessor accessor, String sessionId) {
        if (sessionId == null) {
            throw reject(null, "unauthorized");
        }
        String header = accessor.getFirstNativeHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER)) {
            throw reject(sessionId, "unauthorized");
        }
        String token = header.substring(BEARER.length()).trim();
        AuthUserPrincipal principal;
        Instant expiresAt;
        try {
            if (!jwtTokenProvider.validateToken(token) || jwtTokenProvider.isRefreshToken(token)
                    || jwtTokenProvider.isWithdrawToken(token)) {
                throw reject(sessionId, "unauthorized");
            }
            principal = userDetailsService.loadUserById(jwtTokenProvider.getUserId(token));
            expiresAt = jwtTokenProvider.getExpiration(token);
        } catch (StompRejectedException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
            throw reject(sessionId, "unauthorized");
        } catch (com.seatswap.exception.AccountSuspendedException e) {
            // 이용 정지 중인 계정은 연결할 수 없다(사유 상세는 ERROR 프레임에 싣지 않는다)
            throw reject(sessionId, "account suspended");
        } catch (RuntimeException e) {
            // DB 장애 등: 원인은 로그에만 남기고(토큰 제외) 연결은 거부한다
            log.warn("STOMP CONNECT 인증 중 오류: {}", e.toString());
            throw reject(sessionId, "unauthorized");
        }
        try {
            registry.authenticate(sessionId, principal.userId(), expiresAt);
        } catch (WebSocketSessionRegistry.ConnectionLimitExceededException e) {
            throw reject(sessionId, "connection limit exceeded");
        } catch (IllegalStateException e) {
            throw reject(sessionId, "unauthorized");
        }
        accessor.setUser(principal);
        return message;
    }

    private StompRejectedException reject(String sessionId, String code) {
        registry.closePolicyViolationSoon(sessionId);
        return new StompRejectedException(code);
    }
}
