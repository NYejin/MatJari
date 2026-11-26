package com.matjari.repository;

import com.matjari.domain.PerformanceSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PerformanceSourceRepository extends JpaRepository<PerformanceSource, Long> {

    Optional<PerformanceSource> findBySourceKey(String sourceKey);

    /** 한 공연의 링크(추가 순). 표시 순서(대표 먼저)는 서비스가 정한다. */
    List<PerformanceSource> findByPerformanceIdOrderByIdAsc(Long performanceId);

    List<PerformanceSource> findByPerformanceIdIn(Collection<Long> performanceIds);

    long countByPerformanceId(Long performanceId);
}
