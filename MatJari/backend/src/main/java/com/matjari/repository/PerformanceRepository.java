package com.matjari.repository;

import com.matjari.domain.Performance;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    @Query("select p.id from Performance p where p.sourceKey = :sourceKey")
    Optional<Long> findIdBySourceKey(@Param("sourceKey") String sourceKey);

    /**
     * 같은 공연 후보 찾기용(숨기지 않은 공연). 회차 범위(첫 회차 ~ 마지막 회차)가 [start, endExclusive) 와 겹치는 공연, 최신 등록순.
     * 공연장·제목 비교는 정규화가 필요해 서비스가 자바에서 한다. 개수는 Pageable 로 막는다.
     */
    @Query("""
            select p from Performance p
            where p.hiddenAt is null
              and p.id in (select s.performance.id from PerformanceSession s group by s.performance.id
                           having min(s.startsAt) < :endExclusive and max(s.startsAt) >= :start)
            order by p.id desc
            """)
    List<Performance> findVisibleWithSessionsOverlapping(@Param("start") LocalDateTime start,
                                                         @Param("endExclusive") LocalDateTime endExclusive,
                                                         Pageable pageable);

    /** 공연 행 잠금(SELECT ... FOR UPDATE). 수정 제안 승인에서만 쓴다(제안 행 잠금 다음에 호출). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Performance p where p.id = :id")
    Optional<Performance> findByIdForUpdate(@Param("id") Long id);

    /**
     * 관리자 목록: 숨긴 공연 포함, 제목 부분 일치, 최신 등록순. mode = ALL / VISIBLE / HIDDEN.
     */
    @Query(value = """
            select p from Performance p
            join fetch p.registrant
            where p.title like :titlePattern escape '!'
              and (:mode = 'ALL' or (:mode = 'VISIBLE' and p.hiddenAt is null)
                   or (:mode = 'HIDDEN' and p.hiddenAt is not null))
            order by p.createdAt desc, p.id desc
            """,
            countQuery = """
            select count(p) from Performance p
            where p.title like :titlePattern escape '!'
              and (:mode = 'ALL' or (:mode = 'VISIBLE' and p.hiddenAt is null)
                   or (:mode = 'HIDDEN' and p.hiddenAt is not null))
            """)
    Page<Performance> findForAdmin(@Param("titlePattern") String titlePattern, @Param("mode") String mode,
                                   Pageable pageable);

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
     * 끝난 공연(openFrom = 오늘(KST) 0시 이후 회차가 하나도 없는 공연, 즉 모든 회차가 마감)은 목록·검색에서 뺀다(사용자 지시 2026-11-25).
     * 기준은 회차 마감 규칙(회차 당일 끝 = 다음날 0시)과 같다. 링크 lookup·상세는 끝난 공연도 그대로 보인다.
     * titlePattern은 '!'로 이스케이프한 "%...%" (검색어 없으면 "%"). 대소문자 무시는 컬럼 collation(ci)에 맡긴다.
     * 다음 회차 서브쿼리는 uk_performance_session_performance_starts_at (performance_id, starts_at) 인덱스로 처리된다.
     */
    @Query(value = """
            select p from Performance p
            where p.title like :titlePattern escape '!' and p.hiddenAt is null
              and exists (select 1 from PerformanceSession s0 where s0.performance = p and s0.startsAt >= :openFrom)
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
            where p.title like :titlePattern escape '!' and p.hiddenAt is null
              and exists (select 1 from PerformanceSession s0 where s0.performance = p and s0.startsAt >= :openFrom)
            """)
    Page<Performance> search(@Param("titlePattern") String titlePattern,
                             @Param("now") LocalDateTime now,
                             @Param("openFrom") LocalDateTime openFrom,
                             Pageable pageable);
}
