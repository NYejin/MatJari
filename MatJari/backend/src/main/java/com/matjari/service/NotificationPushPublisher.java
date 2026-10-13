package com.matjari.service;

import com.matjari.domain.NotificationType;

/**
 * 알림 실시간 전파 자리(커밋 후, 트랜잭션 밖에서만 호출된다). 구현 실패는 저장에 영향을 주지 않으므로 호출하는 쪽이 예외를 삼키고 로그만 남긴다.
 * 푸시는 "새 알림이 생겼다"는 신호일 뿐이고, 화면은 이 신호를 받으면 REST 로 개수·목록을 다시 읽는다.
 */
public interface NotificationPushPublisher {

    void publishCreated(Long recipientUserId, Long notificationId, NotificationType type, Long matchId);
}
