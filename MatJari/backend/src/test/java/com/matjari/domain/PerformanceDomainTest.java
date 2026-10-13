package com.matjari.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 공연장 이름 정리 규칙, 공연/회차 생성 규칙. */
class PerformanceDomainTest {

    @Test
    void 공연장_이름은_공백만_정리해_저장한다() {
        User registrant = User.create("a@b.com", "encoded", "닉네임");

        Performance performance = Performance.create("  KSPO   DOME　 ", "콘서트",
                "https://example.com/goods/1", "example:1", registrant);

        assertThat(performance.getVenueName()).isEqualTo("KSPO DOME");
        // 전각 통일·구두점 제거 같은 정규화는 하지 않는다
        assertThat(Performance.create("ＫＳＰＯ (돔)", "콘서트", "https://example.com/goods/1", "example:1", registrant)
                .getVenueName()).isEqualTo("ＫＳＰＯ (돔)");
    }

    @Test
    void 공연장_이름이_비었거나_100자를_넘으면_생성할_수_없다() {
        User registrant = User.create("a@b.com", "encoded", "닉네임");
        assertThatThrownBy(() -> Performance.create("   ", "t", "https://x", "x", registrant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Performance.create(null, "t", "https://x", "x", registrant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Performance.create("가".repeat(Performance.VENUE_NAME_MAX_LENGTH + 1), "t",
                "https://x", "x", registrant)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Performance.create("가".repeat(Performance.VENUE_NAME_MAX_LENGTH), "t", "https://x", "x",
                registrant).getVenueName()).hasSize(100);
    }

    @Test
    void 공연_생성과_등록자_판정() {
        User registrant = User.create("a@b.com", "encoded", "닉네임");
        Performance performance = Performance.create("KSPO DOME", "  콘서트  ", " https://example.com/goods/1 ",
                "example:1", registrant);

        assertThat(performance.getTitle()).isEqualTo("콘서트");
        assertThat(performance.getSourceUrl()).isEqualTo("https://example.com/goods/1");
        assertThat(performance.getSourceKey()).isEqualTo("example:1");
        assertThat(performance.isRegisteredBy(null)).isFalse();
        assertThatThrownBy(() -> Performance.create("KSPO DOME", " ", "https://x", "x", registrant))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Performance.create("KSPO DOME", "t", "https://x", " ", registrant))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 회차_일시는_분_단위로_잘린다() {
        User registrant = User.create("a@b.com", "encoded", "닉네임");
        Performance performance = Performance.create("KSPO DOME", "콘서트",
                "https://example.com/goods/1", "example:1", registrant);

        PerformanceSession session = PerformanceSession.create(performance,
                LocalDateTime.of(2026, 12, 24, 19, 0, 30, 123));

        assertThat(session.getStartsAt()).isEqualTo(LocalDateTime.of(2026, 12, 24, 19, 0));
        assertThatThrownBy(() -> PerformanceSession.create(performance, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승인된_수정은_null이_아닌_값만_바꾸고_정리한다() {
        User registrant = User.create("a@b.com", "encoded", "닉네임");
        Performance performance = Performance.create("KSPO DOME", "콘서트",
                "https://example.com/goods/1", "example:1", registrant);

        performance.applyApprovedEdit("  새   제목 ", null);
        assertThat(performance.getTitle()).isEqualTo("새 제목");
        assertThat(performance.getVenueName()).isEqualTo("KSPO DOME");

        performance.applyApprovedEdit(null, " 올림픽홀 ");
        assertThat(performance.getTitle()).isEqualTo("새 제목");
        assertThat(performance.getVenueName()).isEqualTo("올림픽홀");

        assertThatThrownBy(() -> performance.applyApprovedEdit(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> performance.applyApprovedEdit("가".repeat(201), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(performance.getTitle()).isEqualTo("새 제목");
    }
}
