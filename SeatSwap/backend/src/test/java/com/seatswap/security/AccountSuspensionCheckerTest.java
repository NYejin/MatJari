package com.seatswap.security;

import com.seatswap.domain.UserSanction;
import com.seatswap.exception.AccountSuspendedException;
import com.seatswap.repository.UserSanctionRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountSuspensionCheckerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 12, 0, 0);

    private final UserSanctionRepository repository = mock(UserSanctionRepository.class);
    private final AccountSuspensionChecker checker = new AccountSuspensionChecker(repository, Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE));

    private static UserSanction sanction(Integer days) {
        return UserSanction.suspend(1L, null, "사유", NOW.minusDays(1), days, 99L);
    }

    @Test
    void 활성_제재가_없으면_통과한다() {
        when(repository.findActive(1L, NOW)).thenReturn(List.of());
        assertThatCode(() -> checker.assertNotSuspended(1L)).doesNotThrowAnyException();
    }

    @Test
    void 기간제는_끝나는_시각을_무기한은_null을_알려준다() {
        when(repository.findActive(1L, NOW)).thenReturn(List.of(sanction(7)));
        assertThatThrownBy(() -> checker.assertNotSuspended(1L))
                .isInstanceOfSatisfying(AccountSuspendedException.class, e -> assertThat(e.getUntil()).isEqualTo(NOW.minusDays(1).plusDays(7)));

        when(repository.findActive(1L, NOW)).thenReturn(List.of(sanction(null)));
        assertThatThrownBy(() -> checker.assertNotSuspended(1L))
                .isInstanceOfSatisfying(AccountSuspendedException.class, e -> assertThat(e.getUntil()).isNull());
    }

    @Test
    void 여러_건이_겹치면_무기한이_우선이고_아니면_가장_늦은_시각이다() {
        assertThat(AccountSuspensionChecker.untilOf(List.of(sanction(7), sanction(30)))).isEqualTo(NOW.minusDays(1).plusDays(30));
        assertThat(AccountSuspensionChecker.untilOf(List.of(sanction(7), sanction(null)))).isNull();
    }

    @Test
    void 응답_본문은_message_code_until_세_키다() {
        assertThat(new AccountSuspendedException(NOW).toBody()).containsExactly(
                Map.entry("message", "이용이 정지된 계정입니다."), Map.entry("code", "ACCOUNT_SUSPENDED"),
                Map.entry("until", "2026-10-12T12:00:00"));
    }
}
