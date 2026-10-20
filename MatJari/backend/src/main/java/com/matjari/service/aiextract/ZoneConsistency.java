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
            if (chips.size() >= 2) {
                dropOffLegend(zones, byId, color, chips, reread, img);
                addMissingLegendColors(zones, byId, color, chips, img, extractor);
            }
            addMirrorZones(zones, byId, img, jpeg, names, extractor);
            if (chips.size() >= 2) {
                refillDented(zones, byId, color, chips, img);
            }
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

    /** 범례에 없는 색의 구역은 타일 재판독에서 그 구역 이름이 한 번도 읽히지 않았으면 뺀다(글자가 실제로 쓰여 있는 회색 블록은 남는다). */
    private static void dropOffLegend(Map<String, List<Integer>> zones, Map<Integer, ShapeSegmenter.Segment> byId, Map<Integer, Integer> color,
                                      List<Chip> chips, Map<Integer, String> reread, BufferedImage img) {
        for (Map.Entry<String, List<Integer>> z : zones.entrySet()) {
            boolean inLegend = false, confirmed = false;
            String key = DetailExtractionService.keyOf(z.getKey());
            for (Integer id : z.getValue()) {
                if (!byId.containsKey(id)) {
                    continue;
                }
                inLegend |= chips.stream().anyMatch(c -> dist(c.rgb(), color.get(id)) < SAME_COLOR);
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
                    boolean off = byId.containsKey(id) && chips.stream().noneMatch(c -> dist(c.rgb(), color.get(id)) < SAME_COLOR);
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
                    ShapeSegmenter.Segment grown = regrow(img, removed, byId, main, color.get(main.id()), nextId);
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
                                     List<Chip> chips, BufferedImage img) {
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
                if (s == null || chips.stream().noneMatch(c -> dist(c.rgb(), color.get(id)) < SAME_COLOR)) {
                    continue;
                }
                double outline = Math.abs(ShapeSegmenter.shoelaceArea(s.pixels())), hull = Math.abs(ShapeSegmenter.shoelaceArea(ShapeSegmenter.convexHull(s.pixels())));
                if (outline <= 0 || hull <= outline * 1.3) {
                    continue;
                }
                int zc = color.get(id);
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
                        if (own.contains(gx, gy) && dist(img.getRGB(gx, gy), zc) < 60) {
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
                        if (dist(img.getRGB(gx, gy), zc) >= 60 || polys.entrySet().stream().anyMatch(e -> e.getKey() != id && e.getValue().contains(gx, gy))) {
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
                ShapeSegmenter.Segment filled = outlineSegment(keep, bounds, zc, id);
                if (filled != null && Math.abs(ShapeSegmenter.shoelaceArea(filled.pixels())) > outline * 1.25) { // 원래 오목한 블록(원형 무대를 따라 파인 F1)은 다시 채워도 거의 그대로라 바꾸지 않는다
                    log.info("글자로 파인 조각을 같은 색으로 다시 채움: 조각 {} 넓이 {} → {}", id, (int) outline, filled.area());
                    byId.put(id, filled);
                    polys.put(id, poly(filled.pixels(), 0, false));
                }
            }
        }
    }

    /** 픽셀 마스크의 합집합 외곽선(흰 글자 구멍은 테두리 3px 덧칠로 메움)에서 가장 넓은 고리로 조각을 만든다. */
    private static ShapeSegmenter.Segment outlineSegment(boolean[] keep, java.awt.Rectangle bounds, int zoneColor, int id) {
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

    /**
     * 뺀 조각들 안에서 구역 색(RGB 거리 60 이내) 픽셀을 모아, 대표 조각과 4px 안으로 맞닿고 대표 조각 넓이의 15% 이상인 덩어리를 하나의 조각으로 만든다.
     * 외곽선은 픽셀 줄들의 합집합(흰 글자 구멍은 테두리 덧칠로 메움)에서 가장 넓은 고리다. 없으면 null.
     */
    private static ShapeSegmenter.Segment regrow(BufferedImage img, List<Integer> removed, Map<Integer, ShapeSegmenter.Segment> byId,
                                                 ShapeSegmenter.Segment main, int zoneColor, int id) {
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
                m[y * bw + x] = inside && dist(img.getRGB(px, py), zoneColor) < 60;
            }
        }
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
                near |= gx >= mb[0] - 4 && gx <= mb[2] + 4 && gy >= mb[1] - 4 && gy <= mb[3] + 4;
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
            log.info("범례 이름으로 구역 추가: {} 도형 {}", name, found.get(c));
            zones.computeIfAbsent(name, k -> new ArrayList<>()).addAll(found.get(c));
        }
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

    /**
     * 범례 칩: 구역 도형들이 모인 영역의 바로 아래(또는 위) 띠에서, 배경과 다른 색의 작은(6~40px) 꽉 찬(상자의 60% 이상) 점·네모·알약이
     * 같은 높이(±6px)에 2개 이상 늘어선 것. 글자 획은 가늘거나 상자를 덜 채워 빠진다.
     */
    static List<Chip> legendChips(BufferedImage img, List<ShapeSegmenter.Segment> owned) {
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
        scanBand(rgb, w, Math.min(h, bottom + 4), Math.min(h, bottom + band), bg, found, blobs);
        scanBand(rgb, w, Math.max(0, top - band), Math.max(0, top - 4), bg, found, blobs);
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

    private static void scanBand(int[] rgb, int w, int y0, int y1, int bg, List<Chip> out, List<int[]> blobs) {
        if (y1 - y0 < 6) {
            return;
        }
        boolean[] seen = new boolean[w * (y1 - y0)];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int y = y0; y < y1; y++) {
            for (int x = 0; x < w; x++) {
                int k = (y - y0) * w + x;
                if (seen[k] || dist(rgb[y * w + x], bg) <= 60) {
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
                        if (!seen[nk] && dist(rgb[ny * w + nx], base) <= 60 && dist(rgb[ny * w + nx], bg) > 60) {
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
