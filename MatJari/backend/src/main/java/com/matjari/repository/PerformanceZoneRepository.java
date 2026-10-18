package com.matjari.repository;

import com.matjari.domain.PerformanceZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface PerformanceZoneRepository extends JpaRepository<PerformanceZone, Long> {

    /** 공연별 목록(드롭다운 순서). idx_performance_zone_performance 로 조회한다. */
    List<PerformanceZone> findByPerformanceIdOrderBySortOrderAscIdAsc(Long performanceId);

    long countByPerformanceId(Long performanceId);

    /** 목록 화면용 일괄 개수(N+1 방지). 구역이 없는 공연은 행이 없다. */
    @Query("""
            select z.performance.id as performanceId, count(z) as zoneCount
            from PerformanceZone z
            where z.performance.id in :performanceIds
            group by z.performance.id
            """)
    List<ZoneCount> countByPerformanceIds(@Param("performanceIds") Collection<Long> performanceIds);

    interface ZoneCount {
        Long getPerformanceId();

        Long getZoneCount();
    }
}
