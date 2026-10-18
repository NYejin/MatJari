package com.matjari.service;

import com.matjari.dto.request.PerformanceCreateRequest.ZoneInput;
import com.matjari.exception.FieldValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerformanceZoneSanitizerTest {

    private static List<Object> square(Object x) {
        return List.of(List.of(x, 0), List.of(10, 0), List.of(10, 10), List.of(x, 10));
    }

    private static List<ZoneInput> one(String name, Object shape) {
        return List.of(new ZoneInput(name, shape));
    }

    @Test
    void nullOrEmptyGivesNoZones() {
        assertThat(PerformanceZoneSanitizer.sanitize(null)).isEmpty();
        assertThat(PerformanceZoneSanitizer.sanitize(List.of())).isEmpty();
    }

    @Test
    void trimsNameAndBuildsKey() {
        var zones = PerformanceZoneSanitizer.sanitize(one("  a   구역 ", null));
        assertThat(zones).singleElement().satisfies(z -> {
            assertThat(z.name()).isEqualTo("a 구역");
            assertThat(z.nameKey()).isEqualTo("A구역");
            assertThat(z.shapePolygons()).isNull();
        });
    }

    @Test
    void mergesSameKeyKeepingFirstSpellingAndConcatenatingShapes() {
        var zones = PerformanceZoneSanitizer.sanitize(List.of(
                new ZoneInput("A 구역", List.of(square(0))),
                new ZoneInput("B", null),
                new ZoneInput("ａ구역", List.of(square(5), square(6))))); // 전각 a, 공백 없음
        assertThat(zones).extracting("name").containsExactly("A 구역", "B");
        assertThat(zones.get(0).shapePolygons()).hasSize(3);
    }

    @Test
    void mergeKeepsOnlyFirstEightPolygons() {
        List<Object> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            five.add(square(i));
        }
        var zones = PerformanceZoneSanitizer.sanitize(List.of(
                new ZoneInput("A", five), new ZoneInput("A", five)));
        assertThat(zones).singleElement().satisfies(z -> {
            assertThat(z.shapePolygons()).hasSize(8);
            assertThat(z.shapePolygons().get(5).get(0).get(0)).isZero(); // 두 번째 입력의 첫 조각
        });
    }

    @Test
    void mergeUsesShapeFromLaterZoneWhenFirstHasNone() {
        var zones = PerformanceZoneSanitizer.sanitize(List.of(
                new ZoneInput("A", null), new ZoneInput("a", List.of(square(0)))));
        assertThat(zones.get(0).shapePolygons()).hasSize(1);
    }

    @Test
    void dropsBlankNamesAndKeepsOrderOfRest() {
        var zones = PerformanceZoneSanitizer.sanitize(List.of(
                new ZoneInput("   ", null), new ZoneInput(null, null), new ZoneInput("　", null),
                new ZoneInput("B", null), new ZoneInput("A", null)));
        assertThat(zones).extracting("name").containsExactly("B", "A");
    }

    @Test
    void rejectsNamesLongerThan30AfterTrimming() {
        assertThat(PerformanceZoneSanitizer.sanitize(one("  " + "가".repeat(30) + "  ", null))).hasSize(1);
        assertThatThrownBy(() -> PerformanceZoneSanitizer.sanitize(one("가".repeat(31), null)))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getField()).isEqualTo("zones"));
    }

    @Test
    void rejectsControlAndZeroWidthCharacters() {
        for (String bad : new String[]{"A​B", "A\u0007", "A﻿B".replace("﻿", "⁠")}) {
            assertThatThrownBy(() -> PerformanceZoneSanitizer.sanitize(one(bad, null)))
                    .isInstanceOfSatisfying(FieldValidationException.class,
                            e -> assertThat(e.getField()).isEqualTo("zones"));
        }
    }

    @Test
    void shapeRoundsCoordinatesToIntegers() {
        var zones = PerformanceZoneSanitizer.sanitize(one("A",
                List.of(List.of(List.of(0.4, 999.5), List.of(10.49, 20.5), List.of(1000, 0)))));
        assertThat(zones.get(0).shapePolygons().get(0)).containsExactly(
                List.of(0, 1000), List.of(10, 21), List.of(1000, 0));
    }

    @Test
    void invalidShapeIsDroppedButNameIsKept() {
        List<Object> badShapes = new ArrayList<>();
        badShapes.add("text");
        badShapes.add(List.of());                                                   // 조각 0개
        badShapes.add(List.of(List.of(List.of(0, 0), List.of(1, 1))));              // 꼭짓점 2개
        badShapes.add(List.of(List.of(List.of(0, 0), List.of(1, 1), List.of(1, 0, 5)))); // 쌍이 아님
        badShapes.add(List.of(List.of(List.of(0, 0), List.of(1, 1), List.of("1", 0))));  // 숫자가 아님
        badShapes.add(List.of(List.of(List.of(0, 0), List.of(1, 1), List.of(1001, 0)))); // 범위 밖
        badShapes.add(List.of(List.of(List.of(0, 0), List.of(1, 1), List.of(-1, 0))));
        badShapes.add(List.of(List.of(List.of(0, 0), List.of(1, 1), java.util.Arrays.asList(1, null))));
        badShapes.add(List.of(List.of(List.of(0, 0), List.of(1, 1), List.of(true, 0))));
        List<Object> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            nine.add(square(0));
        }
        badShapes.add(nine);                                                        // 조각 9개
        List<Object> manyVertices = new ArrayList<>();
        for (int i = 0; i < 41; i++) {
            manyVertices.add(List.of(i, i));
        }
        badShapes.add(List.of(manyVertices));                                       // 꼭짓점 41개
        badShapes.add(java.util.Collections.singletonList(null));
        for (Object bad : badShapes) {
            var zones = PerformanceZoneSanitizer.sanitize(one("A", bad));
            assertThat(zones).as(String.valueOf(bad)).singleElement().satisfies(z -> {
                assertThat(z.name()).isEqualTo("A");
                assertThat(z.shapePolygons()).isNull();
            });
        }
    }

    @Test
    void boundaryShapesAreAccepted() {
        List<Object> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(square(0));
        }
        List<Object> forty = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            forty.add(List.of(i, i));
        }
        assertThat(PerformanceZoneSanitizer.sanitize(one("A", eight)).get(0).shapePolygons()).hasSize(8);
        assertThat(PerformanceZoneSanitizer.sanitize(one("A", List.of(forty))).get(0).shapePolygons().get(0)).hasSize(40);
    }

    @Test
    void aspectMustBePositiveAndAtMost999_999() {
        assertThat(PerformanceZoneSanitizer.sanitizeAspect(null)).isNull();
        assertThat(PerformanceZoneSanitizer.sanitizeAspect(BigDecimal.ZERO)).isNull();
        assertThat(PerformanceZoneSanitizer.sanitizeAspect(new BigDecimal("-1"))).isNull();
        assertThat(PerformanceZoneSanitizer.sanitizeAspect(new BigDecimal("0.0004"))).isNull(); // 반올림하면 0
        assertThat(PerformanceZoneSanitizer.sanitizeAspect(new BigDecimal("1000"))).isNull();
        assertThat(PerformanceZoneSanitizer.sanitizeAspect(new BigDecimal("999.9994"))).isEqualByComparingTo("999.999");
        assertThat(PerformanceZoneSanitizer.sanitizeAspect(new BigDecimal("999.9995"))).isNull();
        assertThat(PerformanceZoneSanitizer.sanitizeAspect(new BigDecimal("1.77777"))).isEqualByComparingTo("1.778");
    }
}
