package com.matjari.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 409 Conflict. 응답: {"message": ..., ...details}
 * details에는 프론트가 후속 동작에 쓸 식별자 등을 담는다 (예: 이미 등록된 공연의 performanceId).
 */
public class ConflictException extends MatJariException {

    private final Map<String, Object> details;

    public ConflictException(String message) {
        this(message, Map.of());
    }

    public ConflictException(String message, Map<String, ?> details) {
        super(message);
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
