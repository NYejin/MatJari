package com.matjari.service;

import com.matjari.repository.ChatMessageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 채팅 사진 보관 정리 (V30). 두 가지를 주기적으로 지운다.
 * <ol>
 *   <li><b>보관 기간이 지난 사진</b>: 매칭이 COMPLETED·CANCELED 로 닫힌 뒤 {@code matjari.chat.image-retention-days}(기본 30일)가 지난 사진.
 *       닫힌 시각은 exchange_match 에서 CANCELED 는 {@code canceled_at}, COMPLETED 는 두 사람의 교환 수락 시각({@code a/b_completed_at}) 중 늦은 것이다
 *       (COMPLETED 는 ck_exchange_match_completed 로 둘 다 NOT NULL 이고, 매칭 행의 updated_at 은 이후 다른 갱신으로 움직일 수 있어 쓰지 않는다).
 *       그 매칭에 <b>처리되지 않은(OPEN) 신고</b>가 있으면 처리될 때까지 지우지 않는다(신고 근거). 파일을 먼저 지우고(없어도 성공) 성공하면
 *       chat_message 의 image_deleted_at 을 PK 로 기록한다 — 기록이 실패하면 다음 주기에 다시 한다(파일은 이미 없어 삭제가 다시 성공한다).
 *       회원 탈퇴 때는 지우지 않는다(채팅 보관 규칙과 같음, 보관 기간이 지나면 지워진다).</li>
 *   <li><b>고아 파일</b>: 살아 있는 chat_message 행이 가리키지 않는 파일(저장 직후 서버가 멈춰 INSERT 가 없는 경우 등). INSERT 가 커밋되기까지의 틈을
 *       지키려고 {@code grace-minutes}(기본 60분)보다 오래된 파일만 본다.</li>
 * </ol>
 * 매칭·티켓 행은 잠그지 않는다(조회는 잠금 없는 일관 읽기, 갱신은 chat_message PK 한 행). 한 번에 {@code batch}(기본 200)건까지만 처리하고 나머지는 다음 주기에 한다.
 * 알려진 좁은 틈: 조회 직후 파일을 지우기 전에 그 매칭의 신고가 들어오면 그 사진은 신고 근거로 남지 못한다(보관 기간이 이미 지난 사진에 한정).
 * 이미지 바이트·경로는 로그에 남기지 않는다.
 */
@Slf4j
@Service
public class ChatImageCleanupService {

    /** 한 번 실행의 결과(테스트·로그용). */
    public record Result(int expiredDeleted, int orphanDeleted) {
    }

    private static final int IN_CHUNK = 100;

    private final ChatMessageRepository messageRepository;
    private final ChatImageStore imageStore;
    private final Clock clock;
    private final Duration retention;
    private final Duration grace;
    private final int batch;

    public ChatImageCleanupService(ChatMessageRepository messageRepository, ChatImageStore imageStore, Clock clock,
                                   @Value("${matjari.chat.image-retention-days:30}") long retentionDays,
                                   @Value("${matjari.chat.image-cleanup.grace-minutes:60}") long graceMinutes,
                                   @Value("${matjari.chat.image-cleanup.batch:200}") int batch) {
        this.messageRepository = messageRepository;
        this.imageStore = imageStore;
        this.clock = clock;
        this.retention = Duration.ofDays(Math.max(1, retentionDays));
        this.grace = Duration.ofMinutes(Math.max(1, graceMinutes));
        this.batch = Math.max(1, batch);
    }

    /** 두 정리를 차례로 실행한다. 한 단계가 실패해도 다른 단계는 계속하고 실패는 종류만 로그로 남긴다. */
    public Result cleanup() {
        int expired = 0;
        int orphans = 0;
        try {
            expired = deleteExpiredImages();
        } catch (RuntimeException e) {
            log.warn("Chat image cleanup (retention) failed: {}", e.getClass().getSimpleName());
        }
        try {
            orphans = deleteOrphanFiles();
        } catch (RuntimeException e) {
            log.warn("Chat image cleanup (orphans) failed: {}", e.getClass().getSimpleName());
        }
        if (expired > 0 || orphans > 0) {
            log.info("Chat image cleanup: expiredDeleted={}, orphanDeleted={}", expired, orphans);
        }
        return new Result(expired, orphans);
    }

    private int deleteExpiredImages() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ChatMessageRepository.ExpiredImage> targets = messageRepository.findImagesPastRetention(now.minus(retention), batch);
        int deleted = 0;
        for (ChatMessageRepository.ExpiredImage t : targets) {
            if (!ChatImageStore.isValidKey(t.getImageKey()) || !imageStore.delete(t.getImageKey())) {
                continue;   // 이번엔 못 지웠다 -> 다음 주기에 다시 시도
            }
            messageRepository.markImageDeleted(t.getId(), now);
            deleted++;
        }
        return deleted;
    }

    private int deleteOrphanFiles() {
        List<String> keys = imageStore.listKeysOlderThan(Instant.now(clock).minus(grace), batch);
        int deleted = 0;
        for (int from = 0; from < keys.size(); from += IN_CHUNK) {
            List<String> chunk = keys.subList(from, Math.min(keys.size(), from + IN_CHUNK));
            Set<String> live = new HashSet<>(messageRepository.findLiveImageKeys(chunk));
            for (String key : chunk) {
                if (!live.contains(key) && imageStore.delete(key)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }
}
