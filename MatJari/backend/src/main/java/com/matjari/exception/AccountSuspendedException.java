package com.matjari.exception;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 이용 정지(제재) 중인 계정 -> 403 {"message", "code":"ACCOUNT_SUSPENDED", "until"}. until 은 오프셋 없는 KST 초 단위 문자열이고 무기한이면 null.
 * 컨트롤러 단계는 GlobalExceptionHandler, 보안 필터 단계(JwtAuthenticationFilter)는 SecurityErrorResponseWriter 가 같은 본문을 쓴다.
 * 401(로그인 필요)과 구분되는 403 이라 프론트는 토큰 재발급을 시도하지 않는다.
 * 로그인에서 비밀번호가 맞았을 때만 {@code withdrawToken}(탈퇴 전용 단기 토큰)이 본문에 더해진다 — 정지 중에도 회원탈퇴를 할 수 있게 한다.
 */
public class AccountSuspendedException extends MatJariException {

    public static final String CODE = "ACCOUNT_SUSPENDED";
    public static final String MESSAGE = "이용이 정지된 계정입니다.";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final LocalDateTime until;
    private final String withdrawToken;

    public AccountSuspendedException(LocalDateTime until) {
        this(until, null);
    }

    private AccountSuspendedException(LocalDateTime until, String withdrawToken) {
        super(MESSAGE);
        this.until = until;
        this.withdrawToken = withdrawToken;
    }

    /** 같은 정지 정보에 탈퇴 전용 토큰을 더한 새 예외(로그인에서 비밀번호 확인 뒤에만 쓴다). */
    public AccountSuspendedException withWithdrawToken(String token) {
        return new AccountSuspendedException(until, token);
    }

    /** 정지가 풀리는 시각(무기한이면 null). */
    public LocalDateTime getUntil() {
        return until;
    }

    /** 응답 본문: message, code, until(null 포함). */
    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", MESSAGE);
        body.put("code", CODE);
        body.put("until", until == null ? null : until.format(FORMAT));
        if (withdrawToken != null) {
            body.put("withdrawToken", withdrawToken);
        }
        return body;
    }
}
