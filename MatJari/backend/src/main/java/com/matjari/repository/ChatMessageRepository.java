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

    /** 읽음 포인터 절단: 요청 id 이하의 이 매칭 메시지 중 가장 큰 id (없으면 null). */
    @Query("select max(m.id) from ChatMessage m where m.matchId = :matchId and m.id <= :id")
    Long findMaxIdUpTo(@Param("matchId") Long matchId, @Param("id") Long id);
}
