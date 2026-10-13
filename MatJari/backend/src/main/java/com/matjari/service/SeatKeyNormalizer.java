package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.domain.Ticket;
import com.matjari.exception.FieldValidationException;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 좌석 입력 정규화 (설계 exchange-schema-design 1.12). 표시용 label과 비교용 key를 만든다.
 * - label: 앞뒤 공백 제거 + 연속 공백 한 칸 (입력 원문 유지)
 * - 구역 key: NFKC(전각 -> 반각), 모든 공백 제거, 영문 대문자. 접미사('구역', '존', '층')는 지우지 않는다.
 * - 열 key: 위 규칙 + 끝의 '열' 제거. 번 key: 위 규칙 + 끝의 '번' 제거.
 *   순수 숫자는 앞 0을 제거하고(03 -> 3) 1 이상, 설정 상한 이하여야 한다. 숫자가 아니면 문자(A, 가 등)로 취급한다.
 * - 제어·서식·제로폭·사설·미할당 문자(\p{C})와 변이 선택자는 제거하지 않고 '사용할 수 없는 문자'로 거부한다(label·key 공통).
 * - 숫자(Nd, 아랍-인도 숫자 등)는 NFKC 뒤 ASCII 0-9로 바꿔 key를 만든다.
 *   정책: 숫자와 문자가 섞인 값(03A)은 그대로 허용하며 앞 0 제거는 순수 숫자일 때만 한다.
 *   부호 붙은 정수형(-3, +3, U+2212)은 문자로 취급하지 않고 400으로 거부한다.
 * 오류는 필드(zone/row/col)에 귀속된 FieldValidationException(400)으로 던진다.
 */
public final class SeatKeyNormalizer {

    public static final String ZONE = "zone";
    public static final String ROW = "row";
    public static final String COL = "col";

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}\\uFEFF]+");
    private static final String FORBIDDEN_SUFFIX = "에 사용할 수 없는 문자가 있습니다.";
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    /** 부호 붙은 정수형(-3, +3, U+2212 마이너스 포함). NFKC 로 전각 부호(－, ＋)는 이미 ASCII 가 된 뒤에 검사한다. */
    private static final Pattern SIGNED_INTEGER = Pattern.compile("^[-+\u2212][0-9]+$");
    private static final Pattern LEADING_ZEROS = Pattern.compile("^0+(?=[0-9])");
    private static final int MAX_NUMBER_DIGITS = 9;

    private SeatKeyNormalizer() {}

    public static String zoneLabel(String raw) {
        return label(raw, ZONE, "구역", Ticket.ZONE_MAX_LENGTH);
    }

    public static String zoneKey(String raw) {
        String key = baseKey(raw, ZONE, "구역");
        return checkLength(key, ZONE, "구역", Ticket.ZONE_MAX_LENGTH);
    }

    public static String rowLabel(String raw) {
        return label(raw, ROW, "열", Ticket.ROW_MAX_LENGTH);
    }

    public static String rowKey(String raw, int maxNumber) {
        return seatNumberKey(raw, ROW, "열", "열", Ticket.ROW_MAX_LENGTH, maxNumber);
    }

    public static String colLabel(String raw) {
        return label(raw, COL, "번", Ticket.COL_MAX_LENGTH);
    }

    public static String colKey(String raw, int maxNumber) {
        return seatNumberKey(raw, COL, "번", "번", Ticket.COL_MAX_LENGTH, maxNumber);
    }

    private static String label(String raw, String field, String name, int maxLength) {
        String cleaned = Performance.cleanDisplayText(raw);
        if (cleaned == null || cleaned.isEmpty()) {
            throw new FieldValidationException(field, name + "을 입력해주세요.");
        }
        rejectForbidden(cleaned, field, name);
        rejectForbidden(Normalizer.normalize(cleaned, Normalizer.Form.NFKC), field, name);
        if (cleaned.length() > maxLength) {
            throw new FieldValidationException(field, name + "은 " + maxLength + "자 이하로 입력해주세요.");
        }
        return cleaned;
    }

    private static String baseKey(String raw, String field, String name) {
        if (raw == null) {
            throw new FieldValidationException(field, name + "을 입력해주세요.");
        }
        String key = WHITESPACE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFKC)).replaceAll("");
        if (key.isEmpty()) {
            throw new FieldValidationException(field, name + "을 입력해주세요.");
        }
        rejectForbidden(key, field, name);
        key = toAsciiDigits(key).toUpperCase(Locale.ROOT);
        return key;
    }

    private static void rejectForbidden(String value, String field, String name) {
        if (value.codePoints().anyMatch(SeatKeyNormalizer::isForbidden)) {
            throw new FieldValidationException(field, name + FORBIDDEN_SUFFIX);
        }
    }

    /** \p{C}(Cc·Cf·Cs·Co·Cn)와 변이 선택자(결합문자 Mn이지만 보이지 않게 key만 달라지게 한다). */
    private static boolean isForbidden(int cp) {
        switch (Character.getType(cp)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE,
                 Character.PRIVATE_USE, Character.UNASSIGNED:
                return true;
            default:
                return (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0x180B && cp <= 0x180D)
                        || (cp >= 0xE0100 && cp <= 0xE01EF);
        }
    }

    private static String toAsciiDigits(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            if (Character.isDigit(cp)) {
                sb.append((char) ('0' + Character.digit(cp, 10)));
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    private static String checkLength(String key, String field, String name, int maxLength) {
        if (key.length() > maxLength) {
            throw new FieldValidationException(field, name + "은 " + maxLength + "자 이하로 입력해주세요.");
        }
        return key;
    }

    private static String seatNumberKey(String raw, String field, String name, String suffix,
                                        int maxLength, int maxNumber) {
        String key = baseKey(raw, field, name);
        if (key.endsWith(suffix)) {
            key = key.substring(0, key.length() - suffix.length());
            if (key.isEmpty()) {
                throw new FieldValidationException(field, name + "을 입력해주세요.");
            }
        }
        if (SIGNED_INTEGER.matcher(key).matches()) {
            // 문자 열·번으로 흘러 들어가 범위·좌석 비교가 어긋나는 것을 막는다(티켓 좌석·희망 범위 공통 정책).
            throw new FieldValidationException(field, name + "은 부호 없는 숫자(1 이상)로 입력해주세요.");
        }
        if (DIGITS.matcher(key).matches()) {
            String digits = LEADING_ZEROS.matcher(key).replaceFirst("");
            if (digits.equals("0")) {
                throw new FieldValidationException(field, name + "은 1 이상이어야 합니다.");
            }
            if (digits.length() > MAX_NUMBER_DIGITS || Long.parseLong(digits) > maxNumber) {
                throw new FieldValidationException(field, name + "은 " + maxNumber + " 이하로 입력해주세요.");
            }
            return digits;
        }
        return checkLength(key, field, name, maxLength);
    }
}
