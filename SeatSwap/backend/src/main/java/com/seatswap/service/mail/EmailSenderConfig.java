package com.seatswap.service.mail;

import com.seatswap.service.social.SocialProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 메일 발송 수단 선택: seatswap.mail.mode = log(기본, 개발용: 메일 내용을 로그에 남김) | smtp(실제 발송, SPRING_MAIL_* 필요).
 * 로그 모드는 인증번호가 로그에 남으므로 운영(백엔드 주소가 루프백이 아님)에서는 기동을 막는다.
 */
@Slf4j
@Configuration
public class EmailSenderConfig {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");

    @Bean
    EmailSender emailSender(@Value("${seatswap.mail.mode:log}") String mode,
                            @Value("${seatswap.mail.from:}") String from,
                            @Value("${spring.mail.username:}") String username,
                            @Value("${seatswap.email-verification.enabled:true}") boolean verificationEnabled,
                            ObjectProvider<JavaMailSender> mailSender,
                            SocialProperties social) {
        String m = mode == null ? "log" : mode.trim().toLowerCase(Locale.ROOT);
        if (m.equals("smtp")) {
            JavaMailSender sender = mailSender.getIfAvailable();
            if (sender == null) {
                throw new IllegalStateException("seatswap.mail.mode=smtp 이면 SPRING_MAIL_HOST 등 SMTP 설정이 필요합니다.");
            }
            String sendFrom = from == null || from.isBlank() ? username : from.trim();
            if (sendFrom == null || sendFrom.isBlank()) {
                throw new IllegalStateException("보내는 주소가 없습니다. SEATSWAP_MAIL_FROM 또는 SPRING_MAIL_USERNAME 을 설정하세요.");
            }
            return (to, subject, text) -> {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(sendFrom);
                msg.setTo(to);
                msg.setSubject(subject);
                msg.setText(text);
                try {
                    sender.send(msg);
                } catch (MailException e) {
                    // 수신 주소·본문은 로그에 남기지 않는다
                    throw new EmailSender.EmailSendException("메일 발송에 실패했습니다: " + e.getClass().getSimpleName(), e);
                }
            };
        }
        if (!m.equals("log")) {
            throw new IllegalStateException("seatswap.mail.mode 는 log 또는 smtp 여야 합니다: " + m);
        }
        if (verificationEnabled && !isLoopback(social.getBackendBaseUrl())) {
            throw new IllegalStateException("운영(비루프백 백엔드 주소)에서는 seatswap.mail.mode=smtp 가 필요합니다. "
                    + "개발용 log 모드는 인증번호를 로그에 남깁니다. (인증을 끄려면 SEATSWAP_EMAIL_VERIFICATION_ENABLED=false)");
        }
        log.warn("메일 발송이 log 모드입니다(개발용). 인증번호가 로그에 출력됩니다.");
        return (to, subject, text) -> log.warn("[DEV MAIL] to={} subject={}\n{}", to, subject, text);
    }

    private static boolean isLoopback(String baseUrl) {
        try {
            String host = URI.create(baseUrl == null ? "" : baseUrl.trim()).getHost();
            return host != null && LOOPBACK.contains(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
