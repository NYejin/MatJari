package com.matjari.security;

import com.matjari.domain.UserSanction;
import com.matjari.exception.AccountSuspendedException;
import com.matjari.repository.UserSanctionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 이용 정지 강제 지점. 요청마다의 JWT 인증({@link CustomUserDetailsService#loadUserById})·로그인·토큰 재발급·STOMP CONNECT 가 같은 검사를 쓴다.
 * 활성 제재가 있으면 {@link AccountSuspendedException}(403 ACCOUNT_SUSPENDED). 기간 만료는 조회 시 ends_at > now 로 판단하므로 스케줄러가 없다.
 * 잠금 없는 일반 SELECT 한 번(idx_user_sanction_user)이다.
 */
@Component
@RequiredArgsConstructor
public class AccountSuspensionChecker {

    private final UserSanctionRepository sanctionRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public void assertNotSuspended(Long userId) {
        List<UserSanction> active = sanctionRepository.findActive(userId, LocalDateTime.now(clock));
        if (!active.isEmpty()) {
            throw new AccountSuspendedException(untilOf(active));
        }
    }

    /** 활성 제재들이 끝나는 시각: 하나라도 무기한이면 null, 아니면 가장 늦은 ends_at. */
    public static LocalDateTime untilOf(List<UserSanction> active) {
        LocalDateTime until = null;
        for (UserSanction s : active) {
            if (s.getEndsAt() == null) {
                return null;
            }
            if (until == null || s.getEndsAt().isAfter(until)) {
                until = s.getEndsAt();
            }
        }
        return until;
    }
}
