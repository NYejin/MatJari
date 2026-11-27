package com.matjari.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 한 공연의 예매처 링크(V32)와 같은 공연 후보 응답. */
public final class PerformanceSourceDtos {

    private PerformanceSourceDtos() {}

    /**
     * 공연에 달린 링크 하나. site: melon / interpark / ticketlink / yes24 (그 밖의 링크는 etc),
     * siteName: 멜론티켓 / 인터파크(NOL) / 티켓링크 / YES24 (그 밖은 기타). primary: 공연의 대표 링크.
     * id 는 저장되지 않은 대표 링크 합성 응답(단위 시험)에서만 null.
     * mapStatus·zoneCount(V34): 그 링크의 지도 상태(NONE·PENDING·READY·FAILED)와 구역 수. 대표 링크는 대표 지도에 구역이 있으면 READY, 없으면 NONE.
     */
    public record SourceItem(Long id, String site, String siteName, String url, boolean primary, String mapStatus, long zoneCount) {
        /** 지도 정보 없이(NONE, 구역 0개). */
        public SourceItem(Long id, String site, String siteName, String url, boolean primary) {
            this(id, site, siteName, url, primary, "NONE", 0L);
        }
    }

    public record SourcesResponse(List<SourceItem> sources) {}

    /** 링크 미리보기 중 같은 공연 판정에 쓴 값. status 는 미리보기 상태(OK·UNSUPPORTED_SITE·NO_INFO·FETCH_FAILED). */
    public record PreviewInfo(String status, String title, String venueName, LocalDate periodStart, LocalDate periodEnd) {}

    /** 같은 공연일 수 있는 기존 공연. sites 는 그 공연에 달린 링크의 사이트 목록(중복 없이, 대표 먼저). */
    public record Candidate(Long id, String title, String venueName,
                            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE) LocalDateTime firstSessionAt,
                            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = DateTimeFormats.MINUTE) LocalDateTime lastSessionAt,
                            int sessionCount, List<String> sites) {}

    public record SimilarResponse(PreviewInfo preview, Long existingPerformanceId, List<Candidate> candidates) {}
}
