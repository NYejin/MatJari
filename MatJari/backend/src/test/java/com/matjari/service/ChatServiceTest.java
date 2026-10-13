package com.matjari.service;

import com.matjari.domain.ChatMessage;
import com.matjari.domain.ExchangeMatch;
import com.matjari.domain.ExchangeMatchStatus;
import com.matjari.dto.response.ChatDtos;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ChatMessageRepository;
import com.matjari.repository.ChatReadStateRepository;
import com.matjari.repository.ExchangeMatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ChatService 단위 테스트(저장소 mock). 잠금·동시성·SQL 정확성은 ChatServiceMysqlTest 가 실제 MySQL 로 본다. */
class ChatServiceTest {

    private static final long MATCH = 50L;
    private static final long ME = 1L;
    private static final long OTHER = 2L;
    private static final String CID = "client-msg-0001";

    private ExchangeMatchRepository matchRepository;
    private ChatMessageRepository messageRepository;
    private ChatReadStateRepository readStateRepository;
    private ChatPushPublisher publisher;
    private ChatRateLimiter rateLimiter;
    private NotificationService notificationService;
    private ChatService service;
    private ExchangeMatch match;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        matchRepository = mock(ExchangeMatchRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        readStateRepository = mock(ChatReadStateRepository.class);
        publisher = mock(ChatPushPublisher.class);
        rateLimiter = mock(ChatRateLimiter.class);
        notificationService = mock(NotificationService.class);
        ObjectProvider<ChatPushPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any(Supplier.class))).thenReturn(publisher);
        Clock clock = Clock.fixed(Instant.parse("2026-10-11T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new ChatService(matchRepository, messageRepository, readStateRepository, rateLimiter, notificationService, provider, clock,
                PerformanceFixtures.noopTransactionManager());

        match = new ExchangeMatch();
        ReflectionTestUtils.setField(match, "id", MATCH);
        ReflectionTestUtils.setField(match, "userAId", ME);
        ReflectionTestUtils.setField(match, "userBId", OTHER);
        when(matchRepository.findById(MATCH)).thenReturn(Optional.of(match));
        when(matchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));
        when(messageRepository.saveAndFlush(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "id", 700L);
            ReflectionTestUtils.setField(m, "createdAt", LocalDateTime.of(2026, 10, 11, 12, 0, 0));
            return m;
        });
        when(messageRepository.findByClientMessageIdAndSenderId(any(), anyLong())).thenReturn(Optional.empty());
    }

    private void status(ExchangeMatchStatus s) {
        ReflectionTestUtils.setField(match, "status", s);
    }

    private static ChatMessage stored(long id, long sender, String content) {
        ChatMessage m = ChatMessage.of(MATCH, sender, content, "cid-" + id + "-aaaaaa");
        ReflectionTestUtils.setField(m, "id", id);
        ReflectionTestUtils.setField(m, "createdAt", LocalDateTime.of(2026, 10, 11, 12, 0, 0));
        return m;
    }

    // ------------------------------------------------------------------ 전송

    @Test
    void 전송은_참여자_확인_후_매칭을_잠그고_저장한_뒤_두_참여자에게_전파한다() {
        ChatService.SendResult r = service.send(ME, MATCH, "  안녕하세요\r\n반가워요  ", CID);

        assertThat(r.created()).isTrue();
        assertThat(r.message().id()).isEqualTo(700L);
        assertThat(r.message().content()).isEqualTo("안녕하세요\n반가워요");
        assertThat(r.message().senderId()).isEqualTo(ME);
        InOrder order = inOrder(matchRepository, messageRepository, notificationService, publisher);
        order.verify(matchRepository).findById(MATCH);
        order.verify(matchRepository).findByIdForUpdate(MATCH);
        order.verify(messageRepository).saveAndFlush(any(ChatMessage.class));
        order.verify(notificationService).notifyChatMessage(OTHER, MATCH);   // 저장 성공 뒤, 상대에게
        order.verify(publisher).publishMessage(eq(MATCH), eq(r.message()), eq(List.of(ME, OTHER)));
    }

    @Test
    void RESERVED도_보낼_수_있고_CANCELED_COMPLETED는_409이며_저장도_전파도_없다() {
        status(ExchangeMatchStatus.RESERVED);
        assertThat(service.send(OTHER, MATCH, "예약됐어요", CID).created()).isTrue();

        for (ExchangeMatchStatus closed : List.of(ExchangeMatchStatus.CANCELED, ExchangeMatchStatus.COMPLETED)) {
            status(closed);
            assertThatThrownBy(() -> service.send(ME, MATCH, "더 보내요", "other-cid-0002"))
                    .isInstanceOfSatisfying(ConflictException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("종료된 채팅에는 메시지를 보낼 수 없어요");
                        assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT").containsEntry("status", closed.name());
                    });
        }
        verify(messageRepository).saveAndFlush(any());   // 위 RESERVED 한 번뿐
        verify(publisher).publishMessage(any(), any(), any());
    }

    @Test
    void 비참여자와_없는_매칭은_404이고_잠금_쿼리도_하지_않는다() {
        assertThatThrownBy(() -> service.send(99L, MATCH, "hi", CID)).isInstanceOf(NotFoundException.class);
        when(matchRepository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.send(ME, 404L, "hi", CID)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.list(99L, MATCH, null, null, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.markRead(99L, MATCH, 1L)).isInstanceOf(NotFoundException.class);
        verify(matchRepository, never()).findByIdForUpdate(anyLong());
        verifyNoInteractions(publisher);
    }

    @Test
    void 같은_clientMessageId_재전송은_기존_행을_돌려주고_다시_저장하거나_전파하지_않는다() {
        ChatMessage existing = stored(650L, ME, "먼저 보낸 글");
        when(messageRepository.findByClientMessageIdAndSenderId(CID, ME)).thenReturn(Optional.of(existing));

        ChatService.SendResult r = service.send(ME, MATCH, "다시 보낸 글", CID);

        assertThat(r.created()).isFalse();
        assertThat(r.message().id()).isEqualTo(650L);
        assertThat(r.message().content()).isEqualTo("먼저 보낸 글");
        verify(messageRepository, never()).saveAndFlush(any());
        verify(notificationService, never()).notifyChatMessage(anyLong(), anyLong());
        verifyNoInteractions(publisher);
    }

    @Test
    void 다른_매칭에서_쓴_clientMessageId는_409다() {
        ChatMessage existing = ChatMessage.of(77L, ME, "x", CID);
        ReflectionTestUtils.setField(existing, "id", 1L);
        when(messageRepository.findByClientMessageIdAndSenderId(CID, ME)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.send(ME, MATCH, "hi", CID)).isInstanceOf(ConflictException.class);
    }

    @Test
    void 전파가_실패해도_저장_결과는_그대로_돌려준다() {
        doThrow(new IllegalStateException("broker down")).when(publisher).publishMessage(any(), any(), any());

        ChatService.SendResult r = service.send(ME, MATCH, "hi", CID);

        assertThat(r.created()).isTrue();
        verify(messageRepository).saveAndFlush(any());
    }

    @Test
    void 속도_제한에_걸리면_저장도_전파도_없이_예외가_그대로_나간다() {
        doThrow(new ChatRateLimiter.ChatRateLimitedException(3)).when(rateLimiter).acquire(ME);

        assertThatThrownBy(() -> service.send(ME, MATCH, "hi", CID)).isInstanceOf(ChatRateLimiter.ChatRateLimitedException.class);
        verify(messageRepository, never()).saveAndFlush(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void 비참여자는_속도_제한_이전에_404이고_속도_제한을_소모하지_않는다() {
        assertThatThrownBy(() -> service.send(99L, MATCH, "hi", CID)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void 멱등_재전송은_종료된_매칭에서도_200이고_속도_제한을_소모하지_않으며_전파하지_않는다() {
        ChatMessage existing = stored(650L, ME, "먼저 보낸 글");
        when(messageRepository.findByClientMessageIdAndSenderId(CID, ME)).thenReturn(Optional.of(existing));
        doThrow(new ChatRateLimiter.ChatRateLimitedException(3)).when(rateLimiter).acquire(ME);

        for (ExchangeMatchStatus closed : List.of(ExchangeMatchStatus.CANCELED, ExchangeMatchStatus.COMPLETED)) {
            status(closed);
            ChatService.SendResult r = service.send(ME, MATCH, "먼저 보낸 글", CID);
            assertThat(r.created()).isFalse();
            assertThat(r.message().id()).isEqualTo(650L);
        }
        verifyNoInteractions(rateLimiter, publisher);
        verify(messageRepository, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------ 본문 정규화

    @Test
    void 본문_정규화_규칙() {
        assertThat(ChatService.normalizeContent("  a\r\nb\tc\n  ")).isEqualTo("a\nb\tc");
        assertThat(ChatService.normalizeContent("가".repeat(1000))).hasSize(1000);
        assertThat(ChatService.normalizeContent("😀".repeat(1000))).as("코드 포인트 기준 1000자").hasSize(2000);
        assertThat(ChatService.normalizeContent("👨‍👩")).as("ZWJ 이모지 조합은 허용").contains("‍");

        for (String bad : new String[]{null, "", "   ", "\n\t ", "a".repeat(1001), "😀".repeat(1001), "a\u0000b", "a\u0007b", "a\rb",
                "a\u001Bb", "a\u007Fb", "a\u0085b", "a​b", "a‌b", "a﻿b", "a‮b", "a⁦b", "a\uD83Db"}) {
            assertThatThrownBy(() -> ChatService.normalizeContent(bad)).as(String.valueOf(bad))
                    .isInstanceOf(FieldValidationException.class);
        }
    }

    @Test
    void clientMessageId는_8_36자_영숫자_하이픈_밑줄만_허용한다() {
        for (String bad : new String[]{null, "", "short", "a".repeat(37), "has space123", "한글한글한글한글한글", "semi;colon-1"}) {
            assertThatThrownBy(() -> service.send(ME, MATCH, "hi", bad)).as(String.valueOf(bad))
                    .isInstanceOf(FieldValidationException.class);
        }
        assertThat(service.send(ME, MATCH, "hi", "123e4567-e89b-12d3-a456-426614174000").created()).isTrue();
        assertThat(service.send(ME, MATCH, "hi", "a".repeat(8)).created()).isTrue();
    }

    // ------------------------------------------------------------------ 목록

    @Test
    void 최신_조회는_size_플러스_1건을_가져와_오름차순으로_돌려주고_hasMore를_계산한다() {
        List<ChatMessage> desc = new ArrayList<>();
        for (long id = 10; id >= 7; id--) {
            desc.add(stored(id, OTHER, "m" + id));
        }
        when(messageRepository.findLatest(MATCH, 4)).thenReturn(desc);
        when(readStateRepository.find(MATCH, ME)).thenReturn(Optional.of(8L));
        when(readStateRepository.find(MATCH, OTHER)).thenReturn(Optional.empty());

        ChatDtos.MessagePage page = service.list(ME, MATCH, null, null, 3);

        assertThat(page.messages()).extracting(ChatDtos.Message::id).containsExactly(8L, 9L, 10L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.myLastReadMessageId()).isEqualTo(8L);
        assertThat(page.counterpartLastReadMessageId()).isZero();
    }

    @Test
    void before는_위로_after는_아래로_가져오고_기본_크기는_30과_100이며_최대_100이다() {
        when(messageRepository.findBefore(eq(MATCH), eq(20L), anyInt())).thenReturn(List.of(stored(19, ME, "a"), stored(18, ME, "b")));
        ChatDtos.MessagePage older = service.list(ME, MATCH, 20L, null, null);
        assertThat(older.messages()).extracting(ChatDtos.Message::id).containsExactly(18L, 19L);
        assertThat(older.hasMore()).isFalse();
        verify(messageRepository).findBefore(MATCH, 20L, 31);

        when(messageRepository.findAfter(eq(MATCH), eq(5L), anyInt())).thenReturn(List.of(stored(6, ME, "a"), stored(7, ME, "b")));
        ChatDtos.MessagePage newer = service.list(ME, MATCH, null, 5L, null);
        assertThat(newer.messages()).extracting(ChatDtos.Message::id).containsExactly(6L, 7L);
        verify(messageRepository).findAfter(MATCH, 5L, 101);

        service.list(ME, MATCH, null, null, 5000);
        verify(messageRepository).findLatest(MATCH, 101);
    }

    @Test
    void after_조회에서_size를_넘으면_먼_쪽_1건을_버리고_hasMore가_참이다() {
        when(messageRepository.findAfter(MATCH, 5L, 3)).thenReturn(List.of(stored(6, ME, "a"), stored(7, ME, "b"), stored(8, ME, "c")));

        ChatDtos.MessagePage page = service.list(ME, MATCH, null, 5L, 2);

        assertThat(page.messages()).extracting(ChatDtos.Message::id).containsExactly(6L, 7L);
        assertThat(page.hasMore()).isTrue();
    }

    @Test
    void 잘못된_목록_파라미터는_400이고_종료된_매칭도_읽을_수_있다() {
        assertThatThrownBy(() -> service.list(ME, MATCH, 5L, 3L, null)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.list(ME, MATCH, null, null, 0)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.list(ME, MATCH, -1L, null, null)).isInstanceOf(FieldValidationException.class);

        status(ExchangeMatchStatus.CANCELED);
        assertThat(service.list(ME, MATCH, null, null, null).messages()).isEmpty();
    }

    // ------------------------------------------------------------------ 읽음

    @Test
    void 읽음은_실제_메시지_id로_잘라_포인터를_올리고_상대에게_READ를_전파한다() {
        when(messageRepository.findMaxIdUpTo(MATCH, 9999L)).thenReturn(42L);
        when(readStateRepository.find(MATCH, ME)).thenReturn(Optional.of(10L));

        service.markRead(ME, MATCH, 9999L);

        verify(readStateRepository).advance(eq(MATCH), eq(ME), eq(42L), any(LocalDateTime.class));
        verify(publisher).publishRead(MATCH, OTHER, 42L);
        verify(notificationService).markChatRead(ME, MATCH);
    }

    @Test
    void 이미_읽은_위치이거나_그_이하_메시지가_없으면_아무것도_하지_않는다() {
        when(messageRepository.findMaxIdUpTo(MATCH, 5L)).thenReturn(null);
        service.markRead(ME, MATCH, 5L);

        when(messageRepository.findMaxIdUpTo(MATCH, 20L)).thenReturn(20L);
        when(readStateRepository.find(MATCH, ME)).thenReturn(Optional.of(20L));
        service.markRead(ME, MATCH, 20L);

        verify(readStateRepository, never()).advance(anyLong(), anyLong(), anyLong(), any());
        verify(notificationService, never()).markChatRead(anyLong(), anyLong());
        verifyNoInteractions(publisher);
    }

    @Test
    void 종료된_매칭에서도_읽음_처리는_된다() {
        status(ExchangeMatchStatus.COMPLETED);
        when(messageRepository.findMaxIdUpTo(MATCH, 7L)).thenReturn(7L);
        when(readStateRepository.find(MATCH, OTHER)).thenReturn(Optional.empty());

        service.markRead(OTHER, MATCH, 7L);

        verify(readStateRepository).advance(eq(MATCH), eq(OTHER), eq(7L), any());
        verify(publisher).publishRead(MATCH, ME, 7L);
    }
}
