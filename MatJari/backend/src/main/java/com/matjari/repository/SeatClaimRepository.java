package com.matjari.repository;

import com.matjari.domain.AdminReviewStatus;
import com.matjari.domain.SeatClaim;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

    /** 잠금 없는 SELECT: 사용자의 PENDING 인증 요청 id (오름차순). 이어서 PK 로 잠그고 상태를 다시 확인한다. */
    @Query("select c.id from SeatClaim c where c.claimantId = :userId "
            + "and c.status = com.matjari.domain.AdminReviewStatus.PENDING order by c.id")
    java.util.List<Long> findPendingIdsByClaimant(@Param("userId") Long userId);

    /**
     * 처리(승인·거절)가 끝난 지 {@code cutoff} 보다 오래됐는데 사진 삭제가 기록되지 않은 요청 id(오름차순). 커밋 뒤 삭제가 실패했거나
     * 서버가 그 사이 멈춘 경우다. 정리 작업이 쓴다(잠금 없는 SELECT).
     */
    @Query("select c.id from SeatClaim c where c.status <> com.matjari.domain.AdminReviewStatus.PENDING "
            + "and c.imageDeletedAt is null and c.reviewedAt < :cutoff order by c.id")
    List<Long> findProcessedIdsWithImage(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);

    /** 주어진 키 중 요청 행이 가리키고 있는 것. 디스크에는 있는데 여기에 없는 키가 고아 파일이다. */
    @Query("select c.imageKey from SeatClaim c where c.imageKey in :keys")
    List<String> findExistingImageKeys(@Param("keys") Collection<String> keys);

    long countByClaimantIdAndStatus(Long claimantId, AdminReviewStatus status);

    /** 같은 사용자의 같은 좌석 PENDING 요청 (uk_seat_claim_pending_seat 와 같은 키). */
    @Query("""
            select count(c) > 0 from SeatClaim c
            where c.claimantId = :userId and c.status = com.matjari.domain.AdminReviewStatus.PENDING
              and c.performanceSession.id = :sessionId
              and c.zoneKey = :zoneKey and c.rowKey = :rowKey and c.colKey = :colKey
            """)
    boolean existsPendingBySeat(@Param("userId") Long userId, @Param("sessionId") Long sessionId,
                                @Param("zoneKey") String zoneKey, @Param("rowKey") String rowKey,
                                @Param("colKey") String colKey);
}
