package com.seatswap.service;

import com.seatswap.domain.ExchangeHistory;
import com.seatswap.domain.ExchangeMatch;
import com.seatswap.domain.ExchangeMatchAction;
import com.seatswap.domain.ExchangeMatchStatus;
import com.seatswap.domain.ExchangeRequest;
import com.seatswap.domain.ExchangeRequestStatus;
import com.seatswap.domain.ExtraType;
import com.seatswap.domain.NotificationType;
import com.seatswap.domain.WantExtra;
import com.seatswap.domain.Performance;
import com.seatswap.domain.PerformanceSession;
import com.seatswap.domain.Ticket;
import com.seatswap.domain.TicketStatus;
import com.seatswap.domain.User;
import com.seatswap.dto.response.ExchangeMatchResponse;
import com.seatswap.exception.BusinessRuleException;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.ForbiddenException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.ExchangeCandidateRepository;
import com.seatswap.repository.ExchangeHistoryRepository;
import com.seatswap.repository.ExchangeMatchRepository;
import com.seatswap.repository.ExchangeRequestRepository;
import com.seatswap.repository.ExchangeTicketLockRepository;
import com.seatswap.repository.TicketRepository;
import com.seatswap.repository.ExchangeMatchQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.seatswap.service.PerformanceFixtures.NOW;
import static com.seatswap.service.PerformanceFixtures.performance;
import static com.seatswap.service.PerformanceFixtures.session;
import static com.seatswap.service.PerformanceFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기준 시각 2026-10-06 12:00 KST. 내(1번 사용자) 티켓 700 / 요청 900, 상대(2번) 티켓 600 / 요청 800 -
 * 일부러 내 id 가 더 커서 '티켓·요청 id 오름차순 잠금'을 검증할 수 있다. SQL 판정과 실제 경쟁은 실제 MySQL 테스트가 검증한다.
 */
class ExchangeMatchServiceTest {

    private static final long MY_REQ = 900L;
    private static final long THEIR_REQ = 800L;
    private static final long MY_TICKET = 700L;
    private static final long THEIR_TICKET = 600L;
    private static final long MATCH = 50L;
    private static final WantExtra EXTRA_MY = new WantExtra(ExtraType.POS, 5000);
    private static final WantExtra EXTRA_THEIR = new WantExtra(ExtraType.NEG, -3000);

    private ExchangeMatchRepository matchRepository;
    private ExchangeRequestRepository requestRepository;
    private TicketRepository ticketRepository;
    private ExchangeTicketLockRepository lockRepository;
    private ExchangeCandidateRepository candidateRepository;
    private ExchangeHistoryRepository historyRepository;
    private ExchangeMatchQueryRepository queryRepository;
    private NotificationService notificationService;
    /** 가장 최근에 저장·등록된 매칭(조인 조회 mock 이 이 상태로 응답 행을 만든다). */
    private ExchangeMatch tracked;
    private ExchangeMatchService service;

    private final User me = user(1L, "나");
    private final User other = user(2L, "상대");
    private final Performance performance = performance(10L, "KSPO DOME", other);
    private final PerformanceSession openSession = session(7L, performance, LocalDateTime.of(2026, 11, 1, 19, 0));
    private final PerformanceSession closedSession = session(8L, performance, LocalDateTime.of(2026, 10, 5, 19, 0));

    private Ticket myTicket;
    private Ticket theirTicket;
    private ExchangeRequest myRequest;
    private ExchangeRequest theirRequest;

    @BeforeEach
    void setUp() {
        matchRepository = mock(ExchangeMatchRepository.class);
        requestRepository = mock(ExchangeRequestRepository.class);
        ticketRepository = mock(TicketRepository.class);
        lockRepository = mock(ExchangeTicketLockRepository.class);
        candidateRepository = mock(ExchangeCandidateRepository.class);
        historyRepository = mock(ExchangeHistoryRepository.class);
        queryRepository = mock(ExchangeMatchQueryRepository.class);
        notificationService = mock(NotificationService.class);
        service = new ExchangeMatchService(matchRepository, requestRepository, ticketRepository, lockRepository,
                candidateRepository, historyRepository, queryRepository, notificationService, PerformanceFixtures.timePolicy(),
                PerformanceFixtures.noopTransactionManager());

        myTicket = ticket(MY_TICKET, me, openSession);
        theirTicket = ticket(THEIR_TICKET, other, openSession);
        myRequest = request(MY_REQ, myTicket);
        theirRequest = request(THEIR_REQ, theirTicket);

        when(requestRepository.findTicketIdById(MY_REQ)).thenReturn(Optional.of(MY_TICKET));
        when(requestRepository.findTicketIdById(THEIR_REQ)).thenReturn(Optional.of(THEIR_TICKET));
        when(ticketRepository.findOwnerIdById(MY_TICKET)).thenReturn(Optional.of(1L));
        when(ticketRepository.findByIdForUpdate(MY_TICKET)).thenReturn(Optional.of(myTicket));
        when(ticketRepository.findByIdForUpdate(THEIR_TICKET)).thenReturn(Optional.of(theirTicket));
        when(ticketRepository.findWithSessionById(MY_TICKET)).thenReturn(Optional.of(myTicket));
        when(requestRepository.findByIdForUpdate(MY_REQ)).thenReturn(Optional.of(myRequest));
        when(requestRepository.findByIdForUpdate(THEIR_REQ)).thenReturn(Optional.of(theirRequest));
        when(candidateRepository.findCandidatePair(eq(MY_REQ), eq(THEIR_REQ), any()))
                .thenReturn(Optional.of(new ExchangeCandidateRepository.PairExtras(EXTRA_MY, EXTRA_THEIR)));
        when(matchRepository.findOpenByPair(anyLong(), anyLong())).thenReturn(List.of());
        when(matchRepository.saveAndFlush(any(ExchangeMatch.class))).thenAnswer(inv -> {
            ExchangeMatch m = inv.getArgument(0);
            if (m.getId() == null) {
                ReflectionTestUtils.setField(m, "id", MATCH);
            }
            tracked = m;
            return m;
        });
        when(queryRepository.findOne(anyLong(), anyLong())).thenAnswer(inv -> Optional.of(row(tracked)));
    }

    /** 조인 조회가 돌려줄 행을 매칭 엔티티 상태로 만든다(실제 SQL 정확성은 MySQL 테스트가 본다). */
    private ExchangeMatchQueryRepository.Row row(ExchangeMatch m) {
        ExchangeMatchResponse.Seat mine = new ExchangeMatchResponse.Seat("A", "3", "5", 7L, openSession.getStartsAt());
        return new ExchangeMatchQueryRepository.Row(m.getId(), m.getStatus().name(), m.getUserAId(), m.getUserBId(),
                m.getRequestAId(), m.getRequestBId(), m.getTicketAId(), m.getTicketBId(),
                m.getReservedById(), m.getReservedAt(), m.getReserveProposedById(), m.getACompletedAt() != null, m.getBCompletedAt() != null, m.getCanceledById(), m.getCanceledAt(),
                m.getCreatedAt(), m.getUpdatedAt(), mine, mine, m.getAExtraType().name(), m.getAExtraAmount(),
                m.getBExtraType().name(), m.getBExtraAmount(), false, false, false, false, false, false, "나", "상대", 0);
    }

    private ExchangeMatchResponse toResponse(ExchangeMatch m, long userId) {
        return row(m).toResponse(userId);
    }

    private static Ticket ticket(long id, User owner, PerformanceSession session) {
        Ticket t = Ticket.create(owner, session, "A", "A", "3", "3", "5", "5");
        ReflectionTestUtils.setField(t, "id", id);
        return t;
    }

    private static ExchangeRequest request(long id, Ticket ticket) {
        ExchangeRequest r = ExchangeRequest.create(ticket);
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    /** 내가 a(제안자), 상대가 b 인 매칭을 상태와 함께 mock 저장소에 등록한다. */
    private ExchangeMatch existingMatch(ExchangeMatchStatus status) {
        ExchangeMatch m = ExchangeMatch.propose(MY_REQ, THEIR_REQ, MY_TICKET, THEIR_TICKET, 1L, 2L, EXTRA_MY, EXTRA_THEIR);
        ReflectionTestUtils.setField(m, "id", MATCH);
        ReflectionTestUtils.setField(m, "status", status);
        if (status == ExchangeMatchStatus.RESERVED) {
            ReflectionTestUtils.setField(m, "reservedById", 1L);   // 예약자는 a측(1번)
            ReflectionTestUtils.setField(m, "reservedAt", NOW.minusHours(1));
        }
        if (status == ExchangeMatchStatus.COMPLETED) {
            ReflectionTestUtils.setField(m, "aCompletedAt", NOW.minusHours(2));
            ReflectionTestUtils.setField(m, "bCompletedAt", NOW.minusHours(1));
        }
        if (status == ExchangeMatchStatus.CANCELED) {
            ReflectionTestUtils.setField(m, "canceledAt", NOW.minusHours(1));
        }
        tracked = m;
        when(matchRepository.findById(MATCH)).thenReturn(Optional.of(m));
        when(matchRepository.findByIdForUpdate(MATCH)).thenReturn(Optional.of(m));
        return m;
    }

    /** 내가(1번)·상대(2번) 중 proposer 가 예약을 제안해 둔 CHATTING 매칭. */
    private ExchangeMatch proposedBy(long proposer) {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);
        ReflectionTestUtils.setField(m, "reserveProposedById", proposer);
        ReflectionTestUtils.setField(m, "reserveProposedAt", NOW.minusMinutes(5));
        return m;
    }

    // ------------------------------------------------------------------ 제안

    @Test
    void 제안하면_CHATTING_매칭을_만들고_티켓_요청을_id_오름차순으로_잠근다() {
        ExchangeMatchResponse response = service.propose(1L, MY_REQ, THEIR_REQ);

        assertThat(response.id()).isEqualTo(MATCH);
        assertThat(response.status()).isEqualTo("CHATTING");
        assertThat(response.mySide()).isEqualTo("A");
        assertThat(response.myRequestId()).isEqualTo(MY_REQ);
        assertThat(response.counterpartRequestId()).isEqualTo(THEIR_REQ);
        assertThat(response.counterpartTicketId()).isEqualTo(THEIR_TICKET);
        assertThat(response.counterpartNickname()).isEqualTo("상대");
        assertThat(response.reservedBy()).isNull();
        assertThat(response.reservedAt()).isNull();
        assertThat(response.canceledBy()).isNull();

        InOrder order = inOrder(ticketRepository, requestRepository, matchRepository);
        order.verify(ticketRepository).findByIdForUpdate(THEIR_TICKET);   // 600 -> 700
        order.verify(ticketRepository).findByIdForUpdate(MY_TICKET);
        order.verify(requestRepository).findByIdForUpdate(THEIR_REQ);      // 800 -> 900
        order.verify(requestRepository).findByIdForUpdate(MY_REQ);
        order.verify(matchRepository).findOpenByPair(MY_REQ, THEIR_REQ);
        order.verify(matchRepository).saveAndFlush(any(ExchangeMatch.class));
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
    }

    @Test
    void 제안한_매칭은_제안자가_a측이고_티켓_사용자가_채워진다() {
        service.propose(1L, MY_REQ, THEIR_REQ);

        org.mockito.ArgumentCaptor<ExchangeMatch> captor = org.mockito.ArgumentCaptor.forClass(ExchangeMatch.class);
        verify(matchRepository).saveAndFlush(captor.capture());
        ExchangeMatch saved = captor.getValue();
        assertThat(saved.getRequestAId()).isEqualTo(MY_REQ);
        assertThat(saved.getRequestBId()).isEqualTo(THEIR_REQ);
        // 후보 판정에서 읽은 양쪽 적용 추가금이 매칭 행의 스냅샷으로 복사된다(a = 제안자 쪽)
        assertThat(saved.getAExtraType()).isEqualTo(ExtraType.POS);
        assertThat(saved.getAExtraAmount()).isEqualTo(5000);
        assertThat(saved.getBExtraType()).isEqualTo(ExtraType.NEG);
        assertThat(saved.getBExtraAmount()).isEqualTo(-3000);
        assertThat(saved.getTicketAId()).isEqualTo(MY_TICKET);
        assertThat(saved.getTicketBId()).isEqualTo(THEIR_TICKET);
        assertThat(saved.getUserAId()).isEqualTo(1L);
        assertThat(saved.getUserBId()).isEqualTo(2L);
    }

    @Test
    void 남의_요청으로_제안하면_403이고_잠금을_잡지_않는다() {
        when(ticketRepository.findOwnerIdById(MY_TICKET)).thenReturn(Optional.of(99L));

        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ)).isInstanceOf(ForbiddenException.class);

        verify(ticketRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 없는_요청은_404() {
        when(requestRepository.findTicketIdById(MY_REQ)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ)).isInstanceOf(NotFoundException.class);

        when(requestRepository.findTicketIdById(MY_REQ)).thenReturn(Optional.of(MY_TICKET));
        when(requestRepository.findTicketIdById(THEIR_REQ)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 잠금_사이에_요청이_삭제됐으면_404() {
        when(requestRepository.findByIdForUpdate(THEIR_REQ)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ)).isInstanceOf(NotFoundException.class);
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 자기_자신의_요청을_고르면_422() {
        assertThatThrownBy(() -> service.propose(1L, MY_REQ, MY_REQ))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo("NOT_A_CANDIDATE"));
    }

    @Test
    void 같은_쌍의_열린_매칭이_있으면_409이고_matchId를_알려준다() {
        ExchangeMatch open = existingMatch(ExchangeMatchStatus.CHATTING);
        when(matchRepository.findOpenByPair(MY_REQ, THEIR_REQ)).thenReturn(List.of(open));

        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ))
                .isInstanceOfSatisfying(ConflictException.class, e -> {
                    assertThat(e.getDetails()).containsEntry("code", "MATCH_ALREADY_OPEN").containsEntry("matchId", MATCH);
                });
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 취소된_매칭만_있으면_다시_제안할_수_있다() {
        // 열린 매칭 조회는 CHATTING/RESERVED 만 보므로 취소·완료 이력이 있어도 빈 목록이다 (재매칭 불가는 차단·신고뿐)
        when(matchRepository.findOpenByPair(MY_REQ, THEIR_REQ)).thenReturn(List.of());

        assertThat(service.propose(1L, MY_REQ, THEIR_REQ).status()).isEqualTo("CHATTING");
    }

    @Test
    void 제안_재검증_실패_사례는_모두_422이고_매칭을_만들지_않는다() {
        // 1) 후보 조건 불충족 (SQL 판정)
        when(candidateRepository.findCandidatePair(eq(MY_REQ), eq(THEIR_REQ), any())).thenReturn(Optional.empty());
        assertCode(NOT_A_CANDIDATE());

        // 2) 상대 요청 닫힘
        when(candidateRepository.findCandidatePair(eq(MY_REQ), eq(THEIR_REQ), any()))
                .thenReturn(Optional.of(new ExchangeCandidateRepository.PairExtras(EXTRA_MY, EXTRA_THEIR)));
        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.CLOSED);
        assertCode(NOT_A_CANDIDATE());
        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.OPEN);

        // 3) 상대 티켓 INACTIVE
        ReflectionTestUtils.setField(theirTicket, "status", TicketStatus.INACTIVE);
        assertCode(NOT_A_CANDIDATE());
        ReflectionTestUtils.setField(theirTicket, "status", TicketStatus.ACTIVE);

        // 3-1) 상대 요청 삭제됨 -> 후보에서 빠진 것과 같은 NOT_A_CANDIDATE
        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.DELETED);
        assertCode(NOT_A_CANDIDATE());
        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.OPEN);

        // 4) 내 요청 닫힘 / 내 티켓 INACTIVE
        ReflectionTestUtils.setField(myRequest, "status", ExchangeRequestStatus.CLOSED);
        assertCode("TICKET_NOT_ACTIVE");
        ReflectionTestUtils.setField(myRequest, "status", ExchangeRequestStatus.OPEN);
        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.INACTIVE);
        assertCode("TICKET_NOT_ACTIVE");
        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.ACTIVE);

        // 5) 내 티켓이 예약 잠금 -> TICKET_LOCKED (후보 판정을 통과한 뒤에만 구분)
        when(lockRepository.existsAnyByTicketIds(List.of(MY_TICKET))).thenReturn(true);
        assertCode("TICKET_LOCKED");
        when(lockRepository.existsAnyByTicketIds(List.of(MY_TICKET))).thenReturn(false);

        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 내_회차_마감이_지났으면_422_SESSION_CLOSED() {
        Ticket closedTicket = ticket(MY_TICKET, me, closedSession);
        when(ticketRepository.findWithSessionById(MY_TICKET)).thenReturn(Optional.of(closedTicket));

        assertCode("SESSION_CLOSED");
    }

    @Test
    void 상대_티켓이_잠겼으면_NOT_A_CANDIDATE로_합쳐_예약_상태를_노출하지_않는다() {
        when(lockRepository.existsAnyByTicketIds(List.of(THEIR_TICKET))).thenReturn(true);

        assertCode("NOT_A_CANDIDATE");
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 후보가_아니면_잠금_상태와_무관하게_NOT_A_CANDIDATE이고_잠금을_조회하지_않는다() {
        when(candidateRepository.findCandidatePair(eq(MY_REQ), eq(THEIR_REQ), any())).thenReturn(Optional.empty());
        when(lockRepository.existsAnyByTicketIds(any())).thenReturn(true);

        assertCode("NOT_A_CANDIDATE");
        verify(lockRepository, never()).existsAnyByTicketIds(any());
    }

    private static String NOT_A_CANDIDATE() {
        return "NOT_A_CANDIDATE";
    }

    @Test
    void 내_요청이_삭제됐으면_409_REQUEST_DELETED이고_매칭을_만들지_않는다() {
        ReflectionTestUtils.setField(myRequest, "status", ExchangeRequestStatus.DELETED);

        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ))
                .isInstanceOfSatisfying(ConflictException.class, e ->
                        assertThat(e.getDetails()).containsEntry("code", "REQUEST_DELETED"));
        verify(matchRepository, never()).saveAndFlush(any());
        verify(candidateRepository, never()).findCandidatePair(anyLong(), anyLong(), any());
    }

    private void assertCode(String code) {
        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ))
                .isInstanceOfSatisfying(BusinessRuleException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    // ------------------------------------------------------------------ 예약 제안·수락·거절·철회 (V15)

    @Test
    void 예약_제안은_상태를_바꾸지_않고_제안_컬럼만_채우며_티켓을_잠그지_않는다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);

        ExchangeMatchResponse response = service.reserve(2L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        assertThat(m.getReserveProposedById()).isEqualTo(2L);
        assertThat(m.getReserveProposedAt()).isEqualTo(NOW);
        assertThat(m.getReservedById()).isNull();
        assertThat(response.status()).isEqualTo("CHATTING");
        assertThat(response.reservationProposal()).isEqualTo("MINE");
        assertThat(toResponse(m, 1L).reservationProposal()).isEqualTo("THEIRS");
        verify(ticketRepository, never()).findByIdForUpdate(anyLong());
        verify(requestRepository, never()).findByIdForUpdate(anyLong());
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
        verify(matchRepository).findByIdForUpdate(MATCH);
    }

    @Test
    void 내가_이미_제안했으면_멱등_200이고_아무것도_쓰지_않는다() {
        proposedBy(2L);

        ExchangeMatchResponse response = service.reserve(2L, MATCH);

        assertThat(response.reservationProposal()).isEqualTo("MINE");
        verify(matchRepository, never()).saveAndFlush(any());
        verify(notificationService, never()).notify(any(), any(), any(), any());
    }

    @Test
    void 상대가_이미_제안했으면_제안은_409_RESERVATION_PROPOSAL_PENDING() {
        ExchangeMatch m = proposedBy(1L);

        assertThatThrownBy(() -> service.reserve(2L, MATCH)).isInstanceOfSatisfying(ConflictException.class, e -> {
            assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_PENDING");
            assertThat(e.getMessage()).isEqualTo("상대가 이미 예약을 제안했어요. 수락하거나 거절해 주세요.");
        });
        assertThat(m.getReserveProposedById()).isEqualTo(1L);
        verify(notificationService, never()).notify(any(), any(), any(), any());
    }

    @Test
    void 제안_시점에도_티켓이_다른_매칭에서_잠겼으면_409이고_EXCHANGED면_TICKET_EXCHANGED다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);
        when(lockRepository.existsAnyByTicketIds(anyCollection())).thenReturn(true);
        assertThatThrownBy(() -> service.reserve(1L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_ALREADY_RESERVED"));
        when(lockRepository.existsAnyByTicketIds(anyCollection())).thenReturn(false);

        ReflectionTestUtils.setField(theirTicket, "status", TicketStatus.EXCHANGED);
        when(ticketRepository.findAllById(anyCollection())).thenReturn(List.of(myTicket, theirTicket));
        assertThatThrownBy(() -> service.reserve(1L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_EXCHANGED"));

        assertThat(m.hasReservationProposal()).isFalse();
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void RESERVED와_닫힌_매칭에서_제안은_409_MATCH_STATE_CONFLICT() {
        for (ExchangeMatchStatus status : List.of(ExchangeMatchStatus.RESERVED, ExchangeMatchStatus.COMPLETED, ExchangeMatchStatus.CANCELED)) {
            existingMatch(status);
            assertThatThrownBy(() -> service.reserve(1L, MATCH)).as(status.name())
                    .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.getDetails())
                            .containsEntry("code", "MATCH_STATE_CONFLICT").containsEntry("status", status.name()).containsEntry("action", "RESERVE"));
        }
    }

    @Test
    void 제안받은_쪽이_수락하면_RESERVED_예약자는_제안자_제안은_비워지고_잠금을_id_오름차순으로_넣는다() {
        ExchangeMatch m = proposedBy(1L);

        ExchangeMatchResponse response = service.reserveAccept(2L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.RESERVED);
        assertThat(m.getReservedById()).as("reserved_by_id = 제안자").isEqualTo(1L);
        assertThat(m.getReservedAt()).as("reserved_at = 수락 시각").isEqualTo(NOW);
        assertThat(m.getReserveProposedById()).isNull();
        assertThat(m.getReserveProposedAt()).isNull();
        assertThat(response.status()).isEqualTo("RESERVED");
        assertThat(response.mySide()).isEqualTo("B");
        assertThat(response.reservedBy()).isEqualTo("COUNTERPART");
        assertThat(response.reservationProposal()).isEqualTo("NONE");
        assertThat(toResponse(m, 1L).reservedBy()).isEqualTo("ME");
        InOrder order = inOrder(ticketRepository, requestRepository, matchRepository, lockRepository);
        order.verify(ticketRepository).findByIdForUpdate(THEIR_TICKET);
        order.verify(ticketRepository).findByIdForUpdate(MY_TICKET);
        order.verify(requestRepository).findByIdForUpdate(THEIR_REQ);
        order.verify(requestRepository).findByIdForUpdate(MY_REQ);
        order.verify(matchRepository).findByIdForUpdate(MATCH);
        order.verify(lockRepository).insert(eq(THEIR_TICKET), eq(MATCH), any());
        order.verify(lockRepository).insert(eq(MY_TICKET), eq(MATCH), any());
    }

    @Test
    void 수락_오류_제안_없음은_NOT_FOUND_내_제안은_OWN() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);
        assertThatThrownBy(() -> service.reserveAccept(2L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_NOT_FOUND"));

        proposedBy(1L);
        assertThatThrownBy(() -> service.reserveAccept(1L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_OWN"));

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
        verify(notificationService, never()).notify(any(), any(), any(), any());
    }

    @Test
    void 이미_RESERVED면_수락자도_제안자도_accept는_멱등_200이고_아무것도_쓰지_않는다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);   // 제안자 = 예약자 = 1번

        assertThat(service.reserveAccept(2L, MATCH).status()).isEqualTo("RESERVED");
        assertThat(service.reserveAccept(1L, MATCH).status()).as("제안자의 accept 도 멱등 200").isEqualTo("RESERVED");

        assertThat(m.getReservedById()).isEqualTo(1L);
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
        verify(lockRepository, never()).existsAnyByTicketIds(any());
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 수락_때_티켓이_다른_매칭에서_이미_잠겼으면_409이고_제안과_상태가_그대로다() {
        ExchangeMatch m = proposedBy(1L);
        when(lockRepository.existsAnyByTicketIds(anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.reserveAccept(2L, MATCH))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_ALREADY_RESERVED"));

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        assertThat(m.getReservedById()).isNull();
        assertThat(m.getReserveProposedById()).isEqualTo(1L);
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
    }

    @Test
    void 잠금_INSERT가_PK_충돌이면_409로_바꾼다() {
        proposedBy(1L);
        doThrow(new DuplicateKeyException("dup")).when(lockRepository).insert(eq(MY_TICKET), eq(MATCH), any());

        assertThatThrownBy(() -> service.reserveAccept(2L, MATCH))
                .isInstanceOfSatisfying(ConflictException.class,
                        e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_ALREADY_RESERVED"));
    }

    @Test
    void 수락은_두_티켓_ACTIVE와_두_요청_OPEN을_다시_확인한다() {
        ExchangeMatch m = proposedBy(1L);

        ReflectionTestUtils.setField(theirTicket, "status", TicketStatus.INACTIVE);
        assertThatThrownBy(() -> service.reserveAccept(2L, MATCH)).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_ACTIVE"));
        ReflectionTestUtils.setField(theirTicket, "status", TicketStatus.ACTIVE);

        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.INACTIVE);
        assertThatThrownBy(() -> service.reserveAccept(2L, MATCH)).isInstanceOf(BusinessRuleException.class);
        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.ACTIVE);

        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.CLOSED);
        assertThatThrownBy(() -> service.reserveAccept(2L, MATCH)).isInstanceOfSatisfying(BusinessRuleException.class,
                e -> assertThat(e.getCode()).isEqualTo("TICKET_NOT_ACTIVE"));

        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.DELETED);
        assertThatThrownBy(() -> service.reserveAccept(2L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "REQUEST_DELETED"));
        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.OPEN);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
        assertThat(service.reserveAccept(2L, MATCH).status()).as("정상이면 예약된다").isEqualTo("RESERVED");
    }

    @Test
    void 닫힌_요청이어도_멱등_수락_unreserve_cancel_reject는_검증하지_않는다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);
        when(lockRepository.deleteByMatchId(MATCH)).thenReturn(2);
        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.INACTIVE);
        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.DELETED);

        assertThat(service.reserveAccept(2L, MATCH).status()).as("멱등 수락").isEqualTo("RESERVED");
        assertThat(service.unreserve(2L, MATCH).status()).as("unreserve 는 항상 가능").isEqualTo("CHATTING");
        assertThat(service.reject(2L, MATCH).status()).isEqualTo("CANCELED");
        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CANCELED);
        existingMatch(ExchangeMatchStatus.CHATTING);
        assertThat(service.cancel(1L, MATCH).status()).isEqualTo("CANCELED");
    }

    @Test
    void 회차_마감이_지난_매칭도_제안_수락할_수_있다() {
        // 6차 확정: 이미 시작한 채팅의 예약에는 회차 마감 검사를 하지 않는다
        ExchangeMatch m = proposedBy(1L);
        ReflectionTestUtils.setField(myTicket, "performanceSession", closedSession);

        assertThat(service.reserveAccept(2L, MATCH).status()).isEqualTo("RESERVED");
        assertThat(m.getReservedById()).isEqualTo(1L);
    }

    @Test
    void 제안받은_쪽이_거절하면_제안만_지우고_CHATTING_유지_제안자에게_REJECTED_알림() {
        ExchangeMatch m = proposedBy(1L);

        ExchangeMatchResponse response = service.reserveDecline(2L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        assertThat(m.hasReservationProposal()).isFalse();
        assertThat(response.reservationProposal()).isEqualTo("NONE");
        verify(ticketRepository, never()).findByIdForUpdate(anyLong());
        verify(notificationService).notify(1L, NotificationType.RESERVATION_REJECTED, MATCH, null);
        verify(notificationService, org.mockito.Mockito.times(1)).notify(any(), any(), any(), any());
    }

    @Test
    void 제안자가_철회하면_제안만_지우고_알림은_없다() {
        ExchangeMatch m = proposedBy(1L);

        ExchangeMatchResponse response = service.reserveWithdraw(1L, MATCH);

        assertThat(m.hasReservationProposal()).isFalse();
        assertThat(response.reservationProposal()).isEqualTo("NONE");
        verify(notificationService, never()).notify(any(), any(), any(), any());
    }

    @Test
    void 거절_철회의_권한_오류와_멱등() {
        ExchangeMatch m = proposedBy(1L);
        assertThatThrownBy(() -> service.reserveDecline(1L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_OWN"));
        assertThatThrownBy(() -> service.reserveWithdraw(2L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "RESERVATION_PROPOSAL_NOT_OWN"));
        assertThat(m.getReserveProposedById()).isEqualTo(1L);

        existingMatch(ExchangeMatchStatus.CHATTING);   // 제안 없음: 멱등 200
        assertThat(service.reserveDecline(2L, MATCH).status()).isEqualTo("CHATTING");
        assertThat(service.reserveWithdraw(1L, MATCH).status()).isEqualTo("CHATTING");
        existingMatch(ExchangeMatchStatus.RESERVED);   // 수락 직후 도착한 늦은 거절·철회도 200
        assertThat(service.reserveDecline(2L, MATCH).status()).isEqualTo("RESERVED");
        assertThat(service.reserveWithdraw(1L, MATCH).status()).isEqualTo("RESERVED");
        existingMatch(ExchangeMatchStatus.CANCELED);
        assertThatThrownBy(() -> service.reserveDecline(2L, MATCH)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.reserveWithdraw(1L, MATCH)).isInstanceOf(ConflictException.class);
        verify(notificationService, never()).notify(any(), any(), any(), any());
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 매칭_비참여자는_없는_매칭과_같은_404() {
        existingMatch(ExchangeMatchStatus.CHATTING);

        for (ExchangeMatchAction action : ExchangeMatchAction.values()) {
            if (action == ExchangeMatchAction.PROPOSE || action == ExchangeMatchAction.COMPLETE) {
                continue;
            }
            assertThatThrownBy(() -> run(action, 3L, MATCH)).isInstanceOf(NotFoundException.class)
                    .hasMessage(ExchangeMatchService.MATCH_NOT_FOUND_MESSAGE);
            assertThatThrownBy(() -> run(action, 1L, 999L)).isInstanceOf(NotFoundException.class);
        }
        verify(ticketRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void 제안이_걸린_채_취소_거절하면_제안도_비워진다() {
        ExchangeMatch m = proposedBy(1L);
        service.cancel(1L, MATCH);
        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CANCELED);
        assertThat(m.getReserveProposedById()).isNull();
        assertThat(m.getReserveProposedAt()).isNull();

        ExchangeMatch m2 = proposedBy(2L);
        service.reject(2L, MATCH);
        assertThat(m2.getReserveProposedById()).isNull();
    }

    // ------------------------------------------------------------------ 예약 취소

    @Test
    void 예약한_사람이_아니어도_예약_취소로_CHATTING_복귀_잠금_삭제_표시_초기화() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);
        ReflectionTestUtils.setField(m, "bCompletedAt", NOW.minusMinutes(1));   // 한쪽이 교환 수락을 눌러 둔 상태
        when(lockRepository.deleteByMatchId(MATCH)).thenReturn(2);

        ExchangeMatchResponse response = service.unreserve(2L, MATCH);   // 예약자는 a(1번), 취소는 b

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        assertThat(m.getReservedById()).isNull();
        assertThat(m.getReservedAt()).isNull();
        assertThat(m.getBCompletedAt()).isNull();
        assertThat(m.getACompletedAt()).isNull();
        assertThat(response.status()).isEqualTo("CHATTING");
        assertThat(response.reservedBy()).isNull();
        assertThat(response.myAccepted()).isFalse();
        assertThat(response.counterpartAccepted()).isFalse();
        verify(lockRepository).deleteByMatchId(MATCH);
    }

    @Test
    void 예약_취소_뒤_같은_쌍이_다시_예약할_수_있다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);
        when(lockRepository.deleteByMatchId(MATCH)).thenReturn(2);
        service.unreserve(1L, MATCH);

        // 같은 쌍은 다시 제안부터: 제안만으로는 RESERVED 가 아니다
        ExchangeMatchResponse proposed = service.reserve(2L, MATCH);
        assertThat(proposed.status()).isEqualTo("CHATTING");
        assertThat(proposed.reservationProposal()).isEqualTo("MINE");
        ExchangeMatchResponse again = service.reserveAccept(1L, MATCH);

        assertThat(again.status()).isEqualTo("RESERVED");
        assertThat(m.getReservedById()).isEqualTo(2L);
    }

    @Test
    void CHATTING에서_예약_취소는_멱등_200이고_아무것도_바꾸지_않는다() {
        existingMatch(ExchangeMatchStatus.CHATTING);

        assertThat(service.unreserve(1L, MATCH).status()).isEqualTo("CHATTING");
        verify(lockRepository, never()).deleteByMatchId(anyLong());
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void 잠금이_2행이_아니면_예약_취소를_되돌리려_예외를_던진다() {
        existingMatch(ExchangeMatchStatus.RESERVED);
        when(lockRepository.deleteByMatchId(MATCH)).thenReturn(1);

        assertThatThrownBy(() -> service.unreserve(1L, MATCH)).isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ 알림 (V10 쓰기 경로)

    @Test
    void 제안하면_제안받는_쪽_b에게_PROPOSAL_RECEIVED_알림_1건을_매칭_저장_뒤에_만든다() {
        service.propose(1L, MY_REQ, THEIR_REQ);

        InOrder order = inOrder(matchRepository, notificationService);
        order.verify(matchRepository).saveAndFlush(any(ExchangeMatch.class));
        order.verify(notificationService).notify(2L, NotificationType.PROPOSAL_RECEIVED, MATCH, "PROPOSAL_RECEIVED:" + MATCH);
        verify(notificationService, org.mockito.Mockito.times(1)).notify(any(), any(), any(), any());
    }

    @Test
    void 제안이_오류나_멱등_경로로_끝나면_알림을_만들지_않는다() {
        ExchangeMatch open = existingMatch(ExchangeMatchStatus.CHATTING);
        when(matchRepository.findOpenByPair(MY_REQ, THEIR_REQ)).thenReturn(List.of(open));
        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ)).isInstanceOf(ConflictException.class);
        when(matchRepository.findOpenByPair(MY_REQ, THEIR_REQ)).thenReturn(List.of());
        when(candidateRepository.findCandidatePair(eq(MY_REQ), eq(THEIR_REQ), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.propose(1L, MY_REQ, THEIR_REQ)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.propose(2L, MY_REQ, THEIR_REQ)).isInstanceOf(ForbiddenException.class);

        verify(notificationService, never()).notify(any(), any(), any(), any());
    }

    @Test
    void 예약_제안하면_상대에게_RESERVATION_PROPOSED_알림_1건_dedup_없음_매칭_저장_뒤에() {
        existingMatch(ExchangeMatchStatus.CHATTING);

        service.reserve(2L, MATCH);   // b 가 제안하면 a(1번)에게

        InOrder order = inOrder(matchRepository, notificationService);
        order.verify(matchRepository).saveAndFlush(any(ExchangeMatch.class));
        order.verify(notificationService).notifyReservationProposed(1L, MATCH);
        verify(notificationService, never()).notify(any(), any(), any(), any());

        existingMatch(ExchangeMatchStatus.CHATTING);
        service.reserve(1L, MATCH);
        verify(notificationService).notifyReservationProposed(2L, MATCH);
    }

    @Test
    void 수락하면_제안자에게_MATCH_RESERVED_알림_1건_dedup_없음_잠금_INSERT_뒤에() {
        proposedBy(1L);

        service.reserveAccept(2L, MATCH);

        InOrder order = inOrder(lockRepository, notificationService);
        order.verify(lockRepository).insert(eq(MY_TICKET), eq(MATCH), any());
        order.verify(notificationService).notify(1L, NotificationType.MATCH_RESERVED, MATCH, null);
        verify(notificationService, org.mockito.Mockito.times(1)).notify(any(), any(), any(), any());

        proposedBy(2L);
        service.reserveAccept(1L, MATCH);
        verify(notificationService).notify(2L, NotificationType.MATCH_RESERVED, MATCH, null);
    }

    @Test
    void 예약_취소하면_호출자가_아닌_쪽에게_RESERVATION_CANCELED_알림_1건이다_A든_B든() {
        existingMatch(ExchangeMatchStatus.RESERVED);
        when(lockRepository.deleteByMatchId(MATCH)).thenReturn(2);

        service.unreserve(2L, MATCH);
        verify(notificationService).notify(1L, NotificationType.RESERVATION_CANCELED, MATCH, null);

        existingMatch(ExchangeMatchStatus.RESERVED);
        service.unreserve(1L, MATCH);
        verify(notificationService).notify(2L, NotificationType.RESERVATION_CANCELED, MATCH, null);
        verify(notificationService, org.mockito.Mockito.times(2)).notify(any(), any(), any(), any());
    }

    @Test
    void 멱등_수락_unreserve와_오류_경로와_철회_거절_취소는_알림을_만들지_않는다() {
        existingMatch(ExchangeMatchStatus.RESERVED);
        service.reserveAccept(2L, MATCH);                              // 이미 RESERVED: 멱등
        assertThatThrownBy(() -> service.cancel(1L, MATCH)).isInstanceOf(ConflictException.class);   // RESERVED 에서 취소 409
        assertThatThrownBy(() -> service.reserve(99L, MATCH)).isInstanceOf(NotFoundException.class);   // 비참여자

        existingMatch(ExchangeMatchStatus.CHATTING);
        service.unreserve(1L, MATCH);                                  // 이미 CHATTING: 멱등
        when(lockRepository.existsAnyByTicketIds(anyCollection())).thenReturn(true);
        assertThatThrownBy(() -> service.reserve(2L, MATCH)).isInstanceOf(ConflictException.class);   // TICKET_ALREADY_RESERVED(제안 시점)
        proposedBy(1L);
        assertThatThrownBy(() -> service.reserveAccept(2L, MATCH)).isInstanceOf(ConflictException.class);   // TICKET_ALREADY_RESERVED(수락)
        when(lockRepository.existsAnyByTicketIds(anyCollection())).thenReturn(false);
        service.reserveWithdraw(1L, MATCH);                            // 철회: 알림 없음
        existingMatch(ExchangeMatchStatus.CHATTING);
        service.reject(2L, MATCH);
        existingMatch(ExchangeMatchStatus.CHATTING);
        service.cancel(1L, MATCH);

        verify(notificationService, never()).notify(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ 거절·취소

    @Test
    void 제안받은_쪽은_거절할_수_있고_CANCELED로_바뀐다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);

        ExchangeMatchResponse response = service.reject(2L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CANCELED);
        assertThat(m.getCanceledById()).isEqualTo(2L);
        assertThat(m.getCanceledAt()).isEqualTo(NOW);
        assertThat(response.canceledBy()).isEqualTo("ME");
        verify(lockRepository, never()).deleteByMatchId(anyLong());
    }

    @Test
    void 제안한_쪽이_거절을_부르면_403이다_취소를_써야_한다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);

        assertThatThrownBy(() -> service.reject(1L, MATCH))
                .isInstanceOfSatisfying(ForbiddenException.class,
                        e -> assertThat(e.getMessage()).isEqualTo(ExchangeMatchService.FORBIDDEN_REJECT_MESSAGE));
        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
    }

    @Test
    void 취소는_양쪽_누구나_할_수_있고_상대_화면에서는_COUNTERPART다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);

        ExchangeMatchResponse mine = service.cancel(1L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CANCELED);
        assertThat(m.getCanceledById()).isEqualTo(1L);
        assertThat(mine.canceledBy()).isEqualTo("ME");
        // 상대(b)가 같은 매칭을 조회한다면 COUNTERPART 로 보인다
        assertThat(toResponse(m, 2L).canceledBy()).isEqualTo("COUNTERPART");
    }

    @Test
    void RESERVED에서는_취소와_거절이_모두_409이고_먼저_예약을_취소하라는_문구다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);

        for (ExchangeMatchAction action : List.of(ExchangeMatchAction.CANCEL, ExchangeMatchAction.REJECT)) {
            long actor = action == ExchangeMatchAction.REJECT ? 2L : 1L;
            assertThatThrownBy(() -> run(action, actor, MATCH))
                    .isInstanceOfSatisfying(ConflictException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("먼저 예약을 취소해주세요.");
                        assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT")
                                .containsEntry("status", "RESERVED").containsEntry("action", action.name());
                    });
        }
        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.RESERVED);
        verify(lockRepository, never()).deleteByMatchId(anyLong());
    }

    @Test
    void 시스템_취소된_매칭은_canceledBy가_SYSTEM이다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CANCELED);

        assertThat(toResponse(m, 1L).canceledBy()).isEqualTo("SYSTEM");
    }

    // ------------------------------------------------------------------ 전이 표 (서비스 동작 x 상태 전 조합)

    private ExchangeMatchResponse run(ExchangeMatchAction action, long userId, long matchId) {
        return switch (action) {
            case RESERVE -> service.reserve(userId, matchId);
            case RESERVE_ACCEPT -> service.reserveAccept(userId, matchId);
            case RESERVE_DECLINE -> service.reserveDecline(userId, matchId);
            case RESERVE_WITHDRAW -> service.reserveWithdraw(userId, matchId);
            case UNRESERVE -> service.unreserve(userId, matchId);
            case REJECT -> service.reject(userId, matchId);
            case CANCEL -> service.cancel(userId, matchId);
            default -> throw new IllegalArgumentException(action.name());
        };
    }

    /**
     * 전이 표 전체(서비스 경로). 기대값: 결과 상태 문자열(200) 또는 "409". 행위자는 제안 흐름이 의미 있도록 고른다:
     * CHATTING 은 제안 없는 상태에서 b(2번)가 호출(reject 도 b), RESERVED 는 제안자 = 예약자 = a(1번)이므로 수락·거절·철회는 상대 쪽(2번/2번/1번)이 호출한다.
     */
    @Test
    void 전이_표_상태4종_x_동작7종_서비스_경로_전체() {
        Object[][] table = {
                // status, action, actor, expected
                {ExchangeMatchStatus.CHATTING, ExchangeMatchAction.RESERVE, 2L, "CHATTING"},
                {ExchangeMatchStatus.CHATTING, ExchangeMatchAction.RESERVE_ACCEPT, 2L, "409"},          // 제안 없음
                {ExchangeMatchStatus.CHATTING, ExchangeMatchAction.RESERVE_DECLINE, 2L, "CHATTING"},    // 멱등
                {ExchangeMatchStatus.CHATTING, ExchangeMatchAction.RESERVE_WITHDRAW, 2L, "CHATTING"},   // 멱등
                {ExchangeMatchStatus.CHATTING, ExchangeMatchAction.UNRESERVE, 2L, "CHATTING"},          // 멱등
                {ExchangeMatchStatus.CHATTING, ExchangeMatchAction.REJECT, 2L, "CANCELED"},
                {ExchangeMatchStatus.CHATTING, ExchangeMatchAction.CANCEL, 2L, "CANCELED"},
                {ExchangeMatchStatus.RESERVED, ExchangeMatchAction.RESERVE, 2L, "409"},
                {ExchangeMatchStatus.RESERVED, ExchangeMatchAction.RESERVE_ACCEPT, 2L, "RESERVED"},     // 멱등
                {ExchangeMatchStatus.RESERVED, ExchangeMatchAction.RESERVE_DECLINE, 2L, "RESERVED"},    // 제안 없음 멱등
                {ExchangeMatchStatus.RESERVED, ExchangeMatchAction.RESERVE_WITHDRAW, 1L, "RESERVED"},
                {ExchangeMatchStatus.RESERVED, ExchangeMatchAction.UNRESERVE, 2L, "CHATTING"},
                {ExchangeMatchStatus.RESERVED, ExchangeMatchAction.REJECT, 2L, "409"},
                {ExchangeMatchStatus.RESERVED, ExchangeMatchAction.CANCEL, 1L, "409"},
        };
        for (Object[] row : table) {
            check((ExchangeMatchStatus) row[0], (ExchangeMatchAction) row[1], (Long) row[2], (String) row[3]);
        }
        // 닫힌 매칭은 모든 동작이 409 MATCH_STATE_CONFLICT
        for (ExchangeMatchStatus closed : List.of(ExchangeMatchStatus.COMPLETED, ExchangeMatchStatus.CANCELED)) {
            for (ExchangeMatchAction action : List.of(ExchangeMatchAction.RESERVE, ExchangeMatchAction.RESERVE_ACCEPT,
                    ExchangeMatchAction.RESERVE_DECLINE, ExchangeMatchAction.RESERVE_WITHDRAW, ExchangeMatchAction.UNRESERVE,
                    ExchangeMatchAction.REJECT, ExchangeMatchAction.CANCEL)) {
                check(closed, action, action == ExchangeMatchAction.REJECT ? 2L : 1L, "409");
            }
        }
    }

    private void check(ExchangeMatchStatus status, ExchangeMatchAction action, long actor, String expected) {
        ExchangeMatch m = existingMatch(status);
        when(lockRepository.deleteByMatchId(MATCH)).thenReturn(2);
        String label = status + " + " + action;
        if ("409".equals(expected)) {
            assertThatThrownBy(() -> run(action, actor, MATCH)).as(label)
                    .isInstanceOfSatisfying(ConflictException.class, e -> {
                        assertThat(e.getDetails()).containsKey("code");
                        assertThat(e.getMessage()).isNotBlank();
                    });
            assertThat(m.getStatus()).as(label + ": 불허 전이는 상태를 바꾸지 않는다").isEqualTo(status);
        } else {
            assertThat(run(action, actor, MATCH).status()).as(label).isEqualTo(expected);
        }
    }

    @Test
    void 취소된_매칭과_완료된_매칭의_불허_메시지는_한국어로_구분된다() {
        existingMatch(ExchangeMatchStatus.CANCELED);
        assertThatThrownBy(() -> service.reserve(1L, MATCH)).hasMessage("이미 취소된 매칭입니다.");
        assertThatThrownBy(() -> service.reserveAccept(1L, MATCH)).hasMessage("이미 취소된 매칭입니다.");
        existingMatch(ExchangeMatchStatus.COMPLETED);
        assertThatThrownBy(() -> service.cancel(1L, MATCH)).hasMessage("이미 교환이 완료된 매칭입니다.");
        assertThatThrownBy(() -> service.unreserve(1L, MATCH)).hasMessage("이미 교환이 완료된 매칭입니다.");
    }

    // ------------------------------------------------------------------ 교환 수락·교환 완료 (V9)

    private static final long NEW_TICKET_BASE = 1000L;

    /** 서로 다른 좌석(내 A-3-5 / 상대 B-7-9)의 티켓으로 바꾸고 완료 경로의 저장소 mock 을 준비한다. */
    private void prepareCompletion() {
        theirTicket = ticket(THEIR_TICKET, other, openSession);
        ReflectionTestUtils.setField(theirTicket, "zoneLabel", "B");
        ReflectionTestUtils.setField(theirTicket, "zoneKey", "B");
        ReflectionTestUtils.setField(theirTicket, "rowLabel", "7");
        ReflectionTestUtils.setField(theirTicket, "rowKey", "7");
        ReflectionTestUtils.setField(theirTicket, "colLabel", "9");
        ReflectionTestUtils.setField(theirTicket, "colKey", "9");
        when(ticketRepository.findByIdForUpdate(THEIR_TICKET)).thenReturn(Optional.of(theirTicket));
        when(ticketRepository.findWithSessionById(MY_TICKET)).thenReturn(Optional.of(myTicket));
        when(ticketRepository.findWithSessionById(THEIR_TICKET)).thenReturn(Optional.of(theirTicket));
        when(lockRepository.countByMatchId(MATCH)).thenReturn(2L);
        when(lockRepository.deleteByMatchId(MATCH)).thenReturn(2);
        java.util.concurrent.atomic.AtomicLong ids = new java.util.concurrent.atomic.AtomicLong(NEW_TICKET_BASE);
        when(ticketRepository.saveAndFlush(any(Ticket.class))).thenAnswer(inv -> {
            Ticket t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", ids.incrementAndGet());
            return t;
        });
    }

    @Test
    void 첫_수락은_내_수락_시각만_기록하고_RESERVED를_유지한다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);
        prepareCompletion();

        ExchangeMatchResponse response = service.complete(1L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.RESERVED);
        assertThat(m.getACompletedAt()).isEqualTo(NOW);
        assertThat(m.getBCompletedAt()).isNull();
        assertThat(m.getReservedById()).isEqualTo(1L);
        assertThat(response.myAccepted()).isTrue();
        assertThat(response.counterpartAccepted()).isFalse();
        verify(matchRepository).saveAndFlush(m);
        verify(ticketRepository, never()).flush();
        verify(ticketRepository, never()).saveAndFlush(any(Ticket.class));
        verify(historyRepository, never()).save(any());
        verify(requestRepository, never()).closeByTicketId(anyLong(), any());
        verify(lockRepository, never()).deleteByMatchId(anyLong());
        assertThat(myTicket.getStatus()).isEqualTo(TicketStatus.ACTIVE);
    }

    @Test
    void 내가_이미_수락했으면_멱등_200이고_아무것도_쓰지_않는다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);
        ReflectionTestUtils.setField(m, "aCompletedAt", NOW.minusMinutes(5));
        prepareCompletion();

        ExchangeMatchResponse response = service.complete(1L, MATCH);

        assertThat(response.status()).isEqualTo("RESERVED");
        assertThat(response.myAccepted()).isTrue();
        assertThat(m.getACompletedAt()).as("수락 시각이 덮어써지지 않는다").isEqualTo(NOW.minusMinutes(5));
        verify(matchRepository, never()).saveAndFlush(any());
        verify(ticketRepository, never()).flush();
        verify(historyRepository, never()).save(any());
    }

    @Test
    void 두번째_수락이면_한_트랜잭션에서_티켓_EXCHANGED_flush_새티켓_이력_요청닫기_잠금해제_COMPLETED_순서로_처리한다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);
        ReflectionTestUtils.setField(m, "bCompletedAt", NOW.minusMinutes(5));
        prepareCompletion();

        ExchangeMatchResponse response = service.complete(1L, MATCH);

        // 잠금 순서: 티켓 id↑ -> 요청 id↑ -> 매칭
        InOrder order = inOrder(ticketRepository, requestRepository, matchRepository, historyRepository, lockRepository);
        order.verify(ticketRepository).findByIdForUpdate(THEIR_TICKET);
        order.verify(ticketRepository).findByIdForUpdate(MY_TICKET);
        order.verify(requestRepository).findByIdForUpdate(THEIR_REQ);
        order.verify(requestRepository).findByIdForUpdate(MY_REQ);
        order.verify(matchRepository).findByIdForUpdate(MATCH);
        // 기존 티켓 EXCHANGED 를 flush 한 뒤에야 새 티켓을 INSERT 한다(uk_ticket_active_seat)
        order.verify(ticketRepository).flush();
        order.verify(ticketRepository, org.mockito.Mockito.times(2)).saveAndFlush(any(Ticket.class));
        order.verify(historyRepository, org.mockito.Mockito.times(2)).save(any(ExchangeHistory.class));
        order.verify(historyRepository).flush();
        order.verify(requestRepository).closeByTicketId(eq(MY_TICKET), any());
        order.verify(requestRepository).closeByTicketId(eq(THEIR_TICKET), any());
        order.verify(lockRepository).deleteByMatchId(MATCH);
        order.verify(matchRepository).saveAndFlush(m);

        assertThat(myTicket.getStatus()).isEqualTo(TicketStatus.EXCHANGED);
        assertThat(theirTicket.getStatus()).isEqualTo(TicketStatus.EXCHANGED);
        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.COMPLETED);
        assertThat(m.getReservedById()).isNull();
        assertThat(m.getReservedAt()).isNull();
        assertThat(m.getACompletedAt()).isEqualTo(NOW);
        assertThat(m.getBCompletedAt()).isEqualTo(NOW.minusMinutes(5));
        assertThat(m.getTicketAId()).as("매칭 행은 교환 전 티켓을 계속 가리킨다").isEqualTo(MY_TICKET);

        // 새 티켓: 소유자는 그대로, 좌석은 상대의 기존 티켓 값
        org.mockito.ArgumentCaptor<Ticket> newTickets = org.mockito.ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository, org.mockito.Mockito.times(2)).saveAndFlush(newTickets.capture());
        Ticket mineNew = newTickets.getAllValues().get(0);
        Ticket theirNew = newTickets.getAllValues().get(1);
        assertThat(mineNew.getUser().getId()).isEqualTo(1L);
        assertThat(mineNew.getZoneKey()).isEqualTo("B");
        assertThat(mineNew.getRowLabel()).isEqualTo("7");
        assertThat(mineNew.getColKey()).isEqualTo("9");
        assertThat(mineNew.isActive()).isTrue();
        assertThat(theirNew.getUser().getId()).isEqualTo(2L);
        assertThat(theirNew.getZoneKey()).isEqualTo("A");
        assertThat(theirNew.getRowLabel()).isEqualTo("3");
        assertThat(theirNew.getColKey()).isEqualTo("5");

        // 이력 2행: (기존 자리) -> (바꾼 자리) 스냅샷과 old/new_ticket_id
        org.mockito.ArgumentCaptor<ExchangeHistory> histories = org.mockito.ArgumentCaptor.forClass(ExchangeHistory.class);
        verify(historyRepository, org.mockito.Mockito.times(2)).save(histories.capture());
        ExchangeHistory mine = histories.getAllValues().stream().filter(h -> h.getUserId() == 1L).findFirst().orElseThrow();
        assertThat(mine.getMatchId()).isEqualTo(MATCH);
        assertThat(mine.getOldTicketId()).isEqualTo(MY_TICKET);
        assertThat(mine.getNewTicketId()).isEqualTo(mineNew.getId());
        assertThat(mine.getPerformanceId()).isEqualTo(10L);
        assertThat(mine.getPerformanceTitle()).isEqualTo("공연 10");
        assertThat(mine.getVenueName()).isEqualTo("KSPO DOME");
        assertThat(mine.getOldStartsAt()).isEqualTo(openSession.getStartsAt());
        assertThat(mine.getOldZoneLabel() + mine.getOldRowLabel() + mine.getOldColLabel()).isEqualTo("A35");
        assertThat(mine.getNewZoneLabel() + mine.getNewRowLabel() + mine.getNewColLabel()).isEqualTo("B79");
        ExchangeHistory theirs = histories.getAllValues().stream().filter(h -> h.getUserId() == 2L).findFirst().orElseThrow();
        assertThat(theirs.getOldTicketId()).isEqualTo(THEIR_TICKET);
        assertThat(theirs.getNewTicketId()).isEqualTo(theirNew.getId());
        assertThat(theirs.getOldZoneLabel() + theirs.getOldRowLabel() + theirs.getOldColLabel()).isEqualTo("B79");
        assertThat(theirs.getNewZoneLabel() + theirs.getNewRowLabel() + theirs.getNewColLabel()).isEqualTo("A35");

        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.myAccepted()).isTrue();
        assertThat(response.counterpartAccepted()).isTrue();
        // 다른 CHATTING 매칭은 취소하지 않는다(Q-15)
        verify(matchRepository, never()).cancelChattingByTicketA(anyLong(), any());
        verify(matchRepository, never()).cancelChattingByTicketB(anyLong(), any());
    }

    @Test
    void 상대가_먼저_수락하고_b측이_두번째로_눌러도_완료된다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);
        ReflectionTestUtils.setField(m, "aCompletedAt", NOW.minusMinutes(5));
        prepareCompletion();

        ExchangeMatchResponse response = service.complete(2L, MATCH);

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.COMPLETED);
        assertThat(m.getBCompletedAt()).isEqualTo(NOW);
        assertThat(response.status()).isEqualTo("COMPLETED");
    }

    @Test
    void 예약_전_CHATTING에서는_409이고_예약을_먼저_하라는_문구다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.CHATTING);
        prepareCompletion();

        assertThatThrownBy(() -> service.complete(1L, MATCH)).isInstanceOfSatisfying(ConflictException.class, e -> {
            assertThat(e.getMessage()).isEqualTo("예약한 뒤에 교환 수락할 수 있어요.");
            assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT")
                    .containsEntry("status", "CHATTING").containsEntry("action", "COMPLETE");
        });
        assertThat(m.getACompletedAt()).isNull();
        verify(matchRepository, never()).saveAndFlush(any());
    }

    @Test
    void CANCELED_COMPLETED_매칭의_교환_수락은_409이고_비참여자는_404다() {
        existingMatch(ExchangeMatchStatus.CANCELED);
        assertThatThrownBy(() -> service.complete(1L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT").containsEntry("status", "CANCELED"));
        existingMatch(ExchangeMatchStatus.COMPLETED);
        assertThatThrownBy(() -> service.complete(2L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "MATCH_STATE_CONFLICT").containsEntry("status", "COMPLETED"));
        existingMatch(ExchangeMatchStatus.RESERVED);
        assertThatThrownBy(() -> service.complete(99L, MATCH)).isInstanceOf(NotFoundException.class);
        when(matchRepository.findById(404L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.complete(1L, 404L)).isInstanceOf(NotFoundException.class);
        verify(matchRepository, never()).saveAndFlush(any());
        verify(ticketRepository, never()).flush();
    }

    @Test
    void 불변식이_깨진_RESERVED는_IllegalStateException으로_되돌리고_아무것도_쓰지_않는다() {
        ExchangeMatch m = existingMatch(ExchangeMatchStatus.RESERVED);
        ReflectionTestUtils.setField(m, "bCompletedAt", NOW.minusMinutes(5));
        prepareCompletion();

        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.INACTIVE);
        assertThatThrownBy(() -> service.complete(1L, MATCH)).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.ACTIVE);

        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.CLOSED);
        assertThatThrownBy(() -> service.complete(1L, MATCH)).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(theirRequest, "status", ExchangeRequestStatus.OPEN);

        when(lockRepository.countByMatchId(MATCH)).thenReturn(1L);
        assertThatThrownBy(() -> service.complete(1L, MATCH)).isInstanceOf(IllegalStateException.class);

        verify(matchRepository, never()).saveAndFlush(any());
        verify(ticketRepository, never()).flush();
        assertThat(m.getACompletedAt()).isNull();
    }

    @Test
    void 교환_완료로_EXCHANGED가_된_티켓이_걸린_매칭은_제안_수락이_TICKET_EXCHANGED_409다() {
        ExchangeMatch m = proposedBy(1L);
        ReflectionTestUtils.setField(theirTicket, "status", TicketStatus.EXCHANGED);

        assertThatThrownBy(() -> service.reserveAccept(2L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_EXCHANGED"));
        ReflectionTestUtils.setField(theirTicket, "status", TicketStatus.ACTIVE);
        ReflectionTestUtils.setField(myTicket, "status", TicketStatus.EXCHANGED);
        assertThatThrownBy(() -> service.reserveAccept(2L, MATCH)).isInstanceOfSatisfying(ConflictException.class,
                e -> assertThat(e.getDetails()).containsEntry("code", "TICKET_EXCHANGED"));

        assertThat(m.getStatus()).isEqualTo(ExchangeMatchStatus.CHATTING);
        verify(lockRepository, never()).insert(anyLong(), anyLong(), any());
        // 거절·채팅 종료는 그대로 가능하다
        assertThat(service.cancel(1L, MATCH).status()).isEqualTo("CANCELED");
    }
}
