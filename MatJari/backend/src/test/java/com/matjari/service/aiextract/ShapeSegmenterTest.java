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
}
