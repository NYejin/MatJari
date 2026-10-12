package com.seatswap.service;

import com.seatswap.domain.Notification;
import com.seatswap.domain.NotificationType;
import com.seatswap.dto.response.NotificationResponse;
import com.seatswap.dto.response.PageResponse;
import com.seatswap.dto.response.UnreadCountResponse;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.NotificationQueryRepository;
import com.seatswap.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 알림 생성(쓰기 경로). 알림은 상태를 바꾸는 트랜잭션(제안·예약·예약 취소)의 맨 끝에서만 일반 INSERT 로 만든다.
 * 호출자의 트랜잭션이 없으면 예외(MANDATORY)이므로 롤백되면 알림도 함께 사라지고, 상태가 바뀌지 않은 경로(멱등·오류)에서는 호출하지 않는다.
 * 조회·읽음·삭제는 수신자 본인 것만 다룬다(남의 알림·없는 알림은 404 로 존재를 숨긴다).
 * 읽음·삭제는 잠금 없는 SELECT 로 id 를 모은 뒤 `WHERE id IN (...)` 으로만 갱신한다(범위 UPDATE/DELETE 금지: 갭·넥스트키 잠금이
 * 동시에 들어오는 알림 INSERT 와 교착하는 것을 피한다). id 는 오름차순으로 한 문장 최대 {@link #BATCH}개씩 처리한다.
 * 이 갱신 트랜잭션은 notification 행만 잠그고 티켓·요청·매칭·users 는 건드리지 않으므로 잠금 순서 규약(마지막에 notification)에 어긋나지 않는다.
 * 닫힌 매칭(CANCELED/COMPLETED)의 알림도 개수·목록에서 빼지 않는다. 알림이 사라지는 경로는 {@link #deleteRead} 뿐이다.
 */
@Slf4j
@Service
public class NotificationService {

    static final int MAX_PAGE_SIZE = 100;
    static final int BATCH = 500;
    static final String NOT_FOUND_MESSAGE = "알림을 찾을 수 없습니다.";

    private final NotificationRepository notificationRepository;
    private final NotificationQueryRepository queryRepository;
    private final Clock clock;
    private final NotificationPushPublisher pushPublisher;

    public NotificationService(NotificationRepository notificationRepository, NotificationQueryRepository queryRepository, Clock clock,
                               NotificationPushPublisher pushPublisher) {
        this.notificationRepository = notificationRepository;
        this.queryRepository = queryRepository;
        this.clock = clock;
        this.pushPublisher = pushPublisher;
    }

    /** 내 알림 목록(id 내림차순). 잘못된 page/size 는 400. */
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(Long userId, int page, int size, boolean unreadOnly) {
        if (page < 0) {
            throw new FieldValidationException("page", "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new FieldValidationException("size", "size는 1 이상이어야 합니다.");
        }
        int pageSize = Math.min(size, MAX_PAGE_SIZE);
        long total = queryRepository.count(userId, unreadOnly);
        List<NotificationResponse> content = total == 0 ? List.of()
                : queryRepository.findPage(userId, unreadOnly, pageSize, (long) page * pageSize);
        int totalPages = (int) ((total + pageSize - 1) / pageSize);
        return new PageResponse<>(content, page, pageSize, total, totalPages);
    }

    /** 안 읽은 개수 (닫힌 매칭 알림 포함, 종류 6개 키는 항상 있다). */
    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(Long userId) {
        Map<String, Long> byType = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<NotificationType, Long> e : queryRepository.unreadCountsByType(userId).entrySet()) {
            byType.put(e.getKey().name(), e.getValue());
            total += e.getValue();
        }
        return new UnreadCountResponse(total, byType);
    }

    /** 단건 읽음(멱등). 남의 것·없는 것은 404. */
    @Transactional
    public void markRead(Long userId, Long id) {
        if (notificationRepository.markRead(id, userId, LocalDateTime.now(clock)) == 0
                && !notificationRepository.existsByIdAndUserId(id, userId)) {
            throw new NotFoundException(NOT_FOUND_MESSAGE);
        }
    }

    /** 전체 읽음(type 이 null 이면 전체). 수집 이후 들어온 새 알림은 대상이 아니라 안 읽음으로 남는다. 읽음 처리한 건수를 돌려준다. */
    @Transactional
    public int markAllRead(Long userId, String type) {
        NotificationType parsed = parseType(type);
        List<Long> ids = notificationRepository.findUnreadIds(userId, parsed);
        LocalDateTime now = LocalDateTime.now(clock);
        int updated = 0;
        for (int from = 0; from < ids.size(); from += BATCH) {
            updated += notificationRepository.markAllRead(ids.subList(from, Math.min(ids.size(), from + BATCH)), userId, now);
        }
        return updated;
    }

    /** 읽은 알림 삭제: 내 read_at 이 있는 알림만 물리 삭제한다(안 읽은 알림·남의 알림은 그대로). 지운 건수를 돌려준다. */
    @Transactional
    public int deleteRead(Long userId) {
        List<Long> ids = notificationRepository.findReadIds(userId);
        int deleted = 0;
        for (int from = 0; from < ids.size(); from += BATCH) {
            deleted += notificationRepository.deleteRead(ids.subList(from, Math.min(ids.size(), from + BATCH)), userId);
        }
        return deleted;
    }

    private static NotificationType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return NotificationType.valueOf(type.trim());
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException("type", "type은 PROPOSAL_RECEIVED, RESERVATION_PROPOSED, MATCH_RESERVED, RESERVATION_REJECTED, RESERVATION_CANCELED, CHAT_MESSAGE 중 하나여야 합니다.");
        }
    }

    /**
     * 수신자에게 알림 1건을 만든다. dedupKey 는 한 번만 생기는 알림(PROPOSAL_RECEIVED:{matchId})만 채우고 나머지는 null.
     * 잠금 순서상 notification 쓰기는 트랜잭션의 마지막이어야 한다(호출하는 쪽의 책임).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Notification notify(Long recipientUserId, NotificationType type, Long matchId, String dedupKey) {
        Notification saved = notificationRepository.saveAndFlush(Notification.of(recipientUserId, type, matchId, dedupKey));
        registerPush(saved.getUserId(), saved.getId(), saved.getType(), saved.getMatchId());
        return saved;
    }

    /**
     * 새 채팅 메시지 알림(V15): (수신자, 매칭)당 행 1개를 만들거나(INSERT ... ON DUPLICATE KEY UPDATE) 되살린다(PK UPDATE)(안 읽은 채팅 알림은 매칭당 최대 1건).
     * 채팅 전송 트랜잭션의 맨 끝(매칭 FOR UPDATE 뒤, 메시지 저장 성공 뒤)에서만 호출한다. 같은 매칭의 전송은 매칭 행 잠금으로 직렬화되어
     * 같은 키의 동시 upsert 가 없다. 푸시는 다른 알림처럼 커밋 뒤 신호 1건이다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyChatMessage(Long recipientUserId, Long matchId) {
        String key = chatDedupKey(matchId, recipientUserId);
        LocalDateTime now = LocalDateTime.now(clock);
        // 먼저 잠금 없는 점조회로 기존 행을 찾아 PK 로 되살린다(읽은 알림 삭제와 같은 잠금 순서: PK 먼저). 행이 없을 때만 INSERT(upsert).
        Long id = notificationRepository.findIdsByDedupKey(key).stream().findFirst().orElse(null);
        if (id == null || notificationRepository.reviveChatMessage(id, now) == 0) {
            notificationRepository.upsertChatMessage(recipientUserId, matchId, key, now);
            id = notificationRepository.findIdsByDedupKey(key).stream().findFirst().orElse(null);
        }
        if (id == null) {
            throw new IllegalStateException("채팅 알림 upsert 뒤 행을 찾을 수 없습니다: " + key);
        }
        registerPush(recipientUserId, id, NotificationType.CHAT_MESSAGE, matchId);
    }

    /**
     * 채팅 읽음 연동: 읽음 포인터가 앞으로 움직였을 때 호출한다. 그 (수신자, 매칭)의 CHAT_MESSAGE 알림을 읽음 처리한다(안 읽은 것만).
     * 유니크 키로 id 를 잠금 없이 찾은 뒤 PK 로 한 행만 갱신한다. 매칭·users 잠금을 잡은 트랜잭션 안에서 호출하지 않는다(독립 트랜잭션).
     * 아주 드물게 읽음 처리와 새 메시지 upsert 가 겹치면 새 메시지 알림이 읽음이 될 수 있으나, 그 메시지가 열린 채팅 화면에 전달되어 다시 읽음 처리된다.
     */
    @Transactional
    public void markChatRead(Long userId, Long matchId) {
        for (Long id : notificationRepository.findIdsByDedupKey(chatDedupKey(matchId, userId))) {
            notificationRepository.markRead(id, userId, LocalDateTime.now(clock));
        }
    }

    /** 채팅 알림의 dedup 키(DB ck_notification_chat_dedup 형식 그대로). */
    public static String chatDedupKey(Long matchId, Long recipientId) {
        return NotificationType.CHAT_MESSAGE.name() + ":" + matchId + ":" + recipientId;
    }

    /** 커밋된 뒤에만 실시간 신호를 보낸다(롤백되면 보내지 않는다). 전파가 실패해도 저장·응답에는 영향이 없다(폴링이 보완). */
    private void registerPush(Long recipient, Long id, NotificationType type, Long matchId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    pushPublisher.publishCreated(recipient, id, type, matchId);
                } catch (RuntimeException e) {
                    log.warn("알림 실시간 전파 실패 (notificationId={}): {}", id, e.toString());
                }
            }
        });
    }

    /** 제안 알림의 dedup 키. */
    public static String proposalDedupKey(Long matchId) {
        return NotificationType.PROPOSAL_RECEIVED.name() + ":" + matchId;
    }
}
