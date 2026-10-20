package com.matjari.service.aiextract;

import java.awt.Polygon;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * 구역 도형을 사람이 그린 좌석표처럼 다듬는다(사용자 지시 2026-10-19). 좌표는 0~1000 정규화 값이다.
 * <ul>
 *   <li>직사각형·사다리꼴에 가까운 조각은 꼭짓점 4개로 맞추고, 직각에 가까우면 정확한 직사각형으로, 마주 보는 두 변이 거의 평행하면 평행하게 맞춘다.
 *       거의 수평인 윗변·밑변은 수평으로, 거의 수직인 옆변은 수직으로 편다.</li>
 *   <li>그 밖의 조각은 꼭짓점을 {@link #MAX_VERTICES}개 이하로 줄인다.</li>
 *   <li>배치가 좌우대칭(대칭 짝이 있는 조각이 60% 이상)이면 짝을 이룬 조각끼리 평균 내 정확히 대칭으로 만든다.</li>
 * </ul>
 */
final class ShapeRegularizer {

    static final int MAX_VERTICES = 8;
    /** 사각형으로 바꿔도 되는 최소 IoU. */
    private static final double QUAD_IOU = 0.9;
    /** 수평·수직으로 펼 기울기 한도(도). */
    private static final double SNAP_DEG = 10;
    /** 직각·평행으로 맞출 각도 한도(도). */
    private static final double SQUARE_DEG = 12;

    private ShapeRegularizer() {}

    static List<DetailExtractor.RawZone> regularize(List<DetailExtractor.RawZone> zones) {
        List<DetailExtractor.RawZone> out = new ArrayList<>();
        List<int[][]> all = new ArrayList<>();
        for (DetailExtractor.RawZone z : zones) {
            List<List<List<Integer>>> polys = new ArrayList<>();
            for (List<List<Integer>> p : z.polygons() == null ? List.<List<List<Integer>>>of() : z.polygons()) {
                int[][] r = shape(toArray(p));
                all.add(r);
                polys.add(null); // 대칭 단계 뒤에 채운다
            }
            out.add(new DetailExtractor.RawZone(z.name(), polys));
        }
        symmetrize(all);
        int k = 0;
        List<DetailExtractor.RawZone> done = new ArrayList<>();
        for (DetailExtractor.RawZone z : out) {
            List<List<List<Integer>>> polys = new ArrayList<>();
            for (int i = 0; i < z.polygons().size(); i++) {
                polys.add(toList(all.get(k++)));
            }
            done.add(new DetailExtractor.RawZone(z.name(), polys));
        }
        return done;
    }

    /** 사각형에 가까우면 사각형으로(직각·평행 맞추기, 변 펴기), 아니면 꼭짓점 8개 이하로. */
    static int[][] shape(int[][] p) {
        if (p.length < 3) {
            return p;
        }
        int[][] hull = hull(p);
        int[][] quad = reduce(hull, 4);
        if (quad.length == 4 && iou(quad, p) >= QUAD_IOU) {
            return straighten(square(quad));
        }
        return straighten(p.length <= MAX_VERTICES ? p : reduce(p, MAX_VERTICES)); // ㄱ자·ㄷ자 블록도 거의 수평·수직인 변은 편다
    }

    /**
     * 네 각이 모두 직각에 가까우면(±{@link #SQUARE_DEG}) 정확한 직사각형(기울기 유지, 거의 수평이면 수평)으로,
     * 마주 보는 두 변만 거의 평행하면 그 두 변을 평행하게(거의 수평이면 수평) 맞춰 사다리꼴로 만든다.
     */
    static int[][] square(int[][] q) {
        double[] dir = new double[4];
        for (int i = 0; i < 4; i++) {
            dir[i] = Math.atan2(q[(i + 1) % 4][1] - q[i][1], q[(i + 1) % 4][0] - q[i][0]);
        }
        boolean right = true;
        for (int i = 0; i < 4; i++) {
            right &= Math.abs(lineDiff(dir[i], dir[(i + 1) % 4] + Math.PI / 2)) <= Math.toRadians(SQUARE_DEG);
        }
        if (right) {
            double[] c = centroid(q);
            double t = snap(lineMean(lineMean(dir[0], dir[2]), lineMean(dir[1], dir[3]) - Math.PI / 2));
            double w = (len(q, 0) + len(q, 2)) / 2, h = (len(q, 1) + len(q, 3)) / 2;
            double ux = Math.cos(t), uy = Math.sin(t);
            int[][] r = new int[4][];
            double[][] corner = {{-w / 2, -h / 2}, {w / 2, -h / 2}, {w / 2, h / 2}, {-w / 2, h / 2}};
            for (int i = 0; i < 4; i++) {
                r[i] = new int[]{(int) Math.round(c[0] + corner[i][0] * ux - corner[i][1] * uy),
                        (int) Math.round(c[1] + corner[i][0] * uy + corner[i][1] * ux)};
            }
            return area(r) * area(q) < 0 ? mirrorOrder(r) : r;
        }
        double d02 = Math.abs(lineDiff(dir[0], dir[2])), d13 = Math.abs(lineDiff(dir[1], dir[3]));
        int s = d02 <= d13 ? 0 : 1;
        if (Math.min(d02, d13) > Math.toRadians(SQUARE_DEG)) {
            return q;
        }
        double t = snap(lineMean(dir[s], dir[s + 2])); // 평행으로 맞출 두 변(s, s+2)이 네 꼭짓점을 모두 정한다
        int[][] r = new int[4][];
        for (int side : new int[]{s, s + 2}) {
            int[] a = q[side], b = q[(side + 1) % 4];
            double mx = (a[0] + b[0]) / 2.0, my = (a[1] + b[1]) / 2.0, half = len(q, side) / 2;
            double sign = Math.cos(dir[side] - t) >= 0 ? 1 : -1; // 원래 변의 방향을 지킨다
            double ux = Math.cos(t) * sign, uy = Math.sin(t) * sign;
            r[side] = new int[]{(int) Math.round(mx - ux * half), (int) Math.round(my - uy * half)};
            r[(side + 1) % 4] = new int[]{(int) Math.round(mx + ux * half), (int) Math.round(my + uy * half)};
        }
        return r;
    }

    /** 두 직선 방향의 차이(-90°~90°, 방향은 무시). */
    private static double lineDiff(double a, double b) {
        double d = (a - b) % Math.PI;
        if (d > Math.PI / 2) {
            d -= Math.PI;
        } else if (d < -Math.PI / 2) {
            d += Math.PI;
        }
        return d;
    }

    /** 두 직선 방향의 평균(a 기준). */
    private static double lineMean(double a, double b) {
        return a - lineDiff(a, b) / 2;
    }

    /** 수평에서 {@link #SNAP_DEG} 안이면 정확히 수평으로. */
    private static double snap(double t) {
        double d = lineDiff(t, 0);
        return Math.abs(d) <= Math.toRadians(SNAP_DEG) ? t - d : t;
    }

    private static double len(int[][] q, int i) {
        int[] a = q[i], b = q[(i + 1) % q.length];
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    private static int[][] mirrorOrder(int[][] p) {
        int[][] r = new int[p.length][];
        for (int i = 0; i < p.length; i++) {
            r[i] = p[p.length - 1 - i];
        }
        return r;
    }

    /** 거의 수평인 변은 두 끝의 y를, 거의 수직인 변은 두 끝의 x를 평균으로 맞춘다. */
    static int[][] straighten(int[][] q) {
        int[][] r = new int[q.length][];
        for (int i = 0; i < q.length; i++) {
            r[i] = q[i].clone();
        }
        for (int i = 0; i < r.length; i++) {
            int[] a = r[i], b = r[(i + 1) % r.length];
            double deg = Math.toDegrees(Math.atan2(Math.abs(b[1] - a[1]), Math.abs(b[0] - a[0])));
            if (deg <= SNAP_DEG) {
                int y = (a[1] + b[1]) / 2;
                a[1] = y;
                b[1] = y;
            } else if (deg >= 90 - SNAP_DEG / 2) {
                int x = (a[0] + b[0]) / 2;
                a[0] = x;
                b[0] = x;
            }
        }
        return r;
    }

    /**
     * 좌우대칭 배치면 짝을 이룬 사각형을 대칭으로 맞춘다. 축은 전체 범위의 가운데에서 시작해 짝들의 가운데 평균으로 한 번 다듬는다.
     * 축 위에 걸친 사각형은 자기 자신과 대칭으로 맞춘다.
     */
    static void symmetrize(List<int[][]> polys) {
        if (polys.size() < 4) {
            return;
        }
        int minX = 1000, maxX = 0;
        for (int[][] p : polys) {
            for (int[] v : p) {
                minX = Math.min(minX, v[0]);
                maxX = Math.max(maxX, v[0]);
            }
        }
        double axis = (minX + maxX) / 2.0;
        int[] mate = null;
        for (int round = 0; round < 2; round++) {
            mate = pair(polys, axis);
            List<Double> mids = new ArrayList<>(), goodMids = new ArrayList<>();
            int paired = 0, offAxis = 0;
            for (int i = 0; i < polys.size(); i++) {
                double cx = centroid(polys.get(i))[0];
                if (Math.abs(cx - axis) > 15) {
                    offAxis++;
                    if (mate[i] >= 0) {
                        paired++;
                        double mid = (cx + centroid(polys.get(mate[i]))[0]) / 2;
                        mids.add(mid);
                        if (iou(polys.get(i), mirror(polys.get(mate[i]), axis)) >= 0.8) {
                            goodMids.add(mid);
                        }
                    }
                }
            }
            // 돔 경기장처럼 구역이 많으면 일부가 갈리거나 빠져 짝이 덜 맞는다. 절반 이상이 거울 위치에 짝이 있으면 대칭 배치로 본다
            if (offAxis == 0 || paired < 0.5 * offAxis) {
                return; // 대칭 배치가 아니다
            }
            // 축은 모양이 잘 맞는(거울상 IoU 0.8 이상) 짝 가운데들의 중앙값(빛 번짐이 섞여 중심이 밀린 짝에 끌려가지 않게). 그런 짝이 없으면 모든 짝
            List<Double> use = goodMids.isEmpty() ? mids : goodMids;
            java.util.Collections.sort(use);
            axis = use.size() % 2 == 1 ? use.get(use.size() / 2) : (use.get(use.size() / 2 - 1) + use.get(use.size() / 2)) / 2;
        }
        boolean[] done = new boolean[polys.size()];
        for (int i = 0; i < polys.size(); i++) {
            int j = mate[i];
            int[][] a = polys.get(i);
            if (done[i]) {
                continue;
            }
            if (j < 0) {
                if (Math.abs(centroid(a)[0] - axis) <= 15) { // 가운데 조각: 자기 거울상과 평균
                    polys.set(i, average(a, mirror(a, axis)));
                }
                continue;
            }
            int[][] b = polys.get(j);
            if (done[j]) {
                continue;
            }
            // 꼭짓점 수가 다르거나 모양이 크게 다르면(거울상과 IoU 0.8 미만, 한쪽에 빛 번짐 선이 섞이거나 귀퉁이가 깎임) 평균하지 않고
            // 더 반듯한 쪽(볼록 껍질을 더 꽉 채우고, 같으면 꼭짓점이 적은 쪽)을 기준으로 다른 쪽을 거울상으로 바꾼다
            if (b.length != a.length || iou(a, mirror(b, axis)) < 0.8) {
                int[][] base = tidier(a, b) ? a : b;
                polys.set(base == a ? j : i, mirror(base, axis));
                done[i] = true;
                done[j] = true;
                continue;
            }
            int[][] avg = average(a, mirror(b, axis));
            polys.set(i, avg);
            polys.set(j, mirror(avg, axis));
            done[i] = true;
            done[j] = true;
        }
    }

    /** a 가 b 보다 반듯하다: 볼록 껍질을 채운 비율이 5%p 넘게 높거나, 비슷하면 꼭짓점이 적다(같으면 a). */
    private static boolean tidier(int[][] a, int[][] b) {
        double fa = Math.abs(area(a)) / Math.max(1, Math.abs(area(hull(a)))), fb = Math.abs(area(b)) / Math.max(1, Math.abs(area(hull(b))));
        if (Math.abs(fa - fb) > 0.05) {
            return fa > fb;
        }
        return a.length <= b.length;
    }

    /**
     * 조각마다 축 기준 거울상이 가장 많이 겹치는(IoU 0.35 이상) 조각의 번호(없으면 -1). 서로 짝일 때만 남긴다. 중심 거리 대신 겹침으로 짝을 찾아서,
     * 한쪽에 빛 번짐 선이 섞여 중심이 밀린 조각(고척돔 F11)도 반대편 짝(F10)을 찾는다.
     */
    private static int[] pair(List<int[][]> polys, double axis) {
        int n = polys.size();
        int[][] boxes = new int[n][];
        for (int i = 0; i < n; i++) {
            boxes[i] = boxOf(polys.get(i));
        }
        int[] best = new int[n];
        for (int i = 0; i < n; i++) {
            best[i] = -1;
            int[][] m = null;
            double bestIou = 0.35;
            for (int j = 0; j < n; j++) {
                int mx0 = (int) Math.round(2 * axis - boxes[j][2]), mx1 = (int) Math.round(2 * axis - boxes[j][0]);
                if (j == i || mx1 < boxes[i][0] || mx0 > boxes[i][2] || boxes[j][3] < boxes[i][1] || boxes[j][1] > boxes[i][3]) {
                    continue; // 거울 상자가 겹치지 않으면 넘긴다
                }
                double v = iou(polys.get(i), mirror(polys.get(j), axis));
                if (v > bestIou) {
                    bestIou = v;
                    best[i] = j;
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (best[i] >= 0 && best[best[i]] != i) {
                best[i] = -1;
            }
        }
        return best;
    }

    private static int[] boxOf(int[][] p) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int[] v : p) {
            b[0] = Math.min(b[0], v[0]);
            b[1] = Math.min(b[1], v[1]);
            b[2] = Math.max(b[2], v[0]);
            b[3] = Math.max(b[3], v[1]);
        }
        return b;
    }

    private static int[][] mirror(int[][] p, double axis) {
        int[][] r = new int[p.length][];
        for (int i = 0; i < p.length; i++) {
            r[p.length - 1 - i] = new int[]{(int) Math.round(2 * axis - p[i][0]), p[i][1]}; // 뒤집으면 도는 방향이 바뀌므로 순서도 뒤집는다
        }
        return r;
    }

    /** 같은 꼭짓점 수의 두 다각형을 가장 가깝게 맞춘(순환 이동) 뒤 꼭짓점마다 평균한다. */
    private static int[][] average(int[][] a, int[][] b) {
        int n = a.length, shift = 0;
        double bestD = Double.MAX_VALUE;
        for (int s = 0; s < n; s++) {
            double d = 0;
            for (int i = 0; i < n; i++) {
                int[] q = b[(i + s) % n];
                d += Math.hypot(a[i][0] - q[0], a[i][1] - q[1]);
            }
            if (d < bestD) {
                bestD = d;
                shift = s;
            }
        }
        int[][] r = new int[n][];
        for (int i = 0; i < n; i++) {
            int[] q = b[(i + shift) % n];
            r[i] = new int[]{(a[i][0] + q[0]) / 2, (a[i][1] + q[1]) / 2};
        }
        return r;
    }

    /** 꼭짓점이 max개 이하가 될 때까지 넓이 기여가 가장 작은 꼭짓점을 하나씩 뺀다(Visvalingam). */
    static int[][] reduce(int[][] p, int max) {
        List<int[]> pts = new ArrayList<>(List.of(p));
        while (pts.size() > max) {
            int drop = 0;
            double least = Double.MAX_VALUE;
            for (int i = 0; i < pts.size(); i++) {
                int[] a = pts.get((i - 1 + pts.size()) % pts.size()), b = pts.get(i), c = pts.get((i + 1) % pts.size());
                double t = Math.abs((double) (b[0] - a[0]) * (c[1] - a[1]) - (double) (c[0] - a[0]) * (b[1] - a[1]));
                if (t < least) {
                    least = t;
                    drop = i;
                }
            }
            pts.remove(drop);
        }
        return pts.toArray(new int[0][]);
    }

    static int[][] hull(int[][] p) {
        List<int[]> h = ShapeSegmenter.convexHull(new ArrayList<>(List.of(p)));
        return h.toArray(new int[0][]);
    }

    static double iou(int[][] a, int[][] b) {
        Area x = new Area(poly(a)), y = new Area(poly(b));
        Area inter = new Area(x);
        inter.intersect(y);
        Area union = new Area(x);
        union.add(y);
        double u = areaOf(union);
        return u <= 0 ? 0 : areaOf(inter) / u;
    }

    private static Polygon poly(int[][] p) {
        Polygon g = new Polygon();
        for (int[] v : p) {
            g.addPoint(v[0], v[1]);
        }
        return g;
    }

    static double areaOf(Area a) {
        double sum = 0, x0 = 0, y0 = 0, px = 0, py = 0;
        double[] c = new double[6];
        for (PathIterator it = a.getPathIterator(null); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == PathIterator.SEG_MOVETO) {
                x0 = px = c[0];
                y0 = py = c[1];
            } else if (t == PathIterator.SEG_LINETO) {
                sum += px * c[1] - c[0] * py;
                px = c[0];
                py = c[1];
            } else if (t == PathIterator.SEG_CLOSE) {
                sum += px * y0 - x0 * py;
                px = x0;
                py = y0;
            }
        }
        return Math.abs(sum) / 2;
    }

    private static double area(int[][] p) {
        double s = 0;
        for (int i = 0; i < p.length; i++) {
            int[] a = p[i], b = p[(i + 1) % p.length];
            s += (double) a[0] * b[1] - (double) b[0] * a[1];
        }
        return s / 2;
    }

    private static double[] centroid(int[][] p) {
        double x = 0, y = 0;
        for (int[] v : p) {
            x += v[0];
            y += v[1];
        }
        return new double[]{x / p.length, y / p.length};
    }

    private static int[][] toArray(List<List<Integer>> p) {
        int[][] r = new int[p.size()][];
        for (int i = 0; i < p.size(); i++) {
            r[i] = new int[]{p.get(i).get(0), p.get(i).get(1)};
        }
        return r;
    }

    private static List<List<Integer>> toList(int[][] p) {
        List<List<Integer>> r = new ArrayList<>();
        for (int[] v : p) {
            r.add(List.of(Math.max(0, Math.min(1000, v[0])), Math.max(0, Math.min(1000, v[1]))));
        }
        return r;
    }
}
