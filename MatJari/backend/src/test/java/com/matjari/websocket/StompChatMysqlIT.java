package com.matjari.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matjari.security.JwtTokenProvider;
import com.matjari.service.ExchangeMatchService;
import com.matjari.testsupport.RealMysqlSupport;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STOMP 채팅 푸시의 실제 서버(RANDOM_PORT) + 실제 MySQL 통합 테스트. MATJARI_IT_* 환경변수가 있을 때만 실행된다.
 * 브라우저 없이 JDK WebSocket 으로 STOMP 프레임을 직접 보내 헤더·Origin·close 코드를 그대로 관찰한다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ticket.max-active-per-user=100000",
        "matjari.websocket.sweep-interval-ms=300",
        "matjari.websocket.connect-timeout-ms=4000",
        "matjari.websocket.allowed-origins=http://localhost:5173"
})
class StompChatMysqlIT {

    private static final String OK_ORIGIN = "http://localhost:5173";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (!RealMysqlSupport.configured()) {
            return;
        }
        RealMysqlSupport.cleanDatabase();
        registry.add("spring.datasource.url", RealMysqlSupport::url);
        registry.add("spring.datasource.username", RealMysqlSupport::user);
        registry.add("spring.datasource.password", RealMysqlSupport::password);
    }

    @LocalServerPort
    private int port;
    @Value("${jwt.secret}")
    private String jwtSecret;
    @Autowired
    private JwtTokenProvider tokens;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExchangeMatchService matchService;
    @Autowired
    private WebSocketSessionRegistry sessionRegistry;
    @Autowired
    private org.springframework.messaging.simp.user.SimpUserRegistry userRegistry;

    private final HttpClient http = HttpClient.newHttpClient();
    private final List<StompTestClient> clients = new ArrayList<>();
    private final Map<Integer, Long> users = new HashMap<>();
    private long seq;
    private long performanceId;
    private long session1;

    // ------------------------------------------------------------------ 간이 STOMP 클라이언트

    record Frame(String command, Map<String, String> headers, String body) {}

    static final class StompTestClient implements WebSocket.Listener {
        final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closeCode = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();
        WebSocket socket;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                for (String raw : text.split("\0")) {
                    String t = raw.replaceAll("^\\n+", "");
                    if (t.isEmpty()) {
                        continue; // 하트비트
                    }
                    int split = t.indexOf("\n\n");
                    String head = split < 0 ? t : t.substring(0, split);
                    String body = split < 0 ? "" : t.substring(split + 2);
                    String[] lines = head.split("\n");
                    Map<String, String> headers = new HashMap<>();
                    for (int i = 1; i < lines.length; i++) {
                        int c = lines[i].indexOf(':');
                        if (c > 0) {
                            headers.putIfAbsent(lines[i].substring(0, c), lines[i].substring(c + 1));
                        }
                    }
                    frames.add(new Frame(lines[0], headers, body));
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.complete(-1);
        }

        void send(String stompFrame) throws Exception {
            socket.sendText(stompFrame + "\0", true).get(5, TimeUnit.SECONDS);
        }

        Frame next(long millis) throws InterruptedException {
            return frames.poll(millis, TimeUnit.MILLISECONDS);
        }

        Frame expect(String command) throws InterruptedException {
            Frame f = next(5000);
            assertThat(f).as("%s 프레임을 기다렸으나 없음", command).isNotNull();
            assertThat(f.command()).as(f.toString()).isEqualTo(command);
            return f;
        }

        int awaitClose(long millis) throws Exception {
            return closeCode.get(millis, TimeUnit.MILLISECONDS);
        }

        void stompConnect(String token) throws Exception {
            send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer " + token + "\n\n");
        }

        void subscribe() throws Exception {
            // simple broker 는 RECEIPT 를 보내지 않는다. 호출하는 쪽이 SimpUserRegistry 로 구독 등록을 기다린다.
            send("SUBSCRIBE\nid:sub-0\ndestination:/user/queue/chat\n\n");
        }

        void subscribeNotifications() throws Exception {
            send("SUBSCRIBE\nid:sub-n\ndestination:/user/queue/notifications\n\n");
        }
    }

    private StompTestClient open(String origin, String query) throws Exception {
        StompTestClient client = new StompTestClient();
        var builder = http.newWebSocketBuilder().subprotocols("v12.stomp");
        if (origin != null) {
            builder.header("Origin", origin);
        }
        client.socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/ws" + query), client).get(5, TimeUnit.SECONDS);
        clients.add(client);
        return client;
    }

    /** 인증·구독까지 끝낸 연결(서버가 구독을 등록할 때까지 기다린다). */
    private StompTestClient ready(String token, long userId) throws Exception {
        StompTestClient c = open(OK_ORIGIN, "");
        c.stompConnect(token);
        c.expect("CONNECTED");
        int before = subscriptionCount(userId);
        c.subscribe();
        for (int i = 0; i < 100 && subscriptionCount(userId) <= before; i++) {
            Thread.sleep(50);
        }
        assertThat(subscriptionCount(userId)).as("구독이 등록돼야 한다").isGreaterThan(before);
        Thread.sleep(150); // 사용자 레지스트리 이벤트와 브로커 등록 사이의 짧은 간격
        return c;
    }

    private StompTestClient ready(long userId) throws Exception {
        return ready(token(userId), userId);
    }

    private int subscriptionCount(long userId) {
        var user = userRegistry.getUser(String.valueOf(userId));
        return user == null ? 0 : user.getSessions().stream().mapToInt(s -> s.getSubscriptions().size()).sum();
    }

    /** 서버가 ERROR 뒤 세션을 닫는다. 코드는 Spring 이 보내는 1002(프로토콜 오류) 또는 우리가 보내는 1008. */
    private void assertClosedAfterError(StompTestClient c) throws Exception {
        int code = c.awaitClose(5000);
        assertThat(code).isIn(1002, 1008);
    }

    private String token(long userId) {
        return tokens.generateAccessToken(userId, "u" + userId + "@t.com");
    }

    private String tokenWithExpiry(long userId, String type, Date expiry) {
        return Jwts.builder().subject(String.valueOf(userId)).claim("email", "x").claim("type", type)
                .issuedAt(new Date(System.currentTimeMillis() - 60_000)).expiration(expiry)
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    private HttpResponse<String> rest(String method, String path, long userId, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token(userId)).header("Content-Type", "application/json");
        b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long sendMessage(long matchId, long userId, String content, String clientId) throws Exception {
        HttpResponse<String> r = rest("POST", "/api/exchange/matches/" + matchId + "/messages", userId,
                "{\"content\":\"" + content + "\",\"clientMessageId\":\"" + clientId + "\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return JSON.readTree(r.body()).get("id").asLong();
    }

    // ------------------------------------------------------------------ 준비

    private record Party(long userId, long requestId) {}

    private long user(String nickname) {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, 'x', 'USER')", "tmp" + seq + "@t.com", nickname);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Party party(int n) {
        long userId = users.computeIfAbsent(n, k -> user("user" + k));
        String col = String.valueOf(n);
        jdbc.update("INSERT INTO ticket (performance_session_id, user_id, zone_label, zone_key, row_label, row_key, "
                + "col_label, col_key, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',NOW(6),NOW(6))",
                session1, userId, "Z1", "Z1", "1", "1", col, col);
        long ticketId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_request (ticket_id, status, created_at, updated_at) VALUES (?, 'OPEN', NOW(6), NOW(6))", ticketId);
        long requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,1)", requestId, session1);
        return new Party(userId, requestId);
    }

    private void wants(Party a, int otherCol) {
        jdbc.update("INSERT IGNORE INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount) VALUES (?,?,?,?,'ANY',NULL)",
                a.requestId, "Z1", "1", String.valueOf(otherCol));
    }

    /** 사용자 1·2 가 서로 희망하는 CHATTING 매칭을 만든다. 사용자 3 은 제3자(티켓 없음). */
    private long matchOf1And2() {
        Party p = party(1);
        Party q = party(2);
        wants(p, 2);
        wants(q, 1);
        users.computeIfAbsent(3, k -> user("outsider"));
        return matchService.propose(p.userId, p.requestId, q.requestId).id();
    }

    @BeforeEach
    void reset() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).endsWith("_it");
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : List.of("chat_message", "chat_read_state", "notification", "exchange_history", "exchange_ticket_lock", "exchange_match", "exchange_want_seat",
                "exchange_want_session", "exchange_want_range", "exchange_request", "ticket", "performance_session", "performance", "users")) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
        seq = 0;
        users.clear();
        long reg = user("registrant");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', '채팅 공연', '올림픽홀', ?)", reg);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)",
                LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0), performanceId);
        session1 = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @AfterEach
    void closeAll() throws Exception {
        for (StompTestClient c : clients) {
            try {
                c.socket.abort();
            } catch (Exception ignored) {
                // 이미 닫힘
            }
        }
        clients.clear();
        // 다음 테스트가 사용자당 연결 한도에 걸리지 않도록 서버 쪽 정리를 기다린다
        for (long uid : users.values()) {
            for (int i = 0; i < 50 && sessionRegistry.connectionCount(uid) > 0; i++) {
                Thread.sleep(100);
            }
        }
    }

    // ------------------------------------------------------------------ 테스트

    @Test
    void 참여자_둘은_메시지를_받고_제3자는_받지_못한다_탭_두_개도_모두_받는다() throws Exception {
        long match = matchOf1And2();
        long u1 = users.get(1);
        long u2 = users.get(2);
        long u3 = users.get(3);

        StompTestClient tab1a = ready(u1);
        StompTestClient tab1b = ready(u1);
        StompTestClient tab2 = ready(u2);
        StompTestClient outsider = ready(u3);

        long id = sendMessage(match, u1, "안녕하세요", "11111111-aaaa");

        for (StompTestClient c : List.of(tab1a, tab1b, tab2)) {
            Frame f = c.expect("MESSAGE");
            assertThat(f.headers().get("destination")).isEqualTo("/user/queue/chat");
            JsonNode body = JSON.readTree(f.body());
            assertThat(body.get("type").asText()).isEqualTo("MESSAGE");
            assertThat(body.get("matchId").asLong()).isEqualTo(match);
            JsonNode message = body.get("message");
            assertThat(message.get("id").asLong()).isEqualTo(id);
            assertThat(message.get("senderId").asLong()).isEqualTo(u1);
            assertThat(message.get("content").asText()).isEqualTo("안녕하세요");
            assertThat(message.get("clientMessageId").asText()).isEqualTo("11111111-aaaa");
            assertThat(message.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
        }
        assertThat(outsider.next(1500)).as("제3자는 아무것도 받지 못한다").isNull();
        assertThat(tab1a.next(300)).isNull();
    }

    @Test
    void 읽음_PUT_은_상대의_모든_세션에_READ_푸시를_보낸다() throws Exception {
        long match = matchOf1And2();
        long u1 = users.get(1);
        long u2 = users.get(2);
        StompTestClient c1 = ready(u1);
        StompTestClient c2 = ready(u2);
        StompTestClient outsider = ready(users.get(3));
        long id = sendMessage(match, u1, "읽어보세요", "22222222-bbbb");
        c1.expect("MESSAGE");
        c2.expect("MESSAGE");

        HttpResponse<String> r = rest("PUT", "/api/exchange/matches/" + match + "/messages/read", u2, "{\"lastReadMessageId\":" + id + "}");
        assertThat(r.statusCode()).isEqualTo(204);

        JsonNode read = JSON.readTree(c1.expect("MESSAGE").body());
        assertThat(read.get("type").asText()).isEqualTo("READ");
        assertThat(read.get("matchId").asLong()).isEqualTo(match);
        assertThat(read.get("lastReadMessageId").asLong()).isEqualTo(id);
        assertThat(c2.next(500)).as("읽은 본인에게는 READ 푸시가 없다").isNull();
        assertThat(outsider.next(500)).isNull();
    }

    @Test
    void 허용되지_않은_Origin_은_핸드셰이크에서_거부되고_허용_Origin_과_Origin_없음은_통과한다() throws Exception {
        assertThatThrownBy(() -> open("http://evil.example", ""))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(WebSocketHandshakeException.class)
                .satisfies(e -> assertThat(((WebSocketHandshakeException) e.getCause()).getResponse().statusCode()).isEqualTo(403));
        assertThatThrownBy(() -> open("https://localhost:5173", "")).isInstanceOf(ExecutionException.class);

        StompTestClient ok = open(OK_ORIGIN, "");
        ok.stompConnect(token(user("o1")));
        ok.expect("CONNECTED");
        StompTestClient noOrigin = open(null, "");
        noOrigin.stompConnect(token(user("o2")));
        noOrigin.expect("CONNECTED");
    }

    @Test
    void 핸드셰이크_경로_ws_는_인증_없이_통과한다_일반_GET_도_401_403이_아니다() throws Exception {
        HttpClient plain = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> r = plain.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ws")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as("Upgrade 없는 GET 은 Spring 이 400 으로 답한다").isEqualTo(400);
        // /ws/ 하위 경로도 permitAll 매칭(인증 오류가 아님)
        HttpResponse<String> sub = plain.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/ws/anything")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(sub.statusCode()).isNotIn(401, 403);
    }

    @Test
    void 만료_refresh_무효_토큰과_헤더_없음_쿼리스트링_토큰_CONNECT_는_ERROR_후_1008로_닫힌다() throws Exception {
        long uid = user("a");
        String valid = token(uid);
        String expired = tokenWithExpiry(uid, "access", new Date(System.currentTimeMillis() - 5_000));
        String refresh = tokens.generateRefreshToken(uid, "u@t.com");

        for (String bad : new String[]{expired, refresh, "garbage.token.value", valid + "x"}) {
            StompTestClient c = open(OK_ORIGIN, "");
            c.stompConnect(bad);
            Frame f = c.expect("ERROR");
            assertThat(f.toString()).contains("unauthorized").doesNotContain(bad);
            assertClosedAfterError(c);
        }
        // Authorization 헤더 없음 + 쿼리스트링 토큰(유효한 토큰이어도 읽지 않는다)
        StompTestClient q = open(OK_ORIGIN, "?access_token=" + valid + "&token=" + valid);
        q.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n");
        assertThat(q.expect("ERROR").toString()).contains("unauthorized");
        assertClosedAfterError(q);
        // Bearer 가 아닌 스킴, 토큰을 다른 헤더 이름(passcode/login)에 넣은 경우도 거부
        StompTestClient basic = open(OK_ORIGIN, "");
        basic.send("CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Basic " + valid + "\npasscode:" + valid + "\nlogin:" + uid + "\n\n");
        basic.expect("ERROR");
        assertClosedAfterError(basic);
        // 삭제된 사용자의 유효 토큰
        long ghost = user("ghost");
        String ghostToken = token(ghost);
        jdbc.update("DELETE FROM users WHERE id = ?", ghost);
        StompTestClient g = open(OK_ORIGIN, "");
        g.stompConnect(ghostToken);
        g.expect("ERROR");
        assertClosedAfterError(g);
    }

    @Test
    void CONNECT_하지_않고_구독하거나_오래_두면_거부되고_닫힌다() throws Exception {
        StompTestClient c = open(OK_ORIGIN, "");
        c.send("SUBSCRIBE\nid:s\ndestination:/user/queue/chat\n\n");
        c.expect("ERROR");
        assertClosedAfterError(c);

        StompTestClient idle = open(OK_ORIGIN, "");
        assertThat(idle.awaitClose(8000)).as("connect-timeout(4s) 뒤 정리 스케줄러가 닫는다").isEqualTo(1008);
    }

    @Test
    void 같은_세션의_두_번째_SUBSCRIBE는_거부되고_닫히며_같은_사용자의_다른_탭은_영향이_없다() throws Exception {
        long match = matchOf1And2();
        long u1 = users.get(1);
        StompTestClient healthy = ready(u1);
        StompTestClient dup = ready(u1);

        dup.send("SUBSCRIBE\nid:sub-1\ndestination:/user/queue/chat\n\n");
        Frame f = dup.expect("ERROR");
        assertThat(f.toString()).contains("duplicate subscription");
        assertClosedAfterError(dup);

        // 다른 탭은 그대로 한 번만 받는다
        sendMessage(match, users.get(2), "중복 구독 뒤", "44444444-dddd");
        assertThat(healthy.expect("MESSAGE")).isNotNull();
        assertThat(healthy.next(500)).isNull();
    }

    @Test
    void 제안을_받은_사용자의_모든_탭만_알림_신호를_받고_제안한_사람과_제3자는_받지_못한다() throws Exception {
        Party p = party(1);
        Party q = party(2);
        wants(p, 2);
        wants(q, 1);
        long u3 = users.computeIfAbsent(3, k -> user("outsider"));
        StompTestClient proposer = ready(p.userId);
        StompTestClient recipientA = ready(q.userId);
        StompTestClient recipientB = ready(q.userId);
        StompTestClient outsider = ready(u3);
        for (StompTestClient c : List.of(proposer, recipientA, recipientB, outsider)) {
            c.subscribeNotifications();
        }
        Thread.sleep(500); // 구독 등록(브로커 반영) 대기

        long matchId = matchService.propose(p.userId, p.requestId, q.requestId).id();

        for (StompTestClient c : List.of(recipientA, recipientB)) {
            Frame f = c.expect("MESSAGE");
            assertThat(f.headers().get("destination")).isEqualTo("/user/queue/notifications");
            JsonNode body = JSON.readTree(f.body());
            assertThat(body.get("type").asText()).isEqualTo("NOTIFICATION_CREATED");
            assertThat(body.get("notificationType").asText()).isEqualTo("PROPOSAL_RECEIVED");
            assertThat(body.get("matchId").asLong()).isEqualTo(matchId);
            assertThat(body.has("notificationId")).isTrue();
        }
        assertThat(proposer.next(500)).isNull();
        assertThat(outsider.next(300)).isNull();
    }

    @Test
    void 직접_topic_queue_구독과_클라이언트_SEND_는_모두_거부된다() throws Exception {
        long match = matchOf1And2();
        long u1 = users.get(1);
        StompTestClient victim = ready(u1);

        String[][] attempts = {
                {"SUBSCRIBE", "/topic/chat"}, {"SUBSCRIBE", "/topic/match." + match}, {"SUBSCRIBE", "/queue/chat"},
                {"SUBSCRIBE", "/queue/chat-user" + "abc"}, {"SUBSCRIBE", "/user/queue/chat/extra"}, {"SUBSCRIBE", "/user/" + u1 + "/queue/chat"},
                {"SEND", "/app/chat"}, {"SEND", "/topic/chat"}, {"SEND", "/queue/chat"}, {"SEND", "/user/queue/chat"}, {"SEND", "/user/" + u1 + "/queue/chat"},
        };
        for (String[] a : attempts) {
            StompTestClient attacker = ready(users.get(2));
            String extra = a[0].equals("SUBSCRIBE") ? "id:x9\n" : "content-type:text/plain\n";
            String payload = a[0].equals("SEND") ? "{\"type\":\"MESSAGE\",\"matchId\":" + match + "}" : "";
            attacker.send(a[0] + "\n" + extra + "destination:" + a[1] + "\n\n" + payload);
            Frame f = attacker.expect("ERROR");
            assertThat(f.toString()).as(a[0] + " " + a[1]).contains("forbidden");
            assertClosedAfterError(attacker);
        }
        assertThat(victim.next(1000)).as("공격 시도가 피해자에게 전달되지 않는다").isNull();
        // 그 뒤에도 정상 푸시는 동작
        sendMessage(match, users.get(2), "정상", "33333333-cccc");
        assertThat(victim.expect("MESSAGE")).isNotNull();
    }

    @Test
    void 사용자당_연결은_5개까지_6번째는_거부되고_하나_닫으면_다시_연결된다() throws Exception {
        long uid = user("many");
        String t = token(uid);
        List<StompTestClient> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(ready(t, uid));
        }
        StompTestClient sixth = open(OK_ORIGIN, "");
        sixth.stompConnect(t);
        Frame f = sixth.expect("ERROR");
        assertThat(f.toString()).contains("connection limit exceeded");
        assertClosedAfterError(sixth);
        // 다른 사용자는 영향 없음
        ready(user("other"));

        five.get(0).send("DISCONNECT\n\n");
        five.get(0).socket.sendClose(1000, "bye");
        five.get(0).awaitClose(5000);
        StompTestClient retry = null;
        for (int i = 0; i < 30 && retry == null; i++) {
            StompTestClient c = open(OK_ORIGIN, "");
            c.stompConnect(t);
            Frame r = c.next(3000);
            if (r != null && r.command().equals("CONNECTED")) {
                retry = c;
            } else {
                c.awaitClose(3000);
                Thread.sleep(100);
            }
        }
        assertThat(retry).as("한 연결이 닫히면 다시 연결할 수 있다").isNotNull();
    }

    @Test
    void 토큰이_만료되면_연결은_close_1008로_정리된다() throws Exception {
        long uid = user("short");
        String shortToken = tokenWithExpiry(uid, "access", new Date(System.currentTimeMillis() + 3_000));
        StompTestClient c = ready(shortToken, uid);
        assertThat(sessionRegistry.connectionCount(uid)).isEqualTo(1);
        long start = System.currentTimeMillis();
        assertThat(c.awaitClose(10_000)).isEqualTo(1008);
        assertThat(System.currentTimeMillis() - start).as("만료 시각 근처에서 닫힌다").isLessThan(6_000);
        for (int i = 0; i < 30 && sessionRegistry.connectionCount(uid) > 0; i++) {
            Thread.sleep(100);
        }
        assertThat(sessionRegistry.connectionCount(uid)).isZero();
    }
}
