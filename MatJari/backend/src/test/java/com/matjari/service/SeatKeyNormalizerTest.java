package com.matjari.service;

import com.matjari.exception.FieldValidationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatKeyNormalizerTest {

    private static final int MAX = 999;

    @Test
    void 구역_라벨은_공백만_정리하고_키는_전각_공백_대소문자를_통일한다() {
        assertThat(SeatKeyNormalizer.zoneLabel("  1층   A 구역 ")).isEqualTo("1층 A 구역");
        assertThat(SeatKeyNormalizer.zoneKey("  1층   a 구역 ")).isEqualTo("1층A구역");
        assertThat(SeatKeyNormalizer.zoneKey("Ａ")).isEqualTo("A");
        assertThat(SeatKeyNormalizer.zoneKey("Ｒ석　２층")).isEqualTo("R석2층");
    }

    @Test
    void 구역_접미사는_지우지_않는다() {
        assertThat(SeatKeyNormalizer.zoneKey("A")).isNotEqualTo(SeatKeyNormalizer.zoneKey("A구역"));
        assertThat(SeatKeyNormalizer.zoneKey("1층")).isNotEqualTo(SeatKeyNormalizer.zoneKey("1구역"));
    }

    @Test
    void 숫자_열과_번은_앞_0과_접미사를_제거한다() {
        assertThat(SeatKeyNormalizer.rowKey("03", MAX)).isEqualTo("3");
        assertThat(SeatKeyNormalizer.rowKey(" 3 열", MAX)).isEqualTo("3");
        assertThat(SeatKeyNormalizer.rowKey("３열", MAX)).isEqualTo("3");
        assertThat(SeatKeyNormalizer.colKey("012번", MAX)).isEqualTo("12");
        assertThat(SeatKeyNormalizer.colKey("7", MAX)).isEqualTo("7");
    }

    @Test
    void 문자_열과_번도_허용하고_대문자로_맞춘다() {
        assertThat(SeatKeyNormalizer.rowKey("a열", MAX)).isEqualTo("A");
        assertThat(SeatKeyNormalizer.rowKey("가", MAX)).isEqualTo("가");
        assertThat(SeatKeyNormalizer.rowKey("Ａ", MAX)).isEqualTo("A");
        assertThat(SeatKeyNormalizer.colKey("a-1번", MAX)).isEqualTo("A-1");
        assertThat(SeatKeyNormalizer.rowLabel("  a   열 ")).isEqualTo("a 열");
    }

    @Test
    void 접미사만_있거나_비어_있으면_거부한다() {
        assertThatThrownBy(() -> SeatKeyNormalizer.rowKey("열", MAX)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.colKey("번", MAX)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.rowKey("   ", MAX)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.zoneKey(null)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.zoneLabel("")).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 숫자는_1_이상_상한_이하여야_한다() {
        assertThatThrownBy(() -> SeatKeyNormalizer.rowKey("0", MAX))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("row");
                    assertThat(e.getMessage()).isEqualTo("열은 1 이상이어야 합니다.");
                });
        assertThatThrownBy(() -> SeatKeyNormalizer.colKey("000번", MAX))
                .isInstanceOf(FieldValidationException.class);
        assertThat(SeatKeyNormalizer.colKey("999", MAX)).isEqualTo("999");
        assertThatThrownBy(() -> SeatKeyNormalizer.colKey("1000", MAX))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.getField()).isEqualTo("col");
                    assertThat(e.getMessage()).isEqualTo("번은 999 이하로 입력해주세요.");
                });
        assertThatThrownBy(() -> SeatKeyNormalizer.rowKey("99999999999999999999", MAX))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 길이_상한을_넘으면_거부한다() {
        assertThat(SeatKeyNormalizer.zoneLabel("가".repeat(50))).hasSize(50);
        assertThatThrownBy(() -> SeatKeyNormalizer.zoneLabel("가".repeat(51)))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.zoneKey("가".repeat(51)))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.rowKey("가".repeat(21), MAX))
                .isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.colLabel("가".repeat(21)))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 제어문자는_거부한다() {
        assertThatThrownBy(() -> SeatKeyNormalizer.zoneKey("A\u0000B")).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 제로폭_서식_문자는_제거하지_않고_label과_key_모두_거부한다() {
        // U+200B 제로폭 공백, U+2060 단어 결합자, U+200D 제로폭 결합자, U+00AD 소프트 하이픈
        for (String bad : new String[]{"A​B", "A⁠", "‍1", "1­"}) {
            assertThatThrownBy(() -> SeatKeyNormalizer.zoneKey(bad)).isInstanceOfSatisfying(
                    FieldValidationException.class, e -> assertThat(e.getMessage()).isEqualTo("구역에 사용할 수 없는 문자가 있습니다."));
            assertThatThrownBy(() -> SeatKeyNormalizer.zoneLabel(bad)).isInstanceOf(FieldValidationException.class);
            assertThatThrownBy(() -> SeatKeyNormalizer.rowKey(bad, MAX)).isInstanceOf(FieldValidationException.class);
            assertThatThrownBy(() -> SeatKeyNormalizer.rowLabel(bad)).isInstanceOf(FieldValidationException.class);
            assertThatThrownBy(() -> SeatKeyNormalizer.colKey(bad, MAX)).isInstanceOfSatisfying(
                    FieldValidationException.class, e -> assertThat(e.getField()).isEqualTo("col"));
            assertThatThrownBy(() -> SeatKeyNormalizer.colLabel(bad)).isInstanceOf(FieldValidationException.class);
        }
        // 사설 영역(Co), 짝 없는 서로게이트(Cs), NUL
        assertThatThrownBy(() -> SeatKeyNormalizer.zoneKey("A")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.zoneLabel("A\uD800")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.zoneLabel("A\u0000")).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 일반_공백과_BOM은_기존대로_공백으로_취급한다() {
        assertThat(SeatKeyNormalizer.zoneLabel("A\tB C")).isEqualTo("A B C");
        assertThat(SeatKeyNormalizer.zoneKey("A\tB﻿")).isEqualTo("AB");
    }

    @Test
    void 변이_선택자는_거부하고_합성_가능한_결합문자는_NFKC로_합친다() {
        assertThatThrownBy(() -> SeatKeyNormalizer.zoneKey("A️")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.zoneLabel("1️")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.rowKey("가󠄀", MAX)).isInstanceOf(FieldValidationException.class);
        // A + 결합 acute -> Á (NFKC), 소문자도 같은 key
        assertThat(SeatKeyNormalizer.zoneKey("Á")).isEqualTo("Á");
        assertThat(SeatKeyNormalizer.zoneKey("á")).isEqualTo("Á");
    }

    @Test
    void 아랍_인도_숫자와_다른_문자체계_숫자는_ASCII_숫자로_바꾼다() {
        assertThat(SeatKeyNormalizer.rowKey("٣٥", MAX)).isEqualTo("35");        // 아랍-인도 35
        assertThat(SeatKeyNormalizer.colKey("۰۷번", MAX)).isEqualTo("7");        // 확장 아랍-인도 07번
        assertThat(SeatKeyNormalizer.rowKey("०३", MAX)).isEqualTo("3");         // 데바나가리 03
        assertThat(SeatKeyNormalizer.zoneKey("١층")).isEqualTo("1층");
        assertThatThrownBy(() -> SeatKeyNormalizer.rowKey("٠", MAX))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.getMessage()).isEqualTo("열은 1 이상이어야 합니다."));
        assertThatThrownBy(() -> SeatKeyNormalizer.colKey("١٠٠٠", MAX))
                .isInstanceOf(FieldValidationException.class);
    }

    @Test
    void 숫자와_문자가_섞인_값은_그대로_허용하고_앞_0도_유지한다() {
        assertThat(SeatKeyNormalizer.rowKey("03a", MAX)).isEqualTo("03A");
        assertThat(SeatKeyNormalizer.colKey("٠٣a", MAX)).isEqualTo("03A");
        assertThat(SeatKeyNormalizer.colKey("1-2", MAX)).isEqualTo("1-2");
    }

    @Test
    void 부호_붙은_정수형은_문자가_아니라_400으로_거부한다() {
        for (String signed : new String[]{"-3", "+3", "−3", "－3", "＋3", "-0"}) {
            assertThatThrownBy(() -> SeatKeyNormalizer.rowKey(signed, MAX)).as(signed)
                    .isInstanceOf(FieldValidationException.class).hasMessage("열은 부호 없는 숫자(1 이상)로 입력해주세요.");
            assertThatThrownBy(() -> SeatKeyNormalizer.colKey(signed, MAX)).as(signed)
                    .isInstanceOf(FieldValidationException.class);
        }
        assertThatThrownBy(() -> SeatKeyNormalizer.rowKey("-3열", MAX)).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> SeatKeyNormalizer.colKey("+3번", MAX)).isInstanceOf(FieldValidationException.class);
        // 숫자가 아닌 값 사이의 하이픈은 그대로 문자로 허용
        assertThat(SeatKeyNormalizer.colKey("A-3", MAX)).isEqualTo("A-3");
    }
}
