package com.matjari.repository;

import com.matjari.domain.ExchangeWantSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ExchangeWantSessionRepository extends JpaRepository<ExchangeWantSession, ExchangeWantSession.Key> {

    /** 희망 회차 + 회차 시각 (우선순위 오름차순 -> 시각 오름차순). */
    @Query("""
            select new com.matjari.repository.ExchangeWantSessionRepository$Row(
                w.id.requestId, w.id.sessionId, w.priority, s.startsAt)
            from ExchangeWantSession w, PerformanceSession s
            where s.id = w.id.sessionId and w.id.requestId in :requestIds
            order by w.id.requestId asc, w.priority asc, s.startsAt asc, s.id asc
            """)
    List<Row> findRows(@Param("requestIds") Collection<Long> requestIds);

    @Modifying(flushAutomatically = true)
    @Query("delete from ExchangeWantSession w where w.id.requestId = :requestId")
    int deleteByRequestId(@Param("requestId") Long requestId);

    record Row(Long requestId, Long sessionId, Short priority, LocalDateTime startsAt) {
    }
}
