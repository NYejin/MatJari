package com.matjari.service.aiextract;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ZoneConsistencyTest {

    @Test
    void findsLegendChipsBelowMapButNotTextGlyphs() {
        BufferedImage img = new BufferedImage(600, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 500);
        Color[] colors = {new Color(70, 185, 200), new Color(30, 40, 90), new Color(230, 100, 140)};
        List<ShapeSegmenter.Segment> blocks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            g.setColor(colors[i]);
            g.fillRect(50 + i * 180, 50, 140, 250);
            blocks.add(new ShapeSegmenter.Segment(i + 1, List.of(new int[]{50 + i * 180, 50}, new int[]{190 + i * 180, 50},
                    new int[]{190 + i * 180, 300}, new int[]{50 + i * 180, 300}), 140 * 250, 120 + i * 180, 175, 0.5, 150));
        }
        // 범례: 원형 칩 + 굵은 글자(글자 획은 칩으로 잡히면 안 된다)
        g.setFont(new Font(Font.SERIF, Font.BOLD, 18));
        for (int i = 0; i < 3; i++) {
            g.setColor(colors[i]);
            g.fillOval(60 + i * 180, 400, 14, 14);
            g.setColor(new Color(40, 40, 60));
            g.drawString("Seat Rae", 80 + i * 180, 414);
        }
        g.dispose();
        List<ZoneConsistency.Chip> chips = ZoneConsistency.legendChips(img, blocks);
        assertThat(chips).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(ZoneConsistency.dist(chips.get(i).rgb(), colors[i].getRGB())).isLessThan(ZoneConsistency.SAME_COLOR);
        }
    }

    @Test
    void seriesKeySplitsHundredsAndPrefixes() {
        assertThat(ZoneConsistency.seriesKey("311")).isEqualTo(ZoneConsistency.seriesKey("322"));
        assertThat(ZoneConsistency.seriesKey("311")).isNotEqualTo(ZoneConsistency.seriesKey("411"));
        assertThat(ZoneConsistency.seriesKey("T01")).isEqualTo(ZoneConsistency.seriesKey("T17"));
        assertThat(ZoneConsistency.seriesKey("STANDING R")).isNull();
    }

    @Test
    void eraseTextPaintsWhiteDigitsWithBlockColor() {
        int w = 60, h = 40, blue = 0xFF5B9FFF, white = 0xFFFFFFFF, black = 0xFF050505;
        int[] px = new int[w * h];
        java.util.Arrays.fill(px, black);
        for (int y = 5; y < 35; y++) {
            for (int x = 5; x < 55; x++) {
                px[y * w + x] = blue;
            }
        }
        for (int y = 12; y < 28; y++) { // 폭 2px 세로 획 두 개(숫자 '11')
            px[y * w + 25] = white;
            px[y * w + 26] = white;
            px[y * w + 33] = white;
            px[y * w + 34] = white;
        }
        int[] out = ShapeSegmenter.eraseText(px, new int[]{5, 5, 5}, w, h);
        assertThat(out[20 * w + 25]).isEqualTo(blue);
        assertThat(out[20 * w + 34]).isEqualTo(blue);
        assertThat(out[2 * w + 2]).isEqualTo(black);
    }
}
