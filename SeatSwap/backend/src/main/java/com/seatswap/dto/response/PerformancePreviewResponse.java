package com.seatswap.dto.response;

import java.time.LocalDate;

/**
 * 링크에서 읽은 공연 정보 미리보기 (항상 200). status: OK(제목을 읽음) / UNSUPPORTED_SITE / NO_INFO / FETCH_FAILED.
 * 회차(시각)는 공개 HTML에 없어 포함하지 않는다. 기간은 날짜만.
 */
public record PerformancePreviewResponse(String status, String site, String title, String venueName,
                                         LocalDate periodStart, LocalDate periodEnd) {

    public static PerformancePreviewResponse ok(String site, String title, String venueName,
                                                LocalDate periodStart, LocalDate periodEnd) {
        return new PerformancePreviewResponse("OK", site, title, venueName, periodStart, periodEnd);
    }

    public static PerformancePreviewResponse empty(String status, String site) {
        return new PerformancePreviewResponse(status, site, null, null, null, null);
    }
}
