package com.matjari.service.mail;

import com.matjari.service.social.SocialProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 메일을 실제로 보낼 수 있는 설정인가. log 모드(개발용: 메일 내용이 로그에 남는다)이면서 백엔드 주소가 루프백이 아니면(운영) 보낼 수 없는 설정이다.
 * 비밀번호 재설정({@code PasswordResetService})과 같은 판정이며, 알림 메일 스캔·내 이메일 인증번호 발송이 이 값을 쓴다.
 */
@Component
public class MailDeliveryPolicy {

    private final boolean unavailable;

    @Autowired
    public MailDeliveryPolicy(@Value("${matjari.mail.mode:log}") String mailMode, SocialProperties socialProperties) {
        this(mailMode, socialProperties.getBackendBaseUrl());
    }

    public MailDeliveryPolicy(String mailMode, String backendBaseUrl) {
        boolean logMode = "log".equalsIgnoreCase(mailMode == null ? "log" : mailMode.trim());
        this.unavailable = logMode && !EmailSenderConfig.isLoopback(backendBaseUrl);
    }

    /** log 모드 + 비루프백 백엔드면 false: 메일을 보내면 내용이 로그로만 남으므로 보내지 않는다. */
    public boolean canSendRealMail() {
        return !unavailable;
    }
}
