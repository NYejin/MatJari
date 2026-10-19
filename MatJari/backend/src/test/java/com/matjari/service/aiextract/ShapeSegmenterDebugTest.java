package com.matjari.service.aiextract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 좌석표 도형 찾기 점검용 도구(기본은 건너뜀). 환경변수 SEG_DEBUG_IMAGE(좌석표 이미지 경로)와 SEG_DEBUG_OUT(출력 폴더)이 있을 때만 실행해
 * 번호 이미지·타일 이미지·도형 목록을 저장한다. 서버·AI 없이 영상 처리만 빠르게 반복해 볼 때 쓴다.
 */
class ShapeSegmenterDebugTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "SEG_DEBUG_IMAGE", matches = ".+")
    void dumpSegments() throws Exception {
        byte[] img = Files.readAllBytes(Path.of(System.getenv("SEG_DEBUG_IMAGE")));
        Path out = Path.of(System.getenv("SEG_DEBUG_OUT"));
        Files.createDirectories(out);
        ShapeSegmenter.Result r = new ShapeSegmenter().segment(img);
        Files.write(out.resolve("numbered.jpg"), r.annotatedJpeg());
        Files.write(out.resolve("tiles.jpg"), r.tilesJpeg());
        StringBuilder sb = new StringBuilder();
        for (ShapeSegmenter.Segment s : r.segments()) {
            sb.append(s.id()).append(" area=").append(s.area()).append(" c=").append(s.cx()).append(',').append(s.cy())
                    .append(" vertices=").append(s.pixels().size()).append('\n');
        }
        Files.writeString(out.resolve("segments.txt"), sb.toString());
        for (int pass = 0; pass < 2; pass++) { // raws.png: 합치기 전 요소, groups.png: 합친 뒤 도형(색으로 구분)
            int[] map = pass == 0 ? ShapeSegmenter.lastRawOwner : ShapeSegmenter.lastGroupMap;
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(r.width(), r.height(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int i = 0; i < map.length; i++) {
                int v = map[i];
                int rgb = v == 0 ? 0x202020 : java.awt.Color.HSBtoRGB((v * 0.61803f) % 1f, 0.7f, 0.95f) & 0xFFFFFF;
                bi.setRGB(i % r.width(), i / r.width(), rgb);
            }
            javax.imageio.ImageIO.write(bi, "png", out.resolve(pass == 0 ? "raws.png" : "groups.png").toFile());
        }
    }
}
