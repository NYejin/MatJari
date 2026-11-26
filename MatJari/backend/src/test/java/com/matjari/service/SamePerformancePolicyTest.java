package com.matjari.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SamePerformancePolicyTest {

    @Test
    void 공연장_이름은_NFKC_공백_대소문자_괄호_부연을_무시한다() {
        assertThat(SamePerformancePolicy.sameVenue("코엑스 D홀 (COEX HALL D)", "코엑스 D홀")).isTrue();
        assertThat(SamePerformancePolicy.sameVenue("KSPO DOME", "kspo  dome")).isTrue();
        assertThat(SamePerformancePolicy.sameVenue("ＫＳＰＯ ＤＯＭＥ（체조경기장）", "KSPO DOME")).isTrue();
        assertThat(SamePerformancePolicy.sameVenue("인스파이어 아레나", "인스파이어아레나")).isTrue();
    }

    @Test
    void 한쪽이_다른쪽을_포함해도_같은_공연장이지만_너무_짧으면_포함_일치를_쓰지_않는다() {
        assertThat(SamePerformancePolicy.sameVenue("올림픽공원 올림픽홀", "올림픽홀")).isTrue();
        assertThat(SamePerformancePolicy.sameVenue("세종문화회관 대극장", "세종문화회관")).isTrue();
        assertThat(SamePerformancePolicy.sameVenue("올림픽홀", "홀")).isFalse(); // 1자는 포함 일치 불가
        assertThat(SamePerformancePolicy.sameVenue("올림픽홀", "KSPO DOME")).isFalse();
        assertThat(SamePerformancePolicy.sameVenue(null, "KSPO DOME")).isFalse();
        assertThat(SamePerformancePolicy.sameVenue("  ", "KSPO DOME")).isFalse();
    }

    @Test
    void 괄호만_있는_공연장도_비교할_수_있다() {
        assertThat(SamePerformancePolicy.venueKey("(주)")).isEqualTo("주");
        assertThat(SamePerformancePolicy.sameVenue("(KSPO DOME)", "KSPO DOME")).isTrue();
    }

    @Test
    void 기간과_회차_범위는_하루라도_겹치면_겹침이다() {
        LocalDateTime first = LocalDateTime.of(2026, 12, 10, 19, 0);
        LocalDateTime last = LocalDateTime.of(2026, 12, 20, 19, 0);
        assertThat(SamePerformancePolicy.periodOverlaps(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 10), first, last)).isTrue();
        assertThat(SamePerformancePolicy.periodOverlaps(LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 31), first, last)).isTrue();
        assertThat(SamePerformancePolicy.periodOverlaps(LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 13), first, last)).isTrue();
        assertThat(SamePerformancePolicy.periodOverlaps(LocalDate.of(2026, 12, 21), LocalDate.of(2026, 12, 31), first, last)).isFalse();
        assertThat(SamePerformancePolicy.periodOverlaps(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 9), first, last)).isFalse();
        // 한쪽만 있으면 그 하루, 둘 다 없으면 판정 불가
        assertThat(SamePerformancePolicy.periodOverlaps(LocalDate.of(2026, 12, 15), null, first, last)).isTrue();
        assertThat(SamePerformancePolicy.periodOverlaps(null, LocalDate.of(2026, 12, 25), first, last)).isFalse();
        assertThat(SamePerformancePolicy.periodOverlaps(null, null, first, last)).isFalse();
    }

    @Test
    void 제목_유사도는_연도_기호_공백_차이에_강하다() {
        assertThat(SamePerformancePolicy.titleSimilarity("2026 아이유 콘서트 HEAVEN", "[아이유] 콘서트 HEAVEN - 서울"))
                .isGreaterThanOrEqualTo(SamePerformancePolicy.TITLE_THRESHOLD);
        assertThat(SamePerformancePolicy.titleSimilarity("아이유콘서트", "아이유 콘서트")).isGreaterThan(0.8);
        assertThat(SamePerformancePolicy.titleSimilarity("두아 리파 내한공연", "아이유 콘서트")).isLessThan(SamePerformancePolicy.TITLE_THRESHOLD);
        assertThat(SamePerformancePolicy.titleSimilarity(null, "아이유")).isZero();
        assertThat(SamePerformancePolicy.titleSimilarity("2026", "2026")).isZero(); // 연도뿐인 제목은 비교 불가
    }
}
