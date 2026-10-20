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
        Path in = Path.of(System.getenv("SEG_DEBUG_IMAGE"));
        if (Files.isDirectory(in)) { // 폴더를 주면 그 아래 공연 폴더마다 seatmap.jpg 를 나눠 {공연 폴더}/seg 에 저장한다
            try (java.util.stream.Stream<Path> s = Files.list(in)) {
                for (Path p : s.filter(d -> Files.exists(d.resolve("seatmap.jpg"))).sorted().toList()) {
                    dump(Files.readAllBytes(p.resolve("seatmap.jpg")), p.resolve("seg"));
                }
            }
            return;
        }
        dump(Files.readAllBytes(in), Path.of(System.getenv("SEG_DEBUG_OUT")));
    }

    private static void dump(byte[] img, Path out) throws Exception {
        Files.createDirectories(out);
        ShapeSegmenter.Result r = new ShapeSegmenter().segment(img, "1".equals(System.getenv("SEG_DROP_GRAY"))); // 1이면 회색을 배경으로 칠하고 나눈다
        Files.write(out.resolve("numbered.jpg"), r.annotatedJpeg());
        Files.write(out.resolve("tiles.jpg"), r.tilesJpeg());
        StringBuilder sb = new StringBuilder();
        for (ShapeSegmenter.Segment s : r.segments()) {
            sb.append(s.id()).append(" area=").append(s.area()).append(" c=").append(s.cx()).append(',').append(s.cy())
                    .append(" vertices=").append(s.pixels().size()).append('\n');
        }
        Files.writeString(out.resolve("segments.txt"), sb.toString());
        // segments.json: 도형 번호별 외곽 꼭짓점(원본 픽셀) — 평가 정답 작성에 쓴다
        java.util.Map<String, Object> js = new java.util.LinkedHashMap<>();
        js.put("width", r.width());
        js.put("height", r.height());
        java.util.Map<String, Object> segs = new java.util.LinkedHashMap<>();
        for (ShapeSegmenter.Segment s : r.segments()) {
            segs.put(String.valueOf(s.id()), s.pixels());
        }
        js.put("segments", segs);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(out.resolve("segments.json").toFile(), js);
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
