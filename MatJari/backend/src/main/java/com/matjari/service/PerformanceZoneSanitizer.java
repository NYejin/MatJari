package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.domain.PerformanceZone;
import com.matjari.dto.request.PerformanceCreateRequest;
import com.matjari.exception.FieldValidationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 공연 등록 요청의 구역 목록 정리. 이름은 1~30자(공백 정리 후, 제어·제로폭 문자는 SeatKeyNormalizer 규칙) 아니면 400 {zones},
 * 정규화 후 빈 이름은 버린다. 같은 nameKey 는 한 구역으로 합친다(처음 표기 유지, 모양 조각은 이어 붙이고 8개 초과분은 버림).
 * 모양이 규칙에 어긋나면 오류로 거절하지 않고 그 구역의 모양만 null 로 버린다.
 */
public final class PerformanceZoneSanitizer {

    static final String FIELD = "zones";
    static final int MAX_POLYGONS = 8;
    static final int MIN_VERTICES = 3;
    static final int MAX_VERTICES = 40;
    static final int COORD_MAX = 1000;
    static final BigDecimal ASPECT_MAX = new BigDecimal("999.999");

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}\\uFEFF]+");

    private PerformanceZoneSanitizer() {}

    /** 정리된 구역 하나(저장 직전 형태). */
    public record Zone(String name, String nameKey, List<List<List<Integer>>> shapePolygons) {}

    public static List<Zone> sanitize(List<PerformanceCreateRequest.ZoneInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        Map<String, Zone> merged = new LinkedHashMap<>();
        for (PerformanceCreateRequest.ZoneInput input : inputs) {
            if (input == null) {
                continue;
            }
            String name = Performance.cleanDisplayText(input.name());
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (name.length() > PerformanceZone.NAME_MAX_LENGTH) {
                throw new FieldValidationException(FIELD,
                        "구역 이름은 " + PerformanceZone.NAME_MAX_LENGTH + "자 이하로 입력해주세요.");
            }
            String key;
            try {
                SeatKeyNormalizer.zoneLabel(name); // 제어·제로폭 문자 거절
                String compact = WHITESPACE.matcher(Normalizer.normalize(name, Normalizer.Form.NFKC)).replaceAll("");
                if (compact.isEmpty()) {
                    continue;
                }
                key = SeatKeyNormalizer.zoneKey(name);
            } catch (FieldValidationException e) {
                throw new FieldValidationException(FIELD, e.getMessage());
            }
            List<List<List<Integer>>> shape = parseShape(input.shapePolygons());
            Zone existing = merged.get(key);
            if (existing == null) {
                merged.put(key, new Zone(name, key, shape));
            } else {
                merged.put(key, new Zone(existing.name(), key, join(existing.shapePolygons(), shape)));
            }
        }
        return List.copyOf(merged.values());
    }

    /** 0 초과 999.999 이하(소수 3자리 반올림)만 인정하고 아니면 null. */
    public static BigDecimal sanitizeAspect(BigDecimal aspect) {
        if (aspect == null) {
            return null;
        }
        BigDecimal rounded;
        try {
            rounded = aspect.setScale(3, RoundingMode.HALF_UP);
        } catch (ArithmeticException e) {
            return null;
        }
        return rounded.signum() > 0 && rounded.compareTo(ASPECT_MAX) <= 0 ? rounded : null;
    }

    private static List<List<List<Integer>>> join(List<List<List<Integer>>> a, List<List<List<Integer>>> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        List<List<List<Integer>>> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all.size() > MAX_POLYGONS ? all.subList(0, MAX_POLYGONS) : all);
    }

    /** 규칙에 맞으면 정수 좌표로 반올림한 불변 목록, 아니면 null. */
    static List<List<List<Integer>>> parseShape(Object raw) {
        if (!(raw instanceof List<?> polygons) || polygons.isEmpty() || polygons.size() > MAX_POLYGONS) {
            return null;
        }
        List<List<List<Integer>>> result = new ArrayList<>();
        for (Object polygon : polygons) {
            if (!(polygon instanceof List<?> vertices) || vertices.size() < MIN_VERTICES
                    || vertices.size() > MAX_VERTICES) {
                return null;
            }
            List<List<Integer>> points = new ArrayList<>();
            for (Object vertex : vertices) {
                if (!(vertex instanceof List<?> pair) || pair.size() != 2) {
                    return null;
                }
                Integer x = coordinate(pair.get(0));
                Integer y = coordinate(pair.get(1));
                if (x == null || y == null) {
                    return null;
                }
                points.add(List.of(x, y));
            }
            result.add(List.copyOf(points));
        }
        return List.copyOf(result);
    }

    private static Integer coordinate(Object value) {
        if (!(value instanceof Number n)) {
            return null;
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return null;
        }
        long rounded = Math.round(d);
        return rounded < 0 || rounded > COORD_MAX ? null : (int) rounded;
    }
}
