package com.matjari.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 이메일 인증번호 설정(matjari.email-verification.*). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "matjari.email-verification")
public class EmailVerificationProperties {

    /** false 면 가입에 인증번호를 요구하지 않는다(테스트·내부 환경용). */
    private boolean enabled = true;
    /** 인증번호 유효 시간(초). */
    private int codeTtlSeconds = 600;
    /** 같은 이메일로 다시 보내려면 기다려야 하는 시간(초). */
    private int resendCooldownSeconds = 60;
    /** 한 번 보낸 코드로 입력해 볼 수 있는 횟수(틀린 것 포함). */
    private int maxAttempts = 5;
    /** 10분 창 안에서 IP당·이메일당 발송 횟수, 1시간 안의 전체 발송 상한(메일 폭탄·발송 한도 보호). */
    private int ipPer10Min = 10;
    private int emailPer10Min = 5;
    private int globalPerHour = 200;
    /** 코드 해시 HMAC 키. 운영에서는 환경변수(MATJARI_EMAIL_CODE_SECRET)로 덮어쓴다. */
    private String secret = "dev-only-insecure-email-code-secret-please-override";
}
