package com.matjari.service;

import com.matjari.domain.NotificationType;
import com.matjari.repository.NotificationMailRepository;
import com.matjari.repository.NotificationMailRepository.Pending;
import com.matjari.repository.NotificationMailRepository.Recipient;
import com.matjari.service.mail.EmailSender;
import com.matjari.service.social.SocialProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 메일 알림 (V29). 사이트 밖에 있는 사용자에게 아직 안 읽은 알림을 <b>사용자당 메일 1통(요약)</b>으로 알린다.
 *
 * <p>대상: {@code read_at IS NULL AND emailed_at IS NULL AND created_at <= now - delay(기본 5분) AND created_at >= now - 24시간}.
 * 지연은 사이트를 보고 있던 사용자가 그 사이 알림을 읽으면 메일을 보내지 않기 위해서다(발송 직전에 안 읽음 상태를 다시 확인한다).
 *
 * <p>건너뛰기: 설정 꺼짐·메일을 받을 수 없는 주소(.invalid 자리표시 이메일)·탈퇴 사용자의 알림은 emailed_at 을 채워 다시 스캔하지 않는다.
 * 이용 정지 중인 사용자는 채우지 않고 건너뛴다(정지가 풀리면 24시간 안의 것은 보낼 수 있다). 사용자별 쿨다운(기본 30분, user_notification_setting.last_email_at)
 * 안이면 알림을 남겨 두고(다음 주기에 합쳐 보낸다), 전체 시간당 상한(기본 100통, Gmail 일일 한도 대비)에 닿으면 이번 주기를 거기서 멈추고 다음 주기로 미룬다.
 *
 * <p>잠금: users·매칭·티켓 행을 잡지 않는다. 읽기는 잠금 없는 SELECT 이고, 발송 성공 뒤 그 알림 id 들만 {@code WHERE id IN (...)} (500개씩)으로
 * emailed_at 을 채운다(범위 UPDATE 금지). 이 UPDATE 는 스캔 기준 시각(cutoff) 이후에 되살아난 재사용 알림(created_at 이 갱신됨)을 건드리지 않는다.
 * 발송 실패는 알림을 그대로 두고 그 사용자만 이번 주기를 건너뛴다. 로그에는 사용자 id·건수만 남기고 이메일 주소·본문은 남기지 않는다
 * (개발용 log 모드 발송기는 예외 - 개발 전용이다).
 *
 * <p>한계: 단일 인스턴스 전제다. 여러 인스턴스가 동시에 돌면 같은 알림이 두 번 메일될 수 있다(발송 전 선점이 없다).
 * 시간당 상한 기록은 메모리라 재시작하면 초기화된다. 스캔은 한 주기에 최대 {@value #SCAN_LIMIT}건이다.
 */
@Slf4j
@Service
public class NotificationMailService {

    static final int SCAN_LIMIT = 5000;
    static final int MAX_TITLE_LENGTH = 100;

    private final NotificationMailRepository repository;
    private final EmailSender emailSender;
    private final SocialProperties socialProperties;
    private final Clock clock;
    private final boolean enabled;
    private final Duration delay;
    private final Duration lookback;
    private final Duration userCooldown;
    private final int maxPerHour;
    /** 최근 1시간에 보낸 메일의 발송 시각(시간당 상한 판단용, 메모리). */
    private final ArrayDeque<LocalDateTime> sentTimes = new ArrayDeque<>();

    public NotificationMailService(NotificationMailRepository repository, EmailSender emailSender, SocialProperties socialProperties, Clock clock,
                                   @Value("${matjari.notification-mail.enabled:true}") boolean enabled,
                                   @Value("${matjari.notification-mail.delay-minutes:5}") long delayMinutes,
                                   @Value("${matjari.notification-mail.lookback-hours:24}") long lookbackHours,
                                   @Value("${matjari.notification-mail.user-cooldown-minutes:30}") long cooldownMinutes,
                                   @Value("${matjari.notification-mail.max-per-hour:100}") int maxPerHour) {
        this.repository = repository;
        this.emailSender = emailSender;
        this.socialProperties = socialProperties;
        this.clock = clock;
        this.enabled = enabled;
        this.delay = Duration.ofMinutes(delayMinutes);
        this.lookback = Duration.ofHours(lookbackHours);
        this.userCooldown = Duration.ofMinutes(cooldownMinutes);
        this.maxPerHour = maxPerHour;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 한 주기. 보낸 메일 수를 돌려준다. 어떤 예외도 밖으로 던지지 않는다(스케줄이 멈추면 안 된다). */
    public synchronized int runOnce() {
        if (!enabled) {
            return 0;
        }
        try {
            return doRun();
        } catch (RuntimeException e) {
            log.warn("메일 알림 주기 실패: {}", e.toString());
            return 0;
        }
    }

    private int doRun() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minus(delay);
        LocalDateTime floor = now.minus(lookback);
        List<Pending> pending = repository.findPending(cutoff, floor, SCAN_LIMIT);
        if (pending.isEmpty()) {
            return 0;
        }
        Map<Long, List<Pending>> byUser = new LinkedHashMap<>();
        for (Pending p : pending) {
            byUser.computeIfAbsent(p.userId(), k -> new ArrayList<>()).add(p);
        }
        pruneSent(now);
        int sent = 0;
        for (Map.Entry<Long, List<Pending>> e : byUser.entrySet()) {
            if (sentTimes.size() >= maxPerHour) {
                log.info("메일 알림 시간당 상한({}통)에 닿아 다음 주기로 미룹니다", maxPerHour);
                break;
            }
            try {
                if (processUser(e.getKey(), e.getValue(), now, cutoff)) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                log.warn("메일 알림 처리 실패 (userId={}): {}", e.getKey(), ex.toString());
            }
        }
        return sent;
    }

    private boolean processUser(Long userId, List<Pending> items, LocalDateTime now, LocalDateTime cutoff) {
        Recipient recipient = repository.loadRecipient(userId);
        if (recipient == null || recipient.withdrawn() || !recipient.emailEnabled()
                || !NotificationSettingService.isMailable(recipient.email())) {
            repository.markEmailed(items.stream().map(Pending::id).toList(), now, cutoff);   // 메일 대상이 아니다: 다시 스캔하지 않는다
            return false;
        }
        if (repository.isSuspended(userId, now)) {
            return false;   // 정지 중: 표시하지 않고 건너뛴다
        }
        if (recipient.lastEmailAt() != null && recipient.lastEmailAt().isAfter(now.minus(userCooldown))) {
            return false;   // 쿨다운: 알림을 남겨 두면 다음 주기에 합쳐 보낸다
        }
        // 발송 직전에 아직 안 읽은 것만 다시 확인한다(지연 동안 사이트에서 읽었으면 보내지 않는다)
        Set<Long> unread = new HashSet<>(repository.stillUnreadIds(items.stream().map(Pending::id).toList(), cutoff));
        List<Pending> toSend = items.stream().filter(p -> unread.contains(p.id())).toList();
        if (toSend.isEmpty()) {
            return false;
        }
        List<Long> sendIds = toSend.stream().map(Pending::id).toList();
        Map<Long, String> titles = new LinkedHashMap<>();
        repository.performanceTitles(toSend.stream()
                .filter(p -> p.type() == NotificationType.PROPOSAL_RECEIVED || p.type() == NotificationType.NEW_CANDIDATE)
                .map(Pending::id).toList()).forEach((id, t) -> titles.put(id, cleanTitle(t)));
        String subject = "[맞자리] 새 알림 " + toSend.size() + "건";
        String body = buildBody(toSend, titles, socialProperties.frontendBase());
        try {
            emailSender.send(recipient.email(), subject, body);
        } catch (RuntimeException e) {
            log.warn("알림 메일 발송 실패 (userId={}, 건수={}): {}", userId, toSend.size(), e.getClass().getSimpleName());
            return false;   // 그대로 두고 이번 주기는 건너뜀
        }
        sentTimes.addLast(now);
        repository.markEmailed(sendIds, now, cutoff);
        repository.touchLastEmail(userId, now);
        log.info("알림 메일 발송 (userId={}, 건수={})", userId, toSend.size());
        return true;
    }

    private void pruneSent(LocalDateTime now) {
        LocalDateTime hourAgo = now.minusHours(1);
        while (!sentTimes.isEmpty() && sentTimes.peekFirst().isBefore(hourAgo)) {
            sentTimes.pollFirst();
        }
    }

    /** 메일 본문 한 줄에 들어가도록 줄바꿈·제어 문자를 공백으로 바꾸고 길이를 제한한다. */
    static String cleanTitle(String title) {
        if (title == null) {
            return "";
        }
        String t = title.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return t.length() > MAX_TITLE_LENGTH ? t.substring(0, MAX_TITLE_LENGTH) + "…" : t;
    }

    /** 종류별 줄 + 링크. 채팅 본문·상대 닉네임·좌석은 넣지 않는다(공연 제목만). */
    static String buildBody(List<Pending> items, Map<Long, String> titles, String frontendBase) {
        Map<NotificationType, Integer> counts = new EnumMap<>(NotificationType.class);
        Map<NotificationType, Map<String, Integer>> byTitle = new EnumMap<>(NotificationType.class);
        for (Pending p : items) {
            counts.merge(p.type(), 1, Integer::sum);
            String title = titles.get(p.id());
            if (title != null && !title.isEmpty()) {
                byTitle.computeIfAbsent(p.type(), k -> new LinkedHashMap<>()).merge(title, 1, Integer::sum);
            }
        }
        StringBuilder sb = new StringBuilder("맞자리에 아직 확인하지 않은 알림이 있어요.\n\n");
        for (NotificationType type : List.of(NotificationType.PROPOSAL_RECEIVED, NotificationType.NEW_CANDIDATE,
                NotificationType.RESERVATION_PROPOSED, NotificationType.MATCH_RESERVED, NotificationType.RESERVATION_REJECTED,
                NotificationType.RESERVATION_CANCELED, NotificationType.CHAT_MESSAGE)) {
            Integer total = counts.get(type);
            if (total == null) {
                continue;
            }
            Map<String, Integer> titled = byTitle.getOrDefault(type, Collections.emptyMap());
            int untitled = total;
            for (Map.Entry<String, Integer> t : titled.entrySet()) {
                sb.append("- ").append(label(type, t.getValue())).append(" — ").append(t.getKey()).append('\n');
                untitled -= t.getValue();
            }
            if (untitled > 0) {
                sb.append("- ").append(label(type, untitled)).append('\n');
            }
        }
        sb.append("\n알림 보러 가기: ").append(frontendBase).append("/notifications\n\n")
                .append("알림 메일은 마이페이지 > 알림 설정에서 끌 수 있어요: ").append(frontendBase).append("/me\n");
        return sb.toString();
    }

    private static String label(NotificationType type, int n) {
        return switch (type) {
            case PROPOSAL_RECEIVED -> "새 교환 제안 " + n + "건";
            case NEW_CANDIDATE -> "새 교환 후보 " + n + "건";
            case RESERVATION_PROPOSED -> "예약 제안 " + n + "건";
            case MATCH_RESERVED -> "상대가 예약을 수락한 교환 " + n + "건";
            case RESERVATION_REJECTED -> "예약 제안 거절 " + n + "건";
            case RESERVATION_CANCELED -> "상대가 예약을 취소한 교환 " + n + "건";
            case CHAT_MESSAGE -> "새 채팅 메시지 " + n + "건";
        };
    }
}
