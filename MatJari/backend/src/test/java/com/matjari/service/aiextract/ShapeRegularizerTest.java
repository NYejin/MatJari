package com.matjari.service.aiextract;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShapeRegularizerTest {

    @Test
    void nearlyRectangularPieceBecomesFourPointsWithHorizontalBase() {
        // 살짝 기울고 잡꼭짓점이 섞인 직사각형
        int[][] p = {{100, 100}, {150, 102}, {200, 103}, {201, 150}, {199, 200}, {150, 198}, {101, 197}, {99, 150}};
        int[][] r = ShapeRegularizer.shape(p);
        assertThat(r.length).isEqualTo(4);
        for (int i = 0; i < 4; i++) {
            int[] a = r[i], b = r[(i + 1) % 4];
            assertThat(a[0] == b[0] || a[1] == b[1]).as("변이 수평 또는 수직").isTrue();
        }
    }

    @Test
    void tiltedRectangleBecomesExactRectangle() {
        // 30° 기운 직사각형을 조금 비튼 것(원형 경기장 블록)
        int[][] q = {{500, 400}, {587, 452}, {560, 500}, {474, 448}};
        int[][] r = ShapeRegularizer.square(q);
        for (int i = 0; i < 4; i++) {
            int[] a = r[i], b = r[(i + 1) % 4], c = r[(i + 2) % 4];
            double dot = (b[0] - a[0]) * (c[0] - b[0]) + (b[1] - a[1]) * (c[1] - b[1]);
            double cos = dot / (Math.hypot(b[0] - a[0], b[1] - a[1]) * Math.hypot(c[0] - b[0], c[1] - b[1]));
            assertThat(Math.abs(cos)).as("직각").isLessThan(0.03);
        }
    }

    @Test
    void nearTrapezoidGetsHorizontalParallelBases() {
        // 윗변·밑변이 조금 기운 사다리꼴
        int[][] q = {{300, 100}, {500, 106}, {560, 300}, {240, 292}};
        int[][] r = ShapeRegularizer.square(q);
        assertThat(r[0][1]).isEqualTo(r[1][1]);
        assertThat(r[2][1]).isEqualTo(r[3][1]);
    }

    @Test
    void irregularPieceKeepsAtMostEightVertices() {
        List<int[]> pts = new ArrayList<>();
        for (int i = 0; i < 30; i++) { // 별 모양(사각형으로 바꿀 수 없음)
            double a = 2 * Math.PI * i / 30, rad = i % 2 == 0 ? 200 : 90;
            pts.add(new int[]{500 + (int) (rad * Math.cos(a)), 500 + (int) (rad * Math.sin(a))});
        }
        int[][] r = ShapeRegularizer.shape(pts.toArray(new int[0][]));
        assertThat(r.length).isLessThanOrEqualTo(ShapeRegularizer.MAX_VERTICES);
    }

    @Test
    void mirroredPairsBecomeExactlySymmetric() {
        List<int[][]> polys = new ArrayList<>(List.of(
                new int[][]{{100, 100}, {200, 100}, {200, 200}, {100, 200}},
                new int[][]{{803, 98}, {902, 98}, {902, 204}, {803, 204}},   // 왼쪽 것의 거울 짝(조금 어긋남)
                new int[][]{{100, 300}, {200, 300}, {200, 400}, {100, 400}},
                new int[][]{{800, 302}, {899, 302}, {899, 401}, {800, 401}}));
        ShapeRegularizer.symmetrize(polys);
        int[][] left = polys.get(0), right = polys.get(1);
        double axis = 0;
        for (int[] v : left) {
            axis += v[0];
        }
        for (int[] v : right) {
            axis += v[0];
        }
        axis /= 8;
        for (int[] v : left) {
            boolean mirrored = false;
            for (int[] w : right) {
                mirrored |= Math.abs((2 * axis - v[0]) - w[0]) <= 1 && v[1] == w[1];
            }
            assertThat(mirrored).as("왼쪽 꼭짓점의 거울상이 오른쪽에 있다").isTrue();
        }
    }

    @Test
    void messySideOfPairIsReplacedByMirrorOfTidySide() {
        // 오른쪽 위 블록에 빛 번짐 선이 붙어 모양이 크게 다르다: 평균하지 않고 반듯한 왼쪽의 거울상으로 바꾼다
        List<int[][]> polys = new ArrayList<>(List.of(
                new int[][]{{100, 100}, {200, 100}, {200, 200}, {100, 200}},
                new int[][]{{800, 100}, {900, 100}, {900, 200}, {800, 200}, {800, 160}, {720, 150}, {800, 140}},
                new int[][]{{100, 300}, {200, 300}, {200, 400}, {100, 400}},
                new int[][]{{800, 300}, {900, 300}, {900, 400}, {800, 400}},
                new int[][]{{100, 500}, {200, 500}, {200, 600}, {100, 600}},
                new int[][]{{800, 500}, {900, 500}, {900, 600}, {800, 600}}));
        ShapeRegularizer.symmetrize(polys);
        assertThat(polys.get(1)).hasNumberOfRows(4);
        for (int[] v : polys.get(1)) {
            assertThat(v[0]).isBetween(799, 901);
        }
    }
}
