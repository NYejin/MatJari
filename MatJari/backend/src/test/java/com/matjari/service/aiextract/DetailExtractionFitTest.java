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
}
