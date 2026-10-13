package com.matjari.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 422 Unprocessable Entity. 형식은 맞지만 업무 규칙(상한 등)에 걸린 요청.
 * 응답: {"code": ..., "message": ..., ...details} (details는 선택, 예: 상한 초과 시 count/limit)
 */
public class BusinessRuleException extends MatJariException {

    private final String code;
    private final Map<String, Object> details;

    public BusinessRuleException(String code, String message) {
        this(code, message, Map.of());
    }

    public BusinessRuleException(String code, String message, Map<String, ?> details) {
        super(message);
        this.code = code;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
