package com.matjari.controller;

import com.matjari.dto.NotificationSettingDtos;
import com.matjari.security.AuthUserPrincipal;
import com.matjari.security.ClientIpResolver;
import com.matjari.service.NotificationSettingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계정 이메일 소유 확인(알림 메일 수신 확인, V29). 로그인한 본인 것만 다룬다.
 * 코드 발송은 204, 확인은 200 + 알림 설정 응답({emailEnabled, emailAvailable, emailVerified}).
 */
@RestController
@RequestMapping("/api/users/me/email-verification")
@RequiredArgsConstructor
public class AccountEmailVerificationController {

    private final NotificationSettingService service;
    private final ClientIpResolver clientIpResolver;

    /** 계정 이메일로 6자리 인증번호를 보낸다. */
    @PostMapping("/code")
    public ResponseEntity<Void> sendCode(@AuthenticationPrincipal AuthUserPrincipal principal, HttpServletRequest http) {
        service.sendVerificationCode(principal.userId(), clientIpResolver.resolve(http));
        return ResponseEntity.noContent().build();
    }

    /** 인증번호를 확인한다. 틀림·만료는 400 code. 본문이 비어 있어도 400 code 로 응답한다. */
    @PostMapping("/confirm")
    public ResponseEntity<NotificationSettingDtos.Response> confirm(@AuthenticationPrincipal AuthUserPrincipal principal,
                                                                    @RequestBody NotificationSettingDtos.ConfirmRequest body) {
        return ResponseEntity.ok(service.confirmVerification(principal.userId(), body == null ? null : body.code()));
    }
}
