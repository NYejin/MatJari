package com.matjari.service;

import com.matjari.domain.ExchangeRequest;
import com.matjari.domain.ExchangeRequestStatus;
import com.matjari.domain.ExchangeWantRange;
import com.matjari.domain.ExchangeMatch;
import com.matjari.domain.ExchangeMatchStatus;
import com.matjari.domain.ExtraType;
import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceSession;
import com.matjari.domain.SeatKey;
import com.matjari.domain.Ticket;
import com.matjari.domain.TicketStatus;
import com.matjari.domain.User;
import com.matjari.domain.WantExtra;
import com.matjari.dto.request.ExchangeRequestCreateRequest;
import com.matjari.dto.request.ExchangeRequestUpdateRequest;
import com.matjari.dto.request.WantRangeInput;
import com.matjari.dto.request.WantSessionInput;
import com.matjari.dto.response.ExchangeRequestResponse;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.ConflictException;
import com.matjari.exception.FieldValidationException;
import com.matjari.exception.ForbiddenException;
import com.matjari.exception.NotFoundException;
import com.matjari.repository.ExchangeMatchRepository;
import com.matjari.repository.ExchangeRequestRepository;
import com.matjari.repository.ExchangeWantRangeRepository;
import com.matjari.repository.ExchangeWantSeatRepository;
import com.matjari.repository.ExchangeWantSessionRepository;
import com.matjari.repository.PerformanceSessionRepository;
import com.matjari.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.matjari.service.PerformanceFixtures.performance;
import static com.matjari.service.PerformanceFixtures.session;
import static com.matjari.service.PerformanceFixtures.uniqueViolation;
import static com.matjari.service.PerformanceFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 내 티켓: 공연 10의 회차 7 (구역 A, 3열 5번). 같은 공연의 다른 회차 8, 다른 공연의 회차 9. */
class ExchangeRequestServiceTest {

    private ExchangeRequestRepository requestRepository;
    private ExchangeWantRangeRepository rangeRepository;
    private ExchangeWantSessionRepository wantSessionRepository;
    private ExchangeWantSeatRepository seatRepository;
    private TicketRepository ticketRepository;
    private PerformanceSessionRepository sessionRepository;
    private ExchangeMatchRepository matchRepository;
    private ExchangeRequestService service;

    private final User me = user(1L, "나");
    private final User other = user(2L, "상대");
    private final Performance performance = performance(10L, "KSPO DOME", other);
    private final Performance otherPerformance = performance(11L, "다른 홀", other);
    private final PerformanceSession mySession = session(7L, performance, LocalDateTime.of(2026, 11, 1, 19, 0));
    private final PerformanceSession sameShowSession = session(8L, performance, LocalDateTime.of(2026, 11, 2, 19, 0));
    private final PerformanceSession otherShowSession = session(9L, otherPerformance, LocalDateTime.of(2026, 11, 3, 19, 0));

    // 기준 시각 2026-10-06 12:00: 10-05 회차는 마감(10-06 00:00)이 지났다
    private final PerformanceSession closedSession = session(10L, performance, LocalDateTime.of(2026, 10, 5, 19, 0));

    private Ticket myTicket;

    @BeforeEach
    void setUp() {
        requestRepository = mock(ExchangeRequestRepository.class);
        rangeRepository = mock(ExchangeWantRangeRepository.class);
        wantSessionRepository = mock(ExchangeWantSessionRepository.class);
        seatRepository = mock(ExchangeWantSeatRepository.class);
        ticketRepository = mock(TicketRepository.class);
        sessionRepository = mock(PerformanceSessionRepository.class);
        matchRepository = mock(ExchangeMatchRepository.class);
        service = new ExchangeRequestService(requestRepository, rangeRepository, wantSessionRepository,
                seatRepository, ticketRepository, sessionRepository, matchRepository, PerformanceFixtures.noopTransactionManager(),
                PerformanceFixtures.timePolicy(), 5000, 50, 999, 999);

        myTicket = ticket(500L, me, mySession, TicketStatus.ACTIVE);
        when(ticketRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(myTicket));
        when(ticketRepository.findWithSessionById(500L)).thenReturn(Optional.of(myTicket));
        when(sessionRepository.findAllById(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return List.of(mySession, sameShowSession, otherShowSession, closedSession).stream()
                    .filter(s -> ids.contains(s.getId())).toList();
        });
        when(requestRepository.saveAndFlush(any(ExchangeRequest.class))).thenAnswer(inv -> {
            ExchangeRequest r = inv.getArgument(0);
            if (r.getId() == null) {
                ReflectionTestUtils.setField(r, "id", 900L);
            }
            ReflectionTestUtils.setField(r, "createdAt", LocalDateTime.of(2026, 10, 6, 12, 0));
            return r;
        });
    }

    private static Ticket ticket(long id, User owner, PerformanceSession session, TicketStatus status) {
        Ticket t = Ticket.create(owner, session, "A", "A", "3", "3", "5", "5");
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "status", status);
        return t;
    }

    private static WantRangeInput range(String zone, String rowFrom, String rowTo, String colFrom, String colTo) {
        return new WantRangeInput(zone, rowFrom, rowTo, colFrom, colTo, "X", null);
    }

    private static WantRangeInput range(String zone, String rowFrom, String rowTo, String colFrom, String colTo,
                                        String type, Integer amount) {
        return new WantRangeInput(zone, rowFrom, rowTo, colFrom, colTo, type, amount);
    }

    private static ExchangeRequestCreateRequest create(List<WantSessionInput> sessions, WantRangeInput... ranges) {
        return new ExchangeRequestCreateRequest(500L, sessions, List.of(ranges));
    }

    private static ExchangeRequestUpdateRequest update(List<WantSessionInput> sessions, WantRangeInput... ranges) {
        return new ExchangeRequestUpdateRequest(sessions, List.of(ranges));
    }

    private static final List<WantSessionInput> ONE_SESSION = List.of(new WantSessionInput(8L, 1));
    private static final List<WantSessionInput> MY_SESSION_ONLY = List.of(new WantSessionInput(7L, 1));

    private ExchangeRequest existingRequest(long id, Ticket ticket) {
        ExchangeRequest r = ExchangeRequest.create(ticket);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "createdAt", LocalDateTime.of(2026, 10, 6, 11, 0));
        ReflectionTestUtils.setField(r, "updatedAt", LocalDateTime.of(2026, 10, 6, 11, 0));
        return r;
    }

    // ------------------------------------------------------------------ 등록

    @Test
    @SuppressWarnings("unchecked")
    void 등록하면_요청_범위_회차_좌석을_저장하고_개수만_응답한다() {
        ExchangeRequestResponse response = service.create(1L, create(
                List.of(new WantSessionInput(8L, 2), new WantSessionInput(7L, 1)),
                range("B", "1", "2", "3", "5", "NEG", -10000)));

        assertThat(response.id()).isEqualTo(900L);
        assertThat(response.ticketId()).isEqualTo(500L);
        assertThat(response.status()).isEqualTo("OPEN");
        assertThat(response.wantSeatCount()).isEqualTo(6);
        assertThat(response.wantSessions()).extracting(ExchangeRequestResponse.WantSessionItem::sessionId)
                .containsExactly(7L, 8L);
        assertThat(response.ranges()).hasSize(1);
        assertThat(response.ranges().get(0).zone()).isEqualTo("B");
        assertThat(response.ranges().get(0).extraType()).isEqualTo("NEG");
        assertThat(response.ranges().get(0).extraAmount()).isEqualTo(-10000);

        ArgumentCaptor<Map<SeatKey, WantExtra>> seats = ArgumentCaptor.forClass(Map.class);
        verify(seatRepository).insertAll(eq(900L), seats.capture());
        assertThat(seats.getValue()).hasSize(6);
        assertThat(seats.getValue().values()).containsOnly(new WantExtra(ExtraType.NEG, -10000));
        verify(rangeRepository).saveAll(any());
        verify(wantSessionRepository).saveAll(any());
    }

    @Test
    void 티켓_행_잠금이_첫_쿼리다() {
        service.create(1L, create(ONE_SESSION, range("B", "1", "1", "1", "1")));

        InOrder order = inOrder(ticketRepository, requestRepository);
        order.verify(ticketRepository).findByIdForUpdate(500L);
        order.verify(requestRepository).existsLiveByTicketId(500L);
        order.verify(requestRepository).saveAndFlush(any());
    }

    @Test
    void 없는_티켓은_404_남의_티켓은_403() {
        when(ticketRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(1L, new ExchangeRequestCreateRequest(404L, ONE_SESSION,
                List.of(range("B", "1", "1", "1", "1")))))
                .isInstanceOf(NotFoundException.class);

        assertThatThrownBy(() -> service.create(2L, create(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOf(ForbiddenException.class);
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void 내린_티켓에는_등록할_수_없다_422() {
        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.INACTIVE);

        assertThatThrownBy(() -> service.create(1L, create(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e ->
                        assertThat(e.getCode()).isEqualTo("TICKET_NOT_ACTIVE"));
    }

    @Test
    void 티켓에_이미_요청이_있으면_409() {
        when(requestRepository.existsLiveByTicketId(500L)).thenReturn(true);

        assertThatThrownBy(() -> service.create(1L, create(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getDetails()).containsEntry("code", "REQUEST_ALREADY_EXISTS"));
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void 동시_등록으로_유일_제약에_걸리면_같은_409() {
        when(requestRepository.saveAndFlush(any(ExchangeRequest.class)))
                .thenThrow(uniqueViolation("exchange_request", ExchangeRequest.UNIQUE_TICKET));

        assertThatThrownBy(() -> service.create(1L, create(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getDetails()).containsEntry("code", "REQUEST_ALREADY_EXISTS"));
    }

    @Test
    void 다른_제약_위반은_그대로_던진다() {
        when(requestRepository.saveAndFlush(any(ExchangeRequest.class)))
                .thenThrow(uniqueViolation("exchange_request", "uk_other"));

        assertThatThrownBy(() -> service.create(1L, create(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 희망_회차가_내_회차_하나뿐이고_내_좌석이_포함되면_422() {
        // 내 좌석: A구역 3열 5번, 내 회차: 7
        assertThatThrownBy(() -> service.create(1L, create(MY_SESSION_ONLY, range("A", "3", "4", "4", "6"))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e ->
                        assertThat(e.getCode()).isEqualTo("WANT_INCLUDES_OWN_SEAT"));
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void 다른_회차가_하나라도_포함되면_내_좌석_위치가_범위에_있어도_허용한다() {
        // 다른 회차(8)만, 또는 내 회차(7)와 다른 회차(8)를 함께 희망
        assertThat(service.create(1L, create(ONE_SESSION, range("A", "3", "4", "4", "6")))
                .wantSeatCount()).isEqualTo(6);
        assertThat(service.create(1L, create(List.of(new WantSessionInput(7L, 1), new WantSessionInput(8L, 2)), range("A", "3", "3", "5", "5")))
                .wantSeatCount()).isEqualTo(1);
    }

    @Test
    void 내_티켓의_회차가_마감됐으면_등록_수정_모두_422() {
        Ticket old = ticket(501L, me, closedSession, TicketStatus.ACTIVE);
        when(ticketRepository.findByIdForUpdate(501L)).thenReturn(Optional.of(old));
        when(ticketRepository.findWithSessionById(501L)).thenReturn(Optional.of(old));
        assertThatThrownBy(() -> service.create(1L, new ExchangeRequestCreateRequest(501L, ONE_SESSION,
                List.of(range("B", "1", "1", "1", "1")))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e ->
                        assertThat(e.getCode()).isEqualTo("SESSION_CLOSED"));

        ExchangeRequest existing = existingRequest(901L, old);
        when(requestRepository.findByIdForUpdate(901L)).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.update(1L, 901L, update(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e ->
                        assertThat(e.getCode()).isEqualTo("SESSION_CLOSED"));
        verify(seatRepository, never()).deleteByRequestId(anyLong());
    }

    @Test
    void 마감된_희망_회차는_필드_오류() {
        assertThatThrownBy(() -> service.create(1L, create(List.of(new WantSessionInput(8L, 1), new WantSessionInput(10L, 2)), range("B", "1", "1", "1", "1"))))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getErrors()).containsOnlyKeys("wantSessions[1].sessionId");
                    assertThat(e.getErrors().get("wantSessions[1].sessionId"))
                            .isEqualTo(ExchangeRequestService.WANT_SESSION_CLOSED_MESSAGE);
                });
    }

    @Test
    void 구역이_다르거나_범위_밖이면_내_좌석_제외_검사를_통과한다() {
        service.create(1L, create(ONE_SESSION, range("B", "3", "3", "5", "5"), range("A", "3", "3", "6", "9"),
                range("A", "4", "4", "5", "5")));
    }

    @Test
    void 다른_공연의_회차나_없는_회차는_인덱스를_포함한_필드_오류() {
        assertThatThrownBy(() -> service.create(1L, create(List.of(new WantSessionInput(8L, 1), new WantSessionInput(9L, 2), new WantSessionInput(99L, 3)),
                range("B", "1", "1", "1", "1"))))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getErrors()).containsOnlyKeys("wantSessions[1].sessionId", "wantSessions[2].sessionId");
                    assertThat(e.getErrors().get("wantSessions[1].sessionId"))
                            .isEqualTo(ExchangeRequestService.SESSION_OTHER_PERFORMANCE_MESSAGE);
                    assertThat(e.getErrors().get("wantSessions[2].sessionId"))
                            .isEqualTo(ExchangeRequestService.SESSION_NOT_FOUND_MESSAGE);
                });
    }

    @Test
    void 중복_회차는_필드_오류_본인_티켓의_회차는_허용() {
        assertThatThrownBy(() -> service.create(1L, create(List.of(new WantSessionInput(8L, 1), new WantSessionInput(8L, 2)), range("B", "1", "1", "1", "1"))))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getErrors()).containsOnlyKeys("wantSessions[1].sessionId"));

        assertThat(service.create(1L, create(List.of(new WantSessionInput(7L, 1)),
                range("B", "1", "1", "1", "1"))).wantSessions()).hasSize(1);
    }

    @Test
    void 추가금은_범위마다_저장되고_응답에_범위별로_나온다() {
        ExchangeRequestResponse response = service.create(1L, create(ONE_SESSION,
                range("B", "1", "1", "1", "2", "POS", 5000),
                range("B", "2", "2", "1", "1", "neg", -1),
                range("B", "3", "3", "1", "1", "ANY", null),
                range("B", "4", "4", "1", "1", "X", null)));

        assertThat(response.ranges()).extracting(ExchangeRequestResponse.WantRangeItem::extraType)
                .containsExactly("POS", "NEG", "ANY", "X");
        assertThat(response.ranges()).extracting(ExchangeRequestResponse.WantRangeItem::extraAmount)
                .containsExactly(5000, -1, null, null);
    }

    @Test
    void 추가금_유형별_금액_규칙_오류는_범위_인덱스_키() {
        Object[][] invalid = {
                {"POS", 0}, {"POS", -5}, {"POS", null}, {"NEG", 0}, {"NEG", 7}, {"NEG", null}, {"X", 100}, {"ANY", -100}};
        for (Object[] bad : invalid) {
            assertThatThrownBy(() -> service.create(1L, create(ONE_SESSION, range("B", "1", "1", "1", "1"),
                    range("B", "2", "2", "1", "1", (String) bad[0], (Integer) bad[1]))))
                    .as("%s %s", bad[0], bad[1])
                    .isInstanceOfSatisfying(FieldValidationException.class, e ->
                            assertThat(e.getErrors()).containsOnlyKeys("ranges[1].extraAmount"));
        }
        assertThatThrownBy(() -> service.create(1L, create(ONE_SESSION, range("B", "1", "1", "1", "1", "MAYBE", null))))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getErrors()).containsOnlyKeys("ranges[0].extraType"));
    }

    @Test
    void 추가금_오류와_범위_오류를_한_번에_돌려준다() {
        assertThatThrownBy(() -> service.create(1L, create(ONE_SESSION, range("B", "5", "1", "1", "1", "POS", null))))
                .isInstanceOfSatisfying(FieldValidationException.class, e ->
                        assertThat(e.getErrors()).containsKeys("ranges[0].extraAmount", "ranges[0].rowTo"));
        verify(ticketRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 겹치는_범위의_추가금이_다르면_422이고_DB를_건드리지_않는다() {
        assertThatThrownBy(() -> service.create(1L, create(ONE_SESSION,
                range("B", "1", "1", "1", "3", "X", null), range("B", "1", "1", "3", "4", "POS", 1000))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WANT_EXTRA_CONFLICT");
                    assertThat(e.getDetails()).containsEntry("conflicts", List.of(List.of(0, 1)));
                });
        verify(ticketRepository, never()).findByIdForUpdate(anyLong());

        // 완전히 같은 겹침은 허용
        assertThat(service.create(1L, create(ONE_SESSION,
                range("B", "1", "1", "1", "3", "POS", 1000), range("B", "1", "1", "3", "4", "POS", 1000)))
                .wantSeatCount()).isEqualTo(4);
    }

    @Test
    void 좌석_상한_초과는_DB를_건드리기_전에_422() {
        assertThatThrownBy(() -> service.create(1L, create(ONE_SESSION, range("B", "1", "999", "1", "999"))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WANT_SEAT_LIMIT_EXCEEDED");
                    assertThat(e.getDetails()).containsEntry("limit", 5000L);
                });
        verify(ticketRepository, never()).findByIdForUpdate(anyLong());
    }

    // ------------------------------------------------------------------ 수정

    private ExchangeRequest stubExisting() {
        ExchangeRequest existing = existingRequest(900L, myTicket);
        when(requestRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(existing));
        return existing;
    }

    @Test
    void 수정은_파생_데이터를_전부_지우고_다시_만든다() {
        ExchangeRequest existing = stubExisting();

        ExchangeRequestResponse response = service.update(1L, 900L, update(
                List.of(new WantSessionInput(8L, 1)), range("B", "1", "2", "1", "2", "POS", 3000)));

        assertThat(response.ranges().get(0).extraType()).isEqualTo("POS");
        assertThat(response.ranges().get(0).extraAmount()).isEqualTo(3000);
        assertThat(response.wantSeatCount()).isEqualTo(4);
        InOrder order = inOrder(requestRepository, seatRepository, rangeRepository, wantSessionRepository);
        order.verify(requestRepository).findByIdForUpdate(900L);
        order.verify(seatRepository).deleteByRequestId(900L);
        order.verify(rangeRepository).deleteByRequestId(900L);
        order.verify(wantSessionRepository).deleteByRequestId(900L);
        order.verify(requestRepository).saveAndFlush(existing);
        order.verify(seatRepository).insertAll(eq(900L), any());
        assertThat(existing.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 12, 0));
    }

    @Test
    void 남의_요청_수정은_403_없는_요청은_404() {
        stubExisting();

        assertThatThrownBy(() -> service.update(2L, 900L, update(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.update(1L, 404L, update(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOf(NotFoundException.class);
        verify(seatRepository, never()).deleteByRequestId(anyLong());
    }

    @Test
    void 티켓을_내렸으면_수정할_수_없다_422() {
        stubExisting();
        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.INACTIVE);

        assertThatThrownBy(() -> service.update(1L, 900L, update(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e ->
                        assertThat(e.getCode()).isEqualTo("TICKET_NOT_ACTIVE"));
        verify(seatRepository, never()).deleteByRequestId(anyLong());
    }

    @Test
    void CLOSED_요청은_수정할_수_없다() {
        ExchangeRequest existing = stubExisting();
        ReflectionTestUtils.setField(existing, "status", ExchangeRequestStatus.CLOSED);

        assertThatThrownBy(() -> service.update(1L, 900L, update(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void 수정에서도_내_회차만_희망하며_내_좌석을_포함하면_422이고_기존_데이터는_지우지_않는다() {
        stubExisting();

        assertThatThrownBy(() -> service.update(1L, 900L, update(MY_SESSION_ONLY, range("A", "3", "3", "5", "5"))))
                .isInstanceOfSatisfying(BusinessRuleException.class, e ->
                        assertThat(e.getCode()).isEqualTo("WANT_INCLUDES_OWN_SEAT"));
        verify(seatRepository, never()).deleteByRequestId(anyLong());
    }

    // ------------------------------------------------------------------ 삭제

    @Test
    void 수정에서는_다른_회차가_포함되면_내_좌석_위치도_허용한다() {
        stubExisting();

        assertThat(service.update(1L, 900L, update(List.of(new WantSessionInput(7L, 1), new WantSessionInput(8L, 2)), range("A", "3", "3", "5", "5")))
                .wantSeatCount()).isEqualTo(1);
    }

    @Test
    void 삭제는_소프트_삭제로_하위_행을_지우고_요청은_DELETED로_남긴다() {
        ExchangeRequest existing = stubExisting();

        service.delete(1L, 900L);

        assertThat(existing.getStatus()).isEqualTo(ExchangeRequestStatus.DELETED);
        assertThat(existing.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 12, 0));
        InOrder order = inOrder(requestRepository, matchRepository, seatRepository, rangeRepository, wantSessionRepository);
        order.verify(requestRepository).findByIdForUpdate(900L);
        order.verify(matchRepository).findOpenIdsByRequestId(900L);
        order.verify(seatRepository).deleteByRequestId(900L);
        order.verify(rangeRepository).deleteByRequestId(900L);
        order.verify(wantSessionRepository).deleteByRequestId(900L);
        order.verify(requestRepository).saveAndFlush(existing);
        verify(requestRepository, never()).delete(any(ExchangeRequest.class));
    }

    @Test
    void 이미_삭제된_요청의_삭제는_멱등_성공이고_아무것도_하지_않는다() {
        ExchangeRequest existing = stubExisting();
        existing.markDeleted(LocalDateTime.of(2026, 10, 6, 9, 0));

        service.delete(1L, 900L);

        assertThat(existing.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 9, 0));
        verify(matchRepository, never()).findOpenIdsByRequestId(anyLong());
        verify(seatRepository, never()).deleteByRequestId(anyLong());
    }

    @Test
    void 남의_요청_삭제는_403_없는_요청은_404() {
        ExchangeRequest existing = stubExisting();

        assertThatThrownBy(() -> service.delete(2L, 900L)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(1L, 404L)).isInstanceOf(NotFoundException.class);
        verify(seatRepository, never()).deleteByRequestId(anyLong());
        assertThat(existing.isDeleted()).isFalse();
    }

    @Test
    void 삭제된_요청은_수정할_수_없다_409_REQUEST_DELETED() {
        ExchangeRequest existing = stubExisting();
        existing.markDeleted(LocalDateTime.of(2026, 10, 6, 9, 0));

        assertThatThrownBy(() -> service.update(1L, 900L, update(ONE_SESSION, range("B", "1", "1", "1", "1"))))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getDetails()).containsEntry("code", "REQUEST_DELETED"));
        verify(seatRepository, never()).deleteByRequestId(anyLong());
    }

    // ------------------------------------------------------------------ 진행 중인 매칭 (CHATTING 시스템 취소 / RESERVED 409)

    /** 요청 900 의 열린 매칭을 (id 오름차순 조회 + 개별 잠금 읽기) 로 스텁한다. */
    private void stubOpenMatches(ExchangeMatch... matches) {
        when(matchRepository.findOpenIdsByRequestId(900L)).thenReturn(
                java.util.Arrays.stream(matches).map(ExchangeMatch::getId).sorted().toList());
        for (ExchangeMatch m : matches) {
            when(matchRepository.findByIdForUpdate(m.getId())).thenReturn(Optional.of(m));
        }
    }

    private ExchangeMatch openMatch(long id, ExchangeMatchStatus status, long requestA, long requestB) {
        ExchangeMatch m = ExchangeMatch.propose(requestA, requestB, 500L, 501L, 1L, 2L,
                new WantExtra(ExtraType.X, null), new WantExtra(ExtraType.ANY, null));
        ReflectionTestUtils.setField(m, "id", id);
        ReflectionTestUtils.setField(m, "status", status);
        return m;
    }

    @Test
    void 수정하면_CHATTING_매칭을_id_순서로_시스템_취소한다() {
        ExchangeRequest existing = stubExisting();
        ExchangeMatch first = openMatch(11L, ExchangeMatchStatus.CHATTING, 900L, 950L);
        ExchangeMatch second = openMatch(12L, ExchangeMatchStatus.CHATTING, 940L, 900L);
        stubOpenMatches(second, first);   // 입력 순서와 무관하게 id 오름차순으로 잠근다

        service.update(1L, 900L, update(ONE_SESSION, range("B", "1", "1", "1", "1")));

        for (ExchangeMatch m : List.of(first, second)) {
            assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CANCELED);
            assertThat(m.getCanceledById()).isNull();
            assertThat(m.getCanceledAt()).isEqualTo(LocalDateTime.of(2026, 10, 6, 12, 0));
        }
        InOrder order = inOrder(requestRepository, matchRepository, seatRepository);
        order.verify(requestRepository).findByIdForUpdate(900L);
        order.verify(matchRepository).findOpenIdsByRequestId(900L);
        order.verify(matchRepository).findByIdForUpdate(11L);   // 모두 id 오름차순으로 잠근 뒤 취소한다
        order.verify(matchRepository).findByIdForUpdate(12L);
        order.verify(matchRepository).saveAndFlush(first);
        order.verify(matchRepository).saveAndFlush(second);
        order.verify(seatRepository).deleteByRequestId(900L);
        assertThat(existing.isOpen()).isTrue();
        // 요청만 잠그는 경로에서 티켓 행을 새로 잠그지 않는다(잠금 순서 규칙)
        verify(ticketRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 삭제하면_CHATTING_매칭을_시스템_취소한다() {
        stubExisting();
        ExchangeMatch chatting = openMatch(11L, ExchangeMatchStatus.CHATTING, 900L, 950L);
        stubOpenMatches(chatting);

        service.delete(1L, 900L);

        assertThat(chatting.getStatus()).isEqualTo(ExchangeMatchStatus.CANCELED);
        assertThat(chatting.getCanceledById()).isNull();
        verify(ticketRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void RESERVED_매칭이_있으면_수정과_삭제가_409이고_CHATTING도_취소하지_않는다() {
        ExchangeRequest existing = stubExisting();
        ExchangeMatch chatting = openMatch(11L, ExchangeMatchStatus.CHATTING, 900L, 950L);
        ExchangeMatch reserved = openMatch(12L, ExchangeMatchStatus.RESERVED, 940L, 900L);
        stubOpenMatches(chatting, reserved);

        assertThatThrownBy(() -> service.update(1L, 900L, update(ONE_SESSION, range("B", "1", "2", "3", "5"))))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo(ExchangeRequestService.ACTIVE_MATCH_MESSAGE);
                    assertThat(e.getDetails()).containsEntry("code", "ACTIVE_MATCH_EXISTS");
                });
        assertThatThrownBy(() -> service.delete(1L, 900L)).isInstanceOf(ConflictException.class);

        assertThat(chatting.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        verify(matchRepository, never()).saveAndFlush(any(ExchangeMatch.class));
        verify(seatRepository, never()).deleteByRequestId(anyLong());
        assertThat(existing.isDeleted()).isFalse();
    }

    @Test
    void 검증_실패하는_수정은_채팅을_건드리지_않는다() {
        stubExisting();

        assertThatThrownBy(() -> service.update(1L, 900L, update(MY_SESSION_ONLY, range("A", "3", "3", "5", "5"))))
                .isInstanceOf(BusinessRuleException.class);
        verify(matchRepository, never()).findOpenIdsByRequestId(anyLong());
    }

    @Test
    void 잠근_뒤_이미_취소된_매칭은_건너뛴다() {
        stubExisting();
        ExchangeMatch gone = openMatch(11L, ExchangeMatchStatus.CANCELED, 900L, 950L);
        when(matchRepository.findOpenIdsByRequestId(900L)).thenReturn(List.of(11L));
        when(matchRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(gone));

        service.delete(1L, 900L);

        verify(matchRepository, never()).saveAndFlush(any(ExchangeMatch.class));
    }

    @Test
    void 완료된_매칭이_있어도_삭제할_수_있다() {
        ExchangeRequest existing = stubExisting();
        // 열린 매칭 조회에는 COMPLETED 가 나오지 않는다. 예전의 MATCH_HISTORY_EXISTS 409 는 없다.
        when(matchRepository.findOpenIdsByRequestId(900L)).thenReturn(List.of());

        service.delete(1L, 900L);

        assertThat(existing.isDeleted()).isTrue();
    }

    // ------------------------------------------------------------------ 목록

    @Test
    void 내_요청_목록은_범위_회차_개수를_모아_DTO로_만든다() {
        ExchangeRequest existing = existingRequest(900L, myTicket);
        when(requestRepository.findByOwner(1L)).thenReturn(List.of(existing));
        when(rangeRepository.findByRequestIdInOrderByRequestIdAscSortOrderAsc(List.of(900L))).thenReturn(List.of(
                ExchangeWantRange.create(900L, "B", "B", "1", "2", "3", "5", new WantExtra(ExtraType.POS, 7000), 0)));
        when(wantSessionRepository.findRows(List.of(900L))).thenReturn(List.of(
                new ExchangeWantSessionRepository.Row(900L, 8L, (short) 1, LocalDateTime.of(2026, 11, 2, 19, 0))));
        when(seatRepository.countByRequestIds(List.of(900L))).thenReturn(Map.of(900L, 6));

        List<ExchangeRequestResponse> mine = service.listMine(1L, null);

        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).wantSeatCount()).isEqualTo(6);
        assertThat(mine.get(0).ranges().get(0).rowTo()).isEqualTo("2");
        assertThat(mine.get(0).ranges().get(0).extraType()).isEqualTo("POS");
        assertThat(mine.get(0).ranges().get(0).extraAmount()).isEqualTo(7000);
        assertThat(mine.get(0).wantSessions().get(0).priority()).isEqualTo(1);
    }

    @Test
    void 티켓별_조회는_소유자와_티켓으로_거른다_없으면_빈_목록() {
        when(requestRepository.findByOwnerAndTicket(1L, 500L)).thenReturn(List.of());

        assertThat(service.listMine(1L, 500L)).isEmpty();
        verify(requestRepository, never()).findByOwner(anyLong());
        verify(seatRepository, never()).countByRequestIds(any());
    }
}
