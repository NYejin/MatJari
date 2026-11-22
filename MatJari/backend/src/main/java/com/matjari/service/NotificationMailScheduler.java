package com.matjari.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 메일 알림({@link NotificationMailService}) 주기 실행(기본 60초마다, 기동 1분 뒤 첫 실행). {@code matjari.notification-mail.enabled=false} 로 끈다.
 * 서버 한 대 기준이다(서비스 Javadoc 한계 참고). 서비스가 예외를 삼키므로 스케줄이 멈추지 않는다.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matjari.notification-mail.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationMailScheduler {

    private final NotificationMailService mailService;

    @Scheduled(fixedDelayString = "${matjari.notification-mail.interval-ms:60000}",
            initialDelayString = "${matjari.notification-mail.initial-delay-ms:60000}")
    public void run() {
        mailService.runOnce();
    }
}
