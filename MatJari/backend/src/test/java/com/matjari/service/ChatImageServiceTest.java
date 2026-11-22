package com.matjari.service;

import com.matjari.domain.ChatMessage;
import com.matjari.domain.ExchangeMatch;
import com.matjari.domain.ExchangeMatchStatus;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ChatMessageRepository;
import com.matjari.repository.ChatReadStateRepository;
import com.matjari.repository.ExchangeMatchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 채팅 사진 전송·조회 서비스 흐름(저장소 mock, 파일 저장소는 임시 폴더의 실제 구현). */
class ChatImageServiceTest {

    private static final long MATCH = 50L;
    private static final long ME = 1L;
    private static final long OTHER = 2L;
    private static final String CID = "client-img-0001";

    @TempDir
    Path tmp;

    private ExchangeMatchRepository matchRepository;
    private ChatMessageRepository messageRepository;
    private ChatPushPublisher publisher;
    private ChatRateLimiter rateLimiter;
    private ChatImageRateLimiter imageRateLimiter;
    private NotificationService notificationService;
    private ChatImageStore store;
    private ChatService service;
    private ExchangeMatch match;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        matchRepository = mock(ExchangeMatchRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        publisher = mock(ChatPushPublisher.class);
        rateLimiter = mock(ChatRateLimiter.class);
        imageRateLimiter = mock(ChatImageRateLimiter.class);
        notificationService = mock(NotificationService.class);
        store = new ChatImageStore(tmp.toString());
        ObjectProvider<ChatPushPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any(Supplier.class))).thenReturn(publisher);
        Clock clock = Clock.fixed(Instant.parse("2026-11-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new ChatService(matchRepository, messageRepository, mock(ChatReadStateRepository.class), rateLimiter, imageRateLimiter,
                store, notificationService, provider, clock, PerformanceFixtures.noopTransactionManager(),
                new ChatImageProcessingGate(clock, 1000, 2));

        match = new ExchangeMatch();
        ReflectionTestUtils.setField(match, "id", MATCH);
        ReflectionTestUtils.setField(match, "userAId", ME);
        ReflectionTestUtils.setField(match, "userBId", OTHER);
        ReflectionTestUtils.setField(match, "status", ExchangeMatchStatus.CHATTING);
        when(matchRepository.findById(MATCH)).thenReturn(Optional.of(match));
        when(matchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(match));
        when(messageRepository.saveAndFlush(any(ChatMessage.class))).thenAnswer(inv -> {
            ChatMessage m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "id", 700L);
            ReflectionTestUtils.setField(m, "createdAt", LocalDateTime.of(2026, 11, 22, 12, 0, 0));
            return m;
        });
        when(messageRepository.findByClientMessageIdAndSenderId(any(), anyLong())).thenReturn(Optional.empty());
    }

    private static MockMultipartFile jpeg() throws IOException {
        BufferedImage img = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return new MockMultipartFile("image", "photo.jpg", "image/jpeg", out.toByteArray());
    }

    private long filesInChatDir() throws IOException {
        Path chat = tmp.resolve("chat");
        if (!Files.isDirectory(chat)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(chat)) {
            return s.count();
        }
    }

    // ------------------------------------------------------------------ 전송

    @Test
    void 닫힌_매칭은_사진을_디코드하기_전에_409로_끝낸다() throws IOException {
        ReflectionTestUtils.setField(match, "status", ExchangeMatchStatus.CANCELED);
        // 깨진 바이트라면 처리 단계에서 400 이 났을 것이다: 409 라는 것은 처리 전에 상태를 먼저 확인했다는 뜻이다
        MockMultipartFile broken = new MockMultipartFile("image", "x.jpg", "image/jpeg", new byte[]{1, 2, 3});
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.sendImage(ME, MATCH, broken, CID))
                .isInstanceOf(com.matjari.exception.ConflictException.class);
        assertThat(filesInChatDir()).isZero();
        verify(matchRepository, org.mockito.Mockito.never()).findByIdForUpdate(MATCH);
    }

    @Test
    void 닫힌_매칭이어도_이미_저장된_같은_clientMessageId_재전송은_사전_확인을_통과한다() throws IOException {
        ReflectionTestUtils.setField(match, "status", ExchangeMatchStatus.COMPLETED);
        ChatMessage stored = ChatMessage.ofImage(MATCH, ME, CID, "0f0f0f0f-0f0f-0f0f-0f0f-0f0f0f0f0f0f");
        ReflectionTestUtils.setField(stored, "id", 650L);
        ReflectionTestUtils.setField(stored, "createdAt", LocalDateTime.of(2026, 11, 22, 11, 0, 0));
        when(messageRepository.findByClientMessageIdAndSenderId(CID, ME)).thenReturn(Optional.of(stored));
        ChatService.SendResult r = service.sendImage(ME, MATCH, jpeg(), CID);
        assertThat(r.created()).isFalse();
        assertThat(filesInChatDir()).isZero();
    }

    @Test
    void 사진_전송은_텍스트와_같은_순서로_저장하고_알림_전파까지_한다() throws IOException {
        ChatService.SendResult r = service.sendImage(ME, MATCH, jpeg(), CID);

        assertThat(r.created()).isTrue();
        assertThat(r.message().hasImage()).isTrue();
        assertThat(r.message().imageExpired()).isFalse();
        assertThat(r.message().content()).isEmpty();
        assertThat(filesInChatDir()).isEqualTo(1);
        org.mockito.ArgumentCaptor<ChatMessage> saved = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(matchRepository, rateLimiter, imageRateLimiter, messageRepository, notificationService, publisher);
        order.verify(matchRepository).findByIdForUpdate(MATCH);
        order.verify(rateLimiter).acquire(ME);
        order.verify(imageRateLimiter).acquire(ME);
        order.verify(messageRepository).saveAndFlush(saved.capture());
        order.verify(notificationService).notifyChatMessage(OTHER, MATCH);
        order.verify(publisher).publishMessage(org.mockito.ArgumentMatchers.eq(MATCH), org.mockito.ArgumentMatchers.eq(r.message()),
                org.mockito.ArgumentMatchers.eq(List.of(ME, OTHER)));
        assertThat(ChatImageStore.isValidKey(saved.getValue().getImageKey())).isTrue();
        assertThat(saved.getValue().getContent()).isEmpty();
    }

    @Test
    void 같은_clientMessageId_재전송은_파일도_상한도_알림도_쓰지_않는다() throws IOException {
        ChatMessage existing = ChatMessage.ofImage(MATCH, ME, CID, "00000000-0000-4000-8000-000000000001");
        ReflectionTestUtils.setField(existing, "id", 650L);
        ReflectionTestUtils.setField(existing, "createdAt", LocalDateTime.of(2026, 11, 22, 12, 0, 0));
        when(messageRepository.findByClientMessageIdAndSenderId(CID, ME)).thenReturn(Optional.of(existing));

        ChatService.SendResult r = service.sendImage(ME, MATCH, jpeg(), CID);

        assertThat(r.created()).isFalse();
        assertThat(r.message().id()).isEqualTo(650L);
        assertThat(filesInChatDir()).isZero();
        verifyNoInteractions(imageRateLimiter, rateLimiter, publisher);
        verify(messageRepository, never()).saveAndFlush(any());
        verify(notificationService, never()).notifyChatMessage(anyLong(), anyLong());
    }

    @Test
    void 비참여자는_404이고_이미지를_처리하거나_저장하지_않는다() throws IOException {
        assertThatThrownBy(() -> service.sendImage(99L, MATCH, jpeg(), CID)).isInstanceOf(NotFoundException.class);
        // 이미지가 아니어도(처리 전에) 참여자 확인이 먼저라 400 이 아니라 404
        assertThatThrownBy(() -> service.sendImage(99L, MATCH, new MockMultipartFile("image", "x.txt", "text/plain", "hi".getBytes()), CID))
                .isInstanceOf(NotFoundException.class);
        verify(matchRepository, never()).findByIdForUpdate(anyLong());
        assertThat(filesInChatDir()).isZero();
        verifyNoInteractions(imageRateLimiter, rateLimiter, publisher);
    }

    @Test
    void 닫힌_매칭은_409이고_RESERVED는_허용한다() throws IOException {
        for (ExchangeMatchStatus closed : List.of(ExchangeMatchStatus.CANCELED, ExchangeMatchStatus.COMPLETED)) {
            ReflectionTestUtils.setField(match, "status", closed);
            assertThatThrownBy(() -> service.sendImage(ME, MATCH, jpeg(), CID)).isInstanceOfSatisfying(ConflictException.class,
                    e -> assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT"));
        }
        assertThat(filesInChatDir()).isZero();
        verifyNoInteractions(imageRateLimiter, publisher);

        ReflectionTestUtils.setField(match, "status", ExchangeMatchStatus.RESERVED);
        assertThat(service.sendImage(ME, MATCH, jpeg(), CID).created()).isTrue();
    }

    @Test
    void 이미지가_아니거나_비어_있으면_400이다() {
        assertThatThrownBy(() -> service.sendImage(ME, MATCH, new MockMultipartFile("image", "x.txt", "image/jpeg", "not an image".getBytes()), CID))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("image"));
        assertThatThrownBy(() -> service.sendImage(ME, MATCH, null, CID)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.sendImage(ME, MATCH, new MockMultipartFile("image", new byte[0]), CID))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.sendImage(ME, MATCH, null, "bad")).isInstanceOfSatisfying(FieldValidationException.class,
                e -> assertThat(e.getErrors()).containsOnlyKeys("clientMessageId"));
        verifyNoInteractions(publisher);
    }

    @Test
    void 하루_상한에_걸리면_파일도_행도_전파도_없다() throws IOException {
        doThrow(new ChatImageRateLimiter.ChatImageRateLimitedException(30, 3600)).when(imageRateLimiter).acquire(ME);

        assertThatThrownBy(() -> service.sendImage(ME, MATCH, jpeg(), CID))
                .isInstanceOf(ChatImageRateLimiter.ChatImageRateLimitedException.class);
        assertThat(filesInChatDir()).isZero();
        verify(messageRepository, never()).saveAndFlush(any());
        verifyNoInteractions(publisher);
    }

    @Test
    void INSERT가_실패하면_방금_쓴_파일을_지운다() throws IOException {
        when(messageRepository.saveAndFlush(any(ChatMessage.class))).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.sendImage(ME, MATCH, jpeg(), CID)).isInstanceOf(IllegalStateException.class);

        assertThat(filesInChatDir()).isZero();
        verifyNoInteractions(publisher);
    }

    // ------------------------------------------------------------------ 조회

    private ChatMessage imageMessage(long id, long matchId, String key) {
        ChatMessage m = ChatMessage.ofImage(matchId, OTHER, "client-img-" + id + "00", key);
        ReflectionTestUtils.setField(m, "id", id);
        return m;
    }

    @Test
    void 참여자는_사진을_받고_형식은_저장된_바이트로_판정한다() throws IOException {
        service.sendImage(ME, MATCH, jpeg(), CID);
        org.mockito.ArgumentCaptor<ChatMessage> saved = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository).saveAndFlush(saved.capture());
        when(messageRepository.findById(700L)).thenReturn(Optional.of(saved.getValue()));

        ChatImageStore.ImageData data = service.image(OTHER, MATCH, 700L);

        assertThat(data.contentType()).isEqualTo("image/jpeg");
        assertThat(data.bytes()).isNotEmpty();
    }

    @Test
    void 비참여자_다른_매칭의_메시지_사진_없는_메시지는_404다() {
        String key = "00000000-0000-4000-8000-000000000001";
        when(messageRepository.findById(10L)).thenReturn(Optional.of(imageMessage(10L, MATCH, key)));
        when(messageRepository.findById(11L)).thenReturn(Optional.of(imageMessage(11L, 77L, key)));
        ChatMessage text = ChatMessage.of(MATCH, OTHER, "텍스트", "client-txt-0001");
        ReflectionTestUtils.setField(text, "id", 12L);
        when(messageRepository.findById(12L)).thenReturn(Optional.of(text));

        assertThatThrownBy(() -> service.image(99L, MATCH, 10L)).isInstanceOf(NotFoundException.class);   // 비참여자
        assertThatThrownBy(() -> service.image(ME, MATCH, 11L)).isInstanceOf(NotFoundException.class);    // 다른 매칭의 메시지
        assertThatThrownBy(() -> service.image(ME, MATCH, 12L)).isInstanceOf(NotFoundException.class);    // 사진 없는 메시지
        assertThatThrownBy(() -> service.image(ME, MATCH, 404L)).isInstanceOf(NotFoundException.class);   // 없는 메시지
    }

    @Test
    void 지운_사진은_410이다_지운_시각이_있거나_파일이_없을_때() {
        String key = "00000000-0000-4000-8000-000000000001";
        ChatMessage deleted = imageMessage(10L, MATCH, key);
        ReflectionTestUtils.setField(deleted, "imageDeletedAt", LocalDateTime.of(2026, 11, 1, 0, 0));
        when(messageRepository.findById(10L)).thenReturn(Optional.of(deleted));
        when(messageRepository.findById(11L)).thenReturn(Optional.of(imageMessage(11L, MATCH, key)));   // 파일 없음

        assertThat(deleted.isImageExpired()).isTrue();
        assertThatThrownBy(() -> service.image(ME, MATCH, 10L)).isInstanceOf(ChatImageStore.ImageExpiredException.class);
        assertThatThrownBy(() -> service.image(ME, MATCH, 11L)).isInstanceOf(ChatImageStore.ImageExpiredException.class);
    }

    @Test
    void 종료된_매칭의_사진도_참여자는_열람할_수_있다() throws IOException {
        service.sendImage(ME, MATCH, jpeg(), CID);
        org.mockito.ArgumentCaptor<ChatMessage> saved = org.mockito.ArgumentCaptor.forClass(ChatMessage.class);
        verify(messageRepository).saveAndFlush(saved.capture());
        when(messageRepository.findById(700L)).thenReturn(Optional.of(saved.getValue()));
        ReflectionTestUtils.setField(match, "status", ExchangeMatchStatus.CANCELED);

        assertThat(service.image(ME, MATCH, 700L).bytes()).isNotEmpty();
    }
}
