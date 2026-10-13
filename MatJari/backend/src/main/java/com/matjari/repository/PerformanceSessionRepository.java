package com.matjari.repository;

import com.matjari.domain.PerformanceSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PerformanceSessionRepository extends JpaRepository<PerformanceSession, Long> {

    List<PerformanceSession> findByPerformance_IdOrderByStartsAtAsc(Long performanceId);

    @Query("select s from PerformanceSession s join fetch s.performance where s.id = :id")
    Optional<PerformanceSession> findWithPerformanceById(@Param("id") Long id);

    /** 목록 화면용 공연별 회차 수와 다음(now 이후) 회차 시각. */
    @Query("""
            select s.performance.id as performanceId,
                   count(s) as sessionCount,
                   min(case when s.startsAt >= :now then s.startsAt end) as nextStartsAt
            from PerformanceSession s
            where s.performance.id in :performanceIds
            group by s.performance.id
            """)
    List<SessionStats> findStats(@Param("performanceIds") Collection<Long> performanceIds,
                                 @Param("now") LocalDateTime now);

    /** 티켓·교환 희망 회차·좌석 인증 요청이 걸려 있어 삭제할 수 없는 회차 id(FK 때문에 행이 남아 있으면 지울 수 없다). */
    @Query("""
            select s.id from PerformanceSession s
            where s.id in :ids
              and (exists (select 1 from Ticket t where t.performanceSession = s)
                or exists (select 1 from ExchangeWantSession w where w.id.sessionId = s.id)
                or exists (select 1 from SeatClaim c where c.performanceSession = s))
            """)
    List<Long> findInUseIds(@Param("ids") Collection<Long> ids);

    interface SessionStats {
        Long getPerformanceId();

        Long getSessionCount();

        LocalDateTime getNextStartsAt();
    }
}
