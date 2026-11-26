package com.matjari.service;

import com.matjari.domain.Performance;
import com.matjari.dto.response.SeatmapImageResponse;
import com.matjari.service.aiextract.AiProperties;
import com.matjari.service.aiextract.DetailExtractionService;
import com.matjari.service.aiextract.DetailImageFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 원본 좌석표 이미지 위치(V31): 보관 결과에서만 고르고, 주소·비율이 어긋나면 쓰지 않으며, 스위치가 꺼지면 응답에서 뺀다. */
class SeatmapImageServiceTest {

    private static final String URL = "https://cdnticket.melon.co.kr/resource/image/upload/product/a.jpg";

    private DetailExtractionService extraction;
    private DetailImageFetcher fetcher;

    @BeforeEach
    void setUp() {
        extraction = mock(DetailExtractionService.class);
        fetcher = new DetailImageFetcher(new AiProperties());
    }

    private static SeatmapImageResponse image(String url) {
        return new SeatmapImageResponse(url, new BigDecimal("0.1"), new BigDecimal("0.2"), new BigDecimal("0.8"), new BigDecimal("0.5"));
    }

    private static DetailExtractionService.Response stored(SeatmapImageResponse image, Double aspect) {
        return new DetailExtractionService.Response(List.of(), "NONE", List.of(), aspect, true, false, false, false, false, image);
    }

    private SeatmapImageService service(boolean enabled) {
        return new SeatmapImageService(extraction, fetcher, enabled);
    }

    @Test
    void 보관_결과의_이미지_위치는_비율이_같을_때만_쓴다() {
        when(extraction.findStored("melon:1")).thenReturn(Optional.of(stored(image(URL), 1.5)));
        assertThat(service(true).findStored("melon:1", new BigDecimal("1.500"))).contains(image(URL));
        assertThat(service(true).findStored("melon:1", new BigDecimal("1.501"))).isPresent(); // 반올림 오차 이내
        assertThat(service(true).findStored("melon:1", new BigDecimal("1.700"))).isEmpty(); // 다른 기준 영역(예: 사용자가 올린 좌석표)
        assertThat(service(true).findStored("melon:1", null)).isEmpty();
    }

    @Test
    void 보관_결과가_없거나_이미지가_없으면_비어_있다() {
        when(extraction.findStored("melon:2")).thenReturn(Optional.empty());
        when(extraction.findStored("melon:3")).thenReturn(Optional.of(stored(null, 1.5)));
        assertThat(service(true).findStored("melon:2", new BigDecimal("1.5"))).isEmpty();
        assertThat(service(true).findStored("melon:3", new BigDecimal("1.5"))).isEmpty();
    }

    @Test
    void 허용_호스트_밖_http_경로_밖_주소는_거부한다() {
        for (String bad : List.of("https://evil.example.com/upload/product/a.jpg",
                "http://cdnticket.melon.co.kr/resource/image/upload/product/a.jpg",
                "https://cdnticket.melon.co.kr/other/a.jpg",
                "https://cdnticket.melon.co.kr:8443/resource/image/upload/product/a.jpg",
                "https://user@cdnticket.melon.co.kr/resource/image/upload/product/a.jpg",
                "https://cdnticket.melon.co.kr.evil.com/resource/image/upload/product/a.jpg",
                "javascript:alert(1)")) {
            when(extraction.findStored("melon:4")).thenReturn(Optional.of(stored(image(bad), 1.5)));
            assertThat(service(true).findStored("melon:4", new BigDecimal("1.5"))).as(bad).isEmpty();
        }
        assertThat(fetcher.isAllowedImageUrl(URL)).isTrue();
        assertThat(fetcher.isAllowedImageUrl("https://image.toast.com/aaaaab/ticketlink/TKL_9/a.jpg")).isTrue();
        assertThat(fetcher.isAllowedImageUrl("https://ticketimage.interpark.com/Play/image/etc/26/a.jpg")).isTrue();
        assertThat(fetcher.isAllowedImageUrl(URL + "?" + "a".repeat(2100))).isFalse();
        assertThat(fetcher.isAllowedImageUrl(null)).isFalse();
    }

    @Test
    void 범위를_벗어난_값은_쓰지_않는다() {
        SeatmapImageResponse over = new SeatmapImageResponse(URL, new BigDecimal("0.5"), new BigDecimal("0.2"),
                new BigDecimal("0.8"), new BigDecimal("0.5"));
        when(extraction.findStored("melon:5")).thenReturn(Optional.of(stored(over, 1.5)));
        assertThat(service(true).findStored("melon:5", new BigDecimal("1.5"))).isEmpty();
    }

    @Test
    void 스위치가_꺼지면_응답에서_null_켜지면_저장값을_그대로() {
        Performance p = PerformanceFixtures.performance(1L, "V", PerformanceFixtures.user(1L, "a"));
        assertThat(service(true).expose(p)).isNull();
        SeatmapImageService.apply(p, image(URL));
        assertThat(service(true).expose(p)).isEqualTo(image(URL));
        assertThat(service(false).expose(p)).isNull();
        assertThat(p.getSeatmapImageUrl()).isEqualTo(URL); // 저장은 그대로
        SeatmapImageService.apply(p, null);
        assertThat(service(true).expose(p)).isNull();
        assertThat(p.getSeatmapCropLeft()).isNull();
    }
}
