package com.matjari.domain;

/**
 * 추가금 유형 (설계 6절 d). 매칭은 유형만 보고 금액은 계산하지 않는다.
 * X = 추가금 X, ANY = 상관없음, POS = 받아야만 교환(금액 > 0), NEG = 낼 의향 있음(금액 < 0).
 */
public enum ExtraType {
    X,
    ANY,
    POS,
    NEG
}
