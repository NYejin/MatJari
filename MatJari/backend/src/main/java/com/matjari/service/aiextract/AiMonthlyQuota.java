package com.matjari.service.aiextract;

import java.time.LocalDateTime;

/**
 * 사용자당 한 달(KST 달력 월) AI 호출 횟수. 운영은 DB(ai_extract_usage, V24), 단위 시험·평가 도구는 메모리 구현을 쓴다.
 */
public interface AiMonthlyQuota {

    /** 이번 달(monthStart 이후) 반환되지 않은 사용 수. */
    int used(Long userId, LocalDateTime monthStart);

    /**
     * 남은 횟수가 있으면 한 건을 기록하고 그 id 를, 없으면 -1 을 돌려준다. 같은 사용자의 동시 요청이 상한을 넘지 않게
     * 사용자 단위로 직렬화한다.
     */
    long reserve(Long userId, String sourceKey, int limit, LocalDateTime monthStart, LocalDateTime now);

    /** AI 가 읽지 못한 호출의 횟수를 돌려준다(멱등). */
    void refund(long usageId, LocalDateTime now);

    /** 메모리 구현(단위 시험·평가 도구용, 재시작하면 사라진다). */
    final class InMemory implements AiMonthlyQuota {
        private record Usage(long id, Long userId, LocalDateTime usedAt, boolean refunded) {}

        private final java.util.List<Usage> usages = new java.util.ArrayList<>();

        @Override
        public synchronized int used(Long userId, LocalDateTime monthStart) {
            return (int) usages.stream()
                    .filter(u -> u.userId().equals(userId) && !u.usedAt().isBefore(monthStart) && !u.refunded())
                    .count();
        }

        @Override
        public synchronized long reserve(Long userId, String sourceKey, int limit, LocalDateTime monthStart, LocalDateTime now) {
            if (used(userId, monthStart) >= limit) {
                return -1;
            }
            long id = usages.size() + 1L;
            usages.add(new Usage(id, userId, now, false));
            return id;
        }

        @Override
        public synchronized void refund(long usageId, LocalDateTime now) {
            for (int i = 0; i < usages.size(); i++) {
                Usage u = usages.get(i);
                if (u.id() == usageId) {
                    usages.set(i, new Usage(u.id(), u.userId(), u.usedAt(), true));
                }
            }
        }
    }
}
