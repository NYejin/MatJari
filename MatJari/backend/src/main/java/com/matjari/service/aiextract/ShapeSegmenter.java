package com.matjari.service.aiextract;

import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 좌석표 이미지에서 색이 같은 덩어리(구역 도형 후보)를 고전적인 영상 처리로 찾는다(AI 좌표 대신 실제 픽셀 윤곽을 쓰기 위함).
 * 순서: 테두리 중앙값을 배경색으로 보고 배경과 다른 픽셀만 남김 -> 색을 24단계로 묶어 같은 색끼리 -> 5x5 닫기(작은 틈 메움) ->
 * 연결 요소 -> 외곽선 따라가기 -> 꼭짓점 줄이기(RDP) -> 큰 도형 안에 든 작은 도형(글자 등) 제거.
 * 글꼴에 의존하지 않도록 번호는 자체 5x7 숫자 모양으로 그린다(서버에 폰트가 없어도 동작).
 */
@Component
public class ShapeSegmenter {

    static final int MAX_SEGMENTS = 150;
    private static final int BG_DISTANCE = 38;
    private static final int MIN_KEY_PIXELS = 150;
    private static final int MIN_AREA = 300;
    private static final int MAX_VERTICES = 40;
    /** 가로·세로 중 짧은 변이 이보다 작으면(글자 조각·가는 선) 구역 도형 후보에서 뺀다. */
    private static final int MIN_SIDE = 16;
    /** 이미지의 이 비율을 넘는 덩어리는 배경 그라데이션 등으로 보고 도형 후보에서 뺀다(무대도 이보다 작다). */
    private static final double MAX_AREA_RATIO = 0.10;
    /** 이 크기(픽셀) 이하의 닫힌 구멍은 글자 자리로 보고 메운다. */
    private static final int MAX_HOLE = 500;
    /** 색 묶음의 연결 요소가 이보다 작으면 잡티로 보고 버린다(합치기 전 기준이라 작다). */
    private static final int MIN_COMPONENT = 120;
    /** 서로 다른 색 요소를 한 도형으로 합치는 조건: 맞닿은 점 수 / 두 요소의 최소 평균 채도. */
    private static final int MERGE_CONTACT = 10;
    private static final double MERGE_SAT = 0.30;
    /** 맞닿은 점 수가 작은 쪽 요소 크기의 이 비율 이상이어야 합친다(한 블록 안의 두 색은 길게 맞닿고, 간격이 좁은 이웃 블록은 짧게 닿는다). */
    private static final double MERGE_RATIO = 0.2;
    /** 합칠 때 두 요소 모두 이 크기(픽셀) 이상이어야 한다: 작은 경계 조각이 서로 다른 블록을 잇는 다리가 되는 것을 막는다. */
    private static final int MERGE_MIN_AREA = 250;
    /** 합칠 수 있는 요소의 최대 크기(이미지 대비 비율). 블록 한 개는 1% 안팎이다. */
    private static final double MERGE_MAX_RAW_RATIO = 0.04;

    /** pixels: 외곽 꼭짓점 [x,y] 목록(원본 픽셀 좌표). */
    public record Segment(int id, List<int[]> pixels, int area, int cx, int cy, double sat, double val) {}

    public record Result(int width, int height, List<Segment> segments, byte[] annotatedJpeg, byte[] tilesJpeg) {}

    public Result segment(byte[] jpeg) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(jpeg));
        } catch (IOException e) {
            return null;
        }
        if (img == null) {
            return null;
        }
        int w = img.getWidth(), h = img.getHeight();
        if (w < 50 || h < 50 || (long) w * h > 12_000_000L) {
            return null;
        }
        int[] rgb = medianFilter(img.getRGB(0, 0, w, h, null, 0, w), w, h); // 빗금·디더링 질감을 지워 같은 블록이 한 색이 되게 한다
        int[] bg = dominantColor(rgb);
        boolean[] fg = new boolean[w * h];
        int[] key = new int[w * h];
        for (int i = 0; i < rgb.length; i++) {
            int r = (rgb[i] >> 16) & 255, g = (rgb[i] >> 8) & 255, b = rgb[i] & 255;
            int dr = r - bg[0], dg = g - bg[1], db = b - bg[2];
            fg[i] = dr * dr + dg * dg + db * db > BG_DISTANCE * BG_DISTANCE;
            key[i] = (r / 24) * 10000 + (g / 24) * 100 + (b / 24);
        }
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < key.length; i++) {
            if (fg[i]) {
                counts.merge(key[i], 1, Integer::sum);
            }
        }
        int[] owner = new int[w * h];
        List<Raw> raws = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() < MIN_KEY_PIXELS) {
                continue;
            }
            boolean[] m = new boolean[w * h];
            for (int i = 0; i < m.length; i++) {
                m[i] = fg[i] && key[i] == e.getKey();
            }
            // 색이 있거나(채도 0.3 이상) 아주 어두운 블록만 3x3 으로 닫아 잡티를 메운다. 밝은 회색 블록은 닫으면 블록 사이 1~2px 틈이 메워져 이웃 블록이 띠로 이어진다
            double satSum = 0, valSum = 0;
            int satN = 0;
            for (int i = 0; i < m.length; i++) {
                if (m[i]) {
                    int r = (rgb[i] >> 16) & 255, g = (rgb[i] >> 8) & 255, b = rgb[i] & 255;
                    int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                    satSum += mx == 0 ? 0 : (double) (mx - mn) / mx;
                    valSum += mx;
                    satN++;
                }
            }
            if (satN > 0 && (satSum / satN >= 0.3 || valSum / satN < 70)) { // 색이 있거나 아주 어두운(검은) 블록
                m = close(m, w, h);
            }
            m = fillSmallHoles(m, w, h);
            collectRaw(m, w, h, rgb, owner, raws);
        }
        List<Segment> found = buildSegments(owner, raws, fg, w, h);
        found = dropOutsideCluster(dropStageLike(found));
        // 큰 것부터 남기고, 더 큰 도형 안에 중심이 든 작은 도형(글자 조각 등)은 버린다
        found.sort(Comparator.comparingInt(Segment::area).reversed());
        List<Segment> kept = new ArrayList<>();
        List<Polygon> keptPolys = new ArrayList<>();
        for (Segment s : found) {
            boolean inside = false;
            for (int k = 0; k < kept.size(); k++) {
                // 블록 안의 글자 조각(블록이 2~10배 큼)만 버린다. 거대한 배경 덩어리는 그 안의 블록을 지우면 안 된다
                if (kept.get(k).area() >= s.area() * 2 && kept.get(k).area() <= s.area() * 10 && keptPolys.get(k).contains(s.cx(), s.cy())) {
                    inside = true;
                    break;
                }
            }
            if (!inside && kept.size() < MAX_SEGMENTS) {
                kept.add(s);
                keptPolys.add(toPolygon(s.pixels()));
            }
        }
        // 위→아래·왼→오른쪽 순으로 번호를 매긴다(번호가 읽기 순서와 비슷해 AI가 찾기 쉽다)
        kept.sort(Comparator.comparingInt((Segment s) -> s.cy() / 40).thenComparingInt(Segment::cx));
        List<Segment> numbered = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            Segment s = kept.get(i);
            numbered.add(new Segment(i + 1, s.pixels(), s.area(), s.cx(), s.cy(), s.sat(), s.val()));
        }
        return new Result(w, h, List.copyOf(numbered), annotate(img, numbered), tileSheet(img, numbered));
    }

    /** 채널별 3x3 중간값 필터(질감 제거). 5x5 는 블록 사이 2px 틈을 지워 이웃 블록이 한 덩어리가 되므로 3x3 으로 한다. */
    static int[] medianFilter(int[] src, int w, int h) {
        int[] out = new int[src.length];
        int[] r = new int[9], g = new int[9], b = new int[9];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = Math.min(h - 1, Math.max(0, y + dy));
                    for (int dx = -1; dx <= 1; dx++) {
                        int v = src[yy * w + Math.min(w - 1, Math.max(0, x + dx))];
                        r[n] = (v >> 16) & 255;
                        g[n] = (v >> 8) & 255;
                        b[n] = v & 255;
                        n++;
                    }
                }
                Arrays.sort(r);
                Arrays.sort(g);
                Arrays.sort(b);
                out[y * w + x] = (r[4] << 16) | (g[4] << 8) | b[4];
            }
        }
        return out;
    }

    /**
     * 배경색: 이미지에서 가장 많이 쓰인 색(색을 16단계로 묶은 최빈 칸의 평균색). 가장자리 색을 쓰면 지도 주변에 어두운 페이지 여백이 있을 때
     * 흰 지도 판 전체를 도형으로 오인한다.
     */
    static int[] dominantColor(int[] rgb) {
        int[] count = new int[4096];
        long[] sr = new long[4096], sg = new long[4096], sb = new long[4096];
        for (int v : rgb) {
            int r = (v >> 16) & 255, g = (v >> 8) & 255, b = v & 255;
            int k = (r >> 4) * 256 + (g >> 4) * 16 + (b >> 4);
            count[k]++;
            sr[k] += r;
            sg[k] += g;
            sb[k] += b;
        }
        int best = 0;
        for (int k = 1; k < 4096; k++) {
            if (count[k] > count[best]) {
                best = k;
            }
        }
        int c = Math.max(1, count[best]);
        return new int[]{(int) (sr[best] / c), (int) (sg[best] / c), (int) (sb[best] / c)};
    }

    private static int[] borderMedian(int[] rgb, int w, int h) {
        int ring = 6;
        List<int[]> px = new ArrayList<>();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (y < ring || y >= h - ring || x < ring || x >= w - ring) {
                    int v = rgb[y * w + x];
                    px.add(new int[]{(v >> 16) & 255, (v >> 8) & 255, v & 255});
                }
            }
        }
        int[] out = new int[3];
        for (int c = 0; c < 3; c++) {
            int[] vals = new int[px.size()];
            for (int i = 0; i < vals.length; i++) {
                vals[i] = px.get(i)[c];
            }
            Arrays.sort(vals);
            out[c] = vals[vals.length / 2];
        }
        return out;
    }

    /** 3x3 닫기(팽창 뒤 침식). 5x5 로 하면 블록 사이 1~2px 틈(촘촘히 쌓인 같은 색 블록)까지 메워 한 덩어리가 된다. */
    static boolean[] close(boolean[] m, int w, int h) {
        return erode(dilate(m, w, h), w, h);
    }

    /** 바깥(이미지 가장자리)과 이어지지 않은 작은 구멍(블록 안의 숫자·글자 자리)을 메운다. 구멍이 가장자리를 파고들어 외곽선이 우글거리는 것을 막는다. */
    static boolean[] fillSmallHoles(boolean[] m, int w, int h) {
        boolean[] out = m.clone();
        boolean[] seen = new boolean[m.length];
        int[] queue = new int[m.length];
        for (int start = 0; start < m.length; start++) {
            if (m[start] || seen[start]) {
                continue;
            }
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            boolean border = false;
            while (head < tail) {
                int p = queue[head++];
                int x = p % w, y = p / w;
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    border = true;
                }
                int[] nb = {p - 1, p + 1, p - w, p + w};
                for (int q : nb) {
                    if (q < 0 || q >= m.length || m[q] || seen[q]) {
                        continue;
                    }
                    if ((q == p - 1 && x == 0) || (q == p + 1 && x == w - 1)) {
                        continue;
                    }
                    seen[q] = true;
                    queue[tail++] = q;
                }
            }
            if (!border && tail <= MAX_HOLE) {
                for (int i = 0; i < tail; i++) {
                    out[queue[i]] = true;
                }
            }
        }
        return out;
    }

    private static boolean[] dilate(boolean[] m, int w, int h) {
        boolean[] tmp = new boolean[m.length];
        boolean[] out = new boolean[m.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = false;
                for (int dx = -1; dx <= 1 && !v; dx++) {
                    int nx = x + dx;
                    v = nx >= 0 && nx < w && m[y * w + nx];
                }
                tmp[y * w + x] = v;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = false;
                for (int dy = -1; dy <= 1 && !v; dy++) {
                    int ny = y + dy;
                    v = ny >= 0 && ny < h && tmp[ny * w + x];
                }
                out[y * w + x] = v;
            }
        }
        return out;
    }

    private static boolean[] erode(boolean[] m, int w, int h) {
        boolean[] tmp = new boolean[m.length];
        boolean[] out = new boolean[m.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = true;
                for (int dx = -1; dx <= 1 && v; dx++) {
                    int nx = x + dx;
                    v = nx >= 0 && nx < w && m[y * w + nx];
                }
                tmp[y * w + x] = v;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = true;
                for (int dy = -1; dy <= 1 && v; dy++) {
                    int ny = y + dy;
                    v = ny >= 0 && ny < h && tmp[ny * w + x];
                }
                out[y * w + x] = v;
            }
        }
        return out;
    }

    /**
     * 채도가 낮은 밝기 90 이상의 회색이고 도형 크기 중앙값의 2.5배를 넘는 큰 구조물(무대·돌출 무대·통로)은 좌석 블록 후보에서 뺀다. 검은색 큰 도형은 남긴다.
     * 연한 회색 좌석 블록(예: 2F 구역)은 중앙값 크기라 남는다.
     */
    static List<Segment> dropStageLike(List<Segment> found) {
        if (found.size() < 5) {
            return found;
        }
        int[] areas = found.stream().mapToInt(Segment::area).sorted().toArray();
        double median = areas[areas.length / 2];
        List<Segment> out = new ArrayList<>();
        for (Segment sg : found) {
            if (sg.sat() < 0.2 && sg.val() >= 90 && sg.area() > median * 2.5) { // 검은색 큰 블록(i-dle F3·F4·F5)은 남기고 타일 글자(STAGE 여부)로 가른다
                continue;
            }
            out.add(sg);
        }
        return out;
    }

    /**
     * 좌석 블록 크기(넓이가 중앙값의 0.4~2.5배)인 도형들이 모인 영역을 지도로 보고, 그 영역(둘레 12% 여유) 밖에 있는 도형(지도 위 안내 글자·버튼)과
     * 영역의 60% 이상을 덮는 도형(지도를 둘러싼 흰 패널)을 뺀다.
     */
    static List<Segment> dropOutsideCluster(List<Segment> found) {
        if (found.size() < 8) {
            return found;
        }
        int[] areas = found.stream().mapToInt(Segment::area).sorted().toArray();
        double median = areas[areas.length / 2];
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = 0, maxY = 0, n = 0;
        for (Segment sg : found) {
            if (sg.area() >= median * 0.4 && sg.area() <= median * 2.5) {
                n++;
                for (int[] p : sg.pixels()) {
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxY = Math.max(maxY, p[1]);
                }
            }
        }
        if (n < 6) {
            return found;
        }
        double mx = (maxX - minX) * 0.12, my = (maxY - minY) * 0.12;
        double bw = maxX - minX, bh = maxY - minY;
        List<Segment> out = new ArrayList<>();
        for (Segment sg : found) {
            boolean inside = sg.cx() >= minX - mx && sg.cx() <= maxX + mx && sg.cy() >= minY - my && sg.cy() <= maxY + my;
            int sx0 = Integer.MAX_VALUE, sx1 = 0, sy0 = Integer.MAX_VALUE, sy1 = 0;
            for (int[] p : sg.pixels()) {
                sx0 = Math.min(sx0, p[0]);
                sx1 = Math.max(sx1, p[0]);
                sy0 = Math.min(sy0, p[1]);
                sy1 = Math.max(sy1, p[1]);
            }
            boolean panel = (sx1 - sx0) >= bw * 0.6 && (sy1 - sy0) >= bh * 0.6;
            if (inside && !panel) {
                out.add(sg);
            }
        }
        return out;
    }

    /** 점검용(테스트에서만 읽는다): 마지막 분할의 요소 번호 지도와 합친 뒤 도형 번호 지도. */
    static volatile int[] lastRawOwner;
    static volatile int[] lastGroupMap;

    /** 같은 색 묶음의 연결 요소 하나(합치기 전). sat 은 평균 채도(0~1): 회색·검은색·흰색은 낮다. */
    private record Raw(int[] pixels, double sat, double val) {}

    /** 색 묶음 하나의 연결 요소(8방향)들을 모은다. 너무 작은 것(잡티)은 버린다. owner 에는 요소 번호(1부터)를 적는다. */
    private static void collectRaw(boolean[] m, int w, int h, int[] rgb, int[] owner, List<Raw> raws) {
        boolean[] seen = new boolean[m.length];
        int[] queue = new int[m.length];
        for (int start = 0; start < m.length; start++) {
            if (!m[start] || seen[start]) {
                continue;
            }
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            while (head < tail) {
                int p = queue[head++];
                int px = p % w, py = p / w;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx, ny = py + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            int q = ny * w + nx;
                            if (m[q] && !seen[q]) {
                                seen[q] = true;
                                queue[tail++] = q;
                            }
                        }
                    }
                }
            }
            if (tail < MIN_COMPONENT) {
                continue;
            }
            int[] px = Arrays.copyOf(queue, tail);
            double sat = 0, val = 0;
            for (int p : px) {
                int r = (rgb[p] >> 16) & 255, g = (rgb[p] >> 8) & 255, b = rgb[p] & 255;
                int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                sat += mx == 0 ? 0 : (double) (mx - mn) / mx;
                val += mx;
            }
            raws.add(new Raw(px, sat / tail, val / tail));
            int id = raws.size();
            for (int p : px) {
                if (owner[p] == 0) {
                    owner[p] = id;
                }
            }
        }
    }

    /**
     * 두 요소를 합친 모양이 한 좌석 블록답게 거의 볼록(두 넓이의 합이 합친 볼록 껍질의 50% 이상)하고, 둘 다 가는 선·점선(짧은 변 7px 미만, 예: 휠체어석
     * 표시)이 아닐 때만 true. 점선이 여러 블록을 이어 붙여 한 덩어리가 되는 것을 막는다.
     */
    private static boolean compactUnion(Raw a, Raw b, int w) {
        if (thin(a, w) || thin(b, w)) {
            return false;
        }
        List<int[]> pts = new ArrayList<>(a.pixels().length + b.pixels().length);
        for (int p : a.pixels()) {
            pts.add(new int[]{p % w, p / w});
        }
        for (int p : b.pixels()) {
            pts.add(new int[]{p % w, p / w});
        }
        List<int[]> hull = convexHull(pts);
        double ha = Math.abs(shoelace(hull));
        return ha > 0 && (a.pixels().length + b.pixels().length) >= ha * 0.5;
    }

    private static boolean thin(Raw r, int w) {
        int minX = Integer.MAX_VALUE, maxX = 0, minY = Integer.MAX_VALUE, maxY = 0;
        for (int p : r.pixels()) {
            int x = p % w, y = p / w;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return Math.min(maxX - minX + 1, maxY - minY + 1) < 7;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    /**
     * 같은 좌석 블록이 색으로 갈라진 것(예: 진한 빨강 VIP 부분 + 주황 일반석 부분)을 하나로 합친다: 둘 다 채도가 높고(회색·검은 무대·흰 글자 제외)
     * 경계가 맞닿은 길이가 충분하면 같은 도형으로 본다. 합친 뒤에 크기 조건을 보고 외곽선을 따라가 Segment 로 만든다.
     */
    private static List<Segment> buildSegments(int[] owner, List<Raw> raws, boolean[] fg, int w, int h) {
        int n = raws.size();
        // 배경 그라데이션이 만든 거대한 덩어리는 합치기에서 뺀다(블록과 색이 비슷하면 블록까지 한 덩어리가 돼 통째로 버려진다)
        int maxMergeRaw = (int) ((long) w * h * MERGE_MAX_RAW_RATIO);
        int[] parent = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            parent[i] = i;
        }
        java.util.Map<Long, Integer> contact = new java.util.HashMap<>();
                // 블록 안 두 색 사이에는 배경과 섞인 얇은 경계가 있어 3px 거리까지 맞닿은 것으로 본다(옆 블록과는 간격이 더 넓다)
        int[][] offs = {{1, 0}, {0, 1}, {2, 0}, {0, 2}, {1, 1}, {-1, 1}, {3, 0}, {0, 3}, {2, 2}, {-2, 2}};
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = owner[y * w + x];
                if (a == 0) {
                    continue;
                }
                for (int[] o : offs) {
                    int nx = x + o[0], ny = y + o[1];
                    if (nx < 0 || nx >= w || ny < 0 || ny >= h) {
                        continue;
                    }
                    int b = owner[ny * w + nx];
                    if (b == 0 || b == a) {
                        continue;
                    }
                    // 두 점 사이에 배경이 끼어 있으면(간격이 있는 이웃 블록) 맞닿은 것이 아니다. 사이가 모두 도형 색(경계 번짐 포함)일 때만 센다
                    int steps = Math.max(Math.abs(o[0]), Math.abs(o[1]));
                    boolean solid = true;
                    for (int k = 1; k < steps && solid; k++) {
                        solid = fg[(y + o[1] * k / steps) * w + (x + o[0] * k / steps)];
                    }
                    if (solid) {
                        contact.merge((long) Math.min(a, b) * 1_000_000L + Math.max(a, b), 1, Integer::sum);
                    }
                }
            }
        }
        for (java.util.Map.Entry<Long, Integer> e : contact.entrySet()) {
            int a = (int) (e.getKey() / 1_000_000L), b = (int) (e.getKey() % 1_000_000L);
            int smaller = Math.min(raws.get(a - 1).pixels().length, raws.get(b - 1).pixels().length);
            int larger = Math.max(raws.get(a - 1).pixels().length, raws.get(b - 1).pixels().length);
            if (larger <= maxMergeRaw && smaller >= MERGE_MIN_AREA && e.getValue() >= MERGE_CONTACT && e.getValue() >= MERGE_RATIO * smaller
                    && raws.get(a - 1).sat() >= MERGE_SAT && raws.get(b - 1).sat() >= MERGE_SAT
                    && compactUnion(raws.get(a - 1), raws.get(b - 1), w)) {
                parent[find(parent, a)] = find(parent, b);
            }
        }
        int[] grp = new int[w * h];
        java.util.Map<Integer, List<Integer>> members = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= n; i++) {
            members.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        List<Segment> out = new ArrayList<>();
        int gid = 0;
        lastRawOwner = owner;
        lastGroupMap = grp;
        for (List<Integer> ms : members.values()) {
            gid++;
            long sumX = 0, sumY = 0;
            double satSum = 0, valSum = 0;
            int area = 0, minX = w, maxX = 0, minY = h, maxY = 0, first = Integer.MAX_VALUE;
            for (int rid : ms) {
                satSum += raws.get(rid - 1).sat() * raws.get(rid - 1).pixels().length;
                valSum += raws.get(rid - 1).val() * raws.get(rid - 1).pixels().length;
                for (int p : raws.get(rid - 1).pixels()) {
                    if (grp[p] != 0) {
                        continue;
                    }
                    grp[p] = gid;
                    int px = p % w, py = p / w;
                    area++;
                    sumX += px;
                    sumY += py;
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py);
                    maxY = Math.max(maxY, py);
                    first = Math.min(first, p);
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if ((minX <= 1 || maxX >= w - 2) && bw < 40) { // 이미지 좌우 가장자리에 걸친 가는 조각(페이지 여백·그라데이션 잔재)은 도형이 아니다
                continue;
            }
            if (area < MIN_AREA || bw > w * 0.8 || area > (long) w * h * MAX_AREA_RATIO || bw < MIN_SIDE || bh < MIN_SIDE) {
                continue;
            }
            List<int[]> contour = trace(grp, gid, w, h, first);
            // 가장자리의 1픽셀 돌기에서 추적이 일찍 끝나 외곽선이 너무 작게 나오면 가장자리 픽셀들의 볼록 껍질로 대신한다
            if (contour.size() < 12 || Math.abs(shoelace(contour)) < 0.6 * area) {
                contour = boundaryHull(grp, gid, w, h, minX, maxX, minY, maxY);
            }
            if (contour.size() < 3) {
                continue;
            }
            List<int[]> shape = chooseShape(contour, area);
            if (shape == null) {
                continue;
            }
            List<int[]> poly = simplify(shape);
            if (poly.size() >= 3) {
                int rawTotal = Math.max(1, ms.stream().mapToInt(r -> raws.get(r - 1).pixels().length).sum());
                out.add(new Segment(0, poly, area, (int) (sumX / area), (int) (sumY / area), satSum / rawTotal, valSum / rawTotal));
            }
        }
        return out;
    }

    /**
     * 외곽선 모양을 고른다(pixelArea 는 도형 안의 실제 픽셀 수). 색 경계의 잡티로 들쭉날쭉한 외곽선을 다듬는다:
     * 볼록 껍질 넓이가 외곽선 넓이의 1.35배 이하(거의 볼록한 블록)이면 껍질, 껍질에 비해 속이 많이 비었어도(구멍·글자) 픽셀이 껍질의 45% 이상을
     * 채우고 외곽선 안이 75% 미만만 찼으면 껍질(블록은 볼록하다고 보고), 그보다 오목하지만 단단한 도형(무대 같은 것)은 매끄럽게 다듬은 외곽선을 쓴다.
     * 껍질의 25%도 못 채우는 가늘고 찢어진 조각은 도형이 아니라고 보고 null 이다.
     */
    static List<int[]> chooseShape(List<int[]> contour, int pixelArea) {
        List<int[]> hull = convexHull(contour);
        if (hull.size() < 3) {
            return null;
        }
        double ca = Math.abs(shoelace(contour)), ha = Math.abs(shoelace(hull));
        if (ca <= 0 || ha <= 0 || pixelArea < ha * 0.25) {
            return null;
        }
        if (pixelArea >= ha * 0.5) { // 좌석 블록은 볼록하다: 껍질의 절반 이상을 채우면 껍질(들쭉날쭉한 외곽선·글자 자국을 무시)
            return hull;
        }
        if (pixelArea < ca * 0.75) { // 오목하면서 속도 성긴 도형(점선·글자 조각이 이어 붙은 것)은 도형이 아니다
            return null;
        }
        return smooth(contour);
    }

    /**
     * 볼록하지 않아 껍질을 못 쓰는 외곽선(무대 같은 것)의 들쭉날쭉함을 줄인다: 일정 간격으로 다시 뽑은 점들에
     * Chaikin 모서리 깎기를 두 번 적용한다(닫힌 곡선, 원래 모양에서 크게 벗어나지 않는다).
     */
    static List<int[]> smooth(List<int[]> closed) {
        int n = closed.size();
        if (n < 12) {
            return closed;
        }
        int step = Math.max(1, n / 60);
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i += step) {
            pts.add(new double[]{closed.get(i)[0], closed.get(i)[1]});
        }
        for (int it = 0; it < 2; it++) {
            List<double[]> next = new ArrayList<>();
            for (int i = 0; i < pts.size(); i++) {
                double[] p = pts.get(i), q = pts.get((i + 1) % pts.size());
                next.add(new double[]{0.75 * p[0] + 0.25 * q[0], 0.75 * p[1] + 0.25 * q[1]});
                next.add(new double[]{0.25 * p[0] + 0.75 * q[0], 0.25 * p[1] + 0.75 * q[1]});
            }
            pts = next;
        }
        List<int[]> out = new ArrayList<>();
        for (double[] p : pts) {
            out.add(new int[]{(int) Math.round(p[0]), (int) Math.round(p[1])});
        }
        return out;
    }

    /** 꼭짓점 목록이 이루는 다각형의 부호 있는 넓이(다른 클래스에서 쓴다). */
    static double shoelaceArea(List<int[]> pts) {
        return shoelace(pts);
    }

    private static double shoelace(List<int[]> pts) {
        double a = 0;
        for (int i = 0; i < pts.size(); i++) {
            int[] p = pts.get(i), q = pts.get((i + 1) % pts.size());
            a += (double) p[0] * q[1] - (double) q[0] * p[1];
        }
        return a / 2;
    }

    /** Andrew 단조 체인 볼록 껍질. */
    static List<int[]> convexHull(List<int[]> in) {
        List<int[]> pts = new ArrayList<>(in);
        pts.sort(Comparator.<int[]>comparingInt(p -> p[0]).thenComparingInt(p -> p[1]));
        int n = pts.size();
        if (n < 3) {
            return pts;
        }
        int[][] h = new int[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], pts.get(i)) <= 0) {
                k--;
            }
            h[k++] = pts.get(i);
        }
        for (int i = n - 2, t = k + 1; i >= 0; i--) {
            while (k >= t && cross(h[k - 2], h[k - 1], pts.get(i)) <= 0) {
                k--;
            }
            h[k++] = pts.get(i);
        }
        return new ArrayList<>(Arrays.asList(h).subList(0, k - 1));
    }

    private static long cross(int[] o, int[] a, int[] b) {
        return (long) (a[0] - o[0]) * (b[1] - o[1]) - (long) (a[1] - o[1]) * (b[0] - o[0]);
    }

    /** 도형(grp==id)의 가장자리 픽셀(4방향 이웃 중 도형이 아닌 것이 있는 픽셀)들의 볼록 껍질. */
    private static List<int[]> boundaryHull(int[] grp, int id, int w, int h, int minX, int maxX, int minY, int maxY) {
        List<int[]> pts = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (grp[y * w + x] != id) {
                    continue;
                }
                boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1
                        || grp[y * w + x - 1] != id || grp[y * w + x + 1] != id || grp[(y - 1) * w + x] != id || grp[(y + 1) * w + x] != id;
                if (edge) {
                    pts.add(new int[]{x, y});
                }
            }
        }
        return convexHull(pts);
    }

    private static final int[] DX = {-1, -1, 0, 1, 1, 1, 0, -1};
    private static final int[] DY = {0, -1, -1, -1, 0, 1, 1, 1};

    /** Moore 이웃 추적으로 바깥 경계를 시계 방향으로 따라간다(start 는 래스터 순서 첫 픽셀 = 가장 위·왼쪽). */
    private static List<int[]> trace(int[] label, int id, int w, int h, int start) {
        int sx = start % w, sy = start / w;
        List<int[]> pts = new ArrayList<>();
        int cx = sx, cy = sy, dir = 0;
        pts.add(new int[]{cx, cy});
        int limit = w * h;
        for (int step = 0; step < limit; step++) {
            boolean moved = false;
            for (int i = 0; i < 8; i++) {
                int d = (dir + i) % 8;
                int nx = cx + DX[d], ny = cy + DY[d];
                if (nx >= 0 && nx < w && ny >= 0 && ny < h && label[ny * w + nx] == id) {
                    cx = nx;
                    cy = ny;
                    dir = (d + 6) % 8;
                    moved = true;
                    break;
                }
            }
            if (!moved || (cx == sx && cy == sy)) {
                break;
            }
            pts.add(new int[]{cx, cy});
        }
        return pts;
    }

    /** 꼭짓점이 40개 이하가 될 때까지 허용 오차를 키우며 RDP 로 줄인다. */
    static List<int[]> simplify(List<int[]> closed) {
        double eps = 2.5;
        List<int[]> out = rdpClosed(closed, eps);
        while (out.size() > MAX_VERTICES && eps < 200) {
            eps *= 1.4;
            out = rdpClosed(closed, eps);
        }
        return out;
    }

    private static List<int[]> rdpClosed(List<int[]> pts, double eps) {
        int n = pts.size();
        if (n < 4) {
            return pts;
        }
        int far = 0;
        long best = -1;
        int[] a = pts.get(0);
        for (int i = 1; i < n; i++) {
            long dx = pts.get(i)[0] - a[0], dy = pts.get(i)[1] - a[1];
            long d = dx * dx + dy * dy;
            if (d > best) {
                best = d;
                far = i;
            }
        }
        List<int[]> first = new ArrayList<>(pts.subList(0, far + 1));
        List<int[]> second = new ArrayList<>(pts.subList(far, n));
        second.add(pts.get(0));
        List<int[]> r1 = rdp(first, eps);
        List<int[]> r2 = rdp(second, eps);
        List<int[]> out = new ArrayList<>(r1);
        out.addAll(r2.subList(1, r2.size() - 1));
        return out;
    }

    private static List<int[]> rdp(List<int[]> pts, double eps) {
        int n = pts.size();
        if (n < 3) {
            return new ArrayList<>(pts);
        }
        int[] a = pts.get(0), b = pts.get(n - 1);
        double maxD = -1;
        int idx = -1;
        for (int i = 1; i < n - 1; i++) {
            double d = distToSegment(pts.get(i), a, b);
            if (d > maxD) {
                maxD = d;
                idx = i;
            }
        }
        if (maxD <= eps) {
            return new ArrayList<>(List.of(a, b));
        }
        List<int[]> left = rdp(pts.subList(0, idx + 1), eps);
        List<int[]> right = rdp(pts.subList(idx, n), eps);
        List<int[]> out = new ArrayList<>(left);
        out.addAll(right.subList(1, right.size()));
        return out;
    }

    private static double distToSegment(int[] p, int[] a, int[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2));
        double px = a[0] + t * dx, py = a[1] + t * dy;
        return Math.hypot(p[0] - px, p[1] - py);
    }

    private static Polygon toPolygon(List<int[]> pts) {
        Polygon p = new Polygon();
        for (int[] pt : pts) {
            p.addPoint(pt[0], pt[1]);
        }
        return p;
    }

    // ---- 번호 그리기 ----

    private static final String[] DIGITS = {
            "01110100011001110101110011000101110", // 0
            "00100011000010000100001000010001110", // 1
            "01110100010000100110010001000011111", // 2
            "11110000010000101110000010000111110", // 3
            "00010001100101010010111110001000010", // 4
            "11111100001111000001000011000101110", // 5
            "00110010001000011110100011000101110", // 6
            "11111000010001000100010000100001000", // 7
            "01110100011000101110100011000101110", // 8
            "01110100011000101111000010001001100"  // 9
    };

    /** 한 도형을 크게(변의 최대 size px) 잘라 JPEG 로 돌려준다. 이름이 의심스러운 도형만 다시 읽을 때 쓴다. */
    public static byte[] bigTile(byte[] jpeg, Segment s, int size) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(jpeg));
            int minX = Integer.MAX_VALUE, maxX = 0, minY = Integer.MAX_VALUE, maxY = 0;
            for (int[] p : s.pixels()) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
            int pad = Math.max(12, Math.min(maxX - minX, maxY - minY) / 3);
            int sx = Math.max(0, minX - pad), sy = Math.max(0, minY - pad);
            int sw = Math.min(src.getWidth() - sx, maxX - minX + 1 + 2 * pad), sh = Math.min(src.getHeight() - sy, maxY - minY + 1 + 2 * pad);
            double scale = Math.min((double) size / sw, (double) size / sh);
            int dw = Math.max(1, (int) (sw * scale)), dh = Math.max(1, (int) (sh * scale));
            BufferedImage out = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src.getSubimage(sx, sy, sw, sh), 0, 0, dw, dh, null);
            g.dispose();
            return jpeg(out);
        } catch (IOException e) {
            throw new IllegalStateException("tile failed");
        }
    }

    private static final int TILE = 150;
    private static final int TILE_COLS = 8;

    /**
     * 도형마다 주변까지 잘라 키운 타일을 격자로 모은 이미지(왼쪽 위에 도형 번호). 지도에서 10px 남짓한 구역 이름 글자를
     * 모델이 읽기 쉽게 확대해 보여 주는 용도다(조각내서 보는 방식).
     */
    private static byte[] tileSheet(BufferedImage src, List<Segment> segments) {
        int n = segments.size();
        int rows = Math.max(1, (n + TILE_COLS - 1) / TILE_COLS);
        BufferedImage out = new BufferedImage(TILE_COLS * TILE, rows * TILE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setColor(new Color(40, 40, 40));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        for (int i = 0; i < n; i++) {
            Segment s = segments.get(i);
            int minX = Integer.MAX_VALUE, maxX = 0, minY = Integer.MAX_VALUE, maxY = 0;
            for (int[] p : s.pixels()) {
                minX = Math.min(minX, p[0]);
                maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]);
                maxY = Math.max(maxY, p[1]);
            }
            int pad = Math.max(12, Math.min(maxX - minX, maxY - minY) / 3); // 가장자리에 걸친 번호 원(예: i-dle)이 잘리지 않게 넉넉히 둔다
            int sx = Math.max(0, minX - pad), sy = Math.max(0, minY - pad);
            int sw = Math.min(src.getWidth() - sx, maxX - minX + 1 + 2 * pad), sh = Math.min(src.getHeight() - sy, maxY - minY + 1 + 2 * pad);
            double scale = Math.min((TILE - 6.0) / sw, (TILE - 6.0) / sh);
            int dw = Math.max(1, (int) (sw * scale)), dh = Math.max(1, (int) (sh * scale));
            int tx = (i % TILE_COLS) * TILE + (TILE - dw) / 2, ty = (i / TILE_COLS) * TILE + (TILE - dh) / 2;
            g.drawImage(src.getSubimage(sx, sy, sw, sh), tx, ty, dw, dh, null);
            g.setColor(new Color(90, 90, 90));
            g.drawRect((i % TILE_COLS) * TILE, (i / TILE_COLS) * TILE, TILE - 1, TILE - 1);
            drawLabel(g, String.valueOf(s.id()), (i % TILE_COLS) * TILE + 16, (i / TILE_COLS) * TILE + 12, out.getWidth(), out.getHeight());
        }
        g.dispose();
        return jpeg(out);
    }

    private static byte[] jpeg(BufferedImage out) {
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(out, null, null), param);
            } finally {
                writer.dispose();
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode failed");
        }
    }

    private static byte[] annotate(BufferedImage src, List<Segment> segments) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, null);
        g.setStroke(new BasicStroke(1.5f));
        for (Segment s : segments) {
            g.setColor(new Color(0, 200, 0));
            g.drawPolygon(toPolygon(s.pixels()));
        }
        for (Segment s : segments) {
            int[] at = labelSpot(s);
            drawLabel(g, String.valueOf(s.id()), at[0], at[1], src.getWidth(), src.getHeight());
        }
        g.dispose();
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(out, null, null), param);
            } finally {
                writer.dispose();
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("annotate encode failed");
        }
    }

    /** 번호 상자 위치: 이름 글자가 놓이는 도형 가운데를 피해 도형 위쪽 가장자리 안쪽(가운데 x, 맨 위에서 11px 아래)에 두고, 도형 밖이면 무게중심으로 되돌린다. */
    private static int[] labelSpot(Segment s) {
        int minY = Integer.MAX_VALUE;
        for (int[] p : s.pixels()) {
            minY = Math.min(minY, p[1]);
        }
        int y = minY + 11;
        Polygon poly = toPolygon(s.pixels());
        int x = s.cx();
        if (poly.contains(x, y)) {
            return new int[]{x, y};
        }
        return new int[]{s.cx(), s.cy()};
    }

    private static void drawLabel(Graphics2D g, String text, int cx, int cy, int w, int h) {
        int scale = 2, cw = 5 * scale, ch = 7 * scale, gap = scale;
        int tw = text.length() * cw + (text.length() - 1) * gap;
        int x0 = Math.max(1, Math.min(w - tw - 3, cx - tw / 2));
        int y0 = Math.max(1, Math.min(h - ch - 3, cy - ch / 2));
        g.setColor(new Color(255, 255, 255, 235));
        g.fillRect(x0 - 2, y0 - 2, tw + 4, ch + 4);
        g.setColor(Color.RED);
        g.drawRect(x0 - 2, y0 - 2, tw + 3, ch + 3);
        g.setColor(Color.BLACK);
        int x = x0;
        for (char c : text.toCharArray()) {
            String bits = DIGITS[c - '0'];
            for (int i = 0; i < 35; i++) {
                if (bits.charAt(i) == '1') {
                    g.fillRect(x + (i % 5) * scale, y0 + (i / 5) * scale, scale, scale);
                }
            }
            x += cw + gap;
        }
    }
}
