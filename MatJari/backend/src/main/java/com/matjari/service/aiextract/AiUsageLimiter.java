package com.matjari.service.aiextract;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 호출 제한(2026-10-21): 사용자당 한 달(KST 달력 월) 횟수(matjari.ai.monthly-per-user, DB 기록), 전체 하루 상한(메모리),
 * 동시 실행 수(대기 없이 초과 시 거절). 보관 결과 반환처럼 AI 를 부르지 않는 경우는 호출하지 않는다.
 * 허락받은 시점에 횟수 1을 세고, AI 가 읽지 못하면 호출한 쪽이 Permit.refund() 로 돌려준다(월 횟수·전체 횟수 모두).
 */
@Component
public class AiUsageLimiter {

    static final String MONTHLY_LIMIT_CODE = "AI_MONTHLY_LIMIT_REACHED";

    /** 허락 한 건. close()로 동시 실행 자리를 반납한다. */
    public interface Permit extends AutoCloseable {
        /** AI 가 읽지 못한 호출의 횟수를 돌려준다(멱등). */
        void refund();

        @Override
        void close();
    }

    /** 이번 달 사용 현황. resetsAt 은 다음 달 1일 0시(KST). */
    /** aiAvailable: false 면 AI 를 쓸 수 없다(꺼짐·키 없음·크레딧 소진). 화면은 AI 관련 UI 없이 직접 입력만 보인다. */
    /** unlimited: 관리자 — 월 횟수를 세지 않는다(전체 하루 상한만). */
    public record Quota(int limit, int used, int remaining, LocalDateTime resetsAt, boolean aiAvailable, boolean unlimited) {
        public Quota(int limit, int used, int remaining, LocalDateTime resetsAt) {
            this(limit, used, remaining, resetsAt, true, false);
        }

        Quota withAiAvailable(boolean available) {
            return new Quota(limit, used, remaining, resetsAt, available, unlimited);
        }

        Quota asUnlimited() {
            return new Quota(limit, used, Math.max(remaining, limit), resetsAt, aiAvailable, true);
        }
    }

    private final Clock clock;
    private final AiProperties properties;
    private final AiMonthlyQuota monthly;
    private final Semaphore concurrency;
    private LocalDate day;
    private int global;

    /** 단위 시험·평가 도구용: 월 횟수를 메모리에 둔다. */
    public AiUsageLimiter(Clock clock, AiProperties properties) {
        this(clock, properties, new AiMonthlyQuota.InMemory());
    }

    @Autowired
    public AiUsageLimiter(Clock clock, AiProperties properties, AiMonthlyQuota monthly) {
        this.clock = clock;
        this.properties = properties;
        this.monthly = monthly;
        this.concurrency = new Semaphore(Math.max(1, properties.getMaxConcurrent()));
    }

    public Quota quota(Long userId) {
        LocalDateTime monthStart = monthStart();
        int limit = properties.getMonthlyPerUser();
        int used = monthly.used(userId, monthStart);
        return new Quota(limit, used, Math.max(0, limit - used), monthStart.plusMonths(1));
    }

    /** 허락하면 Permit, 아니면 429(전체·동시 AI_RATE_LIMITED, 월 횟수 AI_MONTHLY_LIMIT_REACHED). */
    public Permit acquire(Long userId, String sourceKey) {
        return acquire(userId, sourceKey, true);
    }

    /**
     * 관리자 다시 인식(공연 관리): 월 횟수는 세지 않고 전체 하루 상한·동시 실행 상한만 적용한다(비용은 ai_call_log 에 그대로 남는다).
     */
    public Permit acquireAdmin(String sourceKey) {
        return acquire(null, sourceKey, false);
    }

    private Permit acquire(Long userId, String sourceKey, boolean countMonthly) {
        if (!concurrency.tryAcquire()) {
            throw DetailExtractionService.rateLimited(10);
        }
        boolean ok = false;
        long usageId;
        try {
            ZonedDateTime now = ZonedDateTime.now(clock);
            synchronized (this) {
                LocalDate today = now.toLocalDate();
                if (!today.equals(day)) {
                    day = today;
                    global = 0;
                }
                if (global >= properties.getDailyGlobal()) {
                    long untilMidnight = Math.max(1, Duration.between(now, today.plusDays(1).atStartOfDay(now.getZone())).toSeconds());
                    throw DetailExtractionService.rateLimited(untilMidnight);
                }
                global++;
            }
            LocalDateTime monthStart = monthStart();
            usageId = countMonthly ? monthly.reserve(userId, sourceKey, properties.getMonthlyPerUser(), monthStart, now.toLocalDateTime()) : 0;
            if (usageId < 0) {
                releaseGlobal();
                long untilNextMonth = Math.max(1, Duration.between(now.toLocalDateTime(), monthStart.plusMonths(1)).toSeconds());
                throw new DetailExtractionService.AiExtractException(429, MONTHLY_LIMIT_CODE,
                        "이번 달 공연정보 불러오기 횟수(" + properties.getMonthlyPerUser() + "회)를 모두 사용했어요. 직접 입력해 주세요.",
                        untilNextMonth);
            }
            ok = true;
        } catch (RuntimeException e) {
            if (!(e instanceof DetailExtractionService.AiExtractException)) {
                releaseGlobal(); // 기록 실패(DB 오류 등)는 전체 횟수만 되돌리고 그대로 던진다
            }
            throw e;
        } finally {
            if (!ok) {
                concurrency.release();
            }
        }
        AtomicBoolean closed = new AtomicBoolean();
        AtomicBoolean refunded = new AtomicBoolean();
        return new Permit() {
            @Override
            public void refund() {
                if (refunded.compareAndSet(false, true)) {
                    if (countMonthly) {
                        monthly.refund(usageId, LocalDateTime.now(clock));
                    }
                    releaseGlobal();
                }
            }

            @Override
            public void close() {
                if (closed.compareAndSet(false, true)) {
                    concurrency.release();
                }
            }
        };
    }

    /** 오늘(KST) 전체 AI 호출 수(메모리, 재시작하면 0). 관리자 대시보드용. */
    public synchronized int globalUsedToday() {
        return LocalDate.now(clock).equals(day) ? global : 0;
    }

    private synchronized void releaseGlobal() {
        if (global > 0) {
            global--;
        }
    }

    private LocalDateTime monthStart() {
        return LocalDate.now(clock).withDayOfMonth(1).atStartOfDay();
    }
}
