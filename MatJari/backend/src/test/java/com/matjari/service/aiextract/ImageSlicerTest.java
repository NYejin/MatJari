package com.matjari.service.aiextract;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageSlicerTest {

    private final AiProperties props = new AiProperties();
    private final ImageSlicer slicer = new ImageSlicer(props);

    private static byte[] png(int w, int h, int[] bandTops, int bandHeight) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        for (int top : bandTops) {
            g.fillRect(10, top, w - 20, bandHeight);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] jpeg) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(jpeg));
    }

    @Test
    void smallImageIsNotSliced() throws IOException {
        ImageSlicer.SliceResult r = slicer.slice(png(700, 1400, new int[]{}, 0));
        assertEquals(1, r.slices().size());
        assertFalse(r.truncated());
        assertEquals(0, r.slices().get(0).top());
        assertEquals(1400, r.slices().get(0).height());
    }

    @Test
    void slicesOverlapAndCoverEverything() throws IOException {
        ImageSlicer.SliceResult r = slicer.slice(png(700, 8129, new int[]{}, 0));
        // 간격 1120: 0, 1120, ..., 마지막 조각이 8129 에 닿을 때까지
        int expected = 1 + (int) Math.ceil((8129 - 1400) / 1120.0);
        assertEquals(expected, r.slices().size());
        assertEquals(expected, r.totalSlices());
        assertFalse(r.truncated());
        List<ImageSlicer.SliceImage> s = r.slices();
        for (int i = 0; i + 1 < s.size(); i++) {
            int bottom = s.get(i).top() + s.get(i).height();
            assertTrue(bottom - s.get(i + 1).top() >= 280, "overlap at " + i);
        }
        ImageSlicer.SliceImage last = s.get(s.size() - 1);
        assertEquals(8129, last.top() + last.height());
        assertEquals(700, last.width());
    }

    @Test
    void textBandsAreNeverCutInEverySlicing() throws IOException {
        // 높이 100px 글자 줄을 40px 간격으로 여러 위치에 두어, 어느 줄이든 한 조각에 온전히 들어가는지 확인
        int h = 6000;
        int bandH = 100;
        int[] tops = new int[(h - bandH) / 40];
        for (int i = 0; i < tops.length; i++) {
            tops[i] = i * 40;
        }
        ImageSlicer.SliceResult r = slicer.slice(png(700, h, new int[]{}, 0));
        for (int top : tops) {
            boolean whole = r.slices().stream().anyMatch(sl -> sl.top() <= top && top + bandH <= sl.top() + sl.height());
            assertTrue(whole, "band at " + top + " is cut in every slice");
        }
    }

    @Test
    void bandContentAppearsWholeInAJpegSlice() throws IOException {
        // 조각 경계(1120~1400) 위에 걸친 검은 띠가 어떤 JPEG 조각에서든 위아래 흰 여백과 함께 온전히 보인다
        int bandTop = 1150;
        int bandH = 200;
        ImageSlicer.SliceResult r = slicer.slice(png(700, 4000, new int[]{bandTop}, bandH));
        boolean found = false;
        for (ImageSlicer.SliceImage sl : r.slices()) {
            if (sl.top() <= bandTop && bandTop + bandH <= sl.top() + sl.height()) {
                BufferedImage img = decode(sl.jpeg());
                int y0 = bandTop - sl.top() + 5;
                int y1 = bandTop - sl.top() + bandH - 5;
                assertTrue(new Color(img.getRGB(300, y0)).getRed() < 60);
                assertTrue(new Color(img.getRGB(300, y1)).getRed() < 60);
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void wideImageIsScaledDownTo1000() throws IOException {
        ImageSlicer.SliceResult r = slicer.slice(png(2000, 6000, new int[]{}, 0));
        assertEquals(1000, r.imageWidth());
        assertEquals(3000, r.imageHeight());
        assertEquals(1000, r.slices().get(0).width());
        assertEquals(1000, decode(r.slices().get(0).jpeg()).getWidth());
    }

    @Test
    void sliceCountIsCappedFromTheFront() throws IOException {
        props.setMaxSlices(3);
        ImageSlicer.SliceResult r = slicer.slice(png(700, 18229, new int[]{}, 0));
        assertTrue(r.truncated());
        assertEquals(3, r.slices().size());
        assertTrue(r.totalSlices() > 3);
        assertEquals(0, r.slices().get(0).top());
        assertEquals(1120, r.slices().get(1).top());
        assertEquals(2240, r.slices().get(2).top());
        props.setMaxSlices(24);
    }

    @Test
    void cropReturnsRequestedRangeAndCapsHeight() throws IOException {
        byte[] img = png(700, 8000, new int[]{3000}, 100);
        ImageSlicer.SliceImage c = slicer.crop(img, 2950, 3250);
        assertEquals(300, c.height());
        assertEquals(2950, c.top());
        BufferedImage d = decode(c.jpeg());
        assertEquals(300, d.getHeight());
        assertTrue(new Color(d.getRGB(300, 100)).getRed() < 60);
        assertTrue(new Color(d.getRGB(300, 10)).getRed() > 200);

        assertEquals(2400, slicer.crop(img, 100, 7000).height());
        assertEquals(500, slicer.crop(img, 7500, 9999).height()); // 이미지 밖은 맞춘다
        assertThrows(IllegalArgumentException.class, () -> slicer.crop(img, 9000, 9500));
    }

    @Test
    void cropRangesStacksSegments() throws IOException {
        byte[] img = png(700, 8000, new int[]{}, 0);
        ImageSlicer.SliceImage c = slicer.cropRanges(img, List.of(
                new ImageSlicer.VerticalRange(0, 500), new ImageSlicer.VerticalRange(4000, 4700)));
        assertEquals(1200, c.height());
        assertEquals(1200, decode(c.jpeg()).getHeight());
    }

    @Test
    void undecodableBytesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> slicer.slice(new byte[]{1, 2, 3, 4}));
    }
}
