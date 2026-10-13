package com.matjari.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 서비스에서 "현재 시각"을 얻는 Clock. 회차(starts_at)는 KST 벽시계 시각으로 저장하므로
 * 과거 회차 판정도 Asia/Seoul 기준이어야 한다 (컨테이너 TZ가 UTC여도 동일하게 동작).
 * 테스트에서는 Clock.fixed로 대체한다.
 */
@Configuration
public class ClockConfig {

    public static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(SERVICE_ZONE);
    }
}
