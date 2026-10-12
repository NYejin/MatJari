package com.seatswap.repository;

import com.seatswap.domain.ExchangeMatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 잠금 순서 규약: 티켓(id 오름차순) -> 요청(id 오름차순) -> 매칭. 매칭 행 잠금(findByIdForUpdate)은 항상 마지막이다.
 * 이 인터페이스의 벌크 UPDATE/DELETE 는 호출하는 쪽이 이미 티켓·요청 행을 잠근 트랜잭션 안에서만 쓴다.
 */
public interface ExchangeMatchRepository extends JpaRepository<ExchangeMatch, Long> {

    /** 매칭 행 잠금(SELECT ... FOR UPDATE). 티켓·요청 잠금을 모두 잡은 뒤 호출한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ExchangeMatch m where m.id = :id")
    Optional<ExchangeMatch> findByIdForUpdate(@Param("id") Long id);

    /** 같은 요청 쌍(방향 무관)의 열린 매칭(CHATTING·RESERVED). uk_exchange_match_open_pair 때문에 최대 1건이다. */
    @Query("""
            select m from ExchangeMatch m
            where ((m.requestAId = :a and m.requestBId = :b) or (m.requestAId = :b and m.requestBId = :a))
              and m.status in (com.seatswap.domain.ExchangeMatchStatus.CHATTING,
                               com.seatswap.domain.ExchangeMatchStatus.RESERVED)
            """)
    List<ExchangeMatch> findOpenByPair(@Param("a") Long requestA, @Param("b") Long requestB);

    /**
     * 이 요청이 참여한 열린 매칭(CHATTING·RESERVED)의 id 를 오름차순으로 읽는다(잠금 없음). 요청 수정·삭제가 이 id 들을
     * {@link #findByIdForUpdate} 로 하나씩 오름차순으로 잠근다. OR 조건의 FOR UPDATE 한 방 쿼리는 인덱스 병합/스캔으로
     * 무관한 매칭 행까지 잠글 수 있어 쓰지 않는다. 요청 행 잠금을 쥔 뒤라 이 요청의 새 매칭은 생길 수 없다(제안은 요청 행을 잠근다).
     */
    @Query("""
            select m.id from ExchangeMatch m
            where (m.requestAId = :requestId or m.requestBId = :requestId)
              and m.status in (com.seatswap.domain.ExchangeMatchStatus.CHATTING,
                               com.seatswap.domain.ExchangeMatchStatus.RESERVED)
            order by m.id asc
            """)
    List<Long> findOpenIdsByRequestId(@Param("requestId") Long requestId);

    /** 두 사용자 사이(방향 무관)의 열린 매칭 id 를 오름차순으로 읽는다(잠금 없음). 차단 시스템 취소용. */
    @Query("""
            select m.id from ExchangeMatch m
            where ((m.userAId = :a and m.userBId = :b) or (m.userAId = :b and m.userBId = :a))
              and m.status in (com.seatswap.domain.ExchangeMatchStatus.CHATTING,
                               com.seatswap.domain.ExchangeMatchStatus.RESERVED)
            order by m.id asc
            """)
    List<Long> findOpenIdsBetweenUsers(@Param("a") Long userA, @Param("b") Long userB);

    /** 한 사용자가 참여한 열린 매칭 id 를 오름차순으로 읽는다(잠금 없음). 이용 정지 시스템 취소용. */
    @Query("""
            select m.id from ExchangeMatch m
            where (m.userAId = :userId or m.userBId = :userId)
              and m.status in (com.seatswap.domain.ExchangeMatchStatus.CHATTING,
                               com.seatswap.domain.ExchangeMatchStatus.RESERVED)
            order by m.id asc
            """)
    List<Long> findOpenIdsByUserId(@Param("userId") Long userId);

    /** 매칭 참여자 [userA, userB] (엔티티를 영속성 컨텍스트에 올리지 않는다). */
    @Query("select m.userAId, m.userBId from ExchangeMatch m where m.id = :id")
    List<Object[]> findParticipants(@Param("id") Long id);

    /** 매칭들의 티켓 [ticketA, ticketB] (잠금 없음). */
    @Query("select m.ticketAId, m.ticketBId from ExchangeMatch m where m.id in :ids")
    List<Object[]> findTicketIdsByIds(@Param("ids") java.util.Collection<Long> ids);

    /** 티켓을 내릴 때 그 티켓이 a측인 CHATTING 매칭을 시스템 취소(canceled_by NULL)한다. 티켓 행 잠금 뒤에 호출한다. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ExchangeMatch m
            set m.status = com.seatswap.domain.ExchangeMatchStatus.CANCELED, m.canceledAt = :now, m.updatedAt = :now
            where m.ticketAId = :ticketId and m.status = com.seatswap.domain.ExchangeMatchStatus.CHATTING
            """)
    int cancelChattingByTicketA(@Param("ticketId") Long ticketId, @Param("now") LocalDateTime now);

    /** 티켓을 내릴 때 그 티켓이 b측인 CHATTING 매칭을 시스템 취소한다. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ExchangeMatch m
            set m.status = com.seatswap.domain.ExchangeMatchStatus.CANCELED, m.canceledAt = :now, m.updatedAt = :now
            where m.ticketBId = :ticketId and m.status = com.seatswap.domain.ExchangeMatchStatus.CHATTING
            """)
    int cancelChattingByTicketB(@Param("ticketId") Long ticketId, @Param("now") LocalDateTime now);
}
