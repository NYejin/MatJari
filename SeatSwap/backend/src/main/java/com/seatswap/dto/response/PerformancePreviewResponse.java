package com.seatswap.dto.response;

import java.time.LocalDate;

/**
 * 링크에서 읽은 공연 정보 미리보기 (항상 200). status: OK(제목을 읽음) / UNSUPPORTED_SITE / NO_INFO / FETCH_FAILED.
 * 회차(시각)는 공개 HTML에 없어 포함하지 않는다. 기간은 날짜만.
 */
public record PerformancePreviewResponse(String status, String site, String title, String venueName,
                                         LocalDate periodStart, LocalDate periodEnd, String posterUrl) {

    /** posterUrl: 사이트별 고정 호스트 허용 목록을 통과한 https 이미지 주소 문자열(서버는 이미지를 받지 않는다). 없으면 null. */
    public static PerformancePreviewResponse ok(String site, String title, String venueName,
                                                LocalDate periodStart, LocalDate periodEnd, String posterUrl) {
        return new PerformancePreviewResponse("OK", site, title, venueName, periodStart, periodEnd, posterUrl);
    }

    public static PerformancePreviewResponse empty(String status, String site) {
        return new PerformancePreviewResponse(status, site, null, null, null, null, null);
    }

    /** 포스터를 끈 응답(설정 스위치)에서 posterUrl 만 지운다. */
    public PerformancePreviewResponse withoutPoster() {
        return posterUrl == null ? this
                : new PerformancePreviewResponse(status, site, title, venueName, periodStart, periodEnd, null);
    }
}
