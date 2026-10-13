package com.matjari.service;

import com.matjari.dto.request.TicketCreateRequest;
import com.matjari.dto.response.ChatDtos;
import com.matjari.dto.response.ExchangeMatchResponse;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.testsupport.RealMysqlSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 채팅 서비스의 실제 MySQL 통합 테스트(서비스 + 트랜잭션 + 잠금 + 제약). 환경변수가 없으면 건너뛴다:
 *   MATJARI_IT_JDBC_URL=jdbc:mysql://localhost:13392/matjari_ch2_it  MATJARI_IT_USER=root  MATJARI_IT_PASSWORD=...
 * 속도 제한은 이 테스트에서 끈다(ChatRateLimiterTest 가 따로 본다). 전파는 기록용 발행기로 받아 "커밋 뒤, 트랜잭션 밖"을 확인한다.
 */
@ExtendWith(RealMysqlSupport.Condition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "ticket.max-active-per-user=100000")
class ChatServiceMysqlTest {

    private static final int ROUNDS = 10;

    /** 전파 기록: 호출 시점에 활성 트랜잭션이 없는지, 그리고 메시지 행이 이미 다른 연결에서 보이는지도 함께 확인한다. */
    static final class RecordingPublisher implements ChatPushPublisher {
        final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
        final AtomicInteger insideTransaction = new AtomicInteger();
        final AtomicInteger notVisible = new AtomicInteger();
        volatile JdbcTemplate verifier;

        @Override
        public void publishMessage(Long matchId, ChatDtos.Message message, Collection<Long> recipientUserIds) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                insideTransaction.incrementAndGet();
            }
            Integer visible = verifier == null ? 1 : verifier.queryForObject("SELECT COUNT(*) FROM chat_message WHERE id = ?", Integer.class, message.id());
            if (visible == null || visible == 0) {
                notVisible.incrementAndGet();
            }
            events.add("MESSAGE:" + matchId + ":" + message.id() + ":" + new java.util.TreeSet<>(recipientUserIds));
        }

        @Override
        public void publishRead(Long matchId, Long recipientUserId, long lastReadMessageId) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                insideTransaction.incrementAndGet();
            }
            events.add("READ:" + matchId + ":" + recipientUserId + ":" + lastReadMessageId);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary // 실제 STOMP 구현(ChatPushPublisherImpl)과 함께 있어도 이 기록용 발행기를 쓴다
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }

        @Bean
        @Primary
        ChatRateLimiter unlimitedRateLimiter(Clock clock) {
            return new ChatRateLimiter(clock) {
                @Override
                public void acquire(Long userId) {
                    // 이 테스트에서는 제한 없음
                }
            };
        }
    }

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

    @Autowired
    private ChatService chatService;
    @Autowired
    private ExchangeMatchService matchService;
    @Autowired
    private ExchangeMatchQueryService queryService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private RecordingPublisher publisher;
    @Autowired
    private JdbcTemplate jdbc;

    private long seq;
    private long zoneSeq;
    private long performanceId;
    private long session1;
    private final Map<Integer, Long> users = new java.util.HashMap<>();

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
        zoneSeq = 0;
        users.clear();
        publisher.events.clear();
        publisher.insideTransaction.set(0);
        publisher.notVisible.set(0);
        publisher.verifier = jdbc;
        long reg = user("registrant");
        jdbc.update("INSERT INTO performance (created_at, updated_at, source_key, source_url, title, venue_name, registrant_id) "
                + "VALUES (NOW(6), NOW(6), 'k', 'http://x/1', '채팅 공연', '올림픽홀', ?)", reg);
        performanceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        session1 = session(LocalDateTime.now().plusDays(30).withMinute(0).withSecond(0).withNano(0));
    }

    // ------------------------------------------------------------------ 기능

    @Test
    void 보내기_목록_멱등_전파와_안_읽은_수_읽음_포인터의_기본_흐름() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        Object matchUpdatedAt = jdbc.queryForObject("SELECT updated_at FROM exchange_match WHERE id = ?", Object.class, m);

        ChatService.SendResult first = chatService.send(p.userId, m, "  안녕하세요!\r\n반가워요  ", "11111111-aaaa");
        assertThat(first.created()).isTrue();
        assertThat(first.message().content()).isEqualTo("안녕하세요!\n반가워요");
        assertThat(first.message().senderId()).isEqualTo(p.userId);
        assertThat(first.message().createdAt()).isNotNull();
        // 멱등: 같은 clientMessageId 는 같은 행(다른 본문을 보내도 기존 것), 전파는 한 번만
        ChatService.SendResult again = chatService.send(p.userId, m, "다른 글", "11111111-aaaa");
        assertThat(again.created()).isFalse();
        assertThat(again.message().id()).isEqualTo(first.message().id());
        assertThat(again.message().content()).isEqualTo("안녕하세요!\n반가워요");
        assertThat(count("SELECT COUNT(*) FROM chat_message")).isEqualTo(1);
        assertThat(publisher.events).containsExactly("MESSAGE:" + m + ":" + first.message().id() + ":[" + p.userId + ", " + q.userId + "]");
        assertThat(publisher.insideTransaction.get()).as("전파는 트랜잭션 밖").isZero();
        assertThat(publisher.notVisible.get()).as("전파 시점에 이미 커밋돼 있어야 한다").isZero();
        // 같은 id 를 다른 사람이 쓰면 별개의 메시지(유니크는 (clientMessageId, sender))
        assertThat(chatService.send(q.userId, m, "저도요", "11111111-aaaa").created()).isTrue();
        // 매칭 행은 UPDATE 하지 않는다: updated_at 불변
        assertThat(jdbc.queryForObject("SELECT updated_at FROM exchange_match WHERE id = ?", Object.class, m)).isEqualTo(matchUpdatedAt);

        for (int i = 0; i < 4; i++) {
            chatService.send(p.userId, m, "p" + i, "p-msg-000" + i);
        }
        // 안 읽은 수: q 는 p 가 보낸 5건, p 는 q 가 보낸 1건
        assertThat(queryService.findOne(q.userId, m).unreadChatCount()).isEqualTo(5);
        assertThat(queryService.findOne(p.userId, m).unreadChatCount()).isEqualTo(1);
        assertThat(queryService.findMine(q.userId, null, null, 0, 20).content().get(0).unreadChatCount()).isEqualTo(5);

        ChatDtos.MessagePage latest = chatService.list(q.userId, m, null, null, null);
        assertThat(latest.messages()).hasSize(6).isSortedAccordingTo(java.util.Comparator.comparing(ChatDtos.Message::id));
        assertThat(latest.hasMore()).isFalse();
        assertThat(latest.myLastReadMessageId()).isZero();
        assertThat(latest.counterpartLastReadMessageId()).isZero();
        ChatDtos.MessagePage older = chatService.list(q.userId, m, latest.messages().get(3).id(), null, 2);
        assertThat(older.messages()).extracting(ChatDtos.Message::id).containsExactly(latest.messages().get(1).id(), latest.messages().get(2).id());
        assertThat(older.hasMore()).isTrue();
        ChatDtos.MessagePage newer = chatService.list(q.userId, m, null, latest.messages().get(3).id(), null);
        assertThat(newer.messages()).extracting(ChatDtos.Message::id).containsExactly(latest.messages().get(4).id(), latest.messages().get(5).id());

        // 읽음: 3번째 메시지까지 -> q 의 안 읽은 수는 p 가 보낸 것 중 그 뒤 것만
        long third = latest.messages().get(2).id();
        publisher.events.clear();
        chatService.markRead(q.userId, m, third);
        assertThat(publisher.events).containsExactly("READ:" + m + ":" + p.userId + ":" + third);
        assertThat(chatService.list(p.userId, m, null, null, null).counterpartLastReadMessageId()).isEqualTo(third);
        assertThat(chatService.list(q.userId, m, null, null, null).myLastReadMessageId()).isEqualTo(third);
        assertThat(queryService.findOne(q.userId, m).unreadChatCount()).isEqualTo(3);   // 네 번째~여섯 번째 중 p 가 보낸 것: 인덱스 3,4,5 -> p 3건
        // 줄어들지 않는다 / 이미 읽은 위치는 푸시 없음
        publisher.events.clear();
        chatService.markRead(q.userId, m, latest.messages().get(0).id());
        assertThat(chatService.list(q.userId, m, null, null, null).myLastReadMessageId()).isEqualTo(third);
        assertThat(publisher.events).isEmpty();
        // 범위를 넘는 id 는 이 매칭의 마지막 메시지 id 로 잘린다
        chatService.markRead(q.userId, m, 999_999_999L);
        assertThat(chatService.list(q.userId, m, null, null, null).myLastReadMessageId()).isEqualTo(latest.messages().get(5).id());
        assertThat(queryService.findOne(q.userId, m).unreadChatCount()).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_read_state WHERE match_id = " + m)).isEqualTo(1);
        // 알림: propose 의 새 제안 1건 + 채팅 알림은 (수신자, 매칭)당 1행(메시지 수와 무관, V15)
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type <> 'CHAT_MESSAGE'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE' AND match_id = " + m)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE' AND user_id = " + q.userId + " AND read_at IS NOT NULL"))
                .as("q 가 읽음 포인터를 올렸으므로 q 의 채팅 알림은 읽음").isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notification WHERE type = 'CHAT_MESSAGE' AND user_id = " + p.userId + " AND read_at IS NULL"))
                .as("p 는 읽음 포인터를 올리지 않았다").isEqualTo(1);
    }

    @Test
    void 다른_매칭_메시지는_안_읽은_수와_읽음_절단에_섞이지_않는다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party r = party(3, "Z1");
        mutual(p, q);
        mutual(p, r);
        long m1 = matchService.propose(p.userId, p.requestId, q.requestId).id();
        long m2 = matchService.propose(p.userId, p.requestId, r.requestId).id();
        long a = chatService.send(q.userId, m1, "m1 from q", "m1-from-q-1").message().id();
        long b = chatService.send(r.userId, m2, "m2 from r", "m2-from-r-1").message().id();

        assertThat(queryService.findOne(p.userId, m1).unreadChatCount()).isEqualTo(1);
        assertThat(queryService.findOne(p.userId, m2).unreadChatCount()).isEqualTo(1);
        // m2 의 메시지 id(b)로 m1 을 읽음 처리하면 m1 의 마지막 메시지(a)로 잘린다
        chatService.markRead(p.userId, m1, b);
        assertThat(chatService.list(p.userId, m1, null, null, null).myLastReadMessageId()).isEqualTo(a);
        assertThat(queryService.findOne(p.userId, m1).unreadChatCount()).isZero();
        assertThat(queryService.findOne(p.userId, m2).unreadChatCount()).isEqualTo(1);
        // 같은 clientMessageId 를 다른 매칭에서 쓰면 409
        chatService.send(p.userId, m1, "p in m1", "p-reuse-id-001");
        assertThatThrownBy(() -> chatService.send(p.userId, m2, "x", "p-reuse-id-001")).isInstanceOf(ConflictException.class);
        assertThat(count("SELECT COUNT(*) FROM chat_message")).isEqualTo(3);
    }

    @Test
    void 예약중에도_보낼_수_있고_종료된_매칭은_쓰기_409_읽기와_읽음은_허용하며_닫힌_매칭의_안_읽은_수도_센다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        chatService.send(p.userId, m, "예약 전", "before-reserve-1");
        reserveFlow(q.userId, m);
        assertThat(chatService.send(q.userId, m, "예약했어요", "after-reserve-01").created()).isTrue();
        matchService.unreserve(p.userId, m);
        chatService.send(q.userId, m, "취소했네요", "after-unreserve-1");
        matchService.cancel(p.userId, m);

        assertThatThrownBy(() -> chatService.send(p.userId, m, "더 보내요", "closed-send-0001"))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("종료된 채팅에는 메시지를 보낼 수 없어요");
                    assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT").containsEntry("status", "CANCELED");
                });
        // 이미 저장된 메시지의 멱등 재전송(응답 유실 뒤 재시도)은 종료된 매칭에서도 200이고 새로 저장·전파되지 않는다
        int eventsBefore = publisher.events.size();
        ChatService.SendResult replay = chatService.send(p.userId, m, "예약 전", "before-reserve-1");
        assertThat(replay.created()).isFalse();
        assertThat(replay.message().content()).isEqualTo("예약 전");
        assertThat(publisher.events.size()).isEqualTo(eventsBefore);
        assertThat(count("SELECT COUNT(*) FROM chat_message")).isEqualTo(3);
        // 읽기·읽음은 허용, 닫힌 매칭도 안 읽은 수를 센다
        assertThat(chatService.list(p.userId, m, null, null, null).messages()).hasSize(3);
        assertThat(queryService.findOne(p.userId, m).status()).isEqualTo("CANCELED");
        assertThat(queryService.findOne(p.userId, m).unreadChatCount()).isEqualTo(2);
        chatService.markRead(p.userId, m, 999_999L);
        assertThat(queryService.findOne(p.userId, m).unreadChatCount()).isZero();

        // 완료된 매칭도 같다
        Party r = party(3, "Z2");
        Party s = party(4, "Z2");
        mutual(r, s);
        long m2 = matchService.propose(r.userId, r.requestId, s.requestId).id();
        reserveFlow(r.userId, m2);
        chatService.send(s.userId, m2, "완료 전 메시지", "before-complete-1");
        matchService.complete(r.userId, m2);
        matchService.complete(s.userId, m2);
        assertThatThrownBy(() -> chatService.send(s.userId, m2, "완료 후", "after-complete-001"))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("status", "COMPLETED"));
        assertThat(queryService.findOne(r.userId, m2).unreadChatCount()).isEqualTo(1);
        assertThat(chatService.list(r.userId, m2, null, null, null).messages()).hasSize(1);
    }

    @Test
    void 비참여자와_없는_매칭은_보내기_목록_읽음_모두_404다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        Party outsider = party(3, "Z9");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        chatService.send(p.userId, m, "비밀", "secret-msg-0001");

        assertThatThrownBy(() -> chatService.send(outsider.userId, m, "끼어들기", "outsider-msg-1")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> chatService.list(outsider.userId, m, null, null, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> chatService.markRead(outsider.userId, m, 1L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> chatService.send(p.userId, 987654L, "없는 매칭", "no-such-match-1")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> chatService.list(p.userId, 987654L, null, null, null)).isInstanceOf(NotFoundException.class);
        assertThat(count("SELECT COUNT(*) FROM chat_message")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chat_read_state")).isZero();
    }

    @Test
    void 잘못된_본문은_400이고_아무것도_저장되지_않는다() {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        for (String bad : new String[]{"", "   ", "a".repeat(1001), "제어\u0007문자", "제로폭​문자"}) {
            assertThatThrownBy(() -> chatService.send(p.userId, m, bad, "bad-body-00001")).isInstanceOf(FieldValidationException.class);
        }
        assertThatThrownBy(() -> chatService.send(p.userId, m, "ok", "x")).isInstanceOf(FieldValidationException.class);
        // 경계: 1000자(한글, 이모지 포함 코드 포인트)는 저장된다
        assertThat(chatService.send(p.userId, m, "가".repeat(1000), "limit-korean-001").created()).isTrue();
        assertThat(chatService.send(p.userId, m, "😀".repeat(1000), "limit-emoji-0001").created()).isTrue();
        assertThat(chatService.list(p.userId, m, null, null, null).messages().get(1).content()).isEqualTo("😀".repeat(1000));
        assertThat(count("SELECT COUNT(*) FROM chat_message")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 동시성

    @Test
    void 동시_전송은_누락_없이_저장되고_읽는_쪽이_커서로_따라가면_모두_받는다_id순서는_커밋순서다() throws Exception {
        Party p = party(1, "Z1");
        Party q = party(2, "Z1");
        mutual(p, q);
        long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
        int perThread = 40;
        int senders = 4;   // p 2개, q 2개
        AtomicBoolean done = new AtomicBoolean();
        Map<Long, Set<Long>> seenByReader = new ConcurrentHashMap<>();
        seenByReader.put(p.userId, ConcurrentHashMap.newKeySet());
        seenByReader.put(q.userId, ConcurrentHashMap.newKeySet());

        ExecutorService pool = Executors.newFixedThreadPool(senders + 2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> sendFutures = new ArrayList<>();
            for (int t = 0; t < senders; t++) {
                long sender = t % 2 == 0 ? p.userId : q.userId;
                int tid = t;
                sendFutures.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        chatService.send(sender, m, "t" + tid + "-" + i, UUID.randomUUID().toString());
                    }
                    return null;
                }));
            }
            List<Future<?>> readFutures = new ArrayList<>();
            for (long reader : new long[]{p.userId, q.userId}) {
                readFutures.add(pool.submit(() -> {
                    go.await();
                    long cursor = 0;
                    while (true) {
                        boolean finishing = done.get();
                        ChatDtos.MessagePage page = chatService.list(reader, m, null, cursor, 100);
                        for (ChatDtos.Message msg : page.messages()) {
                            assertThat(msg.id()).as("커서는 항상 앞으로만").isGreaterThan(cursor);
                            cursor = msg.id();
                            seenByReader.get(reader).add(msg.id());
                        }
                        if (finishing && page.messages().isEmpty()) {
                            return null;
                        }
                    }
                }));
            }
            go.countDown();
            for (Future<?> f : sendFutures) {
                f.get(120, TimeUnit.SECONDS);
            }
            done.set(true);
            for (Future<?> f : readFutures) {
                f.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<Long> ids = jdbc.queryForList("SELECT id FROM chat_message WHERE match_id = ? ORDER BY id", Long.class, m);
        assertThat(ids).hasSize(senders * perThread);
        // 전송 중에 커서(after)로만 따라간 두 독자가 각각 모든 메시지를 받았는지(누락 0)
        assertThat(seenByReader.get(p.userId)).as("p 독자").containsExactlyInAnyOrderElementsOf(ids);
        assertThat(seenByReader.get(q.userId)).as("q 독자").containsExactlyInAnyOrderElementsOf(ids);
        assertThat(publisher.events.stream().filter(e -> e.startsWith("MESSAGE:")).count()).isEqualTo(senders * perThread);
        assertThat(publisher.insideTransaction.get()).isZero();
        assertThat(publisher.notVisible.get()).isZero();
    }

    @Test
    void 같은_clientMessageId를_동시에_보내면_한_행만_저장되고_나머지는_멱등_응답이다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            String cid = UUID.randomUUID().toString();
            List<Callable<Object>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(() -> chatService.send(p.userId, m, "동시 재전송", cid));
            }

            List<Object> results = runConcurrently(calls);

            assertThat(results).as("round " + round).allSatisfy(r -> assertThat(r).isInstanceOf(ChatService.SendResult.class));
            assertThat(results.stream().filter(r -> ((ChatService.SendResult) r).created()).count()).as("round " + round).isEqualTo(1);
            assertThat(results.stream().map(r -> ((ChatService.SendResult) r).message().id()).distinct().count()).isEqualTo(1);
            assertThat(count("SELECT COUNT(*) FROM chat_message WHERE match_id = " + m)).as("round " + round).isEqualTo(1);
        }
    }

    @Test
    void 전송과_취소가_겹쳐도_취소_커밋_뒤에는_메시지가_커밋되지_않는다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();

            long maxIdAtCancel = raceAgainstClose(m, p, q, () -> matchService.cancel(q.userId, m));

            assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m)).isEqualTo("CANCELED");
            assertThat(count("SELECT COUNT(*) FROM chat_message WHERE match_id = " + m + " AND id > " + maxIdAtCancel))
                    .as("round " + round + ": 취소 커밋 뒤에 커밋된 메시지").isZero();
        }
    }

    @Test
    void 전송과_교환_완료가_겹쳐도_완료_커밋_뒤에는_메시지가_커밋되지_않는다() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Party p = party(1, "Z" + (++zoneSeq));
            Party q = party(2, "Z" + zoneSeq);
            mutual(p, q);
            long m = matchService.propose(p.userId, p.requestId, q.requestId).id();
            reserveFlow(p.userId, m);
            matchService.complete(p.userId, m);

            long maxIdAtComplete = raceAgainstClose(m, p, q, () -> matchService.complete(q.userId, m));

            assertThat(jdbc.queryForObject("SELECT status FROM exchange_match WHERE id = ?", String.class, m)).isEqualTo("COMPLETED");
            assertThat(count("SELECT COUNT(*) FROM chat_message WHERE match_id = " + m + " AND id > " + maxIdAtComplete))
                    .as("round " + round + ": 완료 커밋 뒤에 커밋된 메시지").isZero();
        }
    }

    /**
     * 두 참여자가 계속 보내는 동안 closer 를 실행한다. 보내는 쪽은 첫 409 에서 멈춘다. closer 가 돌아온 직후(= 종료가 커밋된 뒤)의 최대 메시지 id 를 돌려준다.
     * 호출자는 그 id 보다 큰 메시지가 없어야 한다고 확인한다.
     */
    private long raceAgainstClose(long matchId, Party p, Party q, Runnable closer) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger conflicts = new AtomicInteger();
            List<Future<Integer>> senders = new ArrayList<>();
            for (long sender : new long[]{p.userId, q.userId}) {
                senders.add(pool.submit(() -> {
                    go.await();
                    int sent = 0;
                    for (int i = 0; i < 5000; i++) {
                        try {
                            chatService.send(sender, matchId, "msg" + i, UUID.randomUUID().toString());
                            sent++;
                        } catch (ConflictException e) {
                            assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT");
                            conflicts.incrementAndGet();
                            break;
                        }
                    }
                    return sent;
                }));
            }
            go.countDown();
            Thread.sleep(ThreadLocalRandom.current().nextInt(5, 40));
            closer.run();
            Long maxId = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM chat_message WHERE match_id = ?", Long.class, matchId);
            for (Future<Integer> f : senders) {
                f.get(120, TimeUnit.SECONDS);
            }
            assertThat(conflicts.get()).as("두 보내는 쪽 모두 종료를 409 로 알게 된다").isEqualTo(2);
            return maxId;
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 혼합 스트레스: 전송, 예약/예약 취소 토글, 교환 완료, 티켓 등록, 알림 읽음·모두 읽음·읽은 알림 삭제를 한꺼번에 돌린다.
     * 교착(MySQL 1213)은 한 건도 허용하지 않는다. 락 대기 초과 등 다른 잠금 실패(503 으로 매핑되는 PessimisticLockingFailureException)는 세어서 출력한다.
     */
    @Test
    void 혼합_스트레스에서_교착_1213이_한_건도_없다() throws Exception {
        int pairs = 3;
        List<Party[]> chatPairs = new ArrayList<>();
        List<Long> chatMatches = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            Party p = party(1 + 2 * i, "S" + i);
            Party q = party(2 + 2 * i, "S" + i);
            mutual(p, q);
            chatPairs.add(new Party[]{p, q});
            chatMatches.add(matchService.propose(p.userId, p.requestId, q.requestId).id());
        }
        int completes = 10;
        List<Party[]> completing = new ArrayList<>();
        List<Long> completeMatches = new ArrayList<>();
        for (int i = 0; i < completes; i++) {
            Party p = party(1 + 2 * i, "C" + i);   // 같은 사용자 풀 재사용: 사용자 행 경합을 만든다
            Party q = party(2 + 2 * i, "C" + i);
            mutual(p, q);
            completing.add(new Party[]{p, q});
            completeMatches.add(matchService.propose(p.userId, p.requestId, q.requestId).id());
        }
        long ticketUser = users.get(1);

        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger otherLockFailures = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        ConcurrentLinkedQueue<String> unexpectedMessages = new ConcurrentLinkedQueue<>();
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger sentOk = new AtomicInteger();
        AtomicInteger completedOk = new AtomicInteger();
        AtomicInteger ticketsOk = new AtomicInteger();

        java.util.function.Consumer<Throwable> classify = e -> {
            Throwable root = e;
            boolean deadlock = false;
            while (root != null) {
                if (root instanceof java.sql.SQLException se && se.getErrorCode() == 1213) {
                    deadlock = true;
                }
                if (root.getCause() == root) {
                    break;
                }
                root = root.getCause();
            }
            if (deadlock) {
                deadlocks.incrementAndGet();
            } else if (e instanceof org.springframework.dao.PessimisticLockingFailureException) {
                otherLockFailures.incrementAndGet();
            } else if (e instanceof ConflictException || e instanceof FieldValidationException
                    || e instanceof com.matjari.exception.BusinessRuleException || e instanceof NotFoundException) {
                // 업무 규칙으로 인한 정상 거절
            } else {
                unexpected.incrementAndGet();
                unexpectedMessages.add(e.toString());
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2 * pairs + 3 + completes + 2);
        List<Future<?>> workers = new ArrayList<>();
        List<Future<?>> background = new ArrayList<>();
        try {
            CountDownLatch go = new CountDownLatch(1);
            // 보내는 쪽: 채팅 매칭마다 양쪽
            for (int i = 0; i < pairs; i++) {
                long m = chatMatches.get(i);
                for (Party who : chatPairs.get(i)) {
                    background.add(pool.submit(() -> {
                        go.await();
                        int n = 0;
                        while (!stop.get()) {
                            try {
                                chatService.send(who.userId, m, "stress " + (n++), UUID.randomUUID().toString());
                                sentOk.incrementAndGet();
                            } catch (Exception e) {
                                classify.accept(e);
                            }
                            if (n % 10 == 0) {
                                try {
                                    chatService.markRead(who.userId, m, Long.MAX_VALUE);
                                } catch (Exception e) {
                                    classify.accept(e);
                                }
                            }
                        }
                        return null;
                    }));
                }
            }
            // 예약/예약 취소 토글 (채팅 매칭 첫 번째와 두 번째)
            for (int i = 0; i < 2; i++) {
                long m = chatMatches.get(i);
                Party[] pair = chatPairs.get(i);
                background.add(pool.submit(() -> {
                    go.await();
                    int n = 0;
                    while (!stop.get()) {
                        try {
                            reserveFlow(pair[n % 2].userId, m);
                            matchService.unreserve(pair[(n + 1) % 2].userId, m);
                        } catch (Exception e) {
                            classify.accept(e);
                        }
                        n++;
                    }
                    return null;
                }));
            }
            // 티켓 등록
            background.add(pool.submit(() -> {
                go.await();
                int n = 0;
                while (!stop.get()) {
                    try {
                        ticketService.create(ticketUser, new TicketCreateRequest(session1, "TK" + (n % 50), "1", String.valueOf(1 + n++)));
                        ticketsOk.incrementAndGet();
                    } catch (Exception e) {
                        classify.accept(e);
                    }
                }
                return null;
            }));
            // 알림 읽음·모두 읽음·읽은 알림 삭제
            background.add(pool.submit(() -> {
                go.await();
                while (!stop.get()) {
                    for (long u : new ArrayList<>(users.values())) {
                        try {
                            notificationService.markAllRead(u, null);
                            notificationService.deleteRead(u);
                            List<Long> ids = jdbc.queryForList("SELECT id FROM notification WHERE user_id = ? LIMIT 3", Long.class, u);
                            for (Long id : ids) {
                                notificationService.markRead(u, id);
                            }
                        } catch (Exception e) {
                            classify.accept(e);
                        }
                    }
                }
                return null;
            }));
            // 교환 완료: 두 참여자가 동시에 예약 -> 수락
            for (int i = 0; i < completes; i++) {
                long m = completeMatches.get(i);
                Party[] pair = completing.get(i);
                workers.add(pool.submit(() -> {
                    go.await();
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try {
                            reserveFlow(pair[0].userId, m);
                            break;
                        } catch (Exception e) {
                            classify.accept(e);
                        }
                    }
                    Future<?> other = pool.submit(() -> {
                        try {
                            matchService.complete(pair[1].userId, m);
                            completedOk.incrementAndGet();
                        } catch (Exception e) {
                            classify.accept(e);
                        }
                    });
                    try {
                        matchService.complete(pair[0].userId, m);
                        completedOk.incrementAndGet();
                    } catch (Exception e) {
                        classify.accept(e);
                    }
                    other.get(60, TimeUnit.SECONDS);
                    chatService.send(pair[0].userId, m, "완료 직후", UUID.randomUUID().toString());
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : workers) {
                try {
                    f.get(180, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    classify.accept(e.getCause());   // 마지막 전송이 409 인 것은 정상(이미 완료)
                }
            }
            Thread.sleep(1500);   // 완료가 끝난 뒤에도 잠시 더 섞어 돌린다
            stop.set(true);
            for (Future<?> f : background) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }

        System.out.println("[chat-stress] sent=" + sentOk + " completed=" + completedOk + " tickets=" + ticketsOk
                + " deadlocks=" + deadlocks + " otherLockFailures(503)=" + otherLockFailures + " unexpected=" + unexpected);
        assertThat(deadlocks.get()).as("교착 1213").isZero();
        assertThat(unexpected.get()).as("예상 밖 예외: " + unexpectedMessages).isZero();
        assertThat(sentOk.get()).isGreaterThan(0);
        // 불변식: 종료된 매칭에는 종료 시각 뒤에 만들어진 메시지가 없고, 예약 불변식이 유지된다
        assertThat(count("SELECT COUNT(*) FROM exchange_ticket_lock l JOIN exchange_match m ON m.id = l.match_id WHERE m.status <> 'RESERVED'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match m WHERE m.status = 'RESERVED' AND "
                + "(SELECT COUNT(*) FROM exchange_ticket_lock l WHERE l.match_id = m.id) <> 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM exchange_match m WHERE m.status = 'COMPLETED' AND "
                + "(SELECT COUNT(*) FROM exchange_history h WHERE h.match_id = m.id) <> 2")).isZero();
        assertThat(count("SELECT COUNT(*) FROM chat_read_state s WHERE s.last_read_message_id > "
                + "COALESCE((SELECT MAX(c.id) FROM chat_message c WHERE c.match_id = s.match_id), 0)")).as("포인터가 마지막 메시지를 넘지 않는다").isZero();
    }

    // ------------------------------------------------------------------ 도우미

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    /** 모든 호출을 같은 순간에 출발시키고 결과(응답 또는 던진 예외)를 모은다. 교착·락 대기 초과·DB 예외는 테스트 실패로 본다. */
    private List<Object> runConcurrently(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return call.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            for (Object r : results) {
                if (r instanceof DataAccessException || r instanceof IllegalStateException) {
                    throw new AssertionError("교착/락 대기 초과/DB 예외가 났다: " + r);
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Party(long userId, long ticketId, long requestId, String zone, String row, String col) {}

    private long user(String nickname) {
        seq++;
        jdbc.update("INSERT INTO users (created_at, email, nickname, password, role) VALUES (NOW(6), ?, ?, 'x', 'USER')",
                "u" + seq + "@t.com", nickname);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long session(LocalDateTime startsAt) {
        jdbc.update("INSERT INTO performance_session (created_at, starts_at, performance_id) VALUES (NOW(6), ?, ?)", startsAt, performanceId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 사용자 n 번째(고정 사용자 풀)의 티켓·요청. 열은 "1", 번은 사용자 번호로 둔다. */
    private Party party(int n, String zone) {
        long userId = users.computeIfAbsent(n, k -> user("user" + k));
        String col = String.valueOf(n);
        jdbc.update("INSERT INTO ticket (performance_session_id, user_id, zone_label, zone_key, row_label, row_key, "
                + "col_label, col_key, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',NOW(6),NOW(6))",
                session1, userId, zone, zone, "1", "1", col, col);
        long ticketId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_request (ticket_id, status, created_at, updated_at) "
                + "VALUES (?, 'OPEN', NOW(6), NOW(6))", ticketId);
        long requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO exchange_want_session (request_id, performance_session_id, priority) VALUES (?,?,1)", requestId, session1);
        return new Party(userId, ticketId, requestId, zone, "1", col);
    }

    private void wants(Party a, Party... others) {
        for (Party o : others) {
            jdbc.update("INSERT IGNORE INTO exchange_want_seat (request_id, zone_key, row_key, col_key, extra_type, extra_amount) "
                    + "VALUES (?,?,?,?,'ANY',NULL)", a.requestId, o.zone, o.row, o.col);
        }
    }

    private void mutual(Party a, Party b) {
        wants(a, b);
        wants(b, a);
    }

    /** 예약 제안 -> 상대 수락으로 RESERVED 를 만든다(V15). */
    private com.matjari.dto.response.ExchangeMatchResponse reserveFlow(long proposerId, long matchId) {
        return com.matjari.testsupport.ReserveFlow.reserve(matchService, jdbc, proposerId, matchId);
    }
}
