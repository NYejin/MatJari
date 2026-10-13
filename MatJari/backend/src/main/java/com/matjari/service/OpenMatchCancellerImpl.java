package com.matjari.service;

import com.matjari.domain.ExchangeMatch;
import com.matjari.domain.ExchangeMatchStatus;
import com.matjari.repository.ExchangeMatchRepository;
import com.matjari.repository.ExchangeTicketLockRepository;
import com.matjari.repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * {@link OpenMatchCanceller} 구현. 차단·이용 정지 트랜잭션 안에서 호출된다(MANDATORY).
 *
 * 순서(잠금 순서 규약: 티켓 id↑ -> 요청 id↑ -> 매칭 id↑ 의 부분 순서. 이 경로는 요청 행을 읽지도 쓰지도 않으므로 요청 단계가 없다):
 * <ol>
 *   <li>잠금 없이 대상 매칭 id 와 관련 티켓 id 를 읽는다. 관련 티켓 = 대상 사용자들의 ACTIVE 티켓 전부 + 대상 매칭의 상대 티켓.
 *       새 제안·예약은 반드시 사용자 본인의 ACTIVE 티켓을 잠그므로, 이 티켓들을 모두 잠그면 그 뒤에는 대상 사용자와 새 매칭이 생기지 않는다.</li>
 *   <li>티켓을 id 오름차순으로 FOR UPDATE (propose/reserve/unreserve/complete/티켓 내리기와 같은 순서).</li>
 *   <li>티켓 잠금 뒤 매칭 id 를 다시 읽고(읽는 동안 생긴 매칭 반영; 잠금 전에 몰랐던 상대 티켓이 있으면 마저 잠근다) 매칭을 id 오름차순으로
 *       FOR UPDATE 한 뒤 상태를 재확인한다. 열려 있을 때만 CANCELED(canceled_by_id NULL = 시스템)로 바꾸고 예약 잠금을 지운다.
 *       RESERVED 는 한 문장으로 reserved_*·수락 표시를 비운다(ck_exchange_match_reserved·completed).</li>
 * </ol>
 * 호출자는 가능하면 READ_COMMITTED 트랜잭션에서 부르는 것이 좋다(REPEATABLE READ 에서는 3단계의 재조회가 스냅샷 시점 데이터를 읽을 수 있어
 * 티켓 잠금 사이에 막 커밋된 매칭을 놓칠 수 있다. 매칭 행 자체의 FOR UPDATE 는 항상 최신 행을 읽는다). 알림·채팅 푸시는 보내지 않는다.
 * 요청(exchange_request)·COMPLETED 매칭·교환 이력은 건드리지 않는다.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OpenMatchCancellerImpl implements OpenMatchCanceller {

    private final ExchangeMatchRepository matchRepository;
    private final TicketRepository ticketRepository;
    private final ExchangeTicketLockRepository lockRepository;
    private final SessionTimePolicy timePolicy;

    public OpenMatchCancellerImpl(ExchangeMatchRepository matchRepository, TicketRepository ticketRepository,
                                  ExchangeTicketLockRepository lockRepository, SessionTimePolicy timePolicy) {
        this.matchRepository = matchRepository;
        this.ticketRepository = ticketRepository;
        this.lockRepository = lockRepository;
        this.timePolicy = timePolicy;
    }

    @Override
    public int cancelBetween(long userA, long userB) {
        return cancel(List.of(userA, userB), () -> matchRepository.findOpenIdsBetweenUsers(userA, userB));
    }

    @Override
    public int cancelAllOf(long userId) {
        return cancel(List.of(userId), () -> matchRepository.findOpenIdsByUserId(userId));
    }

    private int cancel(List<Long> userIds, Supplier<List<Long>> openMatchIds) {
        List<Long> firstIds = openMatchIds.get();
        Set<Long> lockedTickets = new TreeSet<>(ticketRepository.findActiveIdsByUserIds(userIds));
        lockedTickets.addAll(ticketIdsOf(firstIds));
        for (Long ticketId : lockedTickets) {
            ticketRepository.findByIdForUpdate(ticketId);   // 없으면 무시(티켓은 삭제되지 않는다)
        }

        List<Long> ids = openMatchIds.get();
        Set<Long> extra = new TreeSet<>(ticketIdsOf(ids));
        extra.removeAll(lockedTickets);
        for (Long ticketId : extra) {   // 드문 경합: 잠금 전에 몰랐던 상대 티켓(순서가 어긋날 수 있어 교착 감지기가 지켜준다)
            ticketRepository.findByIdForUpdate(ticketId);
        }

        LocalDateTime now = timePolicy.now();
        int canceled = 0;
        for (Long matchId : ids) {   // 오름차순
            ExchangeMatch m = matchRepository.findByIdForUpdate(matchId).orElse(null);
            if (m == null || !m.isOpen()) {
                continue;   // 그 사이 닫혔다
            }
            boolean reserved = m.getStatus() == ExchangeMatchStatus.RESERVED;
            if (reserved) {
                m.unreserve();   // reserved_*·a/b_completed_at 초기화 후 같은 UPDATE 에서 CANCELED
            }
            m.cancel(null, now);
            matchRepository.saveAndFlush(m);
            if (reserved) {
                lockRepository.deleteByMatchId(matchId);
            }
            canceled++;
        }
        return canceled;
    }

    private Set<Long> ticketIdsOf(List<Long> matchIds) {
        Set<Long> out = new TreeSet<>();
        if (matchIds.isEmpty()) {
            return out;
        }
        for (Object[] row : matchRepository.findTicketIdsByIds(matchIds)) {
            out.add((Long) row[0]);
            out.add((Long) row[1]);
        }
        return out;
    }
}
