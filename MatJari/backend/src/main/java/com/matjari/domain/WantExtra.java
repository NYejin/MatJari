package com.matjari.domain;

/**
 * 희망 범위 하나에 붙은 추가금 (유형 + 참고 금액). 매칭 판정은 유형만 쓰고 금액은 표시용이다.
 * X/ANY 는 금액 null, POS 는 금액 &gt; 0, NEG 는 금액 &lt; 0 (DB CHECK 와 같은 규칙).
 */
public record WantExtra(ExtraType type, Integer amount) {

    public WantExtra {
        if (type == null) {
            throw new IllegalArgumentException("extraType은 필수입니다.");
        }
        boolean valid = switch (type) {
            case X, ANY -> amount == null;
            case POS -> amount != null && amount > 0;
            case NEG -> amount != null && amount < 0;
        };
        if (!valid) {
            throw new IllegalArgumentException("추가금 유형과 금액이 맞지 않습니다: " + type);
        }
    }
}
