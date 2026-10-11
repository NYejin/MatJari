package com.seatswap.config;

import com.seatswap.security.StompAuthChannelInterceptor;
import com.seatswap.websocket.WebSocketSessionRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 채팅 수신용 STOMP 설정. 순수 WebSocket(`/ws`, SockJS 없음), 브로커는 사용자 큐(`/user/queue/chat`)만 쓰는 simple broker다.
 * 인증은 spring-security-messaging 없이 {@link StompAuthChannelInterceptor}(CONNECT 의 Authorization 헤더)가 한다.
 * 클라이언트 SEND 는 전부 거부하므로 애플리케이션 목적지 prefix 는 두지 않는다. 전송은 REST, 서버가 커밋 후 푸시한다.
 */
@Configuration
@EnableWebSocketMessageBroker
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";
    private static final int MESSAGE_SIZE_LIMIT = 16 * 1024;
    private static final int SEND_BUFFER_SIZE_LIMIT = 512 * 1024;
    private static final int SEND_TIME_LIMIT_MS = 15_000;
    private static final long HEARTBEAT_MS = 10_000;
    private static final Pattern WILDCARD_HOST = Pattern.compile("^(https?://)?\\*(:\\d+)?$");

    private final StompAuthChannelInterceptor authInterceptor;
    private final WebSocketSessionRegistry sessionRegistry;
    private final ThreadPoolTaskScheduler heartbeatScheduler;
    private final String[] allowedOrigins;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor,
                           WebSocketSessionRegistry sessionRegistry,
                           @Qualifier("wsHeartbeatScheduler") ThreadPoolTaskScheduler heartbeatScheduler,
                           @Value("${seatswap.websocket.allowed-origins:http://localhost:5173}") String[] allowedOrigins) {
        this.authInterceptor = authInterceptor;
        this.sessionRegistry = sessionRegistry;
        this.heartbeatScheduler = heartbeatScheduler;
        this.allowedOrigins = validatedOrigins(allowedOrigins);
    }

    /** 하트비트·세션 정리용 스케줄러. 정적 @Bean 이라 설정 클래스 순환 의존이 생기지 않는다. */
    @Bean(name = "wsHeartbeatScheduler")
    public static ThreadPoolTaskScheduler wsHeartbeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** Origin 설정 검증: 비어 있거나 전체 허용('*')이거나 http(s):// 로 시작하지 않으면 시작 시 실패한다. REST CORS 도 같은 검증을 쓴다. */
    public static String[] validatedOrigins(String[] raw) {
        List<String> result = new ArrayList<>();
        if (raw != null) {
            for (String o : raw) {
                String origin = o == null ? "" : o.trim();
                if (origin.isEmpty()) {
                    continue;
                }
                if (WILDCARD_HOST.matcher(origin).matches()) {
                    throw new IllegalStateException("seatswap.websocket.allowed-origins 에 전체 허용('*')은 쓸 수 없습니다: " + origin);
                }
                if (!(origin.startsWith("http://") || origin.startsWith("https://"))) {
                    throw new IllegalStateException("seatswap.websocket.allowed-origins 는 http:// 또는 https:// 로 시작해야 합니다: " + origin);
                }
                result.add(origin);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("seatswap.websocket.allowed-origins 가 비어 있습니다.");
        }
        return result.toArray(new String[0]);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // SockJS 없음. Origin 은 명시 allowlist 만(브라우저가 아닌 클라이언트는 Origin 헤더가 없을 수 있으나 JWT 가 막는다)
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler);
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MESSAGE_SIZE_LIMIT)
                .setSendBufferSizeLimit(SEND_BUFFER_SIZE_LIMIT)
                .setSendTimeLimit(SEND_TIME_LIMIT_MS)
                .addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        sessionRegistry.opened(session);
                        super.afterConnectionEstablished(session);
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                        sessionRegistry.closed(session.getId());
                        super.afterConnectionClosed(session, closeStatus);
                    }
                });
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
