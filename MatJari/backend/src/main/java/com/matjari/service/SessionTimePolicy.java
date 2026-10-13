package com.matjari.service;

import com.matjari.domain.PerformanceSession;
import com.matjari.exception.FieldValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 회차 일시 규칙.
 * - 분 단위 절삭 (unique 제약 판정과 일치시키기 위해 PerformanceSession.truncate 사용)
 * - 10분 단위만 허용: 절삭 후 분이 10의 배수가 아니면 400 (공연 등록·회차 추가·회차 수정 공통).
 *   이미 저장된 회차는 검사하지 않는다 (조회·삭제 그대로, 수정할 때만 새 규칙 적용).
 * - 지난 일시 거부: 지난 공연의 좌석 교환은 의미가 없으므로 생성·변경 시 현재(KST) 이전 시각은 400.
 *   이미 저장된 과거 회차는 그대로 두고 조회에도 포함한다.
 */
@Component
@RequiredArgsConstructor
public class SessionTimePolicy {

    static final String PAST_MESSAGE = "지난 일시는 등록할 수 없습니다.";
    static final String STEP_MESSAGE = "회차 시각은 10분 단위로 입력해주세요.";
    static final int MINUTE_STEP = 10;

    private final Clock clock;

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** 단일 회차: 분 절삭(초 무시) + 10분 단위 검사 + 과거 거부. */
    public LocalDateTime normalize(LocalDateTime startsAt, String field) {
        if (startsAt == null) {
            throw new FieldValidationException(field, "회차 일시를 입력해주세요.");
        }
        LocalDateTime truncated = PerformanceSession.truncate(startsAt);
        if (truncated.getMinute() % MINUTE_STEP != 0) {
            throw new FieldValidationException(field, STEP_MESSAGE);
        }
        if (truncated.isBefore(PerformanceSession.truncate(now()))) {
            throw new FieldValidationException(field, PAST_MESSAGE);
        }
        return truncated;
    }

    /** 여러 회차: 각각 절삭 + 10분 단위 검사 + 과거 거부(하나라도 위반이면 400), 같은 시각(절삭 후)은 하나로 합치고 오름차순 정렬. */
    public List<LocalDateTime> normalizeAll(Collection<LocalDateTime> startsAts, String field) {
        if (startsAts == null || startsAts.isEmpty()) {
            throw new FieldValidationException(field, "회차를 1개 이상 입력해주세요.");
        }
        return startsAts.stream()
                .map(t -> normalize(t, field))
                .distinct()
                .sorted()
                .toList();
    }
}
