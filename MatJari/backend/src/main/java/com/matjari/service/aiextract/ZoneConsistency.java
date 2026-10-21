package com.matjari.service.aiextract;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 구역 연결이 끝난 뒤 지도 전체를 보고 이상한 점을 스스로 찾아 고친다(사용자 지시 2026-10-20).
 * <ol>
 *   <li>범례 색이 구역에 하나도 없으면: 그 색의 이름 없는 도형을 찾아 범례 글자를 읽어 구역으로 더한다(화정체육관 VIP(Standing) 띠).</li>
 *   <li>구역 색이 범례에 없으면: 그 구역 이름이 타일 재판독에서도 같은 조각에서 읽혔을 때만 남긴다(글자 없는 회색 구조물 제거).</li>
 *   <li>좌우대칭 배치에서 짝 없는 구역의 반대편에 이름 없는 도형이 있으면: 그 도형의 글자를 읽어 구역으로 더한다.</li>
 * </ol>
 * 1·3은 이상이 있을 때만 AI 를 부른다. 이미지·AI 오류는 원래 결과를 그대로 돌려준다.
 */
@Slf4j
final class ZoneConsistency {

    /** 같은 색으로 볼 RGB 거리. */
    static final double SAME_COLOR = 50;

    private ZoneConsistency() {}

    /** 범례 칩 하나: 경계 상자와 평균 색. */
    record Chip(int x0, int y0, int x1, int y1, int rgb) {}

    /** 점검 결과: 고친 구역 연결과, 되살린 조각을 더한 도형 목록. */
    record Checked(List<DetailExtractor.RawLabel> labels, ShapeSegmenter.Result seg) {}

    static Checked check(List<DetailExtractor.RawLabel> labels, ShapeSegmenter.Result seg, byte[] jpeg,
                         List<String> names, Map<Integer, String> reread, DetailExtractor extractor) {
        Checked unchanged = new Checked(labels, seg);
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) {
                return unchanged;
            }
            Map<Integer, ShapeSegmenter.Segment> byId = new LinkedHashMap<>();
            seg.segments().forEach(s -> byId.put(s.id(), s));
            Map<String, List<Integer>> zones = new LinkedHashMap<>();
            labels.forEach(l -> zones.put(l.name(), new ArrayList<>(l.segmentIds())));
            Map<Integer, Integer> color = new HashMap<>();
            seg.segments().forEach(s -> color.put(s.id(), segmentColor(img, s)));

            List<ShapeSegmenter.Segment> owned = owned(zones, byId);
            if (owned.size() < 3) {
                return unchanged;
            }
            List<Chip> chips = legendChips(img, owned);
            log.info("범례 칩 {}개", chips.size());
            int bg = dominant(img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth()));
            if (chips.size() >= 2) {
                dropOffLegend(zones, byId, color, chips, bg, reread, img);
                addMissingLegendColors(zones, byId, color, chips, img, extractor);
            }
            addMirrorZones(zones, byId, img, jpeg, names, extractor);
            addSequenceGaps(zones, byId, jpeg, extractor);
            fixSeriesOutliers(zones, byId);
            snapTextBlocks(zones, byId, color, img, bg); // 글자에 쪼개진 블록 조각을 띠로 오인해 옆 구역에 옮기지 않게 먼저 블록으로 바꾼다
            reassignStrips(zones, byId);
            if (chips.size() >= 2) {
                refillDented(zones, byId, color, chips, bg, img);
            }
            joinTextSplits(zones, byId, color, img, bg);
            List<DetailExtractor.RawLabel> out = new ArrayList<>();
            zones.forEach((k, v) -> {
                if (!v.isEmpty()) {
                    out.add(new DetailExtractor.RawLabel(k, v));
                }
            });
            ShapeSegmenter.Result grown = new ShapeSegmenter.Result(seg.width(), seg.height(), List.copyOf(byId.values()), seg.annotatedJpeg(), seg.tilesJpeg());
            return new Checked(out, grown);
        } catch (IOException | RuntimeException e) {
            log.info("구역 점검 실패, 원래 결과 사용: {}", e.getClass().getSimpleName());
            return unchanged;
        }
    }

    private static List<ShapeSegmenter.Segment> owned(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId) {
        List<ShapeSegmenter.Segment> out = new ArrayList<>();
        zones.values().forEach(ids -> ids.forEach(id -> {
            if (byId.containsKey(id)) {
                out.add(byId.get(id));
            }
        }));
        return out;
    }

    private static int medianArea(List<ShapeSegmenter.Segment> segs) {
        int[] a = segs.stream().mapToInt(ShapeSegmenter.Segment::area).sorted().toArray();
        return a.length == 0 ? 0 : a[a.length / 2];
    }

    /**
     * 범례 칩 색에 얼마나 가까워야 같은 색으로 볼지: cap 과, 그 칩에서 배경까지 거리의 절반 중 작은 값. 배경에 가까운 연한 범례(화정체육관 연두 칩, 배경 연회색과 55)에서
     * 범례에 없는 짙은 회색 단(연두 칩과 거리 36)이 연두로 통과하지 않게 한다. 어두운 배경(고척돔)은 cap 그대로다. 칩끼리의 거리는 보지 않는다(비슷한 파랑 칩 두 개가
     * 있는 고척돔에서 허용 폭이 줄어 글자에 파인 블록을 못 채웠다).
     */
    static double tolerance(Chip chip, List<Chip> chips, int bg, double cap) {
        return Math.min(cap, dist(chip.rgb(), bg) / 2);
    }

    /** rgb 가 범례 칩 중 하나의 색이다(칩별 허용 폭은 {@link #tolerance}). */
    static boolean inLegend(int rgb, List<Chip> chips, int bg) {
        return chips.stream().anyMatch(c -> dist(c.rgb(), rgb) < tolerance(c, chips, bg, SAME_COLOR));
    }

    /** 구역 색 zc 로 픽셀을 모을 때의 허용 거리: zc 에 가장 가까운 칩의 허용 폭(상한 60). */
    private static double pixelTolerance(int zc, List<Chip> chips, int bg) {
        Chip near = chips.stream().min(java.util.Comparator.comparingDouble(c -> dist(c.rgb(), zc))).orElse(null);
        return near == null ? 60 : tolerance(near, chips, bg, 60);
    }

    /** 범례에 없는 색의 구역은 타일 재판독에서 그 구역 이름이 한 번도 읽히지 않았으면 뺀다(글자가 실제로 쓰여 있는 회색 블록은 남는다). */
    private static void dropOffLegend(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId, Map<Integer, Integer> color,
                                      List<Chip> chips, int bg, Map<Integer, String> reread, BufferedImage img) {
        for (Map.Entry<String, List<Integer>> z : zones.entrySet()) {
            boolean inLegend = false, confirmed = false;
            String key = DetailExtractionService.keyOf(z.getKey());
            for (Integer id : z.getValue()) {
                if (!byId.containsKey(id)) {
                    continue;
                }
                inLegend |= inLegend(color.get(id), chips, bg);
                String t = reread.get(id);
                confirmed |= t != null && key != null && key.equals(DetailExtractionService.keyOf(t));
            }
            if (!inLegend && !confirmed) {
                log.info("범례에 없는 색이고 글자도 다시 읽히지 않은 구역 제외: {}", z.getKey());
                z.getValue().clear();
            } else if (inLegend) {
                // 범례 색 조각이 있는 구역에 섞인 범례 밖 색 조각(고척돔 회색 통로·단이 T15·T04에 붙음)은 뺀다
                List<Integer> removed = new ArrayList<>();
                z.getValue().removeIf(id -> {
                    boolean off = byId.containsKey(id) && !inLegend(color.get(id), chips, bg);
                    if (off) {
                        removed.add(id);
                    }
                    return off;
                });
                // 뺀 덩어리 안에 그 구역 색 픽셀이 이어져 있으면(분할 때 블록 일부가 통로와 한 덩어리로 묶임) 그 픽셀만 모아 구역 조각으로 되살린다
                ShapeSegmenter.Segment main = z.getValue().stream().map(byId::get).filter(java.util.Objects::nonNull)
                        .max(java.util.Comparator.comparingInt(ShapeSegmenter.Segment::area)).orElse(null);
                if (!removed.isEmpty() && main != null) {
                    int nextId = byId.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
                    ShapeSegmenter.Segment grown = regrow(img, removed, byId, main, color.get(main.id()), pixelTolerance(color.get(main.id()), chips, bg), nextId);
                    if (grown != null) {
                        byId.put(grown.id(), grown);
                        color.put(grown.id(), color.get(main.id()));
                        z.getValue().add(grown.id());
                        log.info("{}: 범례 밖 색 덩어리 안의 같은 색 부분을 되살림({}픽셀)", z.getKey(), grown.area());
                    }
                }
            }
        }
    }

    /**
     * 큰 흰 글자가 블록을 갈라 외곽선이 글자 둘레로 크게 파인 조각(볼록 껍질이 외곽선 넓이의 1.3배 초과, 고척돔 T15)은, 조각 안의 구역 색 픽셀에서 시작해
     * 같은 색으로 이어진 픽셀을 채워(다른 구역 조각 안과 칸 사이 어두운 선에서 멈춤) 실제 블록 외곽선으로 바꾼다. 원래 넓이의 2.5배를 넘게 번지면 그대로 둔다.
     */
    private static void refillDented(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId, Map<Integer, Integer> color,
                                     List<Chip> chips, int bg, BufferedImage img) {
        Map<Integer, Polygon> polys = new HashMap<>();
        zones.values().forEach(ids -> ids.forEach(id -> {
            if (byId.containsKey(id)) {
                polys.put(id, poly(byId.get(id).pixels(), 0, false));
            }
        }));
        int w = img.getWidth(), h = img.getHeight();
        for (List<Integer> ids : zones.values()) {
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.get(i);
                ShapeSegmenter.Segment s = byId.get(id);
                if (s == null || !inLegend(color.get(id), chips, bg)) {
                    continue;
                }
                double outline = Math.abs(ShapeSegmenter.shoelaceArea(s.pixels())), hull = Math.abs(ShapeSegmenter.shoelaceArea(ShapeSegmenter.convexHull(s.pixels())));
                if (outline <= 0 || hull <= outline * 1.3) {
                    continue;
                }
                int zc = color.get(id);
                double tol = pixelTolerance(zc, chips, bg);
                Polygon own = polys.get(id);
                java.awt.Rectangle ob = own.getBounds();
                int pad = Math.max(ob.width, ob.height);
                java.awt.Rectangle bounds = new java.awt.Rectangle(ob.x - pad, ob.y - pad, ob.width + 2 * pad, ob.height + 2 * pad)
                        .intersection(new java.awt.Rectangle(0, 0, w, h));
                int bw = bounds.width, bh = bounds.height;
                boolean[] keep = new boolean[bw * bh];
                ArrayDeque<Integer> q = new ArrayDeque<>();
                for (int y = 0; y < bh; y++) {
                    for (int x = 0; x < bw; x++) {
                        int gx = bounds.x + x, gy = bounds.y + y;
                        if (own.contains(gx, gy) && dist(img.getRGB(gx, gy), zc) < tol) {
                            keep[y * bw + x] = true;
                            q.add(y * bw + x);
                        }
                    }
                }
                int count = q.size(), limit = (int) (Math.max(s.area(), outline) * 2.5);
                int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                while (!q.isEmpty() && count <= limit) {
                    int k = q.poll();
                    int x = k % bw, y = k / bw;
                    for (int[] d : nb) {
                        int nx = x + d[0], ny = y + d[1];
                        if (nx < 0 || nx >= bw || ny < 0 || ny >= bh || keep[ny * bw + nx]) {
                            continue;
                        }
                        int gx = bounds.x + nx, gy = bounds.y + ny;
                        if (dist(img.getRGB(gx, gy), zc) >= tol || polys.entrySet().stream().anyMatch(e -> e.getKey() != id && e.getValue().contains(gx, gy))) {
                            continue;
                        }
                        keep[ny * bw + nx] = true;
                        q.add(ny * bw + nx);
                        count++;
                    }
                }
                if (count > limit) {
                    continue; // 이웃 블록으로 번졌다
                }
                if (tol < 60) {
                    // 배경에 가까운 연한 구역 색(화정체육관 연두)은 이웃 회색 블록과 배경 사이 경계의 중간색과 비슷해, 그 1px 줄을 타고 번진다(I 위쪽).
                    // 원래 조각 밖으로 번진 부분에서만 폭 2px 이하 선을 지운다(어두운 배경의 고척돔은 글자 사이 가는 띠를 지키려고 하지 않는다)
                    boolean[] opened = open1(keep, bw, bh);
                    for (int k = 0; k < keep.length; k++) {
                        keep[k] = keep[k] && (opened[k] || own.contains(bounds.x + k % bw, bounds.y + k / bw));
                    }
                }
                ShapeSegmenter.Segment filled = outlineSegment(keep, bounds, zc, id);
                if (filled != null && Math.abs(ShapeSegmenter.shoelaceArea(filled.pixels())) > outline * 1.25) { // 원래 오목한 블록(원형 무대를 따라 파인 F1)은 다시 채워도 거의 그대로라 바꾸지 않는다
                    log.info("글자로 파인 조각을 같은 색으로 다시 채움: 조각 {} 넓이 {} → {}", id, (int) outline, filled.area());
                    byId.put(id, filled);
                    polys.put(id, poly(filled.pixels(), 0, false));
                }
            }
        }
    }

    /** 글자로 끊겼다고 볼 수 있는 같은 구역 두 조각 사이의 최대 틈(px). */
    static final int TEXT_GAP = 16;
    /** 글자 획 굵기: 글자색 픽셀 양옆으로 이만큼 안에 구역 색이 있어야 블록 안 글자로 본다. */
    static final int TEXT_REACH = 8;

    /**
     * 구역 이름 글자가 가는 블록을 가로질러 한 구역이 두 조각으로 끊긴 것(고척돔 T01·309, 213836 T4·113)을 다시 잇는다(사용자 지시 2026-10-21).
     * 지도 전체 블록 안에서 블록 색이 아닌 가장 흔한 색을 구역 이름 글자색으로 보고, 같은 구역·같은 색의 두 조각 사이(볼록 껍질 안)가
     * 구역 색이나 글자색으로만 차 있으면 그 픽셀을 구역 색으로 채워 한 조각으로 합친다. 다른 구역이 끼어 있거나 통로·배경이 20% 넘게 섞이면
     * 두지 않는다. 블록 긴 변 옆에 나란히 선 가는 띠(화정체육관 앞쪽 띠, 흰 선으로 나뉨)는 글자 끊김이 아니라 별도 조각이라 잇지 않는다.
     */
    private static void joinTextSplits(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId, Map<Integer, Integer> color,
                                       BufferedImage img, int bg) {
        Integer text = labelTextColor(zones, byId, color, img, bg);
        if (text == null) {
            return;
        }
        Map<Integer, Polygon> polys = new HashMap<>();
        zones.values().forEach(ids -> ids.forEach(id -> {
            if (byId.containsKey(id)) {
                polys.put(id, poly(byId.get(id).pixels(), 0, false));
            }
        }));
        int w = img.getWidth(), h = img.getHeight();
        for (Map.Entry<String, List<Integer>> e : zones.entrySet()) {
            List<Integer> ids = e.getValue();
            boolean merged = true;
            while (merged && ids.size() >= 2) {
                merged = false;
                pairs:
                for (int i = 0; i < ids.size(); i++) {
                    for (int j = i + 1; j < ids.size(); j++) {
                        ShapeSegmenter.Segment a = byId.get(ids.get(i)), b = byId.get(ids.get(j));
                        if (a == null || b == null || !color.containsKey(a.id()) || !color.containsKey(b.id())) {
                            continue;
                        }
                        int zc = color.get(a.id());
                        int[] ba = box(a), bb = box(b);
                        if (dist(zc, color.get(b.id())) >= 40 || boxGap(ba, bb) > TEXT_GAP || sideStrip(ba, bb) || sideStrip(bb, ba)) {
                            continue;
                        }
                        List<int[]> pts = new ArrayList<>(a.pixels());
                        pts.addAll(b.pixels());
                        Polygon hull = poly(ShapeSegmenter.convexHull(pts), 0, false);
                        java.awt.Rectangle bounds = hull.getBounds().intersection(new java.awt.Rectangle(0, 0, w, h));
                        int bw = bounds.width, bh = bounds.height;
                        if (bw <= 0 || bh <= 0) {
                            continue;
                        }
                        Polygon pa = polys.get(a.id()), pb = polys.get(b.id());
                        List<Polygon> others = new ArrayList<>();
                        polys.forEach((id, p) -> {
                            if (id != a.id() && id != b.id() && p.getBounds().intersects(bounds)) {
                                others.add(p);
                            }
                        });
                        boolean[] keep = new boolean[bw * bh];
                        int gap = 0, filled = 0, other = 0, glyphs = 0;
                        for (int y = 0; y < bh; y++) {
                            for (int x = 0; x < bw; x++) {
                                int gx = bounds.x + x, gy = bounds.y + y;
                                if (pa.contains(gx, gy) || pb.contains(gx, gy)) {
                                    keep[y * bw + x] = true;
                                    continue;
                                }
                                if (!hull.contains(gx, gy)) {
                                    continue;
                                }
                                gap++;
                                if (others.stream().anyMatch(p -> p.contains(gx, gy))) {
                                    other = Integer.MAX_VALUE / 2; // 다른 구역이 끼어 있다
                                    continue;
                                }
                                int px = img.getRGB(gx, gy);
                                boolean glyph = dist(px, text) < 60;
                                // 글자 테두리는 글자색과 구역 색이 섞인 중간색이고, 글자 그림자·JPEG 번짐은 구역 색과 배경이 섞인 어두운 구역 색이다
                                if (glyph || dist(px, zc) < SAME_COLOR || blendDist(px, zc, text) < 30) {
                                    keep[y * bw + x] = true;
                                    filled++;
                                    glyphs += glyph ? 1 : 0;
                                } else if (blendDist(px, zc, bg) < 30 && dist(px, bg) >= 60) {
                                    keep[y * bw + x] = true;
                                } else if (hull.contains(gx - 2, gy) && hull.contains(gx + 2, gy) && hull.contains(gx, gy - 2) && hull.contains(gx, gy + 2)) {
                                    other++; // 껍질 가장자리 2px(블록 테두리)는 세지 않는다
                                }
                            }
                        }
                        // 틈에 글자 픽셀이 있고, 절반 가까이가 구역·글자색이며, 배경(통로)이나 다른 색은 20% 이하일 때만 글자 끊김이다
                        if (glyphs < 5 || filled < 0.4 * gap || other > 0.2 * gap) {
                            continue;
                        }
                        ShapeSegmenter.Segment joined = outlineSegment(keep, bounds, zc, a.id(), false); // 글자 픽셀은 이미 채웠다
                        if (joined == null) {
                            continue;
                        }
                        log.info("글자로 끊긴 구역 잇기: {} 조각 {}+{} (틈 {}px 중 글자·구역 색 {}px)", e.getKey(), a.id(), b.id(), gap, filled);
                        byId.put(a.id(), joined);
                        polys.put(a.id(), poly(joined.pixels(), 0, false));
                        polys.remove(b.id());
                        ids.remove(j);
                        merged = true;
                        break pairs;
                    }
                }
            }
        }
    }

    /**
     * 이름 글자가 블록 대부분을 덮어 도형이 잘게 쪼개지고 어긋난 구역(213836 200번대·T 줄, 사용자 지적 2026-10-21)을 실제 블록으로 바꾼다.
     * 구역 색 픽셀과 이름 글자색(과 둘이 섞인 글자 테두리색) 픽셀을 함께 4방향으로 이어 블록을 다시 잡으면, 블록 사이의 어두운 경계선에서 끊겨
     * 블록 하나가 덩어리 하나가 된다. 덩어리마다 지금 도형이 가장 많이 덮는 구역(덮인 픽셀의 60% 이상)에 그 덩어리를 주고, 그 구역의 기존 조각 중
     * 덩어리에 절반 넘게 들어간 조각을 뺀다. 두 구역 조각을 각각 절반 넘게 품은 덩어리(경계선이 없는 붙은 블록)와 구역 조각 중앙값의 6배를 넘는 덩어리는 건드리지 않는다.
     */
    private static void snapTextBlocks(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId, Map<Integer, Integer> color,
                                       BufferedImage img, int bg) {
        Integer text = labelTextColor(zones, byId, color, img, bg);
        if (text == null) {
            return;
        }
        int w = img.getWidth(), h = img.getHeight();
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        // 픽셀마다 덮는 조각 id(0 = 없음)
        int[] owner = new int[w * h];
        Map<Integer, String> zoneOf = new HashMap<>();
        List<Integer> palette = new ArrayList<>();
        int medArea = medianArea(owned(zones, byId));
        for (Map.Entry<String, List<Integer>> e : zones.entrySet()) {
            for (Integer id : e.getValue()) {
                ShapeSegmenter.Segment s = byId.get(id);
                if (s == null || !color.containsKey(id)) {
                    continue;
                }
                zoneOf.put(id, e.getKey());
                if (s.sat() >= 0.2 && palette.stream().noneMatch(c -> dist(c, color.get(id)) < 40)) {
                    palette.add(color.get(id));
                }
                Polygon p = poly(s.pixels(), 0, false);
                java.awt.Rectangle r = p.getBounds().intersection(new java.awt.Rectangle(0, 0, w, h));
                for (int y = r.y; y < r.y + r.height; y++) {
                    for (int x = r.x; x < r.x + r.width; x++) {
                        if (p.contains(x, y)) {
                            owner[y * w + x] = id;
                        }
                    }
                }
            }
        }
        int nextId = byId.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        int[] comp = new int[w * h]; // 받아들인 덩어리 번호
        int[] seen = new int[w * h];
        int[] queue = new int[w * h];
        int label = 0;
        java.util.Set<Integer> accepted = new java.util.HashSet<>(), fresh = new java.util.HashSet<>();
        for (int zc : palette) {
            if (dist(zc, text) < 60) {
                continue; // 글자색과 같은 색 구역은 글자와 구별할 수 없다
            }
            int passStart = label; // 이 색에서 처음 붙인 번호보다 작으면 다른 색에서 본 픽셀이다
            boolean[] body = new boolean[w * h];
            for (int i = 0; i < body.length; i++) {
                body[i] = comp[i] == 0 && dist(rgb[i], zc) < 70;
            }
            // 글자색 픽셀은 양옆(가로나 세로)에 구역 색이 있을 때만 블록에 넣는다: 블록 옆을 지나는 흰 선(211358 대각선)은 한쪽만 구역 색이다
            boolean[] mask = body.clone();
            for (int i = 0; i < mask.length; i++) {
                if (!body[i] && comp[i] == 0 && (dist(rgb[i], text) < 60 || blendDist(rgb[i], zc, text) < 35)) {
                    int x = i % w, y = i / w;
                    mask[i] = (reach(body, w, h, x, y, -1, 0) && reach(body, w, h, x, y, 1, 0)) || (reach(body, w, h, x, y, 0, -1) && reach(body, w, h, x, y, 0, 1));
                }
            }
            for (int start = 0; start < mask.length; start++) {
                if (!mask[start] || seen[start] > passStart) {
                    continue;
                }
                label++;
                int head = 0, tail = 0;
                queue[tail++] = start;
                seen[start] = label;
                Map<Integer, Integer> hits = new HashMap<>();
                int x0 = w, y0 = h, x1 = 0, y1 = 0;
                while (head < tail) {
                    int i = queue[head++], x = i % w, y = i / w;
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                    if (owner[i] != 0 && dist(color.get(owner[i]), zc) < 40) {
                        hits.merge(owner[i], 1, Integer::sum);
                    }
                    int[] nb = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                    for (int n : nb) {
                        if (n >= 0 && mask[n] && seen[n] != label) {
                            seen[n] = label;
                            queue[tail++] = n;
                        }
                    }
                }
                int area = tail;
                if (area < 30 || hits.isEmpty() || (medArea > 0 && area > 6 * medArea)) {
                    continue;
                }
                Map<String, Integer> byZone = new HashMap<>();
                hits.forEach((id, n) -> byZone.merge(zoneOf.get(id), n, Integer::sum));
                int covered = byZone.values().stream().mapToInt(Integer::intValue).sum();
                Map.Entry<String, Integer> top = byZone.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
                if (top.getValue() < 0.6 * covered || covered < 0.2 * area) {
                    continue;
                }
                // 조각 하나하나가 덩어리 안에 얼마나 들어갔는지(조각 넓이 대비)
                List<Integer> inside = new ArrayList<>();
                boolean twoZones = false;
                for (Map.Entry<Integer, Integer> hit : hits.entrySet()) {
                    ShapeSegmenter.Segment s = byId.get(hit.getKey());
                    if (hit.getValue() > 0.5 * Math.max(1, s.area())) {
                        if (zoneOf.get(hit.getKey()).equals(top.getKey())) {
                            inside.add(hit.getKey());
                        } else {
                            twoZones = true;
                        }
                    }
                }
                List<Integer> ids = zones.get(top.getKey());
                // 한 덩어리 안에 같은 구역 조각이 둘 이상 들어 있고 서로 긴 변을 맞댄 띠면(흰 선으로 나뉜 앞쪽 띠) 건드리지 않는다
                boolean strips = false;
                for (int a = 0; a < inside.size() && !strips; a++) {
                    for (int b = a + 1; b < inside.size(); b++) {
                        int[] ba = box(byId.get(inside.get(a))), bb = box(byId.get(inside.get(b)));
                        if (sideStrip(ba, bb) || sideStrip(bb, ba)) {
                            strips = true;
                            break;
                        }
                    }
                }
                // 기존 조각이 이미 블록을 거의 다 덮으면(글자에 쪼개지지 않은 블록, 화정체육관) 원래 외곽선을 둔다
                int had = inside.stream().mapToInt(id -> byId.get(id).area()).sum();
                if (twoZones || strips || had >= 0.85 * area) {
                    continue;
                }
                java.awt.Rectangle bounds = new java.awt.Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
                boolean[] keep = new boolean[bounds.width * bounds.height];
                for (int k = 0; k < tail; k++) {
                    int i = queue[k];
                    comp[i] = label;
                    keep[(i / w - y0) * bounds.width + (i % w - x0)] = true;
                }
                ShapeSegmenter.Segment block = outlineSegment(keep, bounds, zc, nextId, false);
                if (block == null) {
                    continue;
                }
                // 가장자리에 걸친 글자 획이 외곽선에 홈을 내므로, 거의 볼록한 블록(볼록 껍질의 85% 이상)은 볼록 껍질을 쓴다
                List<int[]> hull = ShapeSegmenter.convexHull(block.pixels());
                if (hull.size() >= 3 && area >= 0.85 * Math.abs(ShapeSegmenter.shoelaceArea(hull))
                        && othersInside(poly(hull, 0, false), owner, zoneOf, top.getKey(), w, h) <= 0.02 * area) {
                    block = new ShapeSegmenter.Segment(nextId, hull, block.area(), block.cx(), block.cy(), block.sat(), block.val());
                }
                byId.put(nextId, block);
                color.put(nextId, zc);
                ids.removeAll(inside);
                ids.add(nextId);
                log.info("글자 포함 블록으로 바꾸기: {} 조각 {} → {} ({}px)", top.getKey(), inside, nextId, area);
                accepted.add(label);
                fresh.add(nextId);
                nextId++;
            }
        }
        // 블록 두 개에 걸쳐 어긋나 있던 기존 조각(어느 덩어리에도 절반 넘게 들어가지 않은 것)도, 새 블록들이 절반 넘게 덮으면 뺀다
        for (Map.Entry<String, List<Integer>> e : zones.entrySet()) {
            if (e.getValue().stream().noneMatch(fresh::contains)) {
                continue;
            }
            e.getValue().removeIf(id -> {
                ShapeSegmenter.Segment s = byId.get(id);
                if (s == null || fresh.contains(id)) {
                    return false;
                }
                Polygon p = poly(s.pixels(), 0, false);
                java.awt.Rectangle r = p.getBounds().intersection(new java.awt.Rectangle(0, 0, w, h));
                int all = 0, in = 0;
                for (int y = r.y; y < r.y + r.height; y++) {
                    for (int x = r.x; x < r.x + r.width; x++) {
                        if (p.contains(x, y)) {
                            all++;
                            in += accepted.contains(comp[y * w + x]) ? 1 : 0;
                        }
                    }
                }
                return all > 0 && in > 0.5 * all;
            });
        }
    }

    /** (x, y)에서 (dx, dy) 방향으로 TEXT_REACH 픽셀 안에 body 픽셀이 있다. */
    private static boolean reach(boolean[] body, int w, int h, int x, int y, int dx, int dy) {
        for (int k = 1; k <= TEXT_REACH; k++) {
            int nx = x + k * dx, ny = y + k * dy;
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                return false;
            }
            if (body[ny * w + nx]) {
                return true;
            }
        }
        return false;
    }

    /** 다각형 p 안에서 zone 이 아닌 구역 조각이 덮는 픽셀 수(볼록 껍질이 이웃 블록을 덮는지 본다). */
    private static int othersInside(Polygon p, int[] owner, Map<Integer, String> zoneOf, String zone, int w, int h) {
        java.awt.Rectangle r = p.getBounds().intersection(new java.awt.Rectangle(0, 0, w, h));
        int n = 0;
        for (int y = r.y; y < r.y + r.height; y++) {
            for (int x = r.x; x < r.x + r.width; x++) {
                int o = owner[y * w + x];
                if (o != 0 && p.contains(x, y) && !zone.equals(zoneOf.get(o))) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * 구역 이름 글자색: 구역에 연결된 색 있는 블록 안에서 블록 색(RGB 거리 90 이상)과 배경(60 이상) 모두와 먼 픽셀 중 가장 흔한 색(16단계로 묶음).
     * 그런 픽셀이 적거나(블록 넓이 합의 0.5% 미만) 한 색으로 모이지 않으면(40% 미만) null.
     */
    static Integer labelTextColor(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId, Map<Integer, Integer> color,
                                  BufferedImage img, int bg) {
        Map<Integer, int[]> buckets = new HashMap<>();
        long total = 0, area = 0;
        for (List<Integer> ids : zones.values()) {
            for (Integer id : ids) {
                ShapeSegmenter.Segment s = byId.get(id);
                if (s == null || s.sat() < 0.2 || !color.containsKey(id)) {
                    continue;
                }
                int zc = color.get(id);
                Polygon p = poly(s.pixels(), 0, false);
                java.awt.Rectangle r = p.getBounds().intersection(new java.awt.Rectangle(0, 0, img.getWidth(), img.getHeight()));
                for (int y = r.y; y < r.y + r.height; y++) {
                    for (int x = r.x; x < r.x + r.width; x++) {
                        if (!p.contains(x, y)) {
                            continue;
                        }
                        area++;
                        int px = img.getRGB(x, y);
                        if (dist(px, zc) < 90 || dist(px, bg) < 60) {
                            continue;
                        }
                        int key = ((px >> 20) & 15) << 8 | ((px >> 12) & 15) << 4 | ((px >> 4) & 15);
                        int[] acc = buckets.computeIfAbsent(key, k -> new int[4]);
                        acc[0]++;
                        acc[1] += (px >> 16) & 255;
                        acc[2] += (px >> 8) & 255;
                        acc[3] += px & 255;
                        total++;
                    }
                }
            }
        }
        if (area == 0 || total < 0.005 * area) {
            return null;
        }
        int[] best = buckets.values().stream().max(java.util.Comparator.comparingInt(v -> v[0])).orElse(null);
        // 글자 테두리의 섞인 색이 이웃 단계로 흩어지므로, 가장 흔한 색에서 RGB 거리 60 안의 픽셀을 모두 센다
        int c = 0xFF000000 | (best[1] / best[0]) << 16 | (best[2] / best[0]) << 8 | (best[3] / best[0]);
        long near = 0;
        for (Map.Entry<Integer, int[]> b : buckets.entrySet()) {
            int[] v = b.getValue();
            int mean = 0xFF000000 | (v[1] / v[0]) << 16 | (v[2] / v[0]) << 8 | (v[3] / v[0]);
            if (dist(mean, c) < 60) {
                near += v[0];
            }
        }
        return near >= 0.4 * total ? c : null;
    }

    /** RGB 공간에서 색 px와 선분 [a, b](두 색의 섞인 색들) 사이 거리. */
    static double blendDist(int px, int a, int b) {
        double[] p = {(px >> 16) & 255, (px >> 8) & 255, px & 255}, u = {(a >> 16) & 255, (a >> 8) & 255, a & 255}, v = {(b >> 16) & 255, (b >> 8) & 255, b & 255};
        double l2 = 0, t = 0;
        for (int i = 0; i < 3; i++) {
            l2 += (v[i] - u[i]) * (v[i] - u[i]);
            t += (p[i] - u[i]) * (v[i] - u[i]);
        }
        t = l2 == 0 ? 0 : Math.max(0, Math.min(1, t / l2));
        double d = 0;
        for (int i = 0; i < 3; i++) {
            double c = u[i] + t * (v[i] - u[i]) - p[i];
            d += c * c;
        }
        return Math.sqrt(d);
    }

    private static int boxGap(int[] a, int[] b) {
        return Math.max(Math.max(a[0] - b[2], b[0] - a[2]), Math.max(a[1] - b[3], b[1] - a[3]));
    }

    /** s가 가는 띠(상자 긴 변이 짧은 변의 2.2배 이상)이고 o가 그 긴 변 쪽에 나란히 있다(긴 축 방향으로 띠 길이의 절반 이상 겹침). */
    static boolean sideStrip(int[] s, int[] o) {
        int sw = s[2] - s[0], sh = s[3] - s[1];
        if (sw >= 2.2 * sh) {
            return Math.min(s[2], o[2]) - Math.max(s[0], o[0]) >= 0.5 * sw;
        }
        if (sh >= 2.2 * sw) {
            return Math.min(s[3], o[3]) - Math.max(s[1], o[1]) >= 0.5 * sh;
        }
        return false;
    }

    /** 픽셀 마스크의 합집합 외곽선(흰 글자 구멍은 테두리 3px 덧칠로 메움)에서 가장 넓은 고리로 조각을 만든다. */
    private static ShapeSegmenter.Segment outlineSegment(boolean[] keep, java.awt.Rectangle bounds, int zoneColor, int id) {
        return outlineSegment(keep, bounds, zoneColor, id, true);
    }

    /** pad=false 면 테두리 덧칠 없이(구멍을 이미 채운 마스크) 외곽선을 만든다. */
    private static ShapeSegmenter.Segment outlineSegment(boolean[] keep, java.awt.Rectangle bounds, int zoneColor, int id, boolean pad) {
        int bw = bounds.width, bh = bounds.height, total = 0;
        java.awt.geom.Area area = new java.awt.geom.Area();
        long sx = 0, sy = 0;
        for (int y = 0; y < bh; y++) {
            int x = 0;
            while (x < bw) {
                if (!keep[y * bw + x]) {
                    x++;
                    continue;
                }
                int x0 = x;
                while (x < bw && keep[y * bw + x]) {
                    sx += bounds.x + x;
                    sy += bounds.y + y;
                    total++;
                    x++;
                }
                area.add(new java.awt.geom.Area(new java.awt.Rectangle(bounds.x + x0, bounds.y + y, x - x0, 1)));
            }
        }
        if (total == 0) {
            return null;
        }
        if (pad) {
            area.add(new java.awt.geom.Area(new java.awt.BasicStroke(3f).createStrokedShape(area)));
        }
        List<int[]> best = null;
        double bestArea = 0;
        List<List<int[]>> rings = new ArrayList<>();
        List<int[]> cur = new ArrayList<>();
        double[] c = new double[6];
        for (java.awt.geom.PathIterator it = area.getPathIterator(null, 0.5); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == java.awt.geom.PathIterator.SEG_MOVETO) {
                cur = new ArrayList<>();
                rings.add(cur);
            }
            if (t != java.awt.geom.PathIterator.SEG_CLOSE) {
                cur.add(new int[]{(int) Math.round(c[0]), (int) Math.round(c[1])});
            }
        }
        for (List<int[]> r : rings) {
            double a = Math.abs(ShapeSegmenter.shoelaceArea(r));
            if (a > bestArea) {
                bestArea = a;
                best = r;
            }
        }
        if (best == null || best.size() < 3) {
            return null;
        }
        List<int[]> outline = ShapeSegmenter.simplify(best);
        int r = (zoneColor >> 16) & 255, g = (zoneColor >> 8) & 255, b = zoneColor & 255;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return new ShapeSegmenter.Segment(id, outline, total, (int) (sx / total), (int) (sy / total), mx == 0 ? 0 : (double) (mx - mn) / mx, mx);
    }

    /**
     * 뺀 조각들 안에서 구역 색(RGB 거리 tol 이내) 픽셀을 모아, 대표 조각과 8px 안으로 맞닿고 대표 조각 넓이의 15% 이상인 덩어리를 하나의 조각으로 만든다.
     * 외곽선은 픽셀 줄들의 합집합(흰 글자 구멍은 테두리 덧칠로 메움)에서 가장 넓은 고리다. 없으면 null.
     */
    private static ShapeSegmenter.Segment regrow(BufferedImage img, List<Integer> removed, Map<Integer, ShapeSegmenter.Segment> byId,
                                                 ShapeSegmenter.Segment main, int zoneColor, double tol, int id) {
        List<Polygon> region = new ArrayList<>();
        java.awt.Rectangle bounds = null;
        for (Integer r : removed) {
            Polygon p = poly(byId.get(r).pixels(), 0, false);
            region.add(p);
            bounds = bounds == null ? p.getBounds() : bounds.union(p.getBounds());
        }
        bounds = bounds.intersection(new java.awt.Rectangle(0, 0, img.getWidth(), img.getHeight()));
        int bw = bounds.width, bh = bounds.height;
        if (bw <= 0 || bh <= 0) {
            return null;
        }
        boolean[] m = new boolean[bw * bh];
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int px = bounds.x + x, py = bounds.y + y;
                boolean inside = false;
                for (Polygon p : region) {
                    inside |= p.contains(px, py);
                }
                m[y * bw + x] = inside && dist(img.getRGB(px, py), zoneColor) < tol;
            }
        }
        m = open1(m, bw, bh); // 회색 블록과 배경 사이 경계 픽셀(중간 색이 연두와 비슷)이 이루는 1~2px 선은 뺀다(화정체육관 I 오른쪽 가장자리)
        int[] mb = box(main);
        boolean[] keep = new boolean[m.length];
        boolean[] seen = new boolean[m.length];
        int total = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int start = 0; start < m.length; start++) {
            if (!m[start] || seen[start]) {
                continue;
            }
            List<Integer> comp = new ArrayList<>();
            boolean near = false;
            seen[start] = true;
            q.add(start);
            while (!q.isEmpty()) {
                int k = q.poll();
                comp.add(k);
                int x = k % bw, y = k / bw, gx = bounds.x + x, gy = bounds.y + y;
                near |= gx >= mb[0] - 8 && gx <= mb[2] + 8 && gy >= mb[1] - 8 && gy <= mb[3] + 8; // 흰 선 너머 앞쪽 띠(화정체육관 E 옆 연두 띠, 6px 간격)도 붙인다
                for (int[] d : nb) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx >= 0 && nx < bw && ny >= 0 && ny < bh && m[ny * bw + nx] && !seen[ny * bw + nx]) {
                        seen[ny * bw + nx] = true;
                        q.add(ny * bw + nx);
                    }
                }
            }
            if (near && comp.size() >= main.area() * 0.15) {
                comp.forEach(k -> keep[k] = true);
                total += comp.size();
            }
        }
        if (total == 0) {
            return null;
        }
        java.awt.geom.Area area = new java.awt.geom.Area();
        long sx = 0, sy = 0;
        for (int y = 0; y < bh; y++) {
            int x = 0;
            while (x < bw) {
                if (!keep[y * bw + x]) {
                    x++;
                    continue;
                }
                int x0 = x;
                while (x < bw && keep[y * bw + x]) {
                    sx += bounds.x + x;
                    sy += bounds.y + y;
                    x++;
                }
                area.add(new java.awt.geom.Area(new java.awt.Rectangle(bounds.x + x0, bounds.y + y, x - x0, 1)));
            }
        }
        area.add(new java.awt.geom.Area(new java.awt.BasicStroke(3f).createStrokedShape(area)));
        List<int[]> best = null;
        double bestArea = 0;
        List<List<int[]>> rings = new ArrayList<>();
        List<int[]> cur = new ArrayList<>();
        double[] c = new double[6];
        for (java.awt.geom.PathIterator it = area.getPathIterator(null, 0.5); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == java.awt.geom.PathIterator.SEG_MOVETO) {
                cur = new ArrayList<>();
                rings.add(cur);
            }
            if (t != java.awt.geom.PathIterator.SEG_CLOSE) {
                cur.add(new int[]{(int) Math.round(c[0]), (int) Math.round(c[1])});
            }
        }
        for (List<int[]> r : rings) {
            double a = Math.abs(ShapeSegmenter.shoelaceArea(r));
            if (a > bestArea) {
                bestArea = a;
                best = r;
            }
        }
        if (best == null || best.size() < 3) {
            return null;
        }
        List<int[]> outline = ShapeSegmenter.simplify(best);
        int r = (zoneColor >> 16) & 255, g = (zoneColor >> 8) & 255, b = zoneColor & 255;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        return new ShapeSegmenter.Segment(id, outline, total, (int) (sx / total), (int) (sy / total), mx == 0 ? 0 : (double) (mx - mn) / mx, mx);
    }

    /** 3x3 열림(깎은 뒤 다시 키움): 폭 2px 이하의 선을 지우고 블록 모양은 그대로 둔다. */
    static boolean[] open1(boolean[] m, int w, int h) {
        boolean[] e = new boolean[m.length], o = new boolean[m.length];
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                boolean all = true;
                for (int dy = -1; dy <= 1 && all; dy++) {
                    for (int dx = -1; dx <= 1 && all; dx++) {
                        all = m[(y + dy) * w + x + dx];
                    }
                }
                e[y * w + x] = all;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!e[y * w + x]) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            o[ny * w + nx] = m[ny * w + nx];
                        }
                    }
                }
            }
        }
        return o;
    }

    /** 범례 색 중 구역에 하나도 없는 색이 있으면, 그 색의 이름 없는 도형(구역 조각 중앙값의 15% 이상)을 그 범례 이름의 구역으로 더한다. */
    private static void addMissingLegendColors(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId, Map<Integer, Integer> color,
                                               List<Chip> chips, BufferedImage img, DetailExtractor extractor) throws IOException {
        Set<Integer> taken = new HashSet<>();
        zones.values().forEach(taken::addAll);
        int median = medianArea(owned(zones, byId));
        Map<Integer, List<Integer>> found = new LinkedHashMap<>();
        for (int c = 0; c < chips.size(); c++) {
            Chip chip = chips.get(c);
            boolean present = taken.stream().anyMatch(id -> color.containsKey(id) && dist(color.get(id), chip.rgb()) < SAME_COLOR);
            if (present) {
                continue;
            }
            List<Integer> cand = new ArrayList<>();
            for (ShapeSegmenter.Segment s : byId.values()) {
                if (!taken.contains(s.id()) && s.area() >= median * 0.15 && dist(color.get(s.id()), chip.rgb()) < SAME_COLOR) {
                    cand.add(s.id());
                }
            }
            log.info("범례 색 {}이 구역에 없음: 같은 색 이름 없는 도형 {}개", Integer.toHexString(chip.rgb()), cand.size());
            if (!cand.isEmpty()) {
                found.put(c, cand);
            }
        }
        if (found.isEmpty()) {
            return;
        }
        List<Integer> ids = new ArrayList<>(found.keySet());
        List<byte[]> crops = new ArrayList<>();
        for (Integer c : ids) {
            crops.add(legendCrop(img, chips, c));
        }
        Map<Integer, String> read = extractor.readLegend(ids, crops);
        for (Integer c : ids) {
            String name = DetailExtractionService.cleanName(read.getOrDefault(c, ""));
            if (name == null || name.length() > 30 || DetailExtractionService.isStageName(name)) {
                continue;
            }
            if (gradeName(name)) {
                // '시야제한석'·'VIP석' 같은 등급 이름은 구역 이름이 아니다: 그 색 도형은 맞닿은(3px 안) 구역의 조각으로 붙인다(화정체육관 J의 분홍 삼각형)
                for (Integer id : found.get(c)) {
                    String near = nearestZone(byId.get(id), zones, byId, 3);
                    if (near != null) {
                        log.info("등급 색 도형 {}을 맞닿은 구역 {}에 붙임", id, near);
                        zones.get(near).add(id);
                    }
                }
                continue;
            }
            log.info("범례 이름으로 구역 추가: {} 도형 {}", name, found.get(c));
            zones.computeIfAbsent(name, k -> new ArrayList<>()).addAll(found.get(c));
        }
    }

    /** 좌석 등급 이름('~석', '~좌석', 범례 표기): 구역 이름으로 쓰지 않는다. */
    static boolean gradeName(String name) {
        return DetailExtractionService.isLegendName(name);
    }

    /** s 와 상자 간격이 gap 이하인 구역 조각 중 가장 가까운 것의 구역(없으면 null). */
    private static String nearestZone(ShapeSegmenter.Segment s, Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId, int gap) {
        if (s == null) {
            return null;
        }
        int[] a = box(s);
        String best = null;
        int bestGap = Integer.MAX_VALUE;
        for (Map.Entry<String, List<Integer>> z : zones.entrySet()) {
            for (Integer id : z.getValue()) {
                ShapeSegmenter.Segment q = byId.get(id);
                if (q == null || q.id() == s.id()) {
                    continue;
                }
                int[] b = box(q);
                int g = Math.max(Math.max(0, Math.max(b[0] - a[2], a[0] - b[2])), Math.max(0, Math.max(b[1] - a[3], a[1] - b[3])));
                if (g <= gap && g < bestGap) {
                    bestGap = g;
                    best = z.getKey();
                }
            }
        }
        return best;
    }

    /**
     * 번호 계열 크기 점검(사용자 지시 2026-10-21): 같은 계열(401~424 등, 4개 이상) 구역 넓이 중앙값의 1.6배를 넘는 구역이 조각 둘 이상이고 바로 옆 번호 구역이
     * 중앙값의 절반에 못 미치면, 옆 블록을 가져간 것으로 본다(고척돔 420 블록이 타일에서 419로 읽혀 419는 두 블록, 420은 가는 조각만 남음). 큰 구역 조각 중 옆 번호
     * 조각에 가장 가까운 블록 크기 조각을 옆 번호로 옮기고, 옆 번호에 있던 가는 조각(중앙값 30% 미만)은 버린다.
     */
    private static void fixSeriesOutliers(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId) {
        Map<String, Map<Integer, String>> series = new HashMap<>();
        for (Map.Entry<String, List<Integer>> z : zones.entrySet()) {
            String k = seriesKey(z.getKey());
            if (k != null && z.getValue().stream().anyMatch(byId::containsKey)) {
                series.computeIfAbsent(k, x -> new HashMap<>()).put(Integer.parseInt(z.getKey().replaceAll("\\D", "")), z.getKey());
            }
        }
        for (Map<Integer, String> nums : series.values()) {
            if (nums.size() < 4) {
                continue;
            }
            int[] sorted = nums.values().stream().mapToInt(n -> totalArea(zones.get(n), byId)).sorted().toArray();
            int median = sorted[sorted.length / 2];
            for (Map.Entry<Integer, String> e : nums.entrySet()) {
                List<Integer> pieces = zones.get(e.getValue()).stream().filter(byId::containsKey).toList();
                if (pieces.size() < 2 || totalArea(pieces, byId) <= median * 1.6) {
                    continue;
                }
                for (int d : new int[]{-1, 1}) {
                    String small = nums.get(e.getKey() + d);
                    if (small == null || totalArea(zones.get(small), byId) >= median * 0.5) {
                        continue;
                    }
                    double[] c = center(zones.get(small), byId);
                    Integer move = pieces.stream()
                            .min(java.util.Comparator.comparingDouble(id -> Math.hypot(byId.get(id).cx() - c[0], byId.get(id).cy() - c[1]))).orElseThrow();
                    if (byId.get(move).area() < median * 0.5) {
                        continue;
                    }
                    zones.get(small).removeIf(id -> byId.containsKey(id) && byId.get(id).area() < median * 0.3);
                    zones.get(e.getValue()).remove(move);
                    zones.get(small).add(move);
                    log.info("번호 계열에서 유독 넓은 {}의 조각 {}를 옆 번호 {}로 옮김", e.getValue(), move, small);
                    break;
                }
            }
        }
    }

    private static int totalArea(List<Integer> ids, Map<Integer, ShapeSegmenter.Segment> byId) {
        return ids.stream().filter(byId::containsKey).mapToInt(id -> byId.get(id).area()).sum();
    }

    /**
     * 범례 점검 뒤 띠 주인 다시 정하기: 구역의 대표가 아닌 가는 띠(상자 2.2배 이상) 조각들을 맞붙은(간격 4 이하) 것끼리 묶고, 묶음의 띠 중 하나가
     * 구역 블록(맞닿은 덩어리 조각들의 상자)의 마주 보는 변을 가장 많이 덮는 구역을 고른다. 그 구역이 지금 구역보다 30%p 넘게 더 덮으면 묶음째 옮긴다
     * (화정체육관 J 옆 휠체어석 막대와 파란 띠가 서로를 근거로 F1에 붙어 있던 것).
     */
    private static void reassignStrips(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId) {
        Map<String, int[]> blocks = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> z : zones.entrySet()) {
            ShapeSegmenter.Segment main = z.getValue().stream().map(byId::get).filter(java.util.Objects::nonNull)
                    .filter(q -> !thinStrip(q)).max(java.util.Comparator.comparingInt(ShapeSegmenter.Segment::area)).orElse(null);
            if (main == null) {
                continue;
            }
            int[] u = box(main).clone();
            for (Integer id : z.getValue()) {
                ShapeSegmenter.Segment q = byId.get(id);
                if (q == null || thinStrip(q)) {
                    continue;
                }
                int[] b = box(q);
                if (Math.max(Math.max(0, Math.max(b[0] - u[2], u[0] - b[2])), Math.max(0, Math.max(b[1] - u[3], u[1] - b[3]))) <= 2) {
                    u = new int[]{Math.min(u[0], b[0]), Math.min(u[1], b[1]), Math.max(u[2], b[2]), Math.max(u[3], b[3])};
                }
            }
            blocks.put(z.getKey(), u);
        }
        Map<Integer, String> ownerOf = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> z : zones.entrySet()) {
            if (!blocks.containsKey(z.getKey())) {
                continue; // 띠가 대표인 구역(VIP 띠, 사다리꼴 M·B)은 그 자체가 구역이라 건드리지 않는다
            }
            for (Integer id : z.getValue()) {
                ShapeSegmenter.Segment q = byId.get(id);
                if (q != null && thinStrip(q)) {
                    ownerOf.put(id, z.getKey());
                }
            }
        }
        Set<Integer> done = new HashSet<>();
        for (Integer start : ownerOf.keySet()) {
            if (done.contains(start)) {
                continue;
            }
            List<Integer> group = new ArrayList<>(List.of(start));
            done.add(start);
            for (int k = 0; k < group.size(); k++) {
                int[] a = box(byId.get(group.get(k)));
                for (Integer other : ownerOf.keySet()) {
                    if (done.contains(other)) {
                        continue;
                    }
                    int[] b = box(byId.get(other));
                    int g = Math.max(Math.max(0, Math.max(b[0] - a[2], a[0] - b[2])), Math.max(0, Math.max(b[1] - a[3], a[1] - b[3])));
                    if (g <= 4) {
                        group.add(other);
                        done.add(other);
                    }
                }
            }
            Map<String, Double> score = new LinkedHashMap<>();
            for (Map.Entry<String, int[]> b : blocks.entrySet()) {
                double best = 0;
                for (Integer id : group) {
                    best = Math.max(best, cover(byId.get(id), b.getValue()));
                }
                score.put(b.getKey(), best);
            }
            String current = ownerOf.get(start);
            String best = score.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(current);
            if (!best.equals(current) && score.get(best) > score.getOrDefault(current, 0.0) + 0.3) {
                for (Integer id : group) {
                    String from = ownerOf.get(id);
                    if (!from.equals(best)) {
                        log.info("띠 {}를 {}에서 {}로 옮김", id, from, best);
                        zones.get(from).remove(id);
                        zones.get(best).add(id);
                    }
                }
            }
        }
    }

    private static boolean thinStrip(ShapeSegmenter.Segment s) {
        int[] b = box(s);
        int w = b[2] - b[0] + 1, h = b[3] - b[1] + 1;
        return Math.max(w, h) >= 2.2 * Math.min(w, h);
    }

    /** 세로 띠가 옆 블록의 마주 보는 변을 덮는 비율(간격이 띠 폭 또는 블록 짧은 변의 30%를 넘거나 위아래로 떨어져 있으면 0). 가로 띠는 위아래로 본다. */
    private static double cover(ShapeSegmenter.Segment o, int[] b) {
        if (b == null) {
            return 0;
        }
        int[] a = box(o);
        boolean vertical = a[3] - a[1] >= a[2] - a[0];
        int gx = Math.max(0, Math.max(b[0] - a[2], a[0] - b[2])), gy = Math.max(0, Math.max(b[1] - a[3], a[1] - b[3]));
        int g = vertical ? gx : gy, cross = vertical ? gy : gx;
        int shortO = Math.min(a[2] - a[0], a[3] - a[1]) + 1, shortB = Math.min(b[2] - b[0], b[3] - b[1]) + 1;
        if (cross > 0 || g > Math.max(shortO, 0.3 * shortB)) {
            return 0;
        }
        int overlap = vertical ? Math.min(a[3], b[3]) - Math.max(a[1], b[1]) : Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        int side = (vertical ? b[3] - b[1] : b[2] - b[0]) + 1;
        return Math.max(0, Math.min(1.0, (double) overlap / side));
    }

    /**
     * 좌우대칭 배치(구역 조각의 절반 이상이 거울 위치에 짝이 있음)에서, 짝 없는 구역 조각의 거울 위치에 이름 없는 도형(구역 조각 중앙값의 30% 이상)이
     * 있으면 그 도형들의 글자를 확대 타일로 읽어(최대 16개) 구역 코드다운 이름이면 구역으로 더한다.
     */
    private static void addMirrorZones(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId, BufferedImage img, byte[] jpeg,
                                       List<String> names, DetailExtractor extractor) {
        List<ShapeSegmenter.Segment> owned = owned(zones, byId);
        if (owned.size() < 4) {
            return;
        }
        Set<Integer> taken = new HashSet<>();
        zones.values().forEach(taken::addAll);
        int minX = Integer.MAX_VALUE, maxX = 0;
        for (ShapeSegmenter.Segment s : owned) {
            int[] b = box(s);
            minX = Math.min(minX, b[0]);
            maxX = Math.max(maxX, b[2]);
        }
        double axis = (minX + maxX) / 2.0;
        List<Polygon> polys = new ArrayList<>();
        owned.forEach(s -> polys.add(poly(s.pixels(), 0, false)));
        // 축 다듬기: 짝 가운데들의 중앙값
        List<Double> mids = new ArrayList<>();
        int offAxis = 0;
        Map<Integer, Integer> mate = new HashMap<>();
        for (int i = 0; i < owned.size(); i++) {
            ShapeSegmenter.Segment a = owned.get(i);
            if (Math.abs(a.cx() - axis) <= 10) {
                continue;
            }
            offAxis++;
            Polygon m = poly(a.pixels(), axis, true);
            for (int j = 0; j < owned.size(); j++) {
                if (j != i && iou(m, polys.get(j)) >= 0.35) {
                    mate.put(i, j);
                    mids.add((a.cx() + owned.get(j).cx()) / 2.0);
                    break;
                }
            }
        }
        if (offAxis == 0 || mate.size() < 0.5 * offAxis) {
            return;
        }
        mids.sort(Double::compare);
        axis = mids.get(mids.size() / 2);
        int median = medianArea(owned);
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < owned.size() && cand.size() < 16; i++) {
            ShapeSegmenter.Segment a = owned.get(i);
            if (mate.containsKey(i) || Math.abs(a.cx() - axis) <= 10) {
                continue;
            }
            Polygon m = poly(a.pixels(), axis, true);
            for (ShapeSegmenter.Segment s : byId.values()) {
                if (!taken.contains(s.id()) && !cand.contains(s.id()) && s.area() >= median * 0.3 && iou(m, poly(s.pixels(), 0, false)) >= 0.5) {
                    cand.add(s.id());
                }
            }
        }
        if (cand.isEmpty()) {
            return;
        }
        log.info("대칭 반대편의 이름 없는 도형 {}개를 다시 읽음", cand.size());
        Set<String> known = new HashSet<>();
        names.forEach(n -> {
            String k = DetailExtractionService.keyOf(n);
            if (k != null) {
                known.add(k);
            }
        });
        for (int from = 0; from < cand.size(); from += 8) {
            List<Integer> batch = cand.subList(from, Math.min(cand.size(), from + 8));
            List<byte[]> tiles = new ArrayList<>();
            batch.forEach(id -> tiles.add(ShapeSegmenter.bigTile(jpeg, byId.get(id), 320)));
            Map<Integer, String> read;
            try {
                read = extractor.readTiles(new ArrayList<>(batch), tiles);
            } catch (RuntimeException e) {
                continue;
            }
            for (Integer id : batch) {
                String t = DetailExtractionService.cleanName(read.getOrDefault(id, ""));
                String k = t == null ? null : DetailExtractionService.keyOf(t);
                if (k == null || k.isEmpty() || DetailExtractionService.isStageName(t) || !(known.contains(k) || DetailExtractionService.looksLikeZoneCode(t))) {
                    continue;
                }
                // 이미 있는 구역 이름(앞자리 0 차이 포함, T4=T04)이면 더하지 않는다: 반대편 짝이 아니라 다른 자리의 같은 이름을 잘못 읽은 것이다
                if (zones.keySet().stream().anyMatch(z -> loose(k).equals(loose(DetailExtractionService.keyOf(z))))) {
                    continue;
                }
                log.info("대칭 반대편에서 구역 추가: {} 도형 {}", t, id);
                zones.computeIfAbsent(t, x -> new ArrayList<>()).add(id);
            }
        }
    }

    /** 구역 번호 계열 키: 앞 글자 + 숫자 자리수(앞 글자 없는 세 자리 이상은 백의 자리까지, 301~322 와 401~424 를 나눈다). 번호가 아니면 null. */
    static String seriesKey(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Z]*)(\\d+)").matcher(name == null ? "" : name.toUpperCase(java.util.Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        String digits = m.group(2);
        return m.group(1) + ":" + digits.length() + (m.group(1).isEmpty() && digits.length() >= 3 ? ":" + digits.charAt(0) : "");
    }

    /**
     * 번호가 끊긴 곳 찾기: 같은 계열(T01~T17, 301~322 등)에서 앞뒤 번호(간격 4 이하)는 모양이 있는데 사이 번호가 없으면, 두 구역 사이에 놓인
     * 이름 없는 도형(구역 조각 중앙값의 20% 이상, 두 구역 중심을 잇는 선에서 구역 크기 안)의 글자를 읽어(최대 24개) 빠진 번호이면 구역으로 더한다.
     * 읽어도 없으면 원래 없는 번호로 본다(고척돔 T08~T10).
     */
    private static void addSequenceGaps(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId, byte[] jpeg, DetailExtractor extractor) {
        Map<String, java.util.TreeMap<Integer, String>> series = new HashMap<>();
        for (Map.Entry<String, List<Integer>> z : zones.entrySet()) {
            String k = seriesKey(z.getKey());
            if (k != null && z.getValue().stream().anyMatch(byId::containsKey)) {
                series.computeIfAbsent(k, x -> new java.util.TreeMap<>()).put(Integer.parseInt(z.getKey().replaceAll("\\D", "")), z.getKey());
            }
        }
        Set<Integer> taken = new HashSet<>();
        zones.values().forEach(taken::addAll);
        List<ShapeSegmenter.Segment> owned = owned(zones, byId);
        int median = medianArea(owned);
        Map<Integer, Set<String>> wanted = new LinkedHashMap<>(); // 도형 번호 -> 그 자리에 있을 법한 빠진 이름들
        for (java.util.TreeMap<Integer, String> nums : series.values()) {
            if (nums.size() < 3) {
                continue;
            }
            Integer prev = null;
            for (Integer n : nums.keySet()) {
                if (prev != null && n - prev > 1 && n - prev <= 4) {
                    double[] a = center(zones.get(nums.get(prev)), byId), b = center(zones.get(nums.get(n)), byId);
                    double reach = Math.sqrt(median) * 1.2;
                    String sample = nums.get(prev);
                    String prefix = sample.replaceAll("\\d", "");
                    int width = sample.length() - prefix.length();
                    for (ShapeSegmenter.Segment s : byId.values()) {
                        if (taken.contains(s.id()) || s.area() < median * 0.2 || distToSegment(s.cx(), s.cy(), a, b) > reach) {
                            continue;
                        }
                        for (int m = prev + 1; m < n; m++) {
                            wanted.computeIfAbsent(s.id(), x -> new HashSet<>()).add(prefix + String.format("%0" + width + "d", m));
                        }
                    }
                }
                prev = n;
            }
        }
        if (wanted.isEmpty()) {
            return;
        }
        List<Integer> cand = new ArrayList<>(wanted.keySet()).subList(0, Math.min(24, wanted.size()));
        log.info("번호가 끊긴 곳 사이의 이름 없는 도형 {}개를 다시 읽음", cand.size());
        for (int from = 0; from < cand.size(); from += 8) {
            List<Integer> batch = new ArrayList<>(cand.subList(from, Math.min(cand.size(), from + 8)));
            List<byte[]> tiles = new ArrayList<>();
            batch.forEach(id -> tiles.add(ShapeSegmenter.bigTile(jpeg, byId.get(id), 320)));
            Map<Integer, String> read;
            try {
                read = extractor.readTiles(batch, tiles);
            } catch (RuntimeException e) {
                continue;
            }
            for (Integer id : batch) {
                String t = DetailExtractionService.cleanName(read.getOrDefault(id, ""));
                String k = t == null ? null : DetailExtractionService.keyOf(t);
                if (k == null) {
                    continue;
                }
                for (String miss : wanted.get(id)) {
                    if (loose(k).equals(loose(DetailExtractionService.keyOf(miss)))) {
                        log.info("끊긴 번호 찾음: {} 도형 {}", miss, id);
                        zones.computeIfAbsent(miss, x -> new ArrayList<>()).add(id);
                        break;
                    }
                }
            }
        }
    }

    private static double[] center(List<Integer> ids, Map<Integer, ShapeSegmenter.Segment> byId) {
        double sx = 0, sy = 0, n = 0;
        for (Integer id : ids) {
            ShapeSegmenter.Segment s = byId.get(id);
            if (s != null) {
                sx += (double) s.cx() * s.area();
                sy += (double) s.cy() * s.area();
                n += s.area();
            }
        }
        return n == 0 ? new double[]{0, 0} : new double[]{sx / n, sy / n};
    }

    private static double distToSegment(double px, double py, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - a[0]) * dx + (py - a[1]) * dy) / len2));
        return Math.hypot(px - (a[0] + t * dx), py - (a[1] + t * dy));
    }

    /**
     * 범례 칩: 구역 도형들이 모인 영역의 바로 아래(또는 위) 띠에서, 배경과 다른 색의 작은(6~40px) 꽉 찬(상자의 60% 이상) 점·네모·알약이
     * 같은 높이(±6px)에 2개 이상 늘어선 것. 글자 획은 가늘거나 상자를 덜 채워 빠진다.
     */
    static List<Chip> legendChips(BufferedImage img, List<ShapeSegmenter.Segment> owned) {
        List<Chip> chips = legendChips(img, owned, 60);
        // 연한 범례(화정체육관 흰색·연두·보라 칩은 배경 연회색과 RGB 거리 45~56)는 60으로는 배경에 묻힌다: 그때만 30으로 다시 찾는다
        return chips.size() >= 2 ? chips : legendChips(img, owned, 30);
    }

    private static List<Chip> legendChips(BufferedImage img, List<ShapeSegmenter.Segment> owned, double minBg) {
        int w = img.getWidth(), h = img.getHeight();
        int top = Integer.MAX_VALUE, bottom = 0;
        for (ShapeSegmenter.Segment s : owned) {
            int[] b = box(s);
            top = Math.min(top, b[1]);
            bottom = Math.max(bottom, b[3]);
        }
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        int bg = dominant(rgb);
        List<Chip> found = new ArrayList<>();
        List<int[]> blobs = new ArrayList<>();
        int band = (int) (h * 0.3);
        scanBand(rgb, w, Math.min(h, bottom + 4), Math.min(h, bottom + band), bg, minBg, found, blobs);
        scanBand(rgb, w, Math.max(0, top - band), Math.max(0, top - 4), bg, minBg, found, blobs);
        // 칩 바로 왼쪽(칩 높이 안)에 다른 덩어리가 있으면 글자 획이다(범례 칩은 앞 항목 글자와 넓게 떨어져 있다)
        List<Chip> all = new ArrayList<>();
        for (Chip c : found) {
            int ch = c.y1() - c.y0() + 1;
            // 칩을 감싸는 빛 번짐 테두리 조각(가늘다)은 글자가 아니다: 폭 3px 이상·높이가 칩의 40% 이상인 덩어리만 글자로 본다
            boolean glyph = blobs.stream().anyMatch(b -> b[2] < c.x0() && b[2] >= c.x0() - ch && b[3] >= c.y0() && b[1] <= c.y1()
                    && b[2] - b[0] + 1 >= 3 && b[3] - b[1] + 1 >= 0.4 * ch);
            if (!glyph) {
                all.add(c);
            }
        }
        List<Chip> best = List.of();
        for (Chip c : all) {
            List<Chip> row = new ArrayList<>();
            for (Chip d : all) {
                int hc = c.y1() - c.y0() + 1, hd = d.y1() - d.y0() + 1;
                if (Math.abs((c.y0() + c.y1()) - (d.y0() + d.y1())) <= 12 && hd <= hc * 1.6 && hc <= hd * 1.6) {
                    row.add(d);
                }
            }
            if (row.size() > best.size()) {
                best = row;
            }
        }
        if (best.size() < 2) {
            return List.of();
        }
        // 칩과 그 둘레 테두리가 따로 잡히면 상자가 겹친다: 겹치는 칩은 큰 쪽 하나만 남긴다
        List<Chip> sorted = new ArrayList<>(best);
        sorted.sort((a, b) -> Integer.compare((b.x1() - b.x0()) * (b.y1() - b.y0()), (a.x1() - a.x0()) * (a.y1() - a.y0())));
        List<Chip> out = new ArrayList<>();
        for (Chip c : sorted) {
            if (out.stream().noneMatch(o -> c.x0() <= o.x1() + 2 && o.x0() <= c.x1() + 2 && c.y0() <= o.y1() + 2 && o.y0() <= c.y1() + 2)) {
                out.add(c);
            }
        }
        if (out.size() < 2) {
            return List.of();
        }
        out.sort((a, b) -> Integer.compare(a.x0(), b.x0()));
        return out;
    }

    private static void scanBand(int[] rgb, int w, int y0, int y1, int bg, double minBg, List<Chip> out, List<int[]> blobs) {
        if (y1 - y0 < 6) {
            return;
        }
        boolean[] seen = new boolean[w * (y1 - y0)];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < w; x++) {
                int k = (y - y0) * w + x;
                if (seen[k] || dist(rgb[y * w + x], bg) <= minBg) {
                    continue;
                }
                int base = rgb[y * w + x];
                long sr = 0, sg = 0, sb = 0;
                int n = 0, bx0 = x, bx1 = x, by0 = y, by1 = y;
                seen[k] = true;
                q.add(new int[]{x, y});
                while (!q.isEmpty() && n < 4000) {
                    int[] p = q.poll();
                    int c = rgb[p[1] * w + p[0]];
                    sr += (c >> 16) & 255;
                    sg += (c >> 8) & 255;
                    sb += c & 255;
                    n++;
                    bx0 = Math.min(bx0, p[0]);
                    bx1 = Math.max(bx1, p[0]);
                    by0 = Math.min(by0, p[1]);
                    by1 = Math.max(by1, p[1]);
                    int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] d : nb) {
                        int nx = p[0] + d[0], ny = p[1] + d[1];
                        if (nx < 0 || nx >= w || ny < y0 || ny >= y1) {
                            continue;
                        }
                        int nk = (ny - y0) * w + nx;
                        if (!seen[nk] && dist(rgb[ny * w + nx], base) <= 60 && dist(rgb[ny * w + nx], bg) > minBg) {
                            seen[nk] = true;
                            q.add(new int[]{nx, ny});
                        }
                    }
                }
                q.clear();
                int bw = bx1 - bx0 + 1, bh = by1 - by0 + 1;
                blobs.add(new int[]{bx0, by0, bx1, by1});
                if (bw >= 6 && bh >= 6 && bw <= 40 && bh <= 40 && bw <= bh * 3 && bh <= bw * 2 && n >= 0.6 * bw * bh) {
                    out.add(new Chip(bx0, by0, bx1, by1, 0xFF000000 | (int) (sr / n) << 16 | (int) (sg / n) << 8 | (int) (sb / n)));
                }
            }
        }
    }

    /** 범례 항목 이미지: 칩 왼쪽부터 다음 칩 앞(마지막 칩은 칩 높이의 14배)까지, 칩 높이의 2.5배 높이로 잘라 3배 키운다. */
    private static byte[] legendCrop(BufferedImage img, List<Chip> chips, int i) throws IOException {
        Chip c = chips.get(i);
        int h = c.y1() - c.y0() + 1;
        int x0 = Math.max(0, c.x0() - 2);
        int x1 = i + 1 < chips.size() ? chips.get(i + 1).x0() - 3 : Math.min(img.getWidth() - 1, c.x1() + 14 * h);
        int cy = (c.y0() + c.y1()) / 2;
        int y0 = Math.max(0, cy - (int) (1.25 * h) - 2), y1 = Math.min(img.getHeight() - 1, cy + (int) (1.25 * h) + 2);
        x1 = Math.max(x1, Math.min(img.getWidth() - 1, x0 + 10));
        BufferedImage sub = img.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        BufferedImage big = new BufferedImage(sub.getWidth() * 3, sub.getHeight() * 3, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = big.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(sub, 0, 0, big.getWidth(), big.getHeight(), null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(big, "jpg", out);
        return out.toByteArray();
    }

    /** 도형 안에서 가장 많이 나온 색(16단계로 묶은 색 칸의 평균). 흰 숫자·글자 픽셀에 끌려가지 않는다. */
    static int segmentColor(BufferedImage img, ShapeSegmenter.Segment s) {
        Polygon p = poly(s.pixels(), 0, false);
        java.awt.Rectangle b = p.getBounds().intersection(new java.awt.Rectangle(0, 0, img.getWidth(), img.getHeight()));
        Map<Integer, long[]> bins = new HashMap<>();
        int step = Math.max(1, (int) Math.sqrt((double) b.width * b.height / 4000));
        for (int y = b.y; y < b.y + b.height; y += step) {
            for (int x = b.x; x < b.x + b.width; x += step) {
                if (!p.contains(x, y)) {
                    continue;
                }
                int c = img.getRGB(x, y);
                int key = ((c >> 20) & 15) << 8 | ((c >> 12) & 15) << 4 | ((c >> 4) & 15);
                long[] a = bins.computeIfAbsent(key, k -> new long[4]);
                a[0] += (c >> 16) & 255;
                a[1] += (c >> 8) & 255;
                a[2] += c & 255;
                a[3]++;
            }
        }
        long[] best = null;
        for (long[] a : bins.values()) {
            if (best == null || a[3] > best[3]) {
                best = a;
            }
        }
        if (best == null) {
            return 0xFF000000;
        }
        return 0xFF000000 | (int) (best[0] / best[3]) << 16 | (int) (best[1] / best[3]) << 8 | (int) (best[2] / best[3]);
    }

    private static int dominant(int[] rgb) {
        Map<Integer, Integer> count = new HashMap<>();
        int best = 0, bestN = -1;
        for (int i = 0; i < rgb.length; i += 3) {
            int key = rgb[i] & 0xF0F0F0;
            int n = count.merge(key, 1, Integer::sum);
            if (n > bestN) {
                bestN = n;
                best = key | 0x080808;
            }
        }
        return best;
    }

    /** 글자 뒤 숫자의 앞자리 0을 뗀 키(T04 → T4). */
    private static String loose(String key) {
        return key == null ? "" : key.replaceAll("(?<=[^0-9])0+(?=[0-9])", "");
    }

    static double dist(int a, int b) {
        int dr = ((a >> 16) & 255) - ((b >> 16) & 255), dg = ((a >> 8) & 255) - ((b >> 8) & 255), db = (a & 255) - (b & 255);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    private static int[] box(ShapeSegmenter.Segment s) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = 0, y1 = 0;
        for (int[] p : s.pixels()) {
            x0 = Math.min(x0, p[0]);
            y0 = Math.min(y0, p[1]);
            x1 = Math.max(x1, p[0]);
            y1 = Math.max(y1, p[1]);
        }
        return new int[]{x0, y0, x1, y1};
    }

    private static Polygon poly(List<int[]> pts, double axis, boolean mirror) {
        Polygon p = new Polygon();
        for (int[] v : pts) {
            p.addPoint(mirror ? (int) Math.round(2 * axis - v[0]) : v[0], v[1]);
        }
        return p;
    }

    private static double iou(Polygon a, Polygon b) {
        if (!a.getBounds().intersects(b.getBounds())) {
            return 0;
        }
        java.awt.geom.Area ia = new java.awt.geom.Area(a), ib = new java.awt.geom.Area(b);
        java.awt.geom.Area inter = new java.awt.geom.Area(ia);
        inter.intersect(ib);
        double i = inter.isEmpty() ? 0 : ShapeRegularizer.areaOf(inter);
        java.awt.geom.Area uni = new java.awt.geom.Area(ia);
        uni.add(ib);
        double u = ShapeRegularizer.areaOf(uni);
        return u <= 0 ? 0 : i / u;
    }
}
