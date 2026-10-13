package com.matjari.repository;

import com.matjari.domain.Ticket;
import com.matjari.domain.TicketStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /** 티켓 보유자 id (프록시 초기화 없이, 잠금 없음). */
    @Query("select t.user.id from Ticket t where t.id = :id")
    Optional<Long> findOwnerIdById(@Param("id") Long id);

    long countByUser_IdAndStatus(Long userId, TicketStatus status);

    /** 같은 회차·구역·열·번의 활성 티켓 (uk_ticket_active_seat 와 같은 키). */
    @Query("""
            select t from Ticket t
            where t.performanceSession.id = :sessionId
              and t.zoneKey = :zoneKey and t.rowKey = :rowKey and t.colKey = :colKey
              and t.status = com.matjari.domain.TicketStatus.ACTIVE
            """)
    Optional<Ticket> findActiveBySeat(@Param("sessionId") Long sessionId,
                                      @Param("zoneKey") String zoneKey,
                                      @Param("rowKey") String rowKey,
                                      @Param("colKey") String colKey);

    /** 사용자들의 ACTIVE 티켓 id (잠금 없음). 차단·정지 시스템 취소가 이 티켓들을 id 오름차순으로 잠근다. */
    @Query("""
            select t.id from Ticket t
            where t.user.id in :userIds and t.status = com.matjari.domain.TicketStatus.ACTIVE
            """)
    List<Long> findActiveIdsByUserIds(@Param("userIds") java.util.Collection<Long> userIds);

    /** 내 활성 티켓: 회차 시각 오름차순. 응답 DTO에 필요한 회차·공연을 함께 읽는다. */
    @Query("""
            select t from Ticket t
              join fetch t.performanceSession s
              join fetch s.performance
            where t.user.id = :userId and t.status = com.matjari.domain.TicketStatus.ACTIVE
            order by s.startsAt asc, t.id asc
            """)
    List<Ticket> findActiveByUser(@Param("userId") Long userId);

    /**
     * 티켓 행 잠금(SELECT ... FOR UPDATE). 티켓 내리기·교환 요청 등록을 직렬화한다.
     * 회차·공연은 조인하지 않는다(조인하면 같은 회차의 모든 티켓이 회차 행 잠금으로 직렬화된다).
     * 트랜잭션의 첫 쿼리로 호출해야 한다(MySQL REPEATABLE READ 스냅샷 때문).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(@Param("id") Long id);

    /** 잠금 없이 회차·공연까지 읽는다(교환 요청 검증용). */
    @Query("""
            select t from Ticket t
              join fetch t.performanceSession s
              join fetch s.performance
            where t.id = :id
            """)
    Optional<Ticket> findWithSessionById(@Param("id") Long id);

    /**
     * 좌석의 활성 티켓 id 만 잠금 없이 읽는다(엔티티를 영속성 컨텍스트에 올리지 않는다). 관리자 좌석 인증 승인용:
     * 이어서 PK 로 FOR UPDATE(findByIdForUpdate)해야 최신 상태를 본다.
     */
    @Query("""
            select t.id from Ticket t
            where t.performanceSession.id = :sessionId
              and t.zoneKey = :zoneKey and t.rowKey = :rowKey and t.colKey = :colKey
              and t.status = com.matjari.domain.TicketStatus.ACTIVE
            """)
    Optional<Long> findActiveIdBySeat(@Param("sessionId") Long sessionId,
                                      @Param("zoneKey") String zoneKey,
                                      @Param("rowKey") String rowKey,
                                      @Param("colKey") String colKey);

    /** 좌석을 가진 가장 최근 티켓(상태 무관, 잠금 없음) - 관리자 목록의 holder 요약용. Pageable 로 1건만 읽는다. */
    @Query("""
            select t from Ticket t join fetch t.user
            where t.performanceSession.id = :sessionId
              and t.zoneKey = :zoneKey and t.rowKey = :rowKey and t.colKey = :colKey
            order by t.id desc
            """)
    List<Ticket> findLatestBySeat(@Param("sessionId") Long sessionId,
                                  @Param("zoneKey") String zoneKey,
                                  @Param("rowKey") String rowKey,
                                  @Param("colKey") String colKey,
                                  org.springframework.data.domain.Pageable pageable);
}
