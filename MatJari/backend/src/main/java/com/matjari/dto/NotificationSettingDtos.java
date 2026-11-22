package com.matjari.dto;

import jakarta.validation.constraints.NotNull;

/** 알림 설정 API(GET·PATCH /api/users/me/notification-settings) 요청·응답. */
public final class NotificationSettingDtos {

    private NotificationSettingDtos() {
    }

    /** PATCH 본문. null 이면 400 emailEnabled. */
    public record UpdateRequest(@NotNull(message = "emailEnabled를 입력해주세요.") Boolean emailEnabled) {
    }

    /**
     * emailAvailable=false 면 자리표시 이메일(소셜 가입) 계정이라 메일을 받을 수 없다(emailEnabled 값과 무관하게 발송하지 않는다).
     * emailVerified=false 면 이메일 소유가 확인되지 않아 알림 메일이 나가지 않는다(이메일 확인 API 로 인증하면 true).
     */
    public record Response(boolean emailEnabled, boolean emailAvailable, boolean emailVerified) {
    }

    /** POST /api/users/me/email-verification/confirm 본문. 비어 있거나 형식이 틀리면 400 code. */
    public record ConfirmRequest(String code) {
    }
}
