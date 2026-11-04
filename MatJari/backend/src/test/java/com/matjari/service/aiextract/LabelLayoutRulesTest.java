package com.matjari.service.aiextract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 열 머리 이름(columnHeaders)과 범례 알약 빼기(dropLegendRow) 규칙. */
class LabelLayoutRulesTest {

    private static ShapeSegmenter.Segment rect(int id, int x0, int y0, int x1, int y1) {
        List<int[]> pts = List.of(new int[]{x0, y0}, new int[]{x1, y0}, new int[]{x1, y1}, new int[]{x0, y1});
        return new ShapeSegmenter.Segment(id, pts, (x1 - x0) * (y1 - y0), (x0 + x1) / 2, (y0 + y1) / 2, 0.5, 150);
    }

    private static ShapeSegmenter.Result result(List<ShapeSegmenter.Segment> segs) {
        return new ShapeSegmenter.Result(1000, 1000, segs, new byte[0], new byte[0]);
    }

    /** 4열 x 3줄 블록, 이름은 맨 위 줄에만 있다(209335 가~라). */
    @Test
    void columnHeadersAttachBlocksBelowNamedTop() {
        List<ShapeSegmenter.Segment> segs = new ArrayList<>();
        List<DetailExtractor.RawLabel> labels = new ArrayList<>();
        String[] names = {"가", "나", "다", "라"};
        int id = 1;
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 3; r++) {
                segs.add(rect(id, 100 + c * 100, 100 + r * 60, 180 + c * 100, 150 + r * 60));
                if (r == 0) {
                    labels.add(new DetailExtractor.RawLabel(names[c], List.of(id)));
                }
                id++;
            }
        }
        List<DetailExtractor.RawLabel> out = DetailExtractionService.columnHeaders(labels, result(segs));
        assertThat(out).hasSize(4);
        assertThat(out.get(0).segmentIds()).containsExactlyInAnyOrder(1, 2, 3);
        assertThat(out.get(3).segmentIds()).containsExactlyInAnyOrder(10, 11, 12);
    }

    /** 이름 있는 블록 위에 다른 블록이 붙어 있으면 열 머리 지도가 아니다(아무것도 붙이지 않는다). */
    @Test
    void columnHeadersSkipWhenNamedBlockIsNotTop() {
        List<ShapeSegmenter.Segment> segs = new ArrayList<>();
        List<DetailExtractor.RawLabel> labels = new ArrayList<>();
        int id = 1;
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 3; r++) {
                segs.add(rect(id, 100 + c * 100, 100 + r * 60, 180 + c * 100, 150 + r * 60));
                if (r == 1) {
                    labels.add(new DetailExtractor.RawLabel("Z" + c, List.of(id)));
                }
                id++;
            }
        }
        assertThat(DetailExtractionService.columnHeaders(labels, result(segs))).isEqualTo(labels);
    }

    /** 지도 아래 범례 줄의 알약(이름 없는 알약 2개와 나란한 'VIP')은 구역에서 뺀다. 지도 안 구역은 그대로다. */
    @Test
    void dropLegendRowRemovesPillBelowMap() {
        List<ShapeSegmenter.Segment> segs = new ArrayList<>();
        List<DetailExtractor.RawLabel> labels = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            segs.add(rect(i + 1, 100 + i * 80, 100, 160 + i * 80, 200));
            labels.add(new DetailExtractor.RawLabel(String.valueOf(i + 1), List.of(i + 1)));
        }
        segs.add(rect(7, 100, 500, 160, 520));
        segs.add(rect(8, 180, 500, 240, 520));
        segs.add(rect(9, 260, 500, 320, 520));
        labels.add(new DetailExtractor.RawLabel("VIP", List.of(7)));
        List<DetailExtractor.RawLabel> out = DetailExtractionService.dropLegendRow(labels, result(segs));
        assertThat(out).extracting(DetailExtractor.RawLabel::name).containsExactly("1", "2", "3", "4", "5", "6");
    }

    /** 지도 아래 줄에 있어도 같은 줄 도형이 이름 있는 구역이면(207407 2층 C1~C3) 빼지 않는다. */
    @Test
    void dropLegendRowKeepsNamedRow() {
        List<ShapeSegmenter.Segment> segs = new ArrayList<>();
        List<DetailExtractor.RawLabel> labels = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            segs.add(rect(i + 1, 100 + i * 80, 100, 160 + i * 80, 200));
            labels.add(new DetailExtractor.RawLabel(String.valueOf(i + 1), List.of(i + 1)));
        }
        segs.add(rect(7, 100, 500, 160, 560));
        segs.add(rect(8, 180, 500, 240, 560));
        labels.add(new DetailExtractor.RawLabel("C1", List.of(7)));
        labels.add(new DetailExtractor.RawLabel("C2", List.of(8)));
        assertThat(DetailExtractionService.dropLegendRow(labels, result(segs))).hasSize(8);
    }
}
