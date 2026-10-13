package com.matjari.dto.response;

/** 응답 일시 포맷 (프론트와 합의). 모두 타임존 없는 로컬 시각 문자열. */
public final class DateTimeFormats {

    /** 회차 시각: "2026-11-01T19:00" (KST, 초 없음). */
    public static final String MINUTE = "yyyy-MM-dd'T'HH:mm";

    /** 생성 시각 등: "2026-10-06T14:03:21". */
    public static final String SECOND = "yyyy-MM-dd'T'HH:mm:ss";

    private DateTimeFormats() {}
}
