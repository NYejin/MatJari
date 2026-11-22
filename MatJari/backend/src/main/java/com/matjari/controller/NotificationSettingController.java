package com.matjari.controller;

import com.matjari.dto.NotificationSettingDtos;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.service.NotificationSettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 알림 설정(메일 알림 켜기/끄기). 인증된 본인 것만 다룬다. */
@RestController
@RequestMapping("/api/users/me/notification-settings")
@RequiredArgsConstructor
public class NotificationSettingController {

    private final NotificationSettingService service;

    @GetMapping
    public ResponseEntity<NotificationSettingDtos.Response> get(@AuthenticationPrincipal AuthUserPrincipal principal) {
        return ResponseEntity.ok(service.get(principal.userId()));
    }

    /** emailEnabled 가 null 이면 400 emailEnabled. */
    @PatchMapping
    public ResponseEntity<NotificationSettingDtos.Response> update(@AuthenticationPrincipal AuthUserPrincipal principal,
                                                                   @Valid @RequestBody NotificationSettingDtos.UpdateRequest body) {
        return ResponseEntity.ok(service.update(principal.userId(), body.emailEnabled()));
    }
}
