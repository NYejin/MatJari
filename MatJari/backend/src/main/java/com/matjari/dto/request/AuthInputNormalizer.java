package com.matjari.dto.request;

import java.util.Locale;

/**
 * 인증 관련 입력 정규화 규칙 (FR-01).
 * 가입/로그인이 같은 규칙을 써야 "가입한 이메일로 로그인이 안 되는" 불일치가 생기지 않는다.
 *
 * 공백 제거는 프론트(JS String.prototype.trim)와 판정이 갈리지 않도록 JS trim과 동일한 문자 집합을 쓴다.
 * Java trim()은 U+0020 이하 제어문자만, strip()은 Character.isWhitespace 기준이라
 * NBSP(U+00A0)·BOM(U+FEFF)·U+202F 등을 남기므로 둘 다 JS trim과 다르다.
 * JS trim 기준 = WhiteSpace(TAB, VT, FF, SP, NBSP, ZWNBSP(FEFF), 모든 Zs) + LineTerminator(LF, CR, LS, PS).
 */
public final class AuthInputNormalizer {

    private AuthInputNormalizer() {}

    /** 이메일: 앞뒤 공백(JS trim 기준) 제거 + 소문자. null은 그대로 둔다(@NotBlank가 처리). */
    public static String normalizeEmail(String email) {
        return email == null ? null : stripLikeJs(email).toLowerCase(Locale.ROOT);
    }

    /** 닉네임: 앞뒤 공백(JS trim 기준) 제거. 길이 검증(2~20자)은 제거 이후 값 기준. */
    public static String normalizeNickname(String nickname) {
        return nickname == null ? null : stripLikeJs(nickname);
    }

    /** JS String.prototype.trim()과 동일한 규칙으로 앞뒤 공백을 제거한다. */
    public static String stripLikeJs(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isJsWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isJsWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isJsWhitespace(char c) {
        switch (c) {
            case '\t', '\u000B', '\f', '\n', '\r', '\u2028', '\u2029', '\uFEFF':
                return true;
            default:
                // Zs(Space_Separator): U+0020, U+00A0, U+1680, U+2000~U+200A, U+202F, U+205F, U+3000
                return Character.getType(c) == Character.SPACE_SEPARATOR;
        }
    }

    /**
     * 닉네임에 쓸 수 없는 "보이지 않는" 문자가 있는지 검사한다.
     * Cf(FORMAT: zero-width space/joiner, BOM, 방향 제어 등), Cc(CONTROL),
     * 그리고 한글 채움 문자(U+3164 HANGUL FILLER, U+115F/U+1160 초성·중성 채움, U+FFA0 반각 채움)는
     * 화면에 안 보이는데 길이는 채워서 빈 닉네임·사칭 닉네임을 만들 수 있으므로 거부한다.
     * 프론트도 동일 규칙을 사용한다.
     */
    public static boolean containsInvisibleChar(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int type = Character.getType(c);
            if (type == Character.FORMAT || type == Character.CONTROL
                    || c == '\u3164' || c == '\u115F' || c == '\u1160' || c == '\uFFA0') {
                return true;
            }
        }
        return false;
    }
}
