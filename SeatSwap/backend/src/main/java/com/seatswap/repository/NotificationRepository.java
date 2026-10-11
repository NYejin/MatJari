package com.seatswap.repository;

import com.seatswap.domain.Notification;
import com.seatswap.domain.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 알림 저장소. 쓰기 순서 규약: notification 은 항상 트랜잭션의 마지막에 쓴다(티켓 id↑ -> 요청 id↑ -> 매칭 -> users S(FK) -> notification).
 * notification 행을 잠근 뒤 티켓·요청·매칭을 잠그는 경로는 만들지 않는다. 범위 UPDATE/DELETE 는 쓰지 않고 id 목록으로만 갱신한다(갭 잠금 회피).
 * markRead/markAllRead/deleteRead 와 id 수집 쿼리는 후속 작업(조회·읽음·삭제 API)이 쓴다. 이번 범위에서는 선언만 있다.
 */
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** 단건 읽음(본인 것만, 이미 읽었으면 0행). */
    @Modifying
    @Query("update Notification n set n.readAt = :now where n.id = :id and n.userId = :userId and n.readAt is null")
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 전체 읽음: 잠금 없는 SELECT(findUnreadIds)로 모은 id 목록으로만 갱신한다(범위 UPDATE 금지). */
    @Modifying
    @Query("update Notification n set n.readAt = :now where n.id in :ids and n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("ids") Collection<Long> ids, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 읽은 알림 삭제: 본인의 read_at IS NOT NULL 만. 안 읽은 알림은 지우지 않는다. id 목록으로만 호출한다(범위 DELETE 금지). */
    @Modifying
    @Query("delete from Notification n where n.id in :ids and n.userId = :userId and n.readAt is not null")
    int deleteRead(@Param("ids") Collection<Long> ids, @Param("userId") Long userId);

    /** 잠금 없는 SELECT: 전체 읽음 대상 id 수집(type 이 null 이면 전체). */
    @Query("select n.id from Notification n where n.userId = :userId and n.readAt is null "
            + "and (:type is null or n.type = :type)")
    List<Long> findUnreadIds(@Param("userId") Long userId, @Param("type") NotificationType type);

    /** 잠금 없는 SELECT: 읽은 알림 삭제 대상 id 수집. */
    @Query("select n.id from Notification n where n.userId = :userId and n.readAt is not null")
    List<Long> findReadIds(@Param("userId") Long userId);
}
