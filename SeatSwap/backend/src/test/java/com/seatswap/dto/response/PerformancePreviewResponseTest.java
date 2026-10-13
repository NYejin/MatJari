package com.seatswap.dto.response;

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
    void oneDayAndTwoDayPeriodsGiveDateOnlyCandidates() {
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
    void longMissingOrReversedPeriodsGiveNoCandidates() {
        LocalDate d = LocalDate.of(2026, 10, 11);
        assertTrue(candidates(d, d.plusDays(2)).isEmpty());   // 3일 이상은 날짜로 회차를 알 수 없다
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
