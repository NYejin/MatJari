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
            double sum = 0;
            int n = 0, paired = 0, offAxis = 0;
            for (int i = 0; i < polys.size(); i++) {
                double cx = centroid(polys.get(i))[0];
                if (Math.abs(cx - axis) > 15) {
                    offAxis++;
                    if (mate[i] >= 0) {
                        paired++;
                        sum += (cx + centroid(polys.get(mate[i]))[0]) / 2;
                        n++;
                    }
                }
            }
            if (offAxis == 0 || paired < 0.6 * offAxis) {
                return; // 대칭 배치가 아니다
            }
            axis = sum / n;
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
            if (b.length != a.length) { // 꼭짓점 수가 다르면 사각형 쪽(또는 꼭짓점이 적은 쪽)을 기준으로 다른 쪽을 거울상으로 바꾼다
                int[][] base = a.length < b.length ? a : b;
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

    /** 조각마다 거울 위치(축 기준)에 있고 넓이가 비슷한(0.75~1.33배) 가장 가까운 조각의 번호(없으면 -1). 서로 짝일 때만 남긴다. */
    private static int[] pair(List<int[][]> polys, double axis) {
        int n = polys.size();
        int[] best = new int[n];
        for (int i = 0; i < n; i++) {
            best[i] = -1;
            double[] c = centroid(polys.get(i));
            double ai = Math.abs(area(polys.get(i)));
            double bestD = 25;
            for (int j = 0; j < n; j++) {
                if (j == i) {
                    continue;
                }
                double[] d = centroid(polys.get(j));
                double aj = Math.abs(area(polys.get(j)));
                double dist = Math.hypot(2 * axis - c[0] - d[0], c[1] - d[1]);
                if (dist < bestD && aj >= ai * 0.75 && aj <= ai * 1.33) {
                    bestD = dist;
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
