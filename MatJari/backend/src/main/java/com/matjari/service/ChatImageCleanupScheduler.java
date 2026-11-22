package com.matjari.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 채팅 사진 보관 정리({@link ChatImageCleanupService})를 주기적으로 실행한다(기본 1시간마다, 기동 5분 뒤 첫 실행).
 * {@code matjari.chat.image-cleanup.enabled=false} 로 끌 수 있다. 서버 한 대 기준이다(여러 대가 동시에 돌아도 없는 파일 삭제는 성공으로 보고
 * image_deleted_at 갱신은 NULL 일 때만 반영되므로 안전하다). 작업 실패는 서비스 안에서 삼키므로 스케줄이 멈추지 않는다.
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matjari.chat.image-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class ChatImageCleanupScheduler {

    private final ChatImageCleanupService cleanupService;

    @Scheduled(fixedDelayString = "${matjari.chat.image-cleanup.interval-ms:3600000}",
            initialDelayString = "${matjari.chat.image-cleanup.initial-delay-ms:300000}")
    public void run() {
        cleanupService.cleanup();
    }
}
