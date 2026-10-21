package com.matjari.service.aiextract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiCallLogTest {

    @Test
    void costUsesShortOrLongPromptPrices() {
        AiProperties p = new AiProperties(); // Haiku 5.5: $0.10/$0.50, 10만 토큰 초과는 $0.50/$2.50
        // 4만 입력 + 2천 출력 = 0.004 + 0.001 = $0.005 = 5,000 마이크로 달러
        assertThat(AiCallLog.costMicroUsd(40_000, 2_000, p)).isEqualTo(5_000);
        // 12만 입력 + 1천 출력 = 0.06 + 0.0025 = $0.0625
        assertThat(AiCallLog.costMicroUsd(120_000, 1_000, p)).isEqualTo(62_500);
    }
}
