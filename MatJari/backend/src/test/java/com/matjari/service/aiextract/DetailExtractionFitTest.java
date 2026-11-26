package com.matjari.service.aiextract;

import static org.assertj.core.api.Assertions.assertThat;

import com.matjari.service.aiextract.DetailExtractionService.FitResult;
import com.matjari.service.aiextract.DetailExtractionService.ZoneDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class DetailExtractionFitTest {

    private static ZoneDto zone(String name, int x0, int y0, int x1, int y1) {
        return new ZoneDto(name, List.of(List.of(List.of(x0, y0), List.of(x1, y0), List.of(x1, y1), List.of(x0, y1))));
    }

    @Test
    void 좌석표가_이미지의_일부만_차지하면_그_영역이_전체가_되도록_좌표와_비율을_다시_계산한다() {
        // 700x2400 이미지에서 좌석표가 x 100~900, y 350~520(가로 560px, 세로 408px)만 차지
        List<ZoneDto> zones = List.of(zone("A", 100, 350, 500, 520), zone("B", 500, 350, 900, 520), new ZoneDto("C", null));
        FitResult fit = DetailExtractionService.fitToBounds(zones, 700, 2400);
        assertThat(fit).isNotNull();
        assertThat(fit.zones().get(2).shapePolygons()).isNull();
        int minX = 1000, maxX = 0, minY = 1000, maxY = 0;
        for (ZoneDto z : fit.zones()) {
            if (z.shapePolygons() == null) continue;
            for (List<List<Integer>> poly : z.shapePolygons()) {
                for (List<Integer> pt : poly) {
                    minX = Math.min(minX, pt.get(0)); maxX = Math.max(maxX, pt.get(0));
                    minY = Math.min(minY, pt.get(1)); maxY = Math.max(maxY, pt.get(1));
                }
            }
        }
        assertThat(minX).isBetween(0, 40);
        assertThat(maxX).isBetween(960, 1000);
        assertThat(minY).isBetween(0, 70);
        assertThat(maxY).isBetween(930, 1000);
        // 영역 폭 약 560px·높이 약 408px 이므로 비율은 약 1.37
        assertThat(fit.aspect()).isBetween(1.2, 1.6);
    }

    @Test
    void 모양이_없거나_너무_작으면_null() {
        assertThat(DetailExtractionService.fitToBounds(List.of(new ZoneDto("A", null)), 700, 2400)).isNull();
        assertThat(DetailExtractionService.fitToBounds(List.of(zone("A", 100, 100, 110, 110)), 700, 2400)).isNull();
    }

    @Test
    void 좌석표_그림_전체_사각형이_있으면_구역_밖_블록까지_넓혀_여백_6퍼센트로_맞춘다() {
        // 구역은 x 200~800, y 400~800 이지만 위쪽(y 50~)에 구역이 없는 무대·블록이 있다
        List<ZoneDto> zones = List.of(zone("A", 200, 400, 800, 800));
        FitResult fit = DetailExtractionService.fitToBounds(zones, 700, 1126, new int[]{100, 50, 900, 850});
        assertThat(fit).isNotNull();
        // 합집합 x 100~900, y 50~850 (800x800), 여백 6% = 48
        assertThat(fit.x0()).isEqualTo(52.0);
        assertThat(fit.y0()).isEqualTo(2.0);
        assertThat(fit.spanX()).isEqualTo(896.0);
        assertThat(fit.spanY()).isEqualTo(896.0);
        assertThat(fit.aspect()).isEqualTo(Math.round(896.0 * 700 / (896.0 * 1126) * 1000.0) / 1000.0);
        // 좌표는 같은 사각형 기준: (200,400) -> ((200-52)/896, (400-2)/896)
        List<Integer> p0 = fit.zones().get(0).shapePolygons().get(0).get(0);
        assertThat(p0.get(0)).isEqualTo((int) Math.round(148 / 896.0 * 1000));
        assertThat(p0.get(1)).isEqualTo((int) Math.round(398 / 896.0 * 1000));
        // 원본 이미지 비율 사각형도 같은 기준(잘라 낸 이미지가 원본 전체일 때)
        var r = DetailExtractionService.seatmapRect("https://cdnticket.melon.co.kr/upload/product/x.jpg", fit, 0, 1126, 1126);
        assertThat(r.left()).isEqualByComparingTo("0.052");
        assertThat(r.top()).isEqualByComparingTo("0.002");
        assertThat(r.width()).isEqualByComparingTo("0.896");
        assertThat(r.height()).isEqualByComparingTo("0.896");
    }

    @Test
    void 좌석표_그림_사각형은_이미지_안으로_자르고_합집합이라_구역이_잘리지_않는다() {
        FitResult fit = DetailExtractionService.fitToBounds(List.of(zone("A", 20, 20, 980, 980)), 1000, 1000, new int[]{0, 0, 1000, 1000});
        assertThat(fit.x0()).isZero();
        assertThat(fit.y0()).isZero();
        assertThat(fit.spanX()).isEqualTo(1000.0);
        assertThat(fit.spanY()).isEqualTo(1000.0);
        // 구역이 지도 사각형보다 넓어도 합집합이라 구역이 잘리지 않는다
        FitResult small = DetailExtractionService.fitToBounds(List.of(zone("A", 200, 200, 600, 600)), 1000, 1000, new int[]{300, 300, 400, 400});
        assertThat(small.x0()).isEqualTo(200.0 - 24);
        assertThat(small.spanX()).isEqualTo(400.0 + 48);
        // 세그먼트 정보가 없으면(null) 예전처럼 3% 여백
        assertThat(DetailExtractionService.fitToBounds(List.of(zone("A", 200, 200, 600, 600)), 1000, 1000, null).x0()).isEqualTo(188.0);
    }

    @Test
    void 지도_블록_사각형은_구역에_안_붙은_블록과_무대는_포함하고_범례_칩은_뺀다() {
        ShapeSegmenter.Result seg = new ShapeSegmenter.Result(1001, 1001, List.of(
                new ShapeSegmenter.Segment(1, rect(300, 400, 700, 700), 120000, 500, 550, 0.6, 0.8),
                new ShapeSegmenter.Segment(2, rect(350, 60, 650, 150), 27000, 500, 100, 0.1, 0.5), // 구역에 연결되지 않은 무대
                new ShapeSegmenter.Segment(3, rect(100, 950, 160, 975), 1500, 130, 962, 0.6, 0.8)), null, null); // 'R석' 범례 칩
        List<DetailExtractor.RawLabel> before = List.of(
                new DetailExtractor.RawLabel("A", List.of(1)),
                new DetailExtractor.RawLabel("R석", List.of(3)));
        int[] b = DetailExtractionService.mapBounds(before, List.of(before.get(0)), seg);
        assertThat(b).containsExactly(300, 60, 700, 700);
    }

    private static List<int[]> rect(int x0, int y0, int x1, int y1) {
        return List.of(new int[]{x0, y0}, new int[]{x1, y0}, new int[]{x1, y1}, new int[]{x0, y1});
    }
}
