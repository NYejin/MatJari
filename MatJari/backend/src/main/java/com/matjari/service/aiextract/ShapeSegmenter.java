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
    private static final int MIN_AREA = 250;
    private static final int MAX_VERTICES = 40;

    /** pixels: 외곽 꼭짓점 [x,y] 목록(원본 픽셀 좌표). */
    public record Segment(int id, List<int[]> pixels, int area, int cx, int cy) {}

    public record Result(int width, int height, List<Segment> segments, byte[] annotatedJpeg) {}

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
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        int[] bg = borderMedian(rgb, w, h);
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
        List<Segment> found = new ArrayList<>();
        for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() < MIN_KEY_PIXELS) {
                continue;
            }
            boolean[] m = new boolean[w * h];
            for (int i = 0; i < m.length; i++) {
                m[i] = fg[i] && key[i] == e.getKey();
            }
            m = close(m, w, h);
            collect(m, w, h, found);
        }
        // 큰 것부터 남기고, 더 큰 도형 안에 중심이 든 작은 도형(글자 조각 등)은 버린다
        found.sort(Comparator.comparingInt(Segment::area).reversed());
        List<Segment> kept = new ArrayList<>();
        List<Polygon> keptPolys = new ArrayList<>();
        for (Segment s : found) {
            boolean inside = false;
            for (int k = 0; k < kept.size(); k++) {
                if (kept.get(k).area() >= s.area() * 2 && keptPolys.get(k).contains(s.cx(), s.cy())) {
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
            numbered.add(new Segment(i + 1, s.pixels(), s.area(), s.cx(), s.cy()));
        }
        return new Result(w, h, List.copyOf(numbered), annotate(img, numbered));
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

    /** 5x5 닫기(팽창 뒤 침식). */
    static boolean[] close(boolean[] m, int w, int h) {
        return erode(dilate(m, w, h), w, h);
    }

    private static boolean[] dilate(boolean[] m, int w, int h) {
        boolean[] tmp = new boolean[m.length];
        boolean[] out = new boolean[m.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = false;
                for (int dx = -2; dx <= 2 && !v; dx++) {
                    int nx = x + dx;
                    v = nx >= 0 && nx < w && m[y * w + nx];
                }
                tmp[y * w + x] = v;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = false;
                for (int dy = -2; dy <= 2 && !v; dy++) {
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
                for (int dx = -2; dx <= 2 && v; dx++) {
                    int nx = x + dx;
                    v = nx >= 0 && nx < w && m[y * w + nx];
                }
                tmp[y * w + x] = v;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = true;
                for (int dy = -2; dy <= 2 && v; dy++) {
                    int ny = y + dy;
                    v = ny >= 0 && ny < h && tmp[ny * w + x];
                }
                out[y * w + x] = v;
            }
        }
        return out;
    }

    /** 연결 요소(8방향)마다 면적·크기 조건을 보고 외곽선을 따라가 Segment 로 모은다. */
    private static void collect(boolean[] m, int w, int h, List<Segment> out) {
        int[] label = new int[m.length];
        int next = 0;
        int[] queue = new int[m.length];
        for (int start = 0; start < m.length; start++) {
            if (!m[start] || label[start] != 0) {
                continue;
            }
            next++;
            int head = 0, tail = 0;
            queue[tail++] = start;
            label[start] = next;
            long sumX = 0, sumY = 0;
            int minX = w, maxX = 0, minY = h, maxY = 0;
            while (head < tail) {
                int p = queue[head++];
                int px = p % w, py = p / w;
                sumX += px;
                sumY += py;
                minX = Math.min(minX, px);
                maxX = Math.max(maxX, px);
                minY = Math.min(minY, py);
                maxY = Math.max(maxY, py);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx, ny = py + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            int q = ny * w + nx;
                            if (m[q] && label[q] == 0) {
                                label[q] = next;
                                queue[tail++] = q;
                            }
                        }
                    }
                }
            }
            int area = tail;
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (area < MIN_AREA || bw > w * 0.8 || area > (long) w * h * 0.35 || bw < 6 || bh < 6) {
                continue;
            }
            List<int[]> contour = trace(label, next, w, h, start);
            if (contour.size() < 3) {
                continue;
            }
            List<int[]> shape = chooseShape(contour, area);
            if (shape == null) {
                continue;
            }
            List<int[]> poly = simplify(shape);
            if (poly.size() < 3) {
                continue;
            }
            out.add(new Segment(0, poly, area, (int) (sumX / area), (int) (sumY / area)));
        }
    }

    /**
     * 외곽선 모양을 고른다(pixelArea 는 도형 안의 실제 픽셀 수). 색 경계의 잡티로 들쭉날쭉한 외곽선을 다듬는다:
     * 볼록 껍질 넓이가 외곽선 넓이의 1.35배 이하(거의 볼록한 블록)이면 껍질, 껍질에 비해 속이 많이 비었어도(구멍·글자) 픽셀이 껍질의 45% 이상을
     * 채우고 외곽선 안이 75% 미만만 찼으면 껍질(블록은 볼록하다고 보고), 그보다 오목하지만 단단한 도형(무대 같은 것)은 원래 외곽선을 그대로 둔다.
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
        if (ha <= ca * 1.35) {
            return hull;
        }
        if (pixelArea >= ha * 0.45 && pixelArea < ca * 0.75) {
            return hull;
        }
        return contour;
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
        double eps = 1.5;
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
            drawLabel(g, String.valueOf(s.id()), s.cx(), s.cy(), src.getWidth(), src.getHeight());
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
