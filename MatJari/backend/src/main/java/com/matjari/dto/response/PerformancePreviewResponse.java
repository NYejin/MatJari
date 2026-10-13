package com.matjari.dto.response;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 링크에서 읽은 공연 정보 미리보기 (항상 200). status: OK(제목을 읽음) / UNSUPPORTED_SITE / NO_INFO / FETCH_FAILED.
 * 회차(시각)는 공개 HTML에 없어 읽지 않는다. 기간은 날짜만.
 * sessionCandidates: 회차 날짜 후보. 지금은 공연 기간이 하루·이틀일 때만 그 날짜들을 시각 없이(time=null) 채운다.
 * 시각은 사용자가 직접 입력하며, 나중에 상세정보를 읽어 시각까지 채우는 출처가 붙으면 같은 구조(time 채움)로 내려 준다.
 */
public record PerformancePreviewResponse(String status, String site, String title, String venueName,
                                         LocalDate periodStart, LocalDate periodEnd, String posterUrl,
                                         List<SessionCandidate> sessionCandidates) {

    /** 회차 후보 하나. time 이 null 이면 날짜만 아는 후보다. */
    public record SessionCandidate(LocalDate date, LocalTime time) {}

    /** 이 일수(시작·종료 포함) 이하인 공연만 기간의 날짜를 회차 후보로 채운다(그 이상은 회차 수를 날짜로 알 수 없다). */
    public static final int MAX_PERIOD_DAYS_FOR_CANDIDATES = 2;

    /** 기간이 시작·종료 모두 있고 {@value #MAX_PERIOD_DAYS_FOR_CANDIDATES}일 이하일 때만 날짜 후보(시각 없음), 아니면 빈 목록. */
    static List<SessionCandidate> candidatesFromPeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            return List.of();
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_PERIOD_DAYS_FOR_CANDIDATES) {
            return List.of();
        }
        return start.datesUntil(end.plusDays(1)).map(d -> new SessionCandidate(d, null)).toList();
    }

    /** posterUrl: 사이트별 고정 호스트 허용 목록을 통과한 https 이미지 주소 문자열(서버는 이미지를 받지 않는다). 없으면 null. */
    public static PerformancePreviewResponse ok(String site, String title, String venueName,
                                                LocalDate periodStart, LocalDate periodEnd, String posterUrl) {
        return new PerformancePreviewResponse("OK", site, title, venueName, periodStart, periodEnd, posterUrl,
                candidatesFromPeriod(periodStart, periodEnd));
    }

    public static PerformancePreviewResponse empty(String status, String site) {
        return new PerformancePreviewResponse(status, site, null, null, null, null, null, List.of());
    }

    /** 포스터를 끈 응답(설정 스위치)에서 posterUrl 만 지운다. */
    public PerformancePreviewResponse withoutPoster() {
        return posterUrl == null ? this
                : new PerformancePreviewResponse(status, site, title, venueName, periodStart, periodEnd, null, sessionCandidates);
    }
}
