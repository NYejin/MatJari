package com.matjari.service;

import com.matjari.exception.FieldValidationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import static com.matjari.service.PerformanceFixtures.KST;
import static com.matjari.service.PerformanceFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionTimePolicyTest {

    private static final String FIELD = "startsAt";
    private final SessionTimePolicy policy = PerformanceFixtures.timePolicy();

    private void assertRejected(Runnable call, String message) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(FieldValidationException.class, e -> {
            assertThat(e.getField()).isEqualTo(FIELD);
            assertThat(e.getMessage()).isEqualTo(message);
        });
    }

    @Test
    void pastAndOffStepReportsStepErrorFirst() {
        // 어제 19:03 - 과거이면서 10분 단위도 아님 → 단위 오류가 먼저
        LocalDateTime input = NOW.minusDays(1).withHour(19).withMinute(3);
        assertRejected(() -> policy.normalize(input, FIELD), SessionTimePolicy.STEP_MESSAGE);
    }

    @Test
    void pastButOnStepReportsPastError() {
        assertRejected(() -> policy.normalize(NOW.minusDays(1).withHour(19).withMinute(0), FIELD),
                SessionTimePolicy.PAST_MESSAGE);
    }

    @Test
    void normalizeAllReportsStepErrorFirstOnField() {
        assertThatThrownBy(() -> policy.normalizeAll(List.of(NOW.minusDays(1).withHour(19).withMinute(3)), "sessions"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("sessions");
                    assertThat(e.getMessage()).isEqualTo(SessionTimePolicy.STEP_MESSAGE);
                });
    }

    @Test
    void minuteBoundaries() {
        LocalDateTime base = NOW.plusDays(3).withHour(19);
        for (int minute : new int[]{20, 30, 40}) {
            assertThat(policy.normalize(base.withMinute(minute), FIELD)).isEqualTo(base.withMinute(minute));
        }
        for (int minute : new int[]{19, 21, 29, 31, 39, 41}) {
            assertRejected(() -> policy.normalize(base.withMinute(minute), FIELD), SessionTimePolicy.STEP_MESSAGE);
        }
    }

    @Test
    void nullInputIsRejectedWithFieldMessage() {
        assertRejected(() -> policy.normalize(null, FIELD), "회차 일시를 입력해주세요.");
    }

    @Test
    void whenNowIsExactlyOnStepCurrentMinuteIsAllowedAndPreviousIsStepError() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 6, 19, 10, 30);
        SessionTimePolicy atNow = new SessionTimePolicy(Clock.fixed(now.atZone(KST).toInstant(), KST));

        // 19:10 (현재 분 절삭값과 같음) → 허용
        assertThat(atNow.normalize(LocalDateTime.of(2026, 10, 6, 19, 10), FIELD))
                .isEqualTo(LocalDateTime.of(2026, 10, 6, 19, 10));
        // 직전 19:09 → 과거이지만 단위 오류가 먼저
        assertRejected(() -> atNow.normalize(LocalDateTime.of(2026, 10, 6, 19, 9), FIELD),
                SessionTimePolicy.STEP_MESSAGE);
        // 19:00 → 단위는 맞고 과거
        assertRejected(() -> atNow.normalize(LocalDateTime.of(2026, 10, 6, 19, 0), FIELD),
                SessionTimePolicy.PAST_MESSAGE);
    }
}
