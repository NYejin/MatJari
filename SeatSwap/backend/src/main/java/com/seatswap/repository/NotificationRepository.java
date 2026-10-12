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
 * markRead/markAllRead/deleteRead 와 id 수집 쿼리는 NotificationService 가 쓴다(id 수집은 정렬해서 PK 순서로 잠근다).
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
            + "and (:type is null or n.type = :type) order by n.id")
    List<Long> findUnreadIds(@Param("userId") Long userId, @Param("type") NotificationType type);

    /** 잠금 없는 SELECT: 읽은 알림 삭제 대상 id 수집. */
    @Query("select n.id from Notification n where n.userId = :userId and n.readAt is not null order by n.id")
    List<Long> findReadIds(@Param("userId") Long userId);

    /** 단건 읽음에서 '이미 읽음(멱등 204)'과 '없음/남의 것(404)'을 가르는 잠금 없는 점조회. */
    boolean existsByIdAndUserId(Long id, Long userId);

    /**
     * 채팅 알림 되살리기: 이미 있는 (수신자, 매칭) 채팅 알림 행을 PK 로 갱신한다(read_at NULL, created_at 갱신). 읽은 알림 삭제(deleteRead)가
     * PK 행을 먼저 잡고 모든 보조 인덱스(uk_notification_dedup 포함)를 잠그므로, 같은 행을 dedup 유니크 인덱스부터 잠그는 INSERT ... ON DUPLICATE KEY UPDATE 는
     * 삭제와 교착한다(실제 MySQL 테스트로 확인). PK 로 먼저 갱신하면 잠금 순서가 같아 교착이 없다.
     */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = "UPDATE notification SET read_at = NULL, created_at = :now WHERE id = :id")
    int reviveChatMessage(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 채팅 알림 upsert (V15): (수신자, 매칭)당 행 1개(dedup_key = CHAT_MESSAGE:{matchId}:{recipientId}, ck_notification_chat_dedup)를
     * 만들거나 되살린다. 행이 없을 때(처음이거나 읽은 알림 삭제 뒤)만 쓰는 최후 경로이며, 같은 키의 동시 삽입은 매칭 행 잠금이 직렬화한다.
     */
    @Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = "INSERT INTO notification (user_id, type, match_id, dedup_key, created_at) "
            + "VALUES (:userId, 'CHAT_MESSAGE', :matchId, :dedupKey, :now) "
            + "ON DUPLICATE KEY UPDATE read_at = NULL, created_at = :now")
    int upsertChatMessage(@Param("userId") Long userId, @Param("matchId") Long matchId,
                          @Param("dedupKey") String dedupKey, @Param("now") LocalDateTime now);

    /** dedup_key 로 알림 id 를 찾는다(유니크 키 점조회, 잠금 없음). 채팅 알림 푸시·읽음 처리에 쓴다. */
    @Query("select n.id from Notification n where n.dedupKey = :dedupKey")
    List<Long> findIdsByDedupKey(@Param("dedupKey") String dedupKey);
}
