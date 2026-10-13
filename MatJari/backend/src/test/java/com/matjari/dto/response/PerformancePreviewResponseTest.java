package com.matjari.dto.response;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformancePreviewResponseTest {

    private static List<PerformancePreviewResponse.SessionCandidate> candidates(LocalDate s, LocalDate e) {
        return PerformancePreviewResponse.ok("yes24", "t", "v", s, e, null).sessionCandidates();
    }

    @Test
    void consecutivePeriodsGiveEveryDateAsDateOnlyCandidates() {
        LocalDate d = LocalDate.of(2026, 10, 11);
        var one = candidates(d, d);
        assertEquals(1, one.size());
        assertEquals(d, one.get(0).date());
        assertNull(one.get(0).time());

        var two = candidates(d, d.plusDays(1));
        assertEquals(List.of(d, d.plusDays(1)), two.stream().map(PerformancePreviewResponse.SessionCandidate::date).toList());
        two.forEach(c -> assertNull(c.time()));
    }

    @Test
    void weekLongAndMaximumPeriodsGiveEveryDateInOrder() {
        LocalDate d = LocalDate.of(2026, 10, 30);   // 달·연 경계를 넘는 기간도 날짜 그대로 이어진다
        var week = candidates(d, d.plusDays(6));
        assertEquals(7, week.size());
        assertEquals(d, week.get(0).date());
        assertEquals(LocalDate.of(2026, 11, 5), week.get(6).date());
        week.forEach(c -> assertNull(c.time()));

        int max = PerformancePreviewResponse.MAX_PERIOD_DAYS_FOR_CANDIDATES;
        var longest = candidates(d, d.plusDays(max - 1L));   // 시작·종료 포함 정확히 상한 일수
        assertEquals(max, longest.size());
        assertEquals(d.plusDays(max - 1L), longest.get(max - 1).date());
    }

    @Test
    void longMissingOrReversedPeriodsGiveNoCandidates() {
        LocalDate d = LocalDate.of(2026, 10, 11);
        // 공연 등록 한 번에 넣을 수 있는 회차 수(100)를 넘는 기간은 후보를 만들지 않는다
        assertTrue(candidates(d, d.plusDays(PerformancePreviewResponse.MAX_PERIOD_DAYS_FOR_CANDIDATES)).isEmpty());
        assertTrue(candidates(d, null).isEmpty());
        assertTrue(candidates(null, d).isEmpty());
        assertTrue(candidates(d.plusDays(1), d).isEmpty());
        assertTrue(PerformancePreviewResponse.empty("NO_INFO", null).sessionCandidates().isEmpty());
    }

    @Test
    void withoutPosterKeepsCandidates() {
        LocalDate d = LocalDate.of(2026, 10, 11);
        var r = PerformancePreviewResponse.ok("yes24", "t", "v", d, d, "https://tkfile.yes24.com/p.jpg").withoutPoster();
        assertNull(r.posterUrl());
        assertEquals(1, r.sessionCandidates().size());
    }
}
