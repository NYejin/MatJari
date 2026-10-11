package com.seatswap.service;

import com.seatswap.domain.AdminReviewStatus;
import com.seatswap.domain.Performance;
import com.seatswap.domain.PerformanceSession;
import com.seatswap.domain.SeatClaim;
import com.seatswap.domain.Ticket;
import com.seatswap.domain.User;
import com.seatswap.dto.response.SeatClaimDtos.SeatClaimResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.ExchangeTicketLockRepository;
import com.seatswap.repository.PerformanceSessionRepository;
import com.seatswap.repository.SeatClaimRepository;
import com.seatswap.repository.TicketRepository;
import com.seatswap.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.seatswap.service.PerformanceFixtures.performance;
import static com.seatswap.service.PerformanceFixtures.session;
import static com.seatswap.service.PerformanceFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 기준 시각 2026-10-06 12:00 KST. 생성 검증 순서와 파일 정리를 확인한다(실제 DB 동작은 SeatClaimMysqlTest). */
class SeatClaimServiceTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};

    private SeatClaimRepository claimRepository;
    private TicketRepository ticketRepository;
    private PerformanceSessionRepository sessionRepository;
    private UserRepository userRepository;
    private SeatClaimImageStore imageStore;
    private SeatClaimService service;

    private final User me = user(1L, "나");
    private final User other = user(2L, "상대");
    private final Performance performance = performance(10L, "KSPO DOME", other);
    private final PerformanceSession open = session(7L, performance, LocalDateTime.of(2026, 10, 6, 19, 0));
    private final PerformanceSession closed = session(8L, performance, LocalDateTime.of(2026, 10, 5, 23, 50));

    @BeforeEach
    void setUp() {
        claimRepository = mock(SeatClaimRepository.class);
        ticketRepository = mock(TicketRepository.class);
        sessionRepository = mock(PerformanceSessionRepository.class);
        userRepository = mock(UserRepository.class);
        imageStore = mock(SeatClaimImageStore.class);
        service = new SeatClaimService(claimRepository, ticketRepository, sessionRepository, userRepository,
                mock(ExchangeTicketLockRepository.class), mock(TicketService.class), imageStore,
                PerformanceFixtures.timePolicy(), PerformanceFixtures.noopTransactionManager(), 5, 999, 999);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(me));
        when(sessionRepository.findWithPerformanceById(7L)).thenReturn(Optional.of(open));
        when(sessionRepository.findWithPerformanceById(8L)).thenReturn(Optional.of(closed));
        when(ticketRepository.findActiveBySeat(anyLong(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(imageStore.store(any())).thenReturn("123e4567-e89b-12d3-a456-426614174000");
        when(claimRepository.saveAndFlush(any(SeatClaim.class))).thenAnswer(i -> {
            SeatClaim c = i.getArgument(0);
            ReflectionTestUtils.setField(c, "id", 900L);
            return c;
        });
    }

    private Ticket othersTicket() {
        Ticket t = Ticket.create(other, open, "1층 A", "1층 A", "3", "3", "5", "5");
        ReflectionTestUtils.setField(t, "id", 50L);
        return t;
    }

    private SeatClaimResponse create(Long sessionId, String zone, String row, String col, byte[] data) {
        return service.createWithImage(1L, sessionId, zone, row, col, null, data);
    }

    @Test
    void unknownSessionIs400OnSessionId() {
        assertThatThrownBy(() -> create(99L, "1층 A", "3", "5", JPEG))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("sessionId"));
    }

    @Test
    void closedSessionIs422BeforeSeatOrImageValidation() {
        // 구역이 비었고 이미지도 잘못이지만 마감 오류가 먼저다
        assertThatThrownBy(() -> create(8L, " ", "3", "5", new byte[]{1}))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SESSION_CLOSED"));
    }

    @Test
    void seatAndImageErrorsAreCollectedTogether() {
        assertThatThrownBy(() -> create(7L, "", "-3", "5", "GIF89a".getBytes()))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getErrors()).containsOnlyKeys("zone", "row", "image"));
        assertThatThrownBy(() -> create(7L, "1층 A", "3", "5", null))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getErrors()).containsOnlyKeys("image"));
        verify(imageStore, never()).store(any());
    }

    @Test
    void overFiveMiBIs413EvenWithoutMultipartLimit() {
        assertThatThrownBy(() -> create(7L, "1층 A", "3", "5", new byte[(int) SeatClaim.IMAGE_MAX_BYTES + 1]))
                .isInstanceOf(MaxUploadSizeExceededException.class);
    }

    @Test
    void myOwnActiveTicketIs409BeforeNotRegisteredCheck() {
        Ticket mine = Ticket.create(me, open, "1층 A", "1층 A", "3", "3", "5", "5");
        when(ticketRepository.findActiveBySeat(eq(7L), anyString(), anyString(), anyString())).thenReturn(Optional.of(mine));

        assertThatThrownBy(() -> create(7L, "1층 A", "3", "5", JPEG))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("code", "MY_TICKET_ALREADY_REGISTERED"));
        verify(imageStore, never()).store(any());
    }

    @Test
    void emptySeatIs422SeatNotRegistered() {
        assertThatThrownBy(() -> create(7L, "1층 A", "3", "5", JPEG))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SEAT_NOT_REGISTERED"));
        verify(imageStore, never()).store(any());
    }

    @Test
    void duplicatePendingIs409ThenLimitIs422() {
        when(ticketRepository.findActiveBySeat(eq(7L), anyString(), anyString(), anyString())).thenReturn(Optional.of(othersTicket()));
        when(claimRepository.existsPendingBySeat(eq(1L), eq(7L), anyString(), anyString(), anyString())).thenReturn(true);
        assertThatThrownBy(() -> create(7L, "1층 A", "3", "5", JPEG))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("code", "SEAT_CLAIM_ALREADY_PENDING"));

        when(claimRepository.existsPendingBySeat(eq(1L), eq(7L), anyString(), anyString(), anyString())).thenReturn(false);
        when(claimRepository.countByClaimantIdAndStatus(1L, AdminReviewStatus.PENDING)).thenReturn(5L);
        assertThatThrownBy(() -> create(7L, "1층 A", "3", "5", JPEG))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("SEAT_CLAIM_LIMIT_REACHED"));
        verify(imageStore, never()).store(any());
    }

    @Test
    void successStoresFileThenInsertsWithDetectedTypeAndNormalizedKeys() {
        when(ticketRepository.findActiveBySeat(eq(7L), anyString(), anyString(), anyString())).thenReturn(Optional.of(othersTicket()));

        SeatClaimResponse r = service.createWithImage(1L, 7L, " 1층   A ", "03열", "5번", "  예매내역입니다 ", JPEG);

        assertThat(r.id()).isEqualTo(900L);
        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(r.memo()).isEqualTo("예매내역입니다");
        assertThat(r.imageAvailable()).isTrue();
        verify(claimRepository).saveAndFlush(org.mockito.ArgumentMatchers.argThat((SeatClaim c) ->
                c.getImageContentType().equals("image/jpeg") && c.getImageSize() == JPEG.length
                        && c.getImageKey().equals("123e4567-e89b-12d3-a456-426614174000") && c.getClaimantId().equals(1L)));
    }

    @Test
    void savedFileIsDeletedWhenInsertFails() {
        when(ticketRepository.findActiveBySeat(eq(7L), anyString(), anyString(), anyString())).thenReturn(Optional.of(othersTicket()));
        when(claimRepository.saveAndFlush(any(SeatClaim.class))).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> create(7L, "1층 A", "3", "5", JPEG)).isInstanceOf(IllegalStateException.class);
        verify(imageStore).delete("123e4567-e89b-12d3-a456-426614174000");
    }

    @Test
    void imageIsHiddenFromOthersAndGoneAfterReview() {
        SeatClaim claim = SeatClaim.create(1L, open, "1층 A", "1층 A", "3", "3", "5", "5", null,
                "123e4567-e89b-12d3-a456-426614174000", "image/jpeg", 6);
        when(claimRepository.findById(900L)).thenReturn(Optional.of(claim));
        when(imageStore.read("123e4567-e89b-12d3-a456-426614174000")).thenReturn(Optional.of(JPEG));

        assertThatThrownBy(() -> service.image(2L, false, 900L)).isInstanceOf(NotFoundException.class);
        assertThat(service.image(1L, false, 900L).contentType()).isEqualTo("image/jpeg");
        assertThat(service.image(2L, true, 900L).bytes()).isEqualTo(JPEG);

        claim.reject(2L, "x", LocalDateTime.of(2026, 10, 6, 12, 0));
        claim.markImageDeleted(LocalDateTime.of(2026, 10, 6, 12, 0));
        assertThatThrownBy(() -> service.image(1L, false, 900L)).isInstanceOf(SeatClaimService.ImageDeletedException.class);
        assertThatThrownBy(() -> service.image(3L, false, 900L)).as("남은 404").isInstanceOf(NotFoundException.class);
    }
}
