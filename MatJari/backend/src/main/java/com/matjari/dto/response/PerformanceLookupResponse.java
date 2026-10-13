package com.matjari.dto.response;

/** 링크로 기존 공연 조회 결과. 없으면 {"exists":false,"performanceId":null}. */
public record PerformanceLookupResponse(boolean exists, Long performanceId) {
    public static PerformanceLookupResponse found(Long performanceId) {
        return new PerformanceLookupResponse(true, performanceId);
    }

    public static PerformanceLookupResponse notFound() {
        return new PerformanceLookupResponse(false, null);
    }
}
