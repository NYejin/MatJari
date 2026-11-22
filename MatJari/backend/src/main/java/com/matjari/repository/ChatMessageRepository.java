package com.matjari.repository;

import com.matjari.domain.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 채팅 메시지 저장소(append-only). 커서 페이징은 모두 match_id + id 범위이며 idx_chat_message_match(match_id, 암묵적 PK id)로 정렬 없이 처리된다.
 * 옵티마이저가 PRIMARY 역방향 스캔이나 index_merge(+filesort)를 고르지 않도록 {@code FORCE INDEX} 를 쓴다(후보 SQL 의 STRAIGHT_JOIN 과 같은 이유).
 * 그래서 네이티브 쿼리이며 {@code limit} 는 호출자가 정한다(다음 페이지 여부는 size+1 건을 요청해 판단). 메시지 잠금은 없다(매칭 행만 잠근다).
 * 같은 SQL 의 실행 계획은 ChatSchemaMysqlTest 가 EXPLAIN 으로 확인한다(SQL 을 바꾸면 그 테스트의 문장도 같이 바꾼다).
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 최신부터 id 내림차순(커서 없음: 첫 화면). */
    @Query(value = "SELECT m.* FROM chat_message m FORCE INDEX (idx_chat_message_match) WHERE m.match_id = :matchId ORDER BY m.id DESC LIMIT :limit",
            nativeQuery = true)
    List<ChatMessage> findLatest(@Param("matchId") Long matchId, @Param("limit") int limit);

    /** before 보다 오래된 메시지, id 내림차순(위로 스크롤). */
    @Query(value = "SELECT m.* FROM chat_message m FORCE INDEX (idx_chat_message_match) WHERE m.match_id = :matchId AND m.id < :before "
            + "ORDER BY m.id DESC LIMIT :limit", nativeQuery = true)
    List<ChatMessage> findBefore(@Param("matchId") Long matchId, @Param("before") Long before, @Param("limit") int limit);

    /** after 보다 새 메시지, id 오름차순(재연결 뒤 빈 구간 채우기). */
    @Query(value = "SELECT m.* FROM chat_message m FORCE INDEX (idx_chat_message_match) WHERE m.match_id = :matchId AND m.id > :after "
            + "ORDER BY m.id ASC LIMIT :limit", nativeQuery = true)
    List<ChatMessage> findAfter(@Param("matchId") Long matchId, @Param("after") Long after, @Param("limit") int limit);

    /** 멱등 재전송 확인: 같은 (clientMessageId, 보낸 사람)의 기존 메시지. */
    Optional<ChatMessage> findByClientMessageIdAndSenderId(String clientMessageId, Long senderId);

    /** 정리 대상 사진 한 건(id, 파일 키). */
    interface ExpiredImage {
        Long getId();

        String getImageKey();
    }

    /**
     * 보관 기간이 지난 사진(V30). 매칭이 닫힌 시각은 CANCELED 는 canceled_at, COMPLETED 는 두 사람의 교환 수락 시각 중 늦은 것
     * (ck_exchange_match_completed 가 COMPLETED 에서 둘 다 NOT NULL 을 보장)이고, 그 시각이 cutoff 보다 이전이어야 한다.
     * 그 매칭에 처리되지 않은(OPEN) 신고가 있으면 제외한다(처리될 때까지 보관). 잠금 없는 일관 읽기이고 id 순으로 limit 건.
     */
    @Query(value = "SELECT c.id AS id, c.image_key AS imageKey FROM chat_message c JOIN exchange_match m ON m.id = c.match_id "
            + "WHERE c.image_key IS NOT NULL AND c.image_deleted_at IS NULL "
            + "AND ((m.status = 'CANCELED' AND m.canceled_at < :cutoff) "
            + "  OR (m.status = 'COMPLETED' AND GREATEST(m.a_completed_at, m.b_completed_at) < :cutoff)) "
            + "AND NOT EXISTS (SELECT 1 FROM abuse_report r WHERE r.match_id = m.id AND r.status = 'OPEN') "
            + "ORDER BY c.id LIMIT :limit", nativeQuery = true)
    List<ExpiredImage> findImagesPastRetention(@Param("cutoff") java.time.LocalDateTime cutoff, @Param("limit") int limit);

    /** 정리 작업이 PK 로 지운 시각을 기록한다(NULL -> 시각 한 번만). 매칭·티켓은 잠그지 않는다. 바뀐 행 수를 돌려준다. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value = "UPDATE chat_message SET image_deleted_at = :at WHERE id = :id AND image_key IS NOT NULL AND image_deleted_at IS NULL",
            nativeQuery = true)
    int markImageDeleted(@Param("id") Long id, @Param("at") java.time.LocalDateTime at);

    /** 주어진 키 중 아직 살아 있는(지운 시각이 없는) 사진 메시지가 가리키는 키. 고아 파일 판정에 쓴다. */
    @Query("select m.imageKey from ChatMessage m where m.imageKey in :keys and m.imageDeletedAt is null")
    List<String> findLiveImageKeys(@Param("keys") java.util.Collection<String> keys);

    /** 읽음 포인터 절단: 요청 id 이하의 이 매칭 메시지 중 가장 큰 id (없으면 null). */
    @Query("select max(m.id) from ChatMessage m where m.matchId = :matchId and m.id <= :id")
    Long findMaxIdUpTo(@Param("matchId") Long matchId, @Param("id") Long id);
}
