package com.seatswap.repository;

import com.seatswap.domain.AdminReviewStatus;
import com.seatswap.domain.PerformanceEditProposal;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** 공연 수정 제안. 잠금 순서: 제안 행 -> 공연 행. 제안 행 잠금은 트랜잭션의 첫 쿼리여야 한다(REPEATABLE READ 스냅샷). */
public interface PerformanceEditProposalRepository extends JpaRepository<PerformanceEditProposal, Long> {

    /** 제안 행 잠금(SELECT ... FOR UPDATE). 공연은 조인하지 않는다(공연 행은 그 다음에 따로 잠근다). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PerformanceEditProposal p where p.id = :id")
    Optional<PerformanceEditProposal> findByIdForUpdate(@Param("id") Long id);

    @Query("select count(p) from PerformanceEditProposal p where p.proposerId = :proposerId "
            + "and p.status = com.seatswap.domain.AdminReviewStatus.PENDING")
    long countPendingByProposerId(@Param("proposerId") Long proposerId);

    @Query("select count(p) > 0 from PerformanceEditProposal p where p.proposerId = :proposerId "
            + "and p.performance.id = :performanceId and p.status = com.seatswap.domain.AdminReviewStatus.PENDING")
    boolean existsPending(@Param("proposerId") Long proposerId, @Param("performanceId") Long performanceId);

    @Query(value = "select p from PerformanceEditProposal p join fetch p.performance "
            + "where p.proposerId = :proposerId order by p.id desc",
            countQuery = "select count(p) from PerformanceEditProposal p where p.proposerId = :proposerId")
    Page<PerformanceEditProposal> findMine(@Param("proposerId") Long proposerId, Pageable pageable);

    @Query(value = "select p from PerformanceEditProposal p join fetch p.performance "
            + "where (:status is null or p.status = :status) order by p.id desc",
            countQuery = "select count(p) from PerformanceEditProposal p where (:status is null or p.status = :status)")
    Page<PerformanceEditProposal> findForAdmin(@Param("status") AdminReviewStatus status, Pageable pageable);
}
