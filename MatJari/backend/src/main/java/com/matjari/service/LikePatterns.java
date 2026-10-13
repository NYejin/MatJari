package com.matjari.service;

/**
 * JPQL LIKE 패턴 생성. 사용자 입력의 %, _ 를 와일드카드가 아닌 문자로 취급하도록 '!'로 이스케이프한다.
 * 리포지토리 쿼리는 {@code like :pattern escape '!'} 형태여야 한다
 * (MySQL은 문자열 리터럴의 백슬래시를 이스케이프로 해석하므로 '!'를 쓴다).
 */
final class LikePatterns {

    static final char ESCAPE = '!';

    private LikePatterns() {}

    /** "%{escaped}%". null/빈 문자열이면 "%"(전체 일치). */
    static String contains(String value) {
        if (value == null || value.isEmpty()) {
            return "%";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2).append('%');
        for (char c : value.toCharArray()) {
            if (c == ESCAPE || c == '%' || c == '_') {
                sb.append(ESCAPE);
            }
            sb.append(c);
        }
        return sb.append('%').toString();
    }
}
