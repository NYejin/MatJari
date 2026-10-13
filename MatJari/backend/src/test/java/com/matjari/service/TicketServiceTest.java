package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.Ticket;
import com.matjari.domain.TicketStatus;
import com.matjari.domain.User;
import com.matjari.dto.request.TicketCreateRequest;
import com.matjari.dto.response.TicketResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ExchangeMatchRepository;
import com.matjari.repository.ExchangeRequestRepository;
import com.matjari.repository.ExchangeTicketLockRepository;
import com.matjari.repository.PerformanceSessionRepository;
import com.matjari.repository.TicketRepository;
import com.matjari.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.matjari.service.PerformanceFixtures.NOW;
import static com.matjari.service.PerformanceFixtures.performance;
import static com.matjari.service.PerformanceFixtures.session;
import static com.matjari.service.PerformanceFixtures.uniqueViolation;
import static com.matjari.service.PerformanceFixtures.user;
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

/** 기준 시각 2026-10-06 12:00 KST, 상한 20, 숫자 열·번 상한 999. */
class TicketServiceTest {

    private TicketRepository ticketRepository;
    private PerformanceSessionRepository sessionRepository;
    private UserRepository userRepository;
    private ExchangeRequestRepository exchangeRequestRepository;
    private ExchangeMatchRepository matchRepository;
    private ExchangeTicketLockRepository lockRepository;
    private TicketService service;

    private final User me = user(1L, "나");
    private final User other = user(2L, "상대");
    private final Performance performance = performance(10L, "KSPO DOME", other);
    // 오늘(10-06) 19:00 회차: 마감은 10-07 00:00이라 아직 등록 가능
    private final PerformanceSession today = session(7L, performance, LocalDateTime.of(2026, 10, 6, 19, 0));
    // 어제(10-05) 회차: 마감이 10-06 00:00이라 이미 지났다
    private final PerformanceSession yesterday = session(8L, performance, LocalDateTime.of(2026, 10, 5, 23, 50));

    @BeforeEach
    void setUp() {
        ticketRepository = mock(TicketRepository.class);
        sessionRepository = mock(PerformanceSessionRepository.class);
        userRepository = mock(UserRepository.class);
        exchangeRequestRepository = mock(ExchangeRequestRepository.class);
        matchRepository = mock(ExchangeMatchRepository.class);
        lockRepository = mock(ExchangeTicketLockRepository.class);
        service = new TicketService(ticketRepository, sessionRepository, userRepository, exchangeRequestRepository,
                matchRepository, lockRepository,
                PerformanceFixtures.timePolicy(), PerformanceFixtures.noopTransactionManager(), 20, 999, 999);

        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(me));
        when(sessionRepository.findWithPerformanceById(7L)).thenReturn(Optional.of(today));
        when(sessionRepository.findWithPerformanceById(8L)).thenReturn(Optional.of(yesterday));
        when(ticketRepository.findActiveBySeat(anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(ticketRepository.saveAndFlush(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 500L);
            return t;
        });
    }

    private static TicketCreateRequest request(long sessionId) {
        return new TicketCreateRequest(sessionId, " 1층  a ", " 03열 ", "5번");
    }

    private Ticket ticketOf(User owner, TicketStatus status) {
        Ticket t = Ticket.create(owner, today, "1층A", "1층A", "3", "3", "5", "5");
        ReflectionTestUtils.setField(t, "id", 500L);
        ReflectionTestUtils.setField(t, "status", status);
        return t;
    }

    @Test
    void 등록하면_정규화한_키와_표시용_라벨로_저장한다() {
        TicketResponse response = service.create(1L, request(7L));

        assertThat(response.id()).isEqualTo(500L);
        assertThat(response.zone()).isEqualTo("1층 a");
        assertThat(response.row()).isEqualTo("03열");
        assertThat(response.col()).isEqualTo("5번");
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.performanceTitle()).isEqualTo("공연 10");
        assertThat(response.venueName()).isEqualTo("KSPO DOME");
        assertThat(response.startsAt()).isEqualTo(today.getStartsAt());
        verify(ticketRepository).findActiveBySeat(7L, "1층A", "3", "5");
    }

    @Test
    void 사용자_행을_잠그고_상한을_검사한다() {
        service.create(1L, request(7L));

        verify(userRepository).findByIdForUpdate(1L);
        verify(ticketRepository).countByUser_IdAndStatus(1L, TicketStatus.ACTIVE);
    }

    @Test
    void 같은_좌석에_다른_사람의_활성_티켓이_있으면_409이고_보유자_정보를_노출하지_않는다() {
        when(ticketRepository.findActiveBySeat(7L, "1층A", "3", "5"))
                .thenReturn(Optional.of(ticketOf(other, TicketStatus.ACTIVE)));

        assertThatThrownBy(() -> service.create(1L, request(7L)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(TicketService.SEAT_ALREADY_REGISTERED_MESSAGE);
                    assertThat(e.getDetails()).containsOnlyKeys("code").containsEntry("code", "SEAT_ALREADY_REGISTERED");
                });
        verify(ticketRepository, never()).saveAndFlush(any());
    }

    @Test
    void 내가_이미_등록한_좌석이면_별도_문구의_409() {
        when(ticketRepository.findActiveBySeat(7L, "1층A", "3", "5"))
                .thenReturn(Optional.of(ticketOf(me, TicketStatus.ACTIVE)));

        assertThatThrownBy(() -> service.create(1L, request(7L)))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(TicketService.MY_TICKET_ALREADY_REGISTERED_MESSAGE);
                    assertThat(e.getDetails()).containsEntry("code", "MY_TICKET_ALREADY_REGISTERED");
                });
    }

    @Test
    void 동시_등록으로_유일_제약에_걸리면_409() {
        when(ticketRepository.saveAndFlush(any(Ticket.class)))
                .thenThrow(uniqueViolation("ticket", Ticket.UNIQUE_ACTIVE_SEAT));

        assertThatThrownBy(() -> service.create(1L, request(7L)))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getMessage()).isEqualTo(TicketService.SEAT_ALREADY_REGISTERED_MESSAGE));
    }

    @Test
    void 다른_제약_위반은_그대로_던진다() {
        when(ticketRepository.saveAndFlush(any(Ticket.class)))
                .thenThrow(uniqueViolation("ticket", "uk_other"));

        assertThatThrownBy(() -> service.create(1L, request(7L)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 활성_티켓이_상한이면_422_업무_규칙_오류() {
        when(ticketRepository.countByUser_IdAndStatus(1L, TicketStatus.ACTIVE)).thenReturn(20L);

        assertThatThrownBy(() -> service.create(1L, request(7L)))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("TICKET_LIMIT_REACHED");
                    assertThat(e.getMessage()).contains("최대 20개");
                });
        verify(ticketRepository, never()).saveAndFlush(any());
    }

    @Test
    void 상한_바로_아래면_등록된다() {
        when(ticketRepository.countByUser_IdAndStatus(1L, TicketStatus.ACTIVE)).thenReturn(19L);

        assertThat(service.create(1L, request(7L)).id()).isEqualTo(500L);
    }

    @Test
    void 회차_당일이_지나면_400() {
        assertThatThrownBy(() -> service.create(1L, request(8L)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("sessionId");
                    assertThat(e.getMessage()).isEqualTo(TicketService.SESSION_CLOSED_MESSAGE);
                });
        verify(ticketRepository, never()).saveAndFlush(any());
    }

    @Test
    void 공연_시작_후라도_당일이면_등록된다() {
        PerformanceSession started = session(9L, performance, NOW.minusHours(3).withMinute(0));
        when(sessionRepository.findWithPerformanceById(9L)).thenReturn(Optional.of(started));

        assertThat(service.create(1L, request(9L)).sessionId()).isEqualTo(9L);
    }

    @Test
    void 없는_회차는_sessionId_필드_오류() {
        assertThatThrownBy(() -> service.create(1L, request(999L)))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getField()).isEqualTo("sessionId"));
    }

    @Test
    void 좌석_입력이_잘못되면_DB에_가기_전에_필드_오류() {
        assertThatThrownBy(() -> service.create(1L, new TicketCreateRequest(7L, "A", "0", "1")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("row"));
        assertThatThrownBy(() -> service.create(1L, new TicketCreateRequest(7L, "A", "1", "1000")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("col"));
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 사용자가_없으면_인증_예외() {
        assertThatThrownBy(() -> service.create(99L, request(7L))).isInstanceOf(AuthenticationException.class);
    }

    @Test
    void 내_티켓_목록은_저장소_결과를_DTO로_바꾼다() {
        when(ticketRepository.findActiveByUser(1L)).thenReturn(List.of(ticketOf(me, TicketStatus.ACTIVE)));

        List<TicketResponse> mine = service.listMine(1L);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).performanceId()).isEqualTo(10L);
    }

    @Test
    void 내리기는_본인_티켓을_INACTIVE로_바꾼다() {
        Ticket ticket = ticketOf(me, TicketStatus.ACTIVE);
        when(ticketRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(ticket));

        service.deactivate(1L, 500L);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.INACTIVE);
    }

    @Test
    void 이미_내린_티켓은_멱등으로_성공한다() {
        Ticket ticket = ticketOf(me, TicketStatus.INACTIVE);
        when(ticketRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(ticket));

        service.deactivate(1L, 500L);

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.INACTIVE);
    }

    @Test
    void 남의_티켓이나_없는_티켓은_404() {
        // 남의 티켓(보유자 1, 요청자 2)은 존재 여부를 드러내지 않도록 없는 티켓과 같은 404
        when(ticketRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(ticketOf(me, TicketStatus.ACTIVE)));
        assertThatThrownBy(() -> service.deactivate(2L, 500L)).isInstanceOf(NotFoundException.class)
                .hasMessage(TicketService.TICKET_NOT_FOUND_MESSAGE);
        when(ticketRepository.findByIdForUpdate(501L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivate(1L, 501L)).isInstanceOf(NotFoundException.class);
        verify(exchangeRequestRepository, never()).closeByTicketId(anyLong(), any());
    }

    @Test
    void 내리면_그_티켓의_교환_요청도_닫는다() {
        Ticket ticket = ticketOf(me, TicketStatus.ACTIVE);
        when(ticketRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(ticket));

        service.deactivate(1L, 500L);

        verify(exchangeRequestRepository).closeByTicketId(eq(500L), any());
    }

    @Test
    void 내리면_그_티켓의_CHATTING_매칭을_a측_b측_모두_시스템_취소한다() {
        Ticket ticket = ticketOf(me, TicketStatus.ACTIVE);
        when(ticketRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(ticket));

        service.deactivate(1L, 500L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(exchangeRequestRepository, matchRepository);
        order.verify(exchangeRequestRepository).closeByTicketId(eq(500L), any());
        order.verify(matchRepository).cancelChattingByTicketA(eq(500L), any());
        order.verify(matchRepository).cancelChattingByTicketB(eq(500L), any());
    }

    @Test
    void 예약_잠금이_있는_티켓은_409이고_아무것도_바꾸지_않는다() {
        Ticket ticket = ticketOf(me, TicketStatus.ACTIVE);
        when(ticketRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(ticket));
        when(lockRepository.existsByTicketId(500L)).thenReturn(true);

        assertThatThrownBy(() -> service.deactivate(1L, 500L))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(TicketService.TICKET_RESERVED_MESSAGE);
                    assertThat(e.getDetails()).containsEntry("code", "TICKET_RESERVED");
                });

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.ACTIVE);
        verify(exchangeRequestRepository, never()).closeByTicketId(anyLong(), any());
        verify(matchRepository, never()).cancelChattingByTicketA(anyLong(), any());
    }

    @Test
    void 이미_내린_티켓은_잠금_검사_없이_멱등으로_성공한다() {
        Ticket ticket = ticketOf(me, TicketStatus.INACTIVE);
        when(ticketRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(ticket));
        when(lockRepository.existsByTicketId(500L)).thenReturn(true);

        service.deactivate(1L, 500L);

        verify(lockRepository, never()).existsByTicketId(anyLong());
    }

    @Test
    void 교환_완료된_EXCHANGED_티켓은_내릴_수_없고_409_TICKET_EXCHANGED다() {
        Ticket ticket = ticketOf(me, TicketStatus.ACTIVE);
        ticket.markExchanged();
        when(ticketRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.deactivate(1L, 500L))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(TicketService.TICKET_EXCHANGED_MESSAGE);
                    assertThat(e.getDetails()).containsEntry("code", "TICKET_EXCHANGED");
                });

        assertThat(ticket.getStatus()).isEqualTo(TicketStatus.EXCHANGED);
        verify(exchangeRequestRepository, never()).closeByTicketId(anyLong(), any());
        verify(matchRepository, never()).cancelChattingByTicketA(anyLong(), any());
        verify(matchRepository, never()).cancelChattingByTicketB(anyLong(), any());
    }

    private TicketService serviceAt(LocalDateTime now) {
        java.time.Clock clock = java.time.Clock.fixed(now.atZone(PerformanceFixtures.KST).toInstant(),
                PerformanceFixtures.KST);
        return new TicketService(ticketRepository, sessionRepository, userRepository, exchangeRequestRepository,
                matchRepository, lockRepository, new SessionTimePolicy(clock), PerformanceFixtures.noopTransactionManager(), 20, 999, 999);
    }

    @Test
    void 마감_정확히_다음날_0시는_거부하고_직전_1초는_허용한다() {
        // today 회차(10-06 19:00)의 마감은 10-07 00:00:00
        assertThatThrownBy(() -> serviceAt(LocalDateTime.of(2026, 10, 7, 0, 0, 0)).create(1L, request(7L)))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("sessionId");
                    assertThat(e.getMessage()).isEqualTo(TicketService.SESSION_CLOSED_MESSAGE);
                });
        assertThat(serviceAt(LocalDateTime.of(2026, 10, 6, 23, 59, 59)).create(1L, request(7L)).id()).isEqualTo(500L);
    }

    @Test
    void 여러_필드_오류는_한_번에_모아서_던진다() {
        assertThatThrownBy(() -> service.create(1L, new TicketCreateRequest(7L, "A​", "0", "1000")))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getErrors()).containsOnlyKeys("zone", "row", "col");
                    assertThat(e.getErrors().get("zone")).isEqualTo("구역에 사용할 수 없는 문자가 있습니다.");
                    assertThat(e.getErrors().get("row")).isEqualTo("열은 1 이상이어야 합니다.");
                    assertThat(e.getErrors().get("col")).isEqualTo("번은 999 이하로 입력해주세요.");
                });
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }
}
