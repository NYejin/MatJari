package com.seatswap.repository;

import com.seatswap.domain.Performance;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    @Query("select p.id from Performance p where p.sourceKey = :sourceKey")
    Optional<Long> findIdBySourceKey(@Param("sourceKey") String sourceKey);

    /** 공연 행 잠금(SELECT ... FOR UPDATE). 수정 제안 승인에서만 쓴다(제안 행 잠금 다음에 호출). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Performance p where p.id = :id")
    Optional<Performance> findByIdForUpdate(@Param("id") Long id);

    /** 상세 조회용 — 등록자 fetch join (N+1 방지). */
    @Query("""
            select p from Performance p
            join fetch p.registrant
            where p.id = :id
            """)
    Optional<Performance> findDetailById(@Param("id") Long id);

    /**
     * 목록 검색. 정렬: 다가오는 회차(now 이후 가장 이른 starts_at)가 있는 공연 먼저 그 시각 오름차순,
     * 다가오는 회차가 없는 공연은 뒤로(최근 등록 순).
     * titlePattern은 '!'로 이스케이프한 "%...%" (검색어 없으면 "%"). 대소문자 무시는 컬럼 collation(ci)에 맡긴다.
     * 다음 회차 서브쿼리는 uk_performance_session_performance_starts_at (performance_id, starts_at) 인덱스로 처리된다.
     */
    @Query(value = """
            select p from Performance p
            where p.title like :titlePattern escape '!'
            order by
              case when (select min(s1.startsAt) from PerformanceSession s1
                         where s1.performance = p and s1.startsAt >= :now) is null then 1 else 0 end,
              (select min(s2.startsAt) from PerformanceSession s2
               where s2.performance = p and s2.startsAt >= :now) asc,
              p.createdAt desc,
              p.id desc
            """,
            countQuery = """
            select count(p) from Performance p
            where p.title like :titlePattern escape '!'
            """)
    Page<Performance> search(@Param("titlePattern") String titlePattern,
                             @Param("now") LocalDateTime now,
                             Pageable pageable);
}
