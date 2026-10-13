package com.matjari.domain;

/** 사용자 권한. 가입은 항상 USER, ADMIN은 DB에서 직접 부여한다 (2026-10-07 결정). */
public enum UserRole {
    USER,
    ADMIN
}
