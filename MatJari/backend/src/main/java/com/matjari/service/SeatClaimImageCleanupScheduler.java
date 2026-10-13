package com.matjari.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 좌석 인증 사진 정리 작업({@link SeatClaimImageCleanupService})을 주기적으로 실행한다(기본 1시간마다, 기동 5분 뒤 첫 실행).
 * {@code matjari.upload.cleanup.enabled=false} 로 끌 수 있다. 서버 한 대 기준이다(여러 대면 같은 파일을 동시에 지우려 해도
 * 없는 파일 삭제는 성공으로 보므로 안전하다). 작업 실패는 서비스 안에서 삼키므로 스케줄이 멈추지 않는다.
 */
@Slf4j
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matjari.upload.cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class SeatClaimImageCleanupScheduler {

    private final SeatClaimImageCleanupService cleanupService;

    @Scheduled(fixedDelayString = "${matjari.upload.cleanup.interval-ms:3600000}",
            initialDelayString = "${matjari.upload.cleanup.initial-delay-ms:300000}")
    public void run() {
        cleanupService.cleanup();
    }
}
