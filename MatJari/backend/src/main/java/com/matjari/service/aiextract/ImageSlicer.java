package com.matjari.service.aiextract;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 세로로 긴 이미지를 위에서 아래로 겹치게 잘라 JPEG(품질 85) 조각 목록으로 만든다.
 *
 * 좌표계: 폭이 sliceMaxWidth(기본 1000)를 넘으면 비율을 유지해 그 폭으로 줄이며, SliceImage.top 과 crop 의 top/bottom 은
 * 모두 **줄인 뒤 이미지**의 픽셀 좌표다(같은 이미지에 slice 와 crop 을 쓰면 좌표가 일치한다).
 * 조각 간격은 sliceHeight x (1 - overlap) 이라 겹침(기본 280px)보다 낮은 글자 줄은 어느 조각엔가 온전히 들어간다.
 * 조각 수가 maxSlices 를 넘으면 균등하게 건너뛰지 않고 앞에서부터 maxSlices 개만 쓰며 truncated 로 표시한다.
 */
@Component
public class ImageSlicer {

    private static final float JPEG_QUALITY = 0.85f;

    /** index 는 0부터, top 은 줄인 이미지 기준 조각 윗변 y. */
    public record SliceImage(int index, byte[] jpeg, int width, int height, int top) {
    }

    /** totalSlices 는 상한 적용 전 전체 조각 수, truncated 는 상한 때문에 뒤쪽이 잘렸는지. imageWidth/Height 는 줄인 뒤 크기. */
    public record SliceResult(List<SliceImage> slices, boolean truncated, int totalSlices, int imageWidth, int imageHeight) {
    }

    /** crop 으로 이어 붙일 세로 구간(줄인 이미지 기준, bottom 은 미포함). */
    public record VerticalRange(int top, int bottom) {
    }

    private final AiProperties properties;

    @Autowired
    public ImageSlicer(AiProperties properties) {
        this.properties = properties;
    }

    /** 디코드할 수 없는 이미지(WebP 등)는 IllegalArgumentException. */
    public SliceResult slice(byte[] imageBytes) {
        BufferedImage img = load(imageBytes);
        int w = img.getWidth();
        int h = img.getHeight();
        int sliceHeight = Math.max(1, properties.getSliceHeight());
        double overlap = Math.min(0.9, Math.max(0.0, properties.getSliceOverlap()));
        int step = Math.max(1, (int) Math.round(sliceHeight * (1.0 - overlap)));
        int total = h <= sliceHeight ? 1 : 1 + (int) Math.ceil((h - sliceHeight) / (double) step);
        int max = Math.max(1, properties.getMaxSlices());
        List<SliceImage> slices = new ArrayList<>();
        for (int k = 0; k < total && k < max; k++) {
            int top = k * step;
            int bottom = Math.min(h, top + sliceHeight);
            if (h <= sliceHeight) {
                top = 0;
                bottom = h;
            }
            slices.add(new SliceImage(k, encode(img.getSubimage(0, top, w, bottom - top)), w, bottom - top, top));
        }
        return new SliceResult(List.copyOf(slices), total > max, total, w, h);
    }

    /** [top, bottom) 구간 하나를 이미지 한 장으로. 구간은 이미지 안으로 맞추고 높이는 cropMaxHeight 로 제한한다. */
    public SliceImage crop(byte[] imageBytes, int top, int bottom) {
        return cropRanges(imageBytes, List.of(new VerticalRange(top, bottom)));
    }

    /** 여러 세로 구간을 위에서 아래 순서로 이어 붙인 한 장(합친 높이는 cropMaxHeight 로 제한, 넘치면 뒤를 자른다). top 은 첫 구간의 top. */
    public SliceImage cropRanges(byte[] imageBytes, List<VerticalRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            throw new IllegalArgumentException("no ranges");
        }
        BufferedImage img = load(imageBytes);
        int w = img.getWidth();
        int h = img.getHeight();
        int limit = Math.max(1, properties.getCropMaxHeight());
        List<int[]> parts = new ArrayList<>();
        int total = 0;
        for (VerticalRange r : ranges) {
            int top = Math.max(0, Math.min(r.top(), r.bottom()));
            int bottom = Math.min(h, Math.max(r.top(), r.bottom()));
            if (bottom <= top || total >= limit) {
                continue;
            }
            bottom = Math.min(bottom, top + (limit - total));
            parts.add(new int[]{top, bottom});
            total += bottom - top;
        }
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("empty range");
        }
        BufferedImage out;
        if (parts.size() == 1) {
            int[] p = parts.get(0);
            out = img.getSubimage(0, p[0], w, p[1] - p[0]);
        } else {
            out = new BufferedImage(w, total, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                int y = 0;
                for (int[] p : parts) {
                    g.drawImage(img.getSubimage(0, p[0], w, p[1] - p[0]), 0, y, null);
                    y += p[1] - p[0];
                }
            } finally {
                g.dispose();
            }
        }
        return new SliceImage(0, encode(out), w, out.getHeight(), parts.get(0)[0]);
    }

    /** 디코드 + (필요하면) 폭 축소 + RGB 변환. */
    private BufferedImage load(byte[] bytes) {
        BufferedImage src;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IllegalArgumentException("unsupported image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels <= 0 || pixels > properties.getMaxImagePixels()) {
                    throw new IllegalArgumentException("image too large");
                }
                src = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("decode failed", e);
        }
        if (src == null) {
            throw new IllegalArgumentException("decode failed");
        }
        int w = src.getWidth();
        int h = src.getHeight();
        int maxW = Math.max(1, properties.getSliceMaxWidth());
        if (w > maxW) {
            int nh = Math.max(1, (int) Math.round(h * (maxW / (double) w)));
            BufferedImage scaled = new BufferedImage(maxW, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, maxW, nh);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(src, 0, 0, maxW, nh, null);
            } finally {
                g.dispose();
            }
            return scaled;
        }
        if (src.getType() == BufferedImage.TYPE_3BYTE_BGR || src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE); // 투명 PNG 는 흰 배경에 얹는다
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private static byte[] encode(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
    }
}
