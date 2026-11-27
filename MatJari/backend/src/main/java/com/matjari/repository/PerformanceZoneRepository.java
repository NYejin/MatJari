package com.matjari.repository;

import com.matjari.domain.PerformanceZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 구역 저장소. V34 부터 구역은 지도별로 갈린다: source_id NULL = 대표 지도, 값 = 추가 링크의 지도.
 * 기존 이름의 조회·집계 메서드는 모두 **대표 지도만** 뜻한다(추가 지도는 아래 SourceId 메서드로 따로 다룬다).
 */
public interface PerformanceZoneRepository extends JpaRepository<PerformanceZone, Long> {

    /** 공연의 대표 지도 구역(드롭다운 순서). idx_performance_zone_performance 로 조회한다. */
    @Query("""
            select z from PerformanceZone z
            where z.performance.id = :performanceId and z.sourceId is null
            order by z.sortOrder asc, z.id asc
            """)
    List<PerformanceZone> findByPerformanceIdOrderBySortOrderAscIdAsc(@Param("performanceId") Long performanceId);

    /** 공연의 대표 지도 구역 수. */
    @Query("select count(z) from PerformanceZone z where z.performance.id = :performanceId and z.sourceId is null")
    long countByPerformanceId(@Param("performanceId") Long performanceId);

    /** 목록 화면용 일괄 개수(N+1 방지, 대표 지도만). 구역이 없는 공연은 행이 없다. */
    @Query("""
            select z.performance.id as performanceId, count(z) as zoneCount
            from PerformanceZone z
            where z.performance.id in :performanceIds and z.sourceId is null
            group by z.performance.id
            """)
    List<ZoneCount> countByPerformanceIds(@Param("performanceIds") Collection<Long> performanceIds);

    /** 추가 링크 지도들의 구역(링크 id, 표시 순서). */
    @Query("""
            select z from PerformanceZone z
            where z.sourceId in :sourceIds
            order by z.sourceId asc, z.sortOrder asc, z.id asc
            """)
    List<PerformanceZone> findBySourceIds(@Param("sourceIds") Collection<Long> sourceIds);

    /** 추가 링크별 구역 수. 구역이 없는 링크는 행이 없다. */
    @Query("""
            select z.sourceId as sourceId, count(z) as zoneCount
            from PerformanceZone z
            where z.sourceId in :sourceIds
            group by z.sourceId
            """)
    List<SourceZoneCount> countBySourceIds(@Param("sourceIds") Collection<Long> sourceIds);

    /** 한 추가 링크 지도의 구역을 모두 지운다(호출 전에 자동 flush. 같은 트랜잭션에서 그 구역 엔티티를 미리 읽지 않는다). */
    @Modifying(flushAutomatically = true)
    @Query("delete from PerformanceZone z where z.sourceId = :sourceId")
    int deleteBySourceId(@Param("sourceId") Long sourceId);

    interface ZoneCount {
        Long getPerformanceId();

        Long getZoneCount();
    }

    interface SourceZoneCount {
        Long getSourceId();

        Long getZoneCount();
    }
}
