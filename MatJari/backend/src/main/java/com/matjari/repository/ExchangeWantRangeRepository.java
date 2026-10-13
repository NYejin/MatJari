package com.matjari.repository;

import com.matjari.domain.ExchangeWantRange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ExchangeWantRangeRepository extends JpaRepository<ExchangeWantRange, Long> {

    List<ExchangeWantRange> findByRequestIdInOrderByRequestIdAscSortOrderAsc(Collection<Long> requestIds);

    @Modifying(flushAutomatically = true)
    @Query("delete from ExchangeWantRange w where w.requestId = :requestId")
    int deleteByRequestId(@Param("requestId") Long requestId);
}
