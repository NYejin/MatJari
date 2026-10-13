package com.matjari.repository;

import com.matjari.domain.ExchangeRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 잠금 순서 규칙: 항상 티켓 -> 요청이다(TicketService.deactivate 가 티켓을 잠근 뒤 closeByTicketId 로 요청을 갱신).
 * 요청 행만 잠그는 경로(findByIdForUpdate 를 쓰는 수정·삭제)에서는 티켓 행을 잠그지 않는다(일반 읽기만).
 * 이 순서를 깨면 티켓 내리기와 교착이 난다. 여러 티켓을 잠글 때는 ticket id 오름차순.
 */
public interface ExchangeRequestRepository extends JpaRepository<ExchangeRequest, Long> {

    /** 티켓에 미삭제(OPEN·CLOSED) 요청이 있는가. DB 백스톱은 uk_exchange_request_live_ticket. */
    @Query("select count(r) > 0 from ExchangeRequest r where r.ticket.id = :ticketId "
            + "and r.status <> com.matjari.domain.ExchangeRequestStatus.DELETED")
    boolean existsLiveByTicketId(@Param("ticketId") Long ticketId);

    /** 요청이 가리키는 티켓 id (프록시 초기화 없이). 요청의 티켓은 바뀌지 않아(updatable=false) 잠금 대상을 미리 알 수 있다. */
    @Query("select r.ticket.id from ExchangeRequest r where r.id = :id")
    Optional<Long> findTicketIdById(@Param("id") Long id);

    /**
     * 요청 행 잠금(SELECT ... FOR UPDATE). 수정·삭제를 직렬화한다. 티켓은 조인하지 않는다
     * (조인하면 티켓 행까지 잠가 TicketService.deactivate 와 잠금 순서가 엇갈린다).
     * 트랜잭션의 첫 쿼리로 호출해야 한다(MySQL REPEATABLE READ 스냅샷 때문).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ExchangeRequest r where r.id = :id")
    Optional<ExchangeRequest> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select r from ExchangeRequest r join fetch r.ticket t
            where t.user.id = :userId and r.status <> com.matjari.domain.ExchangeRequestStatus.DELETED
            order by r.id asc
            """)
    List<ExchangeRequest> findByOwner(@Param("userId") Long userId);

    @Query("""
            select r from ExchangeRequest r join fetch r.ticket t
            where t.user.id = :userId and t.id = :ticketId
              and r.status <> com.matjari.domain.ExchangeRequestStatus.DELETED
            order by r.id asc
            """)
    List<ExchangeRequest> findByOwnerAndTicket(@Param("userId") Long userId, @Param("ticketId") Long ticketId);

    /**
     * 티켓을 내릴 때 그 티켓의 요청을 CLOSED 로 바꾼다(요청 행 잠금은 UPDATE 가 잡는다).
     * status = OPEN 조건을 반드시 유지한다: 없으면 DELETED 요청을 CLOSED 로 되살려 live_flag 가 1 이 되고 유일 키를 위반할 수 있다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update ExchangeRequest r
            set r.status = com.matjari.domain.ExchangeRequestStatus.CLOSED, r.updatedAt = :now
            where r.ticket.id = :ticketId and r.status = com.matjari.domain.ExchangeRequestStatus.OPEN
            """)
    int closeByTicketId(@Param("ticketId") Long ticketId, @Param("now") LocalDateTime now);
}
