package com.seatswap.repository;

import com.seatswap.domain.AdminReviewStatus;
import com.seatswap.domain.SeatClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SeatClaimRepository extends JpaRepository<SeatClaim, Long> {

    /** 처리(승인·거절)용 행 잠금. 트랜잭션의 첫 쿼리로 호출한다(REPEATABLE READ 스냅샷). 잠금 순서의 맨 앞이다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SeatClaim c where c.id = :id")
    Optional<SeatClaim> findByIdForUpdate(@Param("id") Long id);

    /** 잠금 없이 회차·공연까지 읽는다. */
    @Query("""
            select c from SeatClaim c
              join fetch c.performanceSession s
              join fetch s.performance
            where c.id = :id
            """)
    Optional<SeatClaim> findWithSessionById(@Param("id") Long id);

    @Query(value = """
            select c from SeatClaim c
              join fetch c.performanceSession s
              join fetch s.performance
            where c.claimantId = :userId
            order by c.id desc
            """, countQuery = "select count(c) from SeatClaim c where c.claimantId = :userId")
    Page<SeatClaim> findPageByClaimant(@Param("userId") Long userId, Pageable pageable);

    @Query(value = """
            select c from SeatClaim c
              join fetch c.performanceSession s
              join fetch s.performance
            order by c.id desc
            """, countQuery = "select count(c) from SeatClaim c")
    Page<SeatClaim> findPageAll(Pageable pageable);

    @Query(value = """
            select c from SeatClaim c
              join fetch c.performanceSession s
              join fetch s.performance
            where c.status = :status
            order by c.id desc
            """, countQuery = "select count(c) from SeatClaim c where c.status = :status")
    Page<SeatClaim> findPageByStatus(@Param("status") AdminReviewStatus status, Pageable pageable);

    long countByClaimantIdAndStatus(Long claimantId, AdminReviewStatus status);

    /** 같은 사용자의 같은 좌석 PENDING 요청 (uk_seat_claim_pending_seat 와 같은 키). */
    @Query("""
            select count(c) > 0 from SeatClaim c
            where c.claimantId = :userId and c.status = com.seatswap.domain.AdminReviewStatus.PENDING
              and c.performanceSession.id = :sessionId
              and c.zoneKey = :zoneKey and c.rowKey = :rowKey and c.colKey = :colKey
            """)
    boolean existsPendingBySeat(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                                @Param("zoneKey") String zoneKey, @Param("rowKey") String rowKey,
                                @Param("colKey") String colKey);
}
