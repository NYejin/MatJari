package com.matjari.service;

import com.matjari.domain.ExchangeRequest;
import com.matjari.domain.ListingImage;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.Ticket;
import com.matjari.domain.TicketStatus;
import com.matjari.domain.User;
import com.matjari.dto.response.ListingImageRef;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ExchangeRequestRepository;
import com.matjari.repository.ListingImageIndex;
import com.matjari.repository.ListingImageRepository;
import com.matjari.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 티켓·희망 조건 사진 올리기·지우기(V33) 규칙: 본인·상태·상한·형식·롤백 시 파일 정리(저장소는 임시 폴더, DB 는 목). */
class ListingImageServiceTest {

    @TempDir
    Path dir;

    private ListingImageRepository images;
    private TicketRepository tickets;
    private ExchangeRequestRepository requests;
    private ListingImageStore store;
    private ListingImageService service;
    private final List<ListingImage> saved = new ArrayList<>();
    private Ticket ticket;
    private ExchangeRequest request;

    @BeforeEach
    void setUp() {
        images = mock(ListingImageRepository.class);
        tickets = mock(TicketRepository.class);
        requests = mock(ExchangeRequestRepository.class);
        store = new ListingImageStore(dir.toString());
        Clock clock = Clock.fixed(Instant.parse("2026-11-26T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        ListingImageRateLimiter limiter = new ListingImageRateLimiter(clock, 100, 100);
        ChatImageProcessingGate gate = new ChatImageProcessingGate(clock, 100, 2);
        service = new ListingImageService(images, store, limiter, gate, tickets, requests,
                PerformanceFixtures.noopTransactionManager(), clock);

        User owner = PerformanceFixtures.user(1L, "나");
        PerformanceSession session = mock(PerformanceSession.class);
        ticket = Ticket.create(owner, session, "A", "A", "1", "1", "1", "1");
        ReflectionTestUtils.setField(ticket, "id", 500L);
        request = ExchangeRequest.create(ticket);
        ReflectionTestUtils.setField(request, "id", 900L);
        when(tickets.findById(500L)).thenReturn(Optional.of(ticket));
        when(tickets.findByIdForUpdate(500L)).thenReturn(Optional.of(ticket));
        when(tickets.findOwnerIdById(500L)).thenReturn(Optional.of(1L));
        when(requests.findById(900L)).thenReturn(Optional.of(request));
        when(requests.findByIdForUpdate(900L)).thenReturn(Optional.of(request));
        when(images.saveAndFlush(any(ListingImage.class))).thenAnswer(inv -> {
            ListingImage i = inv.getArgument(0);
            ReflectionTestUtils.setField(i, "id", 100L + saved.size());
            saved.add(i);
            return i;
        });
        when(images.findByTicketIdAndDeletedAtIsNull(anyLong())).thenAnswer(inv -> List.copyOf(saved));
        when(images.findByRequestIdAndDeletedAtIsNull(anyLong())).thenAnswer(inv -> List.copyOf(saved));
    }

    @Test
    void 티켓_사진을_올리면_파일을_저장하고_행을_남긴다() throws IOException {
        ListingImageRef ref = service.uploadForTicket(1L, 500L, png());

        assertThat(ref.id()).isEqualTo(100L);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getTicketId()).isEqualTo(500L);
        assertThat(saved.get(0).getRequestId()).isNull();
        assertThat(saved.get(0).getOwnerId()).isEqualTo(1L);
        assertThat(saved.get(0).getSortOrder()).isZero();
        try (Stream<Path> files = Files.list(dir.resolve("listing"))) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly(saved.get(0).getImageKey());
        }
    }

    @Test
    void 남의_티켓과_없는_티켓은_404이고_이미지를_처리하지_않는다() {
        assertThatThrownBy(() -> service.uploadForTicket(2L, 500L, png())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.uploadForTicket(1L, 501L, png())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.uploadForRequest(2L, 900L, png())).isInstanceOf(NotFoundException.class);
        verify(images, never()).saveAndFlush(any());
    }

    @Test
    void 내린_티켓은_TICKET_NOT_ACTIVE_409이고_삭제된_요청은_REQUEST_DELETED_409다() {
        ReflectionTestUtils.setField(ticket, "status", TicketStatus.INACTIVE);
        assertThatThrownBy(() -> service.uploadForTicket(1L, 500L, png()))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_NOT_ACTIVE"));

        request.markDeleted(java.time.LocalDateTime.of(2026, 11, 26, 12, 0));
        assertThatThrownBy(() -> service.uploadForRequest(1L, 900L, png()))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails()).containsEntry("code", "REQUEST_DELETED"));
    }

    @Test
    void 세_장이_차면_네_번째는_422_IMAGE_LIMIT_REACHED이고_파일이_남지_않는다() throws IOException {
        for (int i = 0; i < 3; i++) {
            service.uploadForTicket(1L, 500L, png());
        }
        when(images.countByTicketIdAndDeletedAtIsNull(500L)).thenReturn(3L);
        assertThatThrownBy(() -> service.uploadForTicket(1L, 500L, png()))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_LIMIT_REACHED"));
        // 사전 확인을 지나쳐도(동시 요청) 잠금 안 재확인이 막는다
        when(images.countByTicketIdAndDeletedAtIsNull(500L)).thenReturn(0L);
        assertThatThrownBy(() -> service.uploadForTicket(1L, 500L, png()))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_LIMIT_REACHED"));
        try (Stream<Path> files = Files.list(dir.resolve("listing"))) {
            assertThat(files.count()).isEqualTo(3);
        }
        // 순서 칸은 겹치지 않는다
        assertThat(saved).extracting(i -> (int) i.getSortOrder()).containsExactly(0, 1, 2);
    }

    @Test
    void 이미지가_아니거나_비어_있으면_400_image다() {
        assertThatThrownBy(() -> service.uploadForTicket(1L, 500L, new MockMultipartFile("image", "a.png", "image/png", "text".getBytes())))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.uploadForTicket(1L, 500L, new MockMultipartFile("image", "a.png", "image/png", new byte[0])))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.uploadForTicket(1L, 500L, null)).isInstanceOf(FieldValidationException.class);
        verify(images, never()).saveAndFlush(any());
    }

    @Test
    void INSERT가_실패하면_방금_쓴_파일을_지운다() throws IOException {
        when(images.saveAndFlush(any(ListingImage.class))).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> service.uploadForTicket(1L, 500L, png())).isInstanceOf(IllegalStateException.class);

        try (Stream<Path> files = Files.list(dir.resolve("listing"))) {
            assertThat(files.count()).as("롤백이면 파일을 지운다").isZero();
        }
    }

    @Test
    void 요청_사진은_요청_행만_잠그고_티켓은_잠그지_않는다() {
        service.uploadForRequest(1L, 900L, png());

        assertThat(saved.get(0).getRequestId()).isEqualTo(900L);
        assertThat(saved.get(0).getTicketId()).isNull();
        verify(requests).findByIdForUpdate(900L);
        verify(tickets, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 요청_사진_삭제는_행을_지우고_다른_요청의_사진은_건드리지_않는다() {
        ListingImage mine = ListingImage.ofRequest(1L, 900L, "11111111-1111-1111-1111-111111111111", 0);
        ReflectionTestUtils.setField(mine, "id", 7L);
        ListingImage others = ListingImage.ofRequest(2L, 901L, "22222222-2222-2222-2222-222222222222", 0);
        ReflectionTestUtils.setField(others, "id", 8L);
        when(images.findById(7L)).thenReturn(Optional.of(mine));
        when(images.findById(8L)).thenReturn(Optional.of(others));

        service.deleteForRequest(1L, 900L, 7L);
        service.deleteForRequest(1L, 900L, 8L);   // 이 요청의 사진이 아니면 아무 일도 없다(멱등)
        service.deleteForRequest(1L, 900L, 9L);   // 없는 사진도 멱등
        assertThatThrownBy(() -> service.deleteForRequest(2L, 900L, 7L)).isInstanceOf(NotFoundException.class);

        verify(images).delete(mine);
        verify(images, never()).delete(others);
    }

    @Test
    void 조회_묶음은_빈_입력이면_쿼리하지_않고_id_별로_순서대로_모은다() {
        assertThat(service.index(List.of(), List.of())).isEqualTo(new ListingImageIndex(java.util.Map.of(), java.util.Map.of()));
        verify(images, never()).findByTicketIdInAndDeletedAtIsNullOrderByTicketIdAscSortOrderAscIdAsc(any());

        ListingImage a = ListingImage.ofTicket(1L, 500L, "k1", 0);
        ReflectionTestUtils.setField(a, "id", 1L);
        ListingImage b = ListingImage.ofTicket(1L, 500L, "k2", 1);
        ReflectionTestUtils.setField(b, "id", 2L);
        when(images.findByTicketIdInAndDeletedAtIsNullOrderByTicketIdAscSortOrderAscIdAsc(any())).thenReturn(List.of(a, b));

        ListingImageIndex index = service.index(List.of(500L, 501L), List.of());
        assertThat(index.ticket(500L)).extracting(ListingImageRef::id).containsExactly(1L, 2L);
        assertThat(index.ticket(501L)).isEmpty();
        assertThat(index.request(900L)).isEmpty();
    }

    private static MockMultipartFile png() {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new MockMultipartFile("image", "a.png", "image/png", out.toByteArray());
    }
}
