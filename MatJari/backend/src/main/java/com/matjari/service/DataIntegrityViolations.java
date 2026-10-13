package com.matjari.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Locale;

/**
 * DataIntegrityViolationException이 특정 이름의 제약(unique 등) 위반인지 판별한다.
 * erd-conventions에 따라 제약 이름을 명시해 두었으므로 이름으로 구분한다.
 * MySQL 8은 제약 이름을 "테이블.제약명"으로 보고하므로(예: performance.uk_performance_source_key) 접미사 일치도 허용한다.
 */
final class DataIntegrityViolations {

    private DataIntegrityViolations() {}

    static boolean isViolationOf(DataIntegrityViolationException e, String constraintName) {
        String expected = constraintName.toLowerCase(Locale.ROOT);
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException cve) {
                String actual = cve.getConstraintName();
                if (actual != null) {
                    String lower = actual.toLowerCase(Locale.ROOT);
                    if (lower.equals(expected) || lower.endsWith("." + expected)) {
                        return true;
                    }
                }
                // 제약명 추출 실패 대비: MySQL 메시지 "Duplicate entry '...' for key 'performance.uk_...'"
                String sqlMessage = cve.getSQLException() != null ? cve.getSQLException().getMessage() : null;
                return sqlMessage != null && sqlMessage.toLowerCase(Locale.ROOT).contains(expected + "'");
            }
            cause = cause.getCause();
        }
        return false;
    }
}
