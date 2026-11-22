package com.matjari.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@code @Scheduled} 작업(메일 알림 주기·사진 정리 등)용 스케줄러. 웹소켓 하트비트 스케줄러({@code wsHeartbeatScheduler}, 2스레드)와 분리해
 * 메일 발송처럼 오래 걸리는 작업이 하트비트·세션 정리를 막지 않게 한다. 이름이 {@code taskScheduler} 라
 * 스케줄러 빈이 둘 이상이어도 {@code @Scheduled} 가 이 빈을 쓴다.
 */
@Configuration
public class SchedulingConfig {

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
