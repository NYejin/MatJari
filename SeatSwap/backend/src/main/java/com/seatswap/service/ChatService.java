package com.seatswap.service;

import com.seatswap.domain.ChatMessage;
import com.seatswap.domain.ExchangeMatch;
import com.seatswap.domain.ExchangeMatchStatus;
import com.seatswap.dto.response.ChatDtos;
import com.seatswap.exception.ConflictException;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.exception.NotFoundException;
import com.seatswap.repository.ChatMessageRepository;
import com.seatswap.repository.ChatReadStateRepository;
import com.seatswap.repository.ExchangeMatchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 매칭 채팅 (V11). 보내기는 REST, 받기는 STOMP 사용자 큐(전파는 {@link ChatPushPublisher}, 커밋 후)다. 시스템 메시지·알림 행은 만들지 않는다.
 *
 * <h3>전송</h3>
 * ①본문 정규화·검증(400) ②사용자별 속도 제한(429) ③<b>트랜잭션 밖</b>에서 매칭을 읽어 참여자 확인(비참여자·없는 매칭 404)
 * ④트랜잭션 안에서 <b>첫 쿼리</b>로 매칭 행 {@code FOR UPDATE}(이후 일관 읽기가 잠금 뒤에 시작되도록) -> 상태가 CHATTING·RESERVED 가 아니면 409 MATCH_STATE_CONFLICT
 * 같은 (clientMessageId, 보낸 사람)이 이미 있으면 그 행을 돌려주고(멱등, 200) 없으면 INSERT(201)
 * ⑤<b>커밋 뒤</b> 트랜잭션 밖에서 두 참여자에게 전파(실패해도 저장은 유지, 멱등 재전송은 다시 전파하지 않는다).
 * 채팅 트랜잭션은 매칭 행 하나만 잠그므로 티켓 -> 요청 -> 매칭 잠금 순서 규약을 깨지 않는다(매칭 행은 UPDATE 하지 않아 updated_at·정렬이 변하지 않는다).
 * 같은 매칭의 전송은 그 행 잠금으로 직렬화되므로 id 순서 = 커밋 순서이고, 종료(cancel/complete)와 겹쳐도 종료 커밋 뒤에는 메시지가 커밋되지 않는다.
 *
 * <h3>읽기·읽음</h3>
 * 목록·읽음 갱신은 매칭 상태와 무관하게 참여자에게 허용한다(종료된 매칭의 이력 열람·안 읽은 수 정리). 포인터는 {@link ChatReadStateRepository#advance}
 * (호출자 트랜잭션 밖 자동 커밋, 값은 줄어들지 않음)로 올리고, 요청 id 는 그 매칭의 실제 최대 메시지 id 로 잘라 저장한다.
 * 포인터가 실제로 올라갔을 때만 상대에게 READ 를 전파한다.
 */
@Slf4j
@Service
public class ChatService {

    public static final int MAX_CONTENT_CODE_POINTS = ChatMessage.MAX_CONTENT_LENGTH;
    public static final int DEFAULT_PAGE_SIZE = 30;
    public static final int DEFAULT_AFTER_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 100;

    static final String CLOSED_MESSAGE = "종료된 채팅에는 메시지를 보낼 수 없어요";
    static final String CLIENT_ID_REUSED_MESSAGE = "이미 다른 채팅에서 쓴 메시지 ID예요.";
    private static final Pattern CLIENT_MESSAGE_ID = Pattern.compile("^[A-Za-z0-9_-]{8,36}$");

    private final ExchangeMatchRepository matchRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatReadStateRepository readStateRepository;
    private final ChatRateLimiter rateLimiter;
    private final ObjectProvider<ChatPushPublisher> publisherProvider;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ChatService(ExchangeMatchRepository matchRepository,
                       ChatMessageRepository messageRepository,
                       ChatReadStateRepository readStateRepository,
                       ChatRateLimiter rateLimiter,
                       ObjectProvider<ChatPushPublisher> publisherProvider,
                       Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.matchRepository = matchRepository;
        this.messageRepository = messageRepository;
        this.readStateRepository = readStateRepository;
        this.rateLimiter = rateLimiter;
        this.publisherProvider = publisherProvider;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 전송 결과: created=false 는 같은 clientMessageId 의 멱등 재전송(기존 행). */
    public record SendResult(ChatDtos.Message message, boolean created) {}

    private record Stored(ChatMessage message, boolean created) {}

    // ---------------------------------------------------------------- 전송

    public SendResult send(Long userId, Long matchId, String rawContent, String clientMessageId) {
        String content = normalizeContent(rawContent);
        validateClientMessageId(clientMessageId);
        rateLimiter.acquire(userId);

        ExchangeMatch seen = requireParticipant(userId, matchId);   // 트랜잭션 밖: 잠금 읽기가 첫 일관 읽기가 되도록
        List<Long> recipients = List.of(seen.getUserAId(), seen.getUserBId());

        Stored stored = tx.execute(status -> {
            ExchangeMatch m = matchRepository.findByIdForUpdate(matchId)
                    .orElseThrow(() -> new NotFoundException(ExchangeMatchService.MATCH_NOT_FOUND_MESSAGE));
            if (m.getStatus() != ExchangeMatchStatus.CHATTING && m.getStatus() != ExchangeMatchStatus.RESERVED) {
                throw new ConflictException(CLOSED_MESSAGE, Map.of("code", ExchangeMatchService.STATE_CONFLICT_CODE,
                        "status", m.getStatus().name(), "action", "SEND"));
            }
            var existing = messageRepository.findByClientMessageIdAndSenderId(clientMessageId, userId);
            if (existing.isPresent()) {
                if (!existing.get().getMatchId().equals(matchId)) {
                    throw new ConflictException(CLIENT_ID_REUSED_MESSAGE, Map.of("code", "CLIENT_MESSAGE_ID_REUSED"));
                }
                return new Stored(existing.get(), false);
            }
            return new Stored(messageRepository.saveAndFlush(ChatMessage.of(matchId, userId, content, clientMessageId)), true);
        });

        ChatDtos.Message dto = toDto(stored.message());
        if (stored.created()) {
            publishMessage(matchId, dto, recipients);   // 커밋 후, 트랜잭션 밖
        }
        return new SendResult(dto, stored.created());
    }

    // ---------------------------------------------------------------- 목록

    /**
     * 커서 조회. before 와 after 를 함께 줄 수 없다(400). 둘 다 없으면 최신부터. size 기본: before/최신 30, after 100. 최대 100.
     * 종료된 매칭도 참여자는 읽을 수 있다.
     */
    public ChatDtos.MessagePage list(Long userId, Long matchId, Long before, Long after, Integer size) {
        if (before != null && after != null) {
            throw new FieldValidationException("before", "before와 after는 함께 쓸 수 없습니다.");
        }
        if (size != null && size < 1) {
            throw new FieldValidationException("size", "size는 1 이상이어야 합니다.");
        }
        if ((before != null && before < 0) || (after != null && after < 0)) {
            throw new FieldValidationException(before != null ? "before" : "after", "커서는 0 이상이어야 합니다.");
        }
        ExchangeMatch m = requireParticipant(userId, matchId);
        int pageSize = Math.min(size != null ? size : (after != null ? DEFAULT_AFTER_SIZE : DEFAULT_PAGE_SIZE), MAX_PAGE_SIZE);

        List<ChatMessage> rows;
        if (after != null) {
            rows = new ArrayList<>(messageRepository.findAfter(matchId, after, pageSize + 1));
        } else if (before != null) {
            rows = new ArrayList<>(messageRepository.findBefore(matchId, before, pageSize + 1));
        } else {
            rows = new ArrayList<>(messageRepository.findLatest(matchId, pageSize + 1));
        }
        boolean hasMore = rows.size() > pageSize;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, pageSize));   // 커서에서 먼 쪽 1건을 버린다
        }
        if (after == null) {
            Collections.reverse(rows);   // id 내림차순 조회 -> 오름차순 응답
        }
        Long counterpart = counterpartOf(m, userId);
        return new ChatDtos.MessagePage(rows.stream().map(ChatService::toDto).toList(), hasMore,
                readStateRepository.find(matchId, userId).orElse(0L),
                readStateRepository.find(matchId, counterpart).orElse(0L));
    }

    // ---------------------------------------------------------------- 읽음

    /** 읽음 포인터를 올린다(204). 그 매칭에 id 이하 메시지가 없거나 이미 그 이상이면 아무것도 하지 않고 푸시도 없다. */
    public void markRead(Long userId, Long matchId, Long lastReadMessageId) {
        ExchangeMatch m = requireParticipant(userId, matchId);
        Long truncated = messageRepository.findMaxIdUpTo(matchId, lastReadMessageId);
        if (truncated == null) {
            return;
        }
        long current = readStateRepository.find(matchId, userId).orElse(0L);
        if (truncated <= current) {
            return;
        }
        readStateRepository.advance(matchId, userId, truncated, LocalDateTime.now(clock));
        publishRead(matchId, counterpartOf(m, userId), truncated);
    }

    // ---------------------------------------------------------------- 도우미

    /** 트랜잭션 없이 매칭을 읽어 참여자를 확인한다. 비참여자와 없는 매칭은 같은 404(존재 은닉). */
    private ExchangeMatch requireParticipant(Long userId, Long matchId) {
        ExchangeMatch m = matchRepository.findById(matchId)
                .orElseThrow(() -> new NotFoundException(ExchangeMatchService.MATCH_NOT_FOUND_MESSAGE));
        if (!userId.equals(m.getUserAId()) && !userId.equals(m.getUserBId())) {
            throw new NotFoundException(ExchangeMatchService.MATCH_NOT_FOUND_MESSAGE);
        }
        return m;
    }

    private static Long counterpartOf(ExchangeMatch m, Long userId) {
        return userId.equals(m.getUserAId()) ? m.getUserBId() : m.getUserAId();
    }

    private static ChatDtos.Message toDto(ChatMessage m) {
        return new ChatDtos.Message(m.getId(), m.getSenderId(), m.getContent(), m.getClientMessageId(), m.getCreatedAt());
    }

    private void publishMessage(Long matchId, ChatDtos.Message dto, List<Long> recipients) {
        try {
            publisherProvider.getIfAvailable(() -> ChatPushPublisher.NOOP).publishMessage(matchId, dto, recipients);
        } catch (RuntimeException e) {
            log.warn("채팅 메시지 전파 실패(저장은 유지됨) matchId={} messageId={}: {}", matchId, dto.id(), e.toString());
        }
    }

    private void publishRead(Long matchId, Long recipient, long lastReadMessageId) {
        try {
            publisherProvider.getIfAvailable(() -> ChatPushPublisher.NOOP).publishRead(matchId, recipient, lastReadMessageId);
        } catch (RuntimeException e) {
            log.warn("채팅 읽음 전파 실패 matchId={}: {}", matchId, e.toString());
        }
    }

    private static void validateClientMessageId(String id) {
        if (id == null || !CLIENT_MESSAGE_ID.matcher(id).matches()) {
            throw new FieldValidationException("clientMessageId", "clientMessageId는 8~36자의 영문·숫자·-·_ 이어야 합니다.");
        }
    }

    /**
     * 본문 정규화: CRLF -> LF, 앞뒤 공백 제거. 줄바꿈·탭 외 제어 문자(C0, DEL, C1)와 제로폭·방향 제어 문자, 짝 없는 서로게이트는 400,
     * 빈 본문 400, 1000자(유니코드 코드 포인트, DB CHAR_LENGTH 와 같은 기준) 초과 400. U+200D(ZWJ)는 이모지 조합에 쓰이므로 허용한다.
     * 일반 텍스트이며 HTML 이스케이프·자동 링크는 하지 않는다(렌더링은 프론트가 텍스트로만 그린다).
     */
    static String normalizeContent(String raw) {
        if (raw == null) {
            throw new FieldValidationException("content", "메시지를 입력해주세요.");
        }
        String s = raw.replace("\r\n", "\n").strip();
        if (s.isEmpty()) {
            throw new FieldValidationException("content", "메시지를 입력해주세요.");
        }
        int codePoints = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            codePoints++;
            if (isForbidden(cp)) {
                throw new FieldValidationException("content", "보낼 수 없는 문자가 포함되어 있어요.");
            }
        }
        if (codePoints > MAX_CONTENT_CODE_POINTS) {
            throw new FieldValidationException("content", "메시지는 " + MAX_CONTENT_CODE_POINTS + "자 이하로 입력해주세요.");
        }
        return s;
    }

    private static boolean isForbidden(int cp) {
        if (cp < 0x20) {
            return cp != '\n' && cp != '\t';
        }
        if (cp == 0x7F || (cp >= 0x80 && cp <= 0x9F)) {
            return true;
        }
        if (cp >= 0xD800 && cp <= 0xDFFF) {
            return true;   // codePointAt 이 짝을 맞추지 못한 서로게이트
        }
        return cp == 0x00AD || cp == 0x180E || cp == 0x200B || cp == 0x200C || cp == 0x200E || cp == 0x200F
                || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2060 && cp <= 0x2064)
                || (cp >= 0x2066 && cp <= 0x2069) || cp == 0xFEFF;
    }
}
