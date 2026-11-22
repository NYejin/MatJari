package com.matjari.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 마지막 메시지 미리보기 자르기: 60 코드 포인트, 줄바꿈은 공백 하나, 서로게이트 쌍을 가르지 않는다. */
class ExchangeMatchPreviewTest {

    @Test
    void 없으면_null이고_짧으면_그대로() {
        assertThat(ExchangeMatchQueryRepository.preview(null)).isNull();
        assertThat(ExchangeMatchQueryRepository.preview("안녕하세요")).isEqualTo("안녕하세요");
    }

    @Test
    void 정확히_60자는_그대로_61자부터_자르고_말줄임표() {
        String s60 = "가".repeat(60);
        assertThat(ExchangeMatchQueryRepository.preview(s60)).isEqualTo(s60);
        assertThat(ExchangeMatchQueryRepository.preview(s60 + "나")).isEqualTo(s60 + "…");
        assertThat(ExchangeMatchQueryRepository.preview("가".repeat(200))).isEqualTo(s60 + "…");
    }

    @Test
    void 이모지_서로게이트_쌍은_한_글자로_세고_중간에서_자르지_않는다() {
        String emoji = "😀"; // UTF-16 2칸, 코드 포인트 1
        String s60 = emoji.repeat(60);
        assertThat(ExchangeMatchQueryRepository.preview(s60)).isEqualTo(s60);
        String cut = ExchangeMatchQueryRepository.preview(s60 + emoji);
        assertThat(cut).isEqualTo(s60 + "…");
        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(61);
    }

    @Test
    void 줄바꿈과_연속_공백은_공백_하나로() {
        assertThat(ExchangeMatchQueryRepository.preview("첫 줄\r\n둘째 줄\n\n  셋째\t줄 ")).isEqualTo("첫 줄 둘째 줄 셋째 줄");
    }
}
