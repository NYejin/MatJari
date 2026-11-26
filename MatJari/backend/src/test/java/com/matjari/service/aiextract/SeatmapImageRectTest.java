package com.matjari.service.aiextract;

import com.matjari.dto.response.SeatmapImageResponse;
import com.matjari.service.SourceKeyResolver;
import com.matjari.service.linkinfo.PerformancePreviewService;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 원본 좌석표 이미지 위치(V31): 줄이기·잘라내기·fitToBounds 를 거친 비율 사각형 환산, 업로드는 null, 스위치. */
class SeatmapImageRectTest {

    private static final String URL = "https://cdnticket.melon.co.kr/resource/image/upload/product/a.jpg";

    private static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static DetailExtractionService.ZoneDto zone(int x0, int y0, int x1, int y1) {
        return new DetailExtractionService.ZoneDto("A", List.of(List.of(
                List.of(x0, y0), List.of(x1, y0), List.of(x1, y1), List.of(x0, y1))));
    }

    @Test
    void 줄인_이미지에서_세로로_잘라_낸_좌석표의_맞춤_사각형을_원본_비율로_환산한다() throws IOException {
        // 원본 2000x6000 -> 폭 1000 으로 줄이면 1000x3000. 그 중 y 1000~2000 을 잘라 낸다(높이 1000).
        AiProperties props = new AiProperties();
        ImageSlicer slicer = new ImageSlicer(props);
        byte[] bytes = png(2000, 6000);
        ImageSlicer.SliceResult sliced = slicer.slice(bytes);
        assertThat(sliced.imageWidth()).isEqualTo(1000);
        assertThat(sliced.imageHeight()).isEqualTo(3000);
        ImageSlicer.SliceImage target = slicer.crop(bytes, 1000, 2000);
        assertThat(target.top()).isEqualTo(1000);
        assertThat(target.height()).isEqualTo(1000);

        // 잘라 낸 이미지(0~1000 좌표) 안에서 x 200~600, y 100~500 을 덮는 구역
        DetailExtractionService.FitResult fit = DetailExtractionService.fitToBounds(
                List.of(zone(200, 100, 600, 500)), target.width(), target.height());
        assertThat(fit).isNotNull();
        // 여백 12 -> 맞춘 사각형 x 188~612, y 88~512 (잘라 낸 이미지 기준)
        assertThat(fit.x0()).isEqualTo(188.0);
        assertThat(fit.spanX()).isEqualTo(424.0);

        SeatmapImageResponse r = DetailExtractionService.seatmapRect(URL, fit, target.top(), target.height(), sliced.imageHeight());
        assertThat(r.url()).isEqualTo(URL);
        assertThat(r.left()).isEqualByComparingTo("0.188");
        assertThat(r.width()).isEqualByComparingTo("0.424");
        // top = (1000 + 0.088*1000)/3000, height = 0.424*1000/3000
        assertThat(r.top()).isEqualByComparingTo(new BigDecimal(1088.0 / 3000).setScale(6, java.math.RoundingMode.HALF_UP));
        assertThat(r.height()).isEqualByComparingTo(new BigDecimal(424.0 / 3000).setScale(6, java.math.RoundingMode.HALF_UP));
        // 화면 비율은 seatmapAspect 와 같아야 한다(원본 픽셀 기준)
        double px = r.width().doubleValue() * 2000, py = r.height().doubleValue() * 6000;
        assertThat(px / py).isCloseTo(fit.aspect(), org.assertj.core.data.Offset.offset(0.002));
    }

    @Test
    void 여백이_이미지_밖으로_나가면_이미지_안으로_자르고_좌표도_같은_사각형으로_맞춘다() {
        // 모양이 왼쪽·위 가장자리에 닿아 있다: 여백(최소 10)을 더하면 음수가 되므로 0 으로 자른다
        DetailExtractionService.FitResult fit = DetailExtractionService.fitToBounds(
                List.of(zone(0, 0, 400, 400)), 1000, 1000);
        assertThat(fit.x0()).isZero();
        assertThat(fit.y0()).isZero();
        assertThat(fit.spanX()).isEqualTo(412.0); // 400 + 오른쪽 여백 12
        List<List<Integer>> pts = fit.zones().get(0).shapePolygons().get(0);
        assertThat(pts.get(0)).containsExactly(0, 0);
        assertThat(pts.get(1).get(0)).isEqualTo(Math.round(400 / 412.0 * 1000)); // 같은 사각형을 기준으로 다시 계산
        SeatmapImageResponse r = DetailExtractionService.seatmapRect(URL, fit, 0, 1000, 1000);
        assertThat(r.left()).isEqualByComparingTo("0");
        assertThat(r.width()).isEqualByComparingTo("0.412");

        // 오른쪽·아래 가장자리도 1 을 넘지 않는다
        DetailExtractionService.FitResult fit2 = DetailExtractionService.fitToBounds(
                List.of(zone(600, 600, 1000, 1000)), 1000, 1000);
        SeatmapImageResponse r2 = DetailExtractionService.seatmapRect(URL, fit2, 0, 1000, 1000);
        assertThat(r2.left().add(r2.width())).isLessThanOrEqualTo(new BigDecimal("1.000001"));
        assertThat(r2.top().add(r2.height())).isLessThanOrEqualTo(new BigDecimal("1.000001"));
    }

    @Test
    void 계산할_수_없는_입력은_null() {
        DetailExtractionService.FitResult fit = DetailExtractionService.fitToBounds(List.of(zone(100, 100, 500, 500)), 1000, 1000);
        assertThat(DetailExtractionService.seatmapRect(null, fit, 0, 1000, 1000)).isNull();
        assertThat(DetailExtractionService.seatmapRect(URL, null, 0, 1000, 1000)).isNull();
        assertThat(DetailExtractionService.seatmapRect(URL, fit, 0, 0, 1000)).isNull();
        assertThat(DetailExtractionService.seatmapRect(URL, fit, 500, 1000, 1000)).isNull(); // 잘라 낸 구간이 이미지 밖
    }

    // ---- 서비스: 업로드는 null, 보관 결과, 스위치 ----

    private DetailExtractionService service(DetailExtractor extractor, AiResultStore store, AiProperties props) {
        PerformancePreviewService preview = mock(PerformancePreviewService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        return new DetailExtractionService(new SourceKeyResolver(), preview, mock(SessionTextParser.class),
                new DetailImageFetcher(props), new ImageSlicer(props), new ShapeSegmenter(), extractor,
                new AiUsageLimiter(clock, props), props, clock, store);
    }

    @Test
    void 좌석표_업로드_인식은_이미지를_저장하지_않으므로_항상_null() throws IOException {
        AiProperties props = new AiProperties();
        DetailExtractor extractor = mock(DetailExtractor.class);
        when(extractor.available()).thenReturn(true);
        when(extractor.first(org.mockito.ArgumentMatchers.anyList())).thenReturn(new DetailExtractor.FirstPass(
                List.of(), List.of("A구역"), new DetailExtractor.SeatMapLocation(false, 0, 0, 0)));
        DetailExtractionService.Response r = service(extractor, null, props).extractSeatMap(1L, png(400, 300));
        assertThat(r.seatmapImage()).isNull();
    }

    @Test
    void 보관_결과의_이미지_위치는_응답에_나가고_스위치가_꺼지면_응답에서만_빠진다() {
        AiProperties props = new AiProperties();
        AiResultStore store = mock(AiResultStore.class);
        SeatmapImageResponse image = new SeatmapImageResponse(URL, new BigDecimal("0.1"), new BigDecimal("0.2"),
                new BigDecimal("0.8"), new BigDecimal("0.5"));
        DetailExtractionService.Response saved = new DetailExtractionService.Response(List.of(), "NONE",
                List.of(new DetailExtractionService.ZoneDto("A", List.of(List.of(List.of(0, 0), List.of(9, 0), List.of(9, 9))))),
                1.5, true, false, false, false, false, image);
        when(store.find(anyString())).thenReturn(Optional.of(saved));
        DetailExtractor extractor = mock(DetailExtractor.class);

        DetailExtractionService on = service(extractor, store, props);
        assertThat(on.extract(1L, "https://ticket.melon.com/performance/index.htm?prodId=111").seatmapImage()).isEqualTo(image);

        DetailExtractionService off = service(extractor, store, props);
        off.setSeatmapImageEnabled(false);
        assertThat(off.extract(1L, "https://ticket.melon.com/performance/index.htm?prodId=111").seatmapImage()).isNull();
        assertThat(off.findStored("melon:111")).get().extracting(DetailExtractionService.Response::seatmapImage).isEqualTo(image); // 저장 값은 그대로
    }
}
