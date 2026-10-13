package com.matjari.repository;

import com.matjari.domain.AbuseReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 신고 저장소. 신고 행 잠금(처리 경로의 첫 쿼리)과 관리자 목록·대상별 누적 집계. */
public interface AbuseReportRepository extends JpaRepository<AbuseReport, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AbuseReport r where r.id = :id")
    Optional<AbuseReport> findByIdForUpdate(@Param("id") Long id);

    /** 엔티티를 영속성 컨텍스트에 올리지 않고 대상 id 만 읽는다(이어지는 잠금 읽기가 낡은 값을 돌려받지 않도록). */
    @Query("select r.reportedId from AbuseReport r where r.id = :id")
    Optional<Long> findReportedIdById(@Param("id") Long id);

    /** 같은 대상의 모든 신고 행을 id 오름차순으로 잠근다(제재를 함께 거는 처리가 대상 단위로 직렬화되도록). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AbuseReport r where r.reportedId = :reportedId order by r.id asc")
    List<AbuseReport> lockAllByReportedId(@Param("reportedId") Long reportedId);

    long countByReporterIdAndStatus(Long reporterId, AbuseReport.Status status);

    boolean existsByReporterIdAndMatchIdAndStatus(Long reporterId, Long matchId, AbuseReport.Status status);

    Page<AbuseReport> findByReporterIdOrderByIdDesc(Long reporterId, Pageable pageable);

    /** 관리자 목록. status·reason 은 각각 null 이면 필터 없음. */
    @Query(value = "select r from AbuseReport r where (:status is null or r.status = :status) and (:reason is null or r.reason = :reason) order by r.id desc",
            countQuery = "select count(r) from AbuseReport r where (:status is null or r.status = :status) and (:reason is null or r.reason = :reason)")
    Page<AbuseReport> search(@Param("status") AbuseReport.Status status, @Param("reason") AbuseReport.Reason reason, Pageable pageable);

    /** 대상별 [reportedId, 전체 건수, OPEN 건수]. */
    @Query("select r.reportedId, count(r), sum(case when r.status = :open then 1 else 0 end) from AbuseReport r "
            + "where r.reportedId in :ids group by r.reportedId")
    List<Object[]> countByReported(@Param("ids") Collection<Long> reportedIds, @Param("open") AbuseReport.Status open);
}
