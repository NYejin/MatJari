package com.matjari.service;

import com.matjari.domain.ExtraType;
import com.matjari.domain.SeatKey;
import com.matjari.domain.WantExtra;
import com.matjari.dto.request.WantRangeInput;
import com.matjari.exception.BusinessRuleException;
import com.matjari.exception.FieldValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 희망 범위 입력을 검증·정규화하고 개별 좌석으로 펼친다 (설계 1.3, 1.4, 6절 b·f).
 * - 구역은 범위 대상이 아니다. 열·번은 숫자 from~to만 범위이고 문자는 from=to(하나씩 추가)다.
 * - 정규화는 SeatKeyNormalizer 를 재사용한다. 오류 키는 범위 인덱스를 포함한다(예: ranges[0].colTo).
 * - 추가금은 범위마다 있다(extraType X/ANY/POS/NEG + 참고 금액). 범위끼리 겹치면 합집합이고(같은 좌석은 한 번만 담긴다),
 *   겹치는 좌석의 추가금(유형 또는 금액)이 다르면 422 WANT_EXTRA_CONFLICT 로 거부한다(설계 9.3 (a)). 완전히 같은 겹침은 허용한다.
 * - 안전 상한: 범위 개수(maxRanges), 펼친 좌석 수(maxSeats). 초과하면 422 + count/limit.
 *   좌석 수는 펼치기 전에 구역별 직사각형 합집합 넓이(좌표 압축, 범위 50개 이하라 O(n^2 log n))로 정확히 계산한다.
 *   그래서 응답 count 와 저장되는 wantSeatCount(겹침 제거 후 개수)의 의미가 같고, 큰 입력도 펼치지 않고 즉시 거부한다.
 */
final class WantSeatExpander {

    static final String RANGE_LIMIT_CODE = "WANT_RANGE_LIMIT_EXCEEDED";
    static final String SEAT_LIMIT_CODE = "WANT_SEAT_LIMIT_EXCEEDED";
    static final String EXTRA_CONFLICT_CODE = "WANT_EXTRA_CONFLICT";
    static final String EXTRA_TYPE_MESSAGE = "추가금 유형은 X, ANY, POS, NEG 중 하나여야 합니다.";
    /** 충돌 응답에 담는 범위 인덱스 쌍의 최대 개수(응답 크기 제한). */
    static final int MAX_CONFLICT_PAIRS = 20;

    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");

    record Range(String zoneLabel, String zoneKey, String rowFrom, String rowTo, String colFrom, String colTo,
                 WantExtra extra) {}

    /** seats: 펼친 개별 좌석 -> 그 좌석이 속한 범위의 추가금 (좌석당 1개, 겹침은 같은 추가금일 때만 허용되어 이미 합쳐졌다). */
    record Result(List<Range> ranges, Map<SeatKey, WantExtra> seats) {}

    private final int maxRanges;
    private final int maxSeats;
    private final int maxRowNumber;
    private final int maxColNumber;

    WantSeatExpander(int maxRanges, int maxSeats, int maxRowNumber, int maxColNumber) {
        this.maxRanges = maxRanges;
        this.maxSeats = maxSeats;
        this.maxRowNumber = maxRowNumber;
        this.maxColNumber = maxColNumber;
    }

    Result expand(List<WantRangeInput> inputs) {
        if (inputs.size() > maxRanges) {
            throw new BusinessRuleException(RANGE_LIMIT_CODE,
                    "희망 좌석 범위는 최대 " + maxRanges + "개까지 입력할 수 있습니다. (현재 " + inputs.size() + "개)",
                    limitDetails(inputs.size(), maxRanges));
        }

        // 필드 오류는 첫 오류에서 멈추지 않고 모아서 한 번에 돌려준다.
        Map<String, String> errors = new LinkedHashMap<>();
        List<Range> ranges = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            Range range = normalize(errors, "ranges[" + i + "].", inputs.get(i));
            ranges.add(range);
        }
        if (!errors.isEmpty()) {
            throw FieldValidationException.ofAll(errors);
        }

        long total = unionCount(ranges);
        if (total > maxSeats) {
            throw new BusinessRuleException(SEAT_LIMIT_CODE,
                    "한 번에 등록할 수 있는 희망 좌석은 최대 " + maxSeats + "석입니다. (현재 " + total + "석) 범위를 줄여주세요.",
                    limitDetails(total, maxSeats));
        }

        // 좌석 -> (추가금, 처음 담은 범위 인덱스). 같은 좌석에 다른 추가금이 오면 충돌 쌍(처음 범위, 현재 범위)을 기록한다.
        Map<SeatKey, WantExtra> seats = new LinkedHashMap<>();
        Map<SeatKey, Integer> firstRange = new java.util.HashMap<>();
        java.util.Set<List<Integer>> conflicts = new java.util.LinkedHashSet<>();
        for (int i = 0; i < ranges.size(); i++) {
            Range range = ranges.get(i);
            List<String> rows = values(range.rowFrom(), range.rowTo());
            List<String> cols = values(range.colFrom(), range.colTo());
            for (String row : rows) {
                for (String col : cols) {
                    SeatKey key = new SeatKey(range.zoneKey(), row, col);
                    WantExtra existing = seats.putIfAbsent(key, range.extra());
                    if (existing == null) {
                        firstRange.put(key, i);
                    } else if (!existing.equals(range.extra()) && conflicts.size() < MAX_CONFLICT_PAIRS) {
                        conflicts.add(List.of(firstRange.get(key), i));
                    }
                }
            }
        }
        if (!conflicts.isEmpty()) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("conflicts", List.copyOf(conflicts));
            throw new BusinessRuleException(EXTRA_CONFLICT_CODE,
                    "겹치는 희망 좌석 범위의 추가금이 서로 다릅니다. 겹치는 좌석에는 같은 추가금을 입력하거나 범위를 겹치지 않게 나눠주세요.",
                    details);
        }
        return new Result(List.copyOf(ranges), seats);
    }

    private Range normalize(Map<String, String> errors, String prefix, WantRangeInput in) {
        if (in == null) {
            errors.put(prefix + "zone", "희망 좌석 범위 정보가 올바르지 않습니다.");
            return null;
        }
        String zoneLabel = guard(errors, prefix + "zone", () -> SeatKeyNormalizer.zoneLabel(in.zone()));
        String zoneKey = guard(errors, prefix + "zone", () -> SeatKeyNormalizer.zoneKey(in.zone()));
        String rowFrom = guard(errors, prefix + "rowFrom", () -> SeatKeyNormalizer.rowKey(in.rowFrom(), maxRowNumber));
        String rowTo = guard(errors, prefix + "rowTo", () -> SeatKeyNormalizer.rowKey(in.rowTo(), maxRowNumber));
        String colFrom = guard(errors, prefix + "colFrom", () -> SeatKeyNormalizer.colKey(in.colFrom(), maxColNumber));
        String colTo = guard(errors, prefix + "colTo", () -> SeatKeyNormalizer.colKey(in.colTo(), maxColNumber));
        checkAxis(errors, prefix + "rowFrom", prefix + "rowTo", "열", rowFrom, rowTo);
        checkAxis(errors, prefix + "colFrom", prefix + "colTo", "번", colFrom, colTo);
        WantExtra extra = parseExtra(errors, prefix, in.extraType(), in.extraAmount());
        if (zoneLabel == null || zoneKey == null || rowFrom == null || rowTo == null || colFrom == null || colTo == null
                || extra == null) {
            return null;
        }
        return new Range(zoneLabel, zoneKey, rowFrom, rowTo, colFrom, colTo, extra);
    }

    /** 추가금 유형·금액 검증: X/ANY 는 금액 없음, POS 는 > 0, NEG 는 < 0. 오류 키는 ranges[i].extraType / ranges[i].extraAmount. */
    private static WantExtra parseExtra(Map<String, String> errors, String prefix, String rawType, Integer amount) {
        ExtraType type;
        try {
            type = rawType == null ? null : ExtraType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            type = null;
        }
        if (type == null) {
            errors.put(prefix + "extraType", EXTRA_TYPE_MESSAGE);
            return null;
        }
        switch (type) {
            case X, ANY -> {
                if (amount != null) {
                    errors.put(prefix + "extraAmount", "추가금 X/상관없음에서는 금액을 입력할 수 없습니다.");
                    return null;
                }
            }
            case POS -> {
                if (amount == null || amount <= 0) {
                    errors.put(prefix + "extraAmount", "받을 금액은 0보다 큰 금액을 입력해주세요.");
                    return null;
                }
            }
            case NEG -> {
                if (amount == null || amount >= 0) {
                    errors.put(prefix + "extraAmount", "낼 금액은 0보다 작은 금액(예: -10000)을 입력해주세요.");
                    return null;
                }
            }
        }
        return new WantExtra(type, amount);
    }

    /** 한 축(열 또는 번)의 from/to 관계 검사. 둘 중 하나라도 이미 오류면 건너뛴다. */
    private static void checkAxis(Map<String, String> errors, String fromField, String toField, String name,
                                  String from, String to) {
        if (from == null || to == null) {
            return;
        }
        boolean fromNumeric = DIGITS.matcher(from).matches();
        boolean toNumeric = DIGITS.matcher(to).matches();
        if (fromNumeric != toNumeric) {
            errors.putIfAbsent(toField, name + "의 시작과 끝은 둘 다 숫자이거나 둘 다 문자여야 합니다.");
        } else if (fromNumeric) {
            if (Long.parseLong(from) > Long.parseLong(to)) {
                errors.putIfAbsent(toField, name + "의 끝은 시작보다 크거나 같아야 합니다.");
            }
        } else if (!from.equals(to)) {
            errors.putIfAbsent(toField, "문자 " + name + "은 범위로 입력할 수 없습니다. 하나씩 추가해주세요.");
        }
    }

    private static String guard(Map<String, String> errors, String field, Supplier<String> action) {
        try {
            return action.get();
        } catch (FieldValidationException e) {
            errors.putIfAbsent(field, e.getMessage());
            return null;
        }
    }

    /**
     * 구역별 직사각형(열 x 번)의 합집합 좌석 수. 열·번 값은 숫자 그대로, 문자는 겹치지 않는 큰 id 로 바꿔
     * 같은 구성(숫자끼리/문자끼리)에서만 비교되게 한다(검증에서 한 축은 숫자 또는 문자로만 이루어진다).
     */
    private static long unionCount(List<Range> ranges) {
        Map<String, Long> letterIds = new java.util.HashMap<>();
        Map<String, List<long[]>> byZone = new LinkedHashMap<>();
        for (Range r : ranges) {
            byZone.computeIfAbsent(r.zoneKey(), k -> new ArrayList<>()).add(new long[]{
                    axisValue(r.rowFrom(), letterIds), axisValue(r.rowTo(), letterIds),
                    axisValue(r.colFrom(), letterIds), axisValue(r.colTo(), letterIds)});
        }
        long total = 0;
        for (List<long[]> rects : byZone.values()) {
            java.util.TreeSet<Long> breaks = new java.util.TreeSet<>();
            for (long[] q : rects) {
                breaks.add(q[0]);
                breaks.add(q[1] + 1);
            }
            Long prev = null;
            for (Long b : breaks) {
                if (prev != null) {
                    final long from = prev;
                    List<long[]> cols = new ArrayList<>();
                    for (long[] q : rects) {
                        if (q[0] <= from && from <= q[1]) {
                            cols.add(new long[]{q[2], q[3]});
                        }
                    }
                    cols.sort(java.util.Comparator.comparingLong(c -> c[0]));
                    long colLen = 0;
                    long curStart = 0;
                    long curEnd = Long.MIN_VALUE;
                    for (long[] c : cols) {
                        if (curEnd == Long.MIN_VALUE || c[0] > curEnd + 1) {
                            if (curEnd != Long.MIN_VALUE) {
                                colLen += curEnd - curStart + 1;
                            }
                            curStart = c[0];
                            curEnd = c[1];
                        } else {
                            curEnd = Math.max(curEnd, c[1]);
                        }
                    }
                    if (curEnd != Long.MIN_VALUE) {
                        colLen += curEnd - curStart + 1;
                    }
                    total += (b - prev) * colLen;
                }
                prev = b;
            }
        }
        return total;
    }

    private static long axisValue(String key, Map<String, Long> letterIds) {
        if (isNumeric(key)) {
            return Long.parseLong(key);
        }
        return letterIds.computeIfAbsent(key, k -> 1_000_000L + letterIds.size() * 2L);
    }

    private static List<String> values(String from, String to) {
        if (!isNumeric(from)) {
            return List.of(from);
        }
        long start = Long.parseLong(from);
        long end = Long.parseLong(to);
        List<String> values = new ArrayList<>((int) (end - start + 1));
        for (long v = start; v <= end; v++) {
            values.add(Long.toString(v));
        }
        return values;
    }

    private static boolean isNumeric(String key) {
        return DIGITS.matcher(key).matches();
    }

    private static Map<String, Object> limitDetails(long count, long limit) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("count", count);
        details.put("limit", limit);
        return details;
    }
}
