package com.matjari.service.aiextract;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeSegmenterTest {

    private static byte[] image() throws Exception {
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(240, 240, 240));
        g.fillRect(0, 0, 400, 300);
        g.setColor(new Color(110, 40, 140)); // 보라 사각형
        g.fillRect(50, 60, 100, 80);
        g.setColor(new Color(60, 130, 200)); // 파랑 삼각형
        g.fillPolygon(new int[]{220, 340, 220}, new int[]{40, 240, 240}, 3);
        g.setColor(Color.WHITE); // 보라 사각형 안의 글자 같은 작은 조각
        g.fillRect(90, 90, 12, 12);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    @Test
    void findsColoredShapesAndIgnoresInnerSpecks() throws Exception {
        ShapeSegmenter.Result r = new ShapeSegmenter().segment(image());
        assertThat(r).isNotNull();
        assertThat(r.segments()).hasSize(2);
        assertThat(r.annotatedJpeg()).isNotEmpty();
        // 번호는 위→아래·왼→오른쪽: 사각형(위 y=100 근처)과 삼각형(y=173 근처)
        ShapeSegmenter.Segment rect = r.segments().get(0);
        assertThat(rect.area()).isBetween(7500, 8300);
        assertThat(rect.pixels().size()).isBetween(3, 8);
        ShapeSegmenter.Segment tri = r.segments().get(1);
        assertThat(tri.area()).isBetween(10000, 12500);
    }

    @Test
    void shapesFromLabelsNormalizesAndSkipsUsedOrMissingIds() throws Exception {
        ShapeSegmenter.Result r = new ShapeSegmenter().segment(image());
        List<DetailExtractor.RawZone> zones = DetailExtractionService.shapesFromLabels(List.of(
                new DetailExtractor.RawLabel("A", List.of(1, 99)),
                new DetailExtractor.RawLabel("B", List.of(1, 2)),
                new DetailExtractor.RawLabel("C", List.of(77))), r);
        assertThat(zones).hasSize(2);
        assertThat(zones.get(0).name()).isEqualTo("A");
        assertThat(zones.get(0).polygons()).hasSize(1);
        assertThat(zones.get(1).polygons()).hasSize(1); // 1번은 A가 이미 썼다
        for (List<List<Integer>> poly : zones.get(0).polygons()) {
            for (List<Integer> pt : poly) {
                assertThat(pt.get(0)).isBetween(0, 1000);
                assertThat(pt.get(1)).isBetween(0, 1000);
            }
        }
    }

    private static byte[] blocks(int gap) throws Exception {
        BufferedImage img = new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(60, 30, 35)); // 어두운 배경
        g.fillRect(0, 0, 300, 200);
        g.setColor(new Color(150, 0, 10)); // 진한 빨강(VIP) 위쪽 부분
        g.fillRect(40, 40, 80, 30);
        g.setColor(new Color(240, 100, 0)); // 주황(일반석) 아래쪽 부분: 바로 붙어 있다
        g.fillRect(40, 70, 80, 50);
        g.setColor(new Color(240, 100, 0)); // 이웃 블록: gap 만큼 떨어져 있다
        g.fillRect(40, 120 + gap, 80, 50);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    @Test
    void mergesTouchingColorPartsOfOneBlockButNotSeparatedNeighbors() throws Exception {
        ShapeSegmenter.Result r = new ShapeSegmenter().segment(blocks(8));
        assertThat(r).isNotNull();
        // 빨강+주황은 한 블록, 8px 떨어진 이웃 블록은 별개
        assertThat(r.segments()).hasSize(2);
        assertThat(r.segments().get(0).area()).isGreaterThan(80 * 80 - 100);
        assertThat(r.tilesJpeg()).isNotEmpty();
    }

    @Test
    void joinPartsMakesOneBlockOnlyWhenPartsFormOneCompactShape() {
        List<List<List<Integer>>> adjacent = List.of(
                List.of(List.of(100, 100), List.of(300, 100), List.of(300, 160), List.of(100, 160)),
                List.of(List.of(100, 170), List.of(300, 170), List.of(300, 300), List.of(100, 300)));
        assertThat(DetailExtractionService.joinParts(adjacent)).hasSize(1);
        List<List<List<Integer>>> apart = List.of(
                List.of(List.of(0, 0), List.of(100, 0), List.of(100, 100), List.of(0, 100)),
                List.of(List.of(800, 800), List.of(900, 800), List.of(900, 900), List.of(800, 900)));
        assertThat(DetailExtractionService.joinParts(apart)).hasSize(2);
    }

    @Test
    void combineVotesPicksMajorityPerSegment() {
        DetailExtractor.LabelResult a = new DetailExtractor.LabelResult(List.of(
                new DetailExtractor.RawLabel("A", List.of(1, 2)), new DetailExtractor.RawLabel("B", List.of(3))), List.of(4));
        DetailExtractor.LabelResult b = new DetailExtractor.LabelResult(List.of(
                new DetailExtractor.RawLabel("A", List.of(1)), new DetailExtractor.RawLabel("B", List.of(2, 3))), List.of(4));
        DetailExtractor.LabelResult c = new DetailExtractor.LabelResult(List.of(
                new DetailExtractor.RawLabel("A", List.of(1, 2)), new DetailExtractor.RawLabel("C", List.of(3))), List.of());
        DetailExtractor.LabelResult r = DetailExtractionService.combineVotes(List.of(a, b, c));
        // 1→A(3표) 2→A(2표 vs B 1표) 3→B(2표 vs C 1표) 4→구역 아님
        assertThat(r.zones()).extracting(DetailExtractor.RawLabel::name).containsExactly("A", "B");
        assertThat(r.zones().get(0).segmentIds()).containsExactly(1, 2);
        assertThat(r.zones().get(1).segmentIds()).containsExactly(3);
        assertThat(r.nonSeatSegments()).containsExactly(4);
        assertThat(DetailExtractionService.combineVotes(List.of())).isNull();
    }

    @Test
    void stageAndFloorNamesAreNotZones() {
        assertThat(DetailExtractionService.cleanNames(List.of("FLOOR", "Floor A", "MAIN STAGE", "무대", "플로어석", "스테이지", "A구역", "B1", "43")))
                .containsExactly("A구역", "B1", "43");
    }
}
