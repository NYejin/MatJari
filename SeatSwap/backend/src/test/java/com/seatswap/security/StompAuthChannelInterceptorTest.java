package com.seatswap.security;

import com.seatswap.domain.UserRole;
import com.seatswap.security.StompAuthChannelInterceptor.StompRejectedException;
import com.seatswap.websocket.WebSocketSessionRegistry;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** STOMP 프레임 인증·인가 규칙 단위 테스트(모의 토큰 공급자, 실제 세션 레지스트리). */
class StompAuthChannelInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    private JwtTokenProvider tokens;
    private CustomUserDetailsService users;
    private MutableClock clock;
    private WebSocketSessionRegistry registry;
    private StompAuthChannelInterceptor interceptor;

    /** 시간을 움직일 수 있는 Clock */
    private static final class MutableClock extends Clock {
        Instant now = NOW;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        tokens = mock(JwtTokenProvider.class);
        users = mock(CustomUserDetailsService.class);
        clock = new MutableClock();
        // 스케줄러 없음: 거부 시 close 가 즉시 일어난다
        registry = new WebSocketSessionRegistry(clock, null, 0, 10_000, 5);
        interceptor = new StompAuthChannelInterceptor(tokens, users, registry);
    }

    private WebSocketSession socket(String id) {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(id);
        registry.opened(ws);
        return ws;
    }

    private void validToken(String token, long userId, Duration validFor) {
        when(tokens.validateToken(token)).thenReturn(true);
        when(tokens.isRefreshToken(token)).thenReturn(false);
        when(tokens.getUserId(token)).thenReturn(userId);
        when(tokens.getExpiration(token)).thenReturn(NOW.plus(validFor));
        when(users.loadUserById(userId)).thenReturn(AuthUserPrincipal.of(userId, "u" + userId + "@t.com", UserRole.USER));
    }

    private Message<byte[]> frame(StompCommand command, String sessionId, String destination, Principal user, String authorization) {
        return frame(command, sessionId, destination, user, authorization, "sub-0");
    }

    private Message<byte[]> frame(StompCommand command, String sessionId, String destination, Principal user, String authorization,
                                 String subscriptionId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId(sessionId);
        if (subscriptionId != null && (command == StompCommand.SUBSCRIBE || command == StompCommand.UNSUBSCRIBE)) {
            accessor.setSubscriptionId(subscriptionId);
        }
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            accessor.setUser(user);
        }
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Principal connect(String sessionId, String token, long userId) {
        Message<byte[]> connect = frame(StompCommand.CONNECT, sessionId, null, null, "Bearer " + token);
        Message<?> out = interceptor.preSend(connect, null);
        Principal user = StompHeaderAccessor.wrap(out).getUser();
        assertThat(user).isNotNull();
        assertThat(user.getName()).isEqualTo(String.valueOf(userId));
        return user;
    }

    @Test
    void CONNECT_는_Bearer_토큰을_검증해_이름이_userId인_Principal을_세팅하고_세션을_등록한다() throws Exception {
        socket("s1");
        validToken("good", 7L, Duration.ofMinutes(10));

        Principal user = connect("s1", "good", 7L);

        assertThat(registry.isActive("s1")).isTrue();
        assertThat(registry.userIdOf("s1")).isEqualTo(7L);
        assertThat(user).isInstanceOf(AuthUserPrincipal.class);
    }

    @Test
    void 이용_정지_중인_계정의_CONNECT는_거부되고_연결이_등록되지_않는다() throws Exception {
        WebSocketSession ws = socket("sus");
        validToken("suspended", 31L, Duration.ofMinutes(1));
        when(users.loadUserById(31L)).thenThrow(new com.seatswap.exception.AccountSuspendedException(null));

        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "sus", null, null, "Bearer suspended"), null))
                .isInstanceOf(StompRejectedException.class).hasMessage("account suspended");
        verify(ws).close(CloseStatus.POLICY_VIOLATION.withReason("policy violation"));
        assertThat(registry.isActive("sus")).isFalse();
    }

    @Test
    void CONNECT_거부_사유들_헤더없음_Bearer아님_무효_refresh_사용자없음() throws Exception {
        WebSocketSession ws = socket("s1");
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "s1", null, null, null), null))
                .isInstanceOf(StompRejectedException.class).hasMessage("unauthorized");
        verify(ws).close(CloseStatus.POLICY_VIOLATION.withReason("policy violation"));

        socket("s2");
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "s2", null, null, "Basic abc"), null))
                .isInstanceOf(StompRejectedException.class);

        socket("s3");
        when(tokens.validateToken("bad")).thenReturn(false);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "s3", null, null, "Bearer bad"), null))
                .isInstanceOf(StompRejectedException.class);

        socket("s4");
        when(tokens.validateToken("refresh")).thenReturn(true);
        when(tokens.isRefreshToken("refresh")).thenReturn(true);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "s4", null, null, "Bearer refresh"), null))
                .isInstanceOf(StompRejectedException.class);

        socket("s4b");
        validToken("wd", 98L, Duration.ofMinutes(1));
        when(tokens.isWithdrawToken("wd")).thenReturn(true);   // 정지 중 로그인이 주는 탈퇴 전용 토큰은 STOMP 에 쓸 수 없다
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "s4b", null, null, "Bearer wd"), null))
                .isInstanceOf(StompRejectedException.class);

        socket("s5");
        validToken("ghost", 99L, Duration.ofMinutes(1));
        when(users.loadUserById(99L)).thenThrow(new UsernameNotFoundException("none"));
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "s5", null, null, "Bearer ghost"), null))
                .isInstanceOf(StompRejectedException.class);

        socket("s6");
        when(tokens.validateToken("boom")).thenThrow(new JwtException("x"));
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "s6", null, null, "Bearer boom"), null))
                .isInstanceOf(StompRejectedException.class);

        assertThat(registry.connectionCount(7L)).isZero();
    }

    @Test
    void 사용자당_연결_5개까지_6번째는_거부되고_닫히면_다시_연결할_수_있다() throws Exception {
        validToken("t", 7L, Duration.ofMinutes(10));
        for (int i = 1; i <= 5; i++) {
            socket("s" + i);
            connect("s" + i, "t", 7L);
        }
        WebSocketSession sixth = socket("s6");
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "s6", null, null, "Bearer t"), null))
                .isInstanceOf(StompRejectedException.class).hasMessage("connection limit exceeded");
        verify(sixth).close(any(CloseStatus.class));
        assertThat(registry.connectionCount(7L)).isEqualTo(5);

        // 다른 사용자는 영향 없음
        validToken("o", 8L, Duration.ofMinutes(10));
        socket("o1");
        connect("o1", "o", 8L);

        registry.closed("s1");
        socket("s7");
        connect("s7", "t", 7L);
        assertThat(registry.connectionCount(7L)).isEqualTo(5);
    }

    @Test
    void 같은_세션의_CONNECT_중복은_거부된다() throws Exception {
        socket("s1");
        validToken("t", 7L, Duration.ofMinutes(10));
        connect("s1", "t", 7L);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.CONNECT, "s1", null, null, "Bearer t"), null))
                .isInstanceOf(StompRejectedException.class);
    }

    @Test
    void SUBSCRIBE_는_user_queue_chat_만_허용한다() throws Exception {
        socket("s1");
        validToken("t", 7L, Duration.ofMinutes(10));
        Principal user = connect("s1", "t", 7L);
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/chat", user, null), null)).isNotNull();
        assertThat(interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, "s1", null, user, null), null)).isNotNull();

        String[] bad = {"/queue/chat", "/queue/chat-userabc", "/topic/chat", "/topic/match.1", "/user/queue/chat/x", "/user/queue/other", "/user/7/queue/chat", null};
        for (int i = 0; i < bad.length; i++) {
            String sid = "bad" + i;
            long uid = 500L + i;
            WebSocketSession fresh = socket(sid);
            registry.authenticate(sid, uid, NOW.plusSeconds(60));
            Principal p = AuthUserPrincipal.of(uid, "e", UserRole.USER);
            String dest = bad[i];
            assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, sid, dest, p, null), null))
                    .as("목적지 %s", dest).isInstanceOf(StompRejectedException.class);
            verify(fresh).close(any(CloseStatus.class));
        }
    }

    @Test
    void 세션당_구독은_1개이고_두_번째_SUBSCRIBE는_거부되며_일치하는_UNSUBSCRIBE_뒤에는_다시_구독할_수_있다() throws Exception {
        WebSocketSession ws = socket("s1");
        validToken("t", 7L, Duration.ofMinutes(10));
        Principal user = connect("s1", "t", 7L);
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/chat", user, null, "a"), null)).isNotNull();

        // 다른 id 의 UNSUBSCRIBE 는 구독을 비우지 않는다
        interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, "s1", null, user, null, "zzz"), null);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/chat", user, null, "b"), null))
                .isInstanceOf(StompRejectedException.class).hasMessage("duplicate subscription");
        verify(ws).close(any(CloseStatus.class));

        // 거부된 세션은 닫히므로, 새 세션에서 UNSUBSCRIBE(일치하는 id) -> 재구독을 확인한다
        socket("s2");
        Principal user2 = connect("s2", "t", 7L);
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s2", "/user/queue/chat", user2, null, "a"), null);
        interceptor.preSend(frame(StompCommand.UNSUBSCRIBE, "s2", null, user2, null, "a"), null);
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s2", "/user/queue/chat", user2, null, "b"), null)).isNotNull();
    }

    @Test
    void 알림_큐도_구독할_수_있고_채팅_큐와_함께_목적지당_1개씩이며_같은_목적지_두_번째는_거부된다() throws Exception {
        WebSocketSession ws = socket("s1");
        validToken("t", 7L, Duration.ofMinutes(10));
        Principal user = connect("s1", "t", 7L);
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/chat", user, null, "a"), null)).isNotNull();
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/notifications", user, null, "b"), null)).isNotNull();

        // 같은 구독 id 를 다른 목적지에 다시 쓰는 것도 거부한다
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/notifications", user, null, "c"), null))
                .isInstanceOf(StompRejectedException.class).hasMessage("duplicate subscription");
        verify(ws).close(any(CloseStatus.class));

        socket("s2");
        Principal user2 = connect("s2", "t", 7L);
        interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s2", "/user/queue/notifications", user2, null, "a"), null);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s2", "/user/queue/chat", user2, null, "a"), null))
                .isInstanceOf(StompRejectedException.class).hasMessage("duplicate subscription");

        // 알림 큐 변형 목적지는 거부된다
        String[] bad = {"/queue/notifications", "/user/queue/notifications/x", "/topic/notifications", "/user/7/queue/notifications"};
        for (int i = 0; i < bad.length; i++) {
            String sid = "v" + i;
            String dest = bad[i];
            socket(sid);
            Principal p = connect(sid, "t", 7L);
            assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, sid, dest, p, null, "z"), null))
                    .as(dest).isInstanceOf(StompRejectedException.class);
        }
    }

    @Test
    void 구독_id가_없는_SUBSCRIBE는_거부된다() throws Exception {
        socket("s1");
        validToken("t", 7L, Duration.ofMinutes(10));
        Principal user = connect("s1", "t", 7L);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/chat", user, null, null), null))
                .isInstanceOf(StompRejectedException.class);
    }

    @Test
    void Origin_설정에_별표가_하나라도_있으면_시작_시_거부하고_정확히_일치하는_값만_허용한다() {
        for (String bad : new String[]{"*", "https://*", "https://*.example.com", "http://localhost:*", "https://a.com,*", "http://exa*mple.com"}) {
            assertThatThrownBy(() -> com.seatswap.config.WebSocketConfig.validatedOrigins(bad.split(",")))
                    .as(bad).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> com.seatswap.config.WebSocketConfig.validatedOrigins(new String[]{" ", ""})).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> com.seatswap.config.WebSocketConfig.validatedOrigins(new String[]{"example.com"})).isInstanceOf(IllegalStateException.class);
        assertThat(com.seatswap.config.WebSocketConfig.validatedOrigins(new String[]{" http://localhost:5173 ", "https://seatswap.example"}))
                .containsExactly("http://localhost:5173", "https://seatswap.example");

        // REST CORS: 정확히 일치(패턴 아님), 별표 설정이면 빈 생성 단계에서 실패
        com.seatswap.config.SecurityConfig security = new com.seatswap.config.SecurityConfig(null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(security, "allowedOrigins", new String[]{"https://seatswap.example"});
        var cors = (org.springframework.web.cors.UrlBasedCorsConfigurationSource) security.corsConfigurationSource();
        var config = cors.getCorsConfigurations().get("/**");
        assertThat(config.getAllowedOrigins()).containsExactly("https://seatswap.example");
        assertThat(config.getAllowedOriginPatterns()).isNull();
        assertThat(config.checkOrigin("https://seatswap.example")).isEqualTo("https://seatswap.example");
        assertThat(config.checkOrigin("https://evil.example")).isNull();
        org.springframework.test.util.ReflectionTestUtils.setField(security, "allowedOrigins", new String[]{"https://*.example.com"});
        assertThatThrownBy(security::corsConfigurationSource).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 클라이언트_SEND와_허용되지_않은_프레임은_모두_거부한다() throws Exception {
        validToken("t", 7L, Duration.ofMinutes(10));
        for (StompCommand command : new StompCommand[]{StompCommand.SEND, StompCommand.ACK, StompCommand.NACK, StompCommand.BEGIN, StompCommand.COMMIT, StompCommand.ABORT}) {
            socket("s-" + command);
            Principal user = connect("s-" + command, "t", 7L);
            // 한도 5개를 넘지 않게 연결 하나씩 닫는다
            assertThatThrownBy(() -> interceptor.preSend(frame(command, "s-" + command, "/user/queue/chat", user, null), null))
                    .as(command.name()).isInstanceOf(StompRejectedException.class);
            registry.closed("s-" + command);
        }
    }

    @Test
    void 미인증_세션의_SUBSCRIBE_SEND는_거부된다() throws Exception {
        socket("s1");
        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/chat", AuthUserPrincipal.of(7L, "e", UserRole.USER), null), null))
                .isInstanceOf(StompRejectedException.class).hasMessage("unauthorized");
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "unknown", "/user/queue/chat", null, null), null))
                .isInstanceOf(StompRejectedException.class);
    }

    @Test
    void Principal이_세션_사용자와_다르면_거부한다() throws Exception {
        socket("s1");
        validToken("t", 7L, Duration.ofMinutes(10));
        connect("s1", "t", 7L);
        assertThatThrownBy(() -> interceptor.preSend(
                frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/chat", AuthUserPrincipal.of(8L, "e", UserRole.USER), null), null))
                .isInstanceOf(StompRejectedException.class);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/chat", null, null), null))
                .isInstanceOf(StompRejectedException.class);
    }

    @Test
    void 토큰_만료_후_프레임은_거부되고_세션은_1008로_닫힌다_DISCONNECT는_통과() throws Exception {
        WebSocketSession ws = socket("s1");
        validToken("t", 7L, Duration.ofSeconds(30));
        Principal user = connect("s1", "t", 7L);
        assertThat(interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/chat", user, null), null)).isNotNull();

        clock.now = NOW.plusSeconds(31);
        assertThatThrownBy(() -> interceptor.preSend(frame(StompCommand.SUBSCRIBE, "s1", "/user/queue/chat", user, null), null))
                .isInstanceOf(StompRejectedException.class);
        verify(ws).close(CloseStatus.POLICY_VIOLATION.withReason("policy violation"));
        // 정리용 DISCONNECT 는 세션이 이미 없어도 통과
        assertThat(interceptor.preSend(frame(StompCommand.DISCONNECT, "s1", null, user, null), null)).isNotNull();
    }

    @Test
    void 정리_스케줄은_만료_세션과_CONNECT_안_한_세션만_1008로_닫는다() throws Exception {
        WebSocketSession expiring = socket("exp");
        validToken("short", 7L, Duration.ofSeconds(30));
        connect("exp", "short", 7L);
        WebSocketSession alive = socket("alive");
        validToken("long", 8L, Duration.ofHours(1));
        connect("alive", "long", 8L);
        WebSocketSession silent = socket("silent");

        clock.now = NOW.plusSeconds(5);
        registry.sweep();
        verify(expiring, never()).close(any(CloseStatus.class));
        verify(silent, never()).close(any(CloseStatus.class));

        clock.now = NOW.plusSeconds(31);
        registry.sweep();
        verify(expiring).close(CloseStatus.POLICY_VIOLATION.withReason("token expired"));
        verify(silent).close(CloseStatus.POLICY_VIOLATION.withReason("connect timeout"));
        verify(alive, never()).close(any(CloseStatus.class));
        assertThat(registry.connectionCount(7L)).isZero();
        assertThat(registry.connectionCount(8L)).isEqualTo(1);
    }

    @Test
    void 명령이_없는_하트비트_메시지는_통과한다() throws Exception {
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        heartbeat.setSessionId("s1");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders());
        assertThat(interceptor.preSend(message, null)).isSameAs(message);
    }
}
