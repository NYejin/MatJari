package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.dto.response.SeatmapImageResponse;
import com.matjari.service.aiextract.DetailExtractionService;
import com.matjari.service.aiextract.DetailImageFetcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 원본 좌석표 이미지 위치(V31)의 저장 값 고르기와 응답 내보내기.
 * 서버는 이미지를 저장·복제·프록시하지 않는다(주소와 비율 사각형만 다룬다, 포스터와 같은 방침).
 *
 * 저장할 값은 사용자 요청에서 받지 않고 항상 링크(sourceKey)로 AI 보관 결과(메모리 → DB)에서만 얻는다.
 * 보관 결과의 좌석표 비율(seatmapAspect)이 요청의 비율과 같을 때만 쓴다: 사용자가 좌석표를 따로 올려 인식한 구역(같은 링크의 보관 결과와
 * 기준 영역이 다르다)에 엉뚱한 원본 이미지 위치가 붙는 것을 막는다.
 */
@Service
public class SeatmapImageService {

    /** 비율은 소수 3자리로 저장·전달되므로 반올림 오차 정도만 허용한다. */
    private static final double ASPECT_TOLERANCE = 0.0025;

    private final DetailExtractionService extraction;
    private final DetailImageFetcher imageFetcher;
    private final boolean enabled;

    public SeatmapImageService(DetailExtractionService extraction, DetailImageFetcher imageFetcher,
                               @Value("${matjari.seatmap-image.enabled:true}") boolean enabled) {
        this.extraction = extraction;
        this.imageFetcher = imageFetcher;
        this.enabled = enabled;
    }

    /**
     * 링크의 AI 보관 결과에서 좌석표 이미지 위치를 찾는다. 없거나(보관 만료 포함), 주소·범위가 올바르지 않거나,
     * 비율이 requestAspect 와 다르면 비어 있다. 호출한 쪽이 "모양 있는 구역이 있다"는 조건을 따로 확인한다.
     */
    public Optional<SeatmapImageResponse> findStored(String sourceKey, BigDecimal requestAspect) {
        if (requestAspect == null) {
            return Optional.empty();
        }
        DetailExtractionService.Response stored = extraction.findStored(sourceKey).orElse(null);
        if (stored == null) {
            return Optional.empty();
        }
        SeatmapImageResponse image = stored.seatmapImage();
        if (image == null || !image.rectValid() || !imageFetcher.isAllowedImageUrl(image.url())
                || stored.seatmapAspect() == null
                || Math.abs(stored.seatmapAspect() - requestAspect.doubleValue()) > ASPECT_TOLERANCE) {
            return Optional.empty();
        }
        return Optional.of(image);
    }

    /** 공연에 저장된 값을 응답용으로. 스위치가 꺼져 있거나 값이 없으면 null. */
    public SeatmapImageResponse expose(Performance performance) {
        if (!enabled || performance == null || performance.getSeatmapImageUrl() == null) {
            return null;
        }
        return new SeatmapImageResponse(performance.getSeatmapImageUrl(), performance.getSeatmapCropLeft(),
                performance.getSeatmapCropTop(), performance.getSeatmapCropWidth(), performance.getSeatmapCropHeight());
    }

    /** 공연에 저장(null 이면 비움). 조회에서 오는 보관 결과만 넘기므로 여기서도 범위를 한 번 더 확인한다. */
    public static void apply(Performance performance, SeatmapImageResponse image) {
        if (image == null) {
            performance.changeSeatmapImage(null, null, null, null, null);
        } else {
            performance.changeSeatmapImage(image.url(), image.left(), image.top(), image.width(), image.height());
        }
    }
}
