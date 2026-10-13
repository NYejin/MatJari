package com.matjari.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 특정 입력 필드에 귀속되는 비즈니스 검증 실패 (예: 이메일 중복).
 * GlobalExceptionHandler가 @Valid 실패와 같은 {field: message} 형태로 응답한다.
 * 여러 필드 오류를 한 번에 돌려줄 때는 {@link #ofAll(Map)}을 쓴다 (getField/getMessage는 첫 항목).
 */
public class FieldValidationException extends MatJariException {

    private final String field;
    private final Map<String, String> errors;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
        this.errors = Collections.singletonMap(field, message);
    }

    private FieldValidationException(Map<String, String> errors) {
        super(errors.values().iterator().next());
        this.field = errors.keySet().iterator().next();
        this.errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
    }

    /** errors는 비어 있으면 안 된다. */
    public static FieldValidationException ofAll(Map<String, String> errors) {
        return new FieldValidationException(errors);
    }

    public String getField() {
        return field;
    }

    /** 필드 -> 메시지 (입력 순서 유지). */
    public Map<String, String> getErrors() {
        return errors;
    }
}
