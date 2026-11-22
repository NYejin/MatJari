package com.matjari.service;

import com.matjari.exception.FieldValidationException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 채팅 사진 검증·정리 (V30). 사진에는 이름·예매번호·촬영 위치가 들어갈 수 있어 올라온 바이트를 그대로 저장하지 않고 다음을 거친다.
 * <ol>
 *   <li>크기 5 MiB 이하.</li>
 *   <li>형식은 확장자·Content-Type 이 아니라 <b>시작 바이트(매직 넘버)</b>로 판정한다: JPEG(FF D8 FF) · PNG(89 50 4E 47 0D 0A 1A 0A)만.</li>
 *   <li><b>디코드 전에</b> 헤더에서 폭·높이만 읽어 한 변 8,000px·총 4,000만 화소를 넘으면 거부한다(압축 폭탄 방지).</li>
 *   <li>디코드한 뒤 <b>다시 인코딩</b>해 EXIF·GPS·ICC·텍스트 청크 등 메타데이터를 모두 버린다(JPEG -> JPEG 품질 0.9, PNG -> PNG).
 *       메타데이터를 버리면 휴대폰 사진의 회전 정보(EXIF 방향)도 사라져 옆으로 누워 보이므로, 버리기 전에 방향 값을 읽어 픽셀에 적용한다.</li>
 * </ol>
 * 실패는 모두 400 {@code image}(FieldValidationException)다. 이미지 내용은 로그에 남기지 않는다.
 */
public final class ChatImageProcessor {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_SIDE = 8_000;
    public static final long MAX_PIXELS = 40_000_000L;
    static final float JPEG_QUALITY = 0.9f;

    static final String MSG_FORMAT = "JPEG 또는 PNG 이미지만 보낼 수 있어요.";
    static final String MSG_SIZE = "이미지는 5MB 이하만 보낼 수 있어요.";
    static final String MSG_DIMENSION = "이미지가 너무 커요. 가로·세로 8,000px 이하, 4,000만 화소 이하로 줄여 주세요.";
    static final String MSG_BROKEN = "이미지를 읽을 수 없어요. 다른 사진으로 다시 시도해 주세요.";

    /** 정리된 사진: 다시 인코딩한 바이트와 형식. */
    public record Processed(byte[] bytes, String contentType) {}

    private ChatImageProcessor() {}

    public static Processed process(byte[] data) {
        if (data == null || data.length == 0) {
            throw new FieldValidationException("image", "보낼 이미지를 선택해 주세요.");
        }
        if (data.length > MAX_BYTES) {
            throw new FieldValidationException("image", MSG_SIZE);
        }
        String type = ChatImageStore.detectContentType(data)
                .orElseThrow(() -> new FieldValidationException("image", MSG_FORMAT));
        boolean jpeg = "image/jpeg".equals(type);

        ImageReader reader = null;
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(jpeg ? "jpeg" : "png");
            if (!readers.hasNext()) {
                throw new FieldValidationException("image", MSG_BROKEN);
            }
            reader = readers.next();
            reader.setInput(in, true, true);   // 메타데이터는 읽지 않는다
            int w = reader.getWidth(0);        // 헤더만 읽는다(디코드 전)
            int h = reader.getHeight(0);
            if (w <= 0 || h <= 0 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS) {
                throw new FieldValidationException("image", MSG_DIMENSION);
            }
            BufferedImage decoded = reader.read(0);
            if (decoded == null) {
                throw new FieldValidationException("image", MSG_BROKEN);
            }
            int orientation = jpeg ? exifOrientation(data) : 1;
            BufferedImage clean = redraw(decoded, orientation, jpeg);
            return new Processed(encode(clean, jpeg), type);
        } catch (FieldValidationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // 깨진 파일·지원하지 않는 색 공간(CMYK 등)·메모리 부족에 가까운 입력은 모두 400 으로 모은다
            throw new FieldValidationException("image", MSG_BROKEN);
        } finally {
            if (reader != null) {
                reader.dispose();
            }
        }
    }

    /** 메타데이터가 없는 새 이미지에 픽셀만 옮겨 그린다(방향 적용 포함). JPEG 는 알파 없는 RGB(투명은 흰색), PNG 는 알파가 있으면 ARGB. */
    private static BufferedImage redraw(BufferedImage src, int orientation, boolean jpeg) {
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5 && orientation <= 8;
        boolean alpha = !jpeg && src.getColorModel().hasAlpha();
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            if (!alpha) {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, dst.getWidth(), dst.getHeight());
            }
            g.drawImage(src, orientationTransform(orientation, w, h), null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** EXIF 방향(1~8)을 원본 w x h 픽셀에 적용하는 변환. 1 이거나 알 수 없는 값은 그대로. */
    static AffineTransform orientationTransform(int orientation, int w, int h) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
    }

    private static byte[] encode(BufferedImage image, boolean jpeg) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(jpeg ? "jpeg" : "png");
        if (!writers.hasNext()) {
            throw new IOException("writer 없음");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (jpeg) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            // 메타데이터 인자를 null 로 넘기면 기본(최소) 메타데이터만 쓰인다: 원본의 EXIF·GPS·텍스트는 들어가지 않는다
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /**
     * JPEG 의 APP1(Exif) 에서 방향 태그(0x0112) 값을 읽는다. 없거나 형식이 이상하면 1. 범위를 벗어나는 읽기는 모두 1 로 돌린다.
     * 마커를 SOS(0xDA) 앞까지만 훑으므로 압축 데이터는 읽지 않는다.
     */
    static int exifOrientation(byte[] d) {
        try {
            int pos = 2;
            while (pos + 4 <= d.length && (d[pos] & 0xFF) == 0xFF) {
                int marker = d[pos + 1] & 0xFF;
                if (marker == 0xDA || marker == 0xD9) {
                    break;
                }
                int len = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
                if (len < 2) {
                    break;
                }
                if (marker == 0xE1 && len >= 16 && d[pos + 4] == 'E' && d[pos + 5] == 'x' && d[pos + 6] == 'i'
                        && d[pos + 7] == 'f' && d[pos + 8] == 0 && d[pos + 9] == 0) {
                    return tiffOrientation(d, pos + 10, Math.min(d.length, pos + 2 + len));
                }
                pos += 2 + len;
            }
        } catch (RuntimeException e) {
            // 읽기 범위 오류 등: 방향 없음으로 본다
        }
        return 1;
    }

    private static int tiffOrientation(byte[] d, int base, int end) {
        if (end - base < 8) {
            return 1;
        }
        boolean little = d[base] == 'I' && d[base + 1] == 'I';
        if (!little && !(d[base] == 'M' && d[base + 1] == 'M')) {
            return 1;
        }
        long ifd = u32(d, base + 4, little);
        if (ifd < 8 || base + ifd + 2 > end) {
            return 1;
        }
        int p = (int) (base + ifd);
        int count = u16(d, p, little);
        p += 2;
        for (int i = 0; i < count && p + 12 <= end; i++, p += 12) {
            if (u16(d, p, little) == 0x0112 && u16(d, p + 2, little) == 3) {
                int v = u16(d, p + 8, little);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static int u16(byte[] d, int p, boolean little) {
        int a = d[p] & 0xFF;
        int b = d[p + 1] & 0xFF;
        return little ? (b << 8) | a : (a << 8) | b;
    }

    private static long u32(byte[] d, int p, boolean little) {
        long r = 0;
        for (int i = 0; i < 4; i++) {
            int v = d[little ? p + 3 - i : p + i] & 0xFF;
            r = (r << 8) | v;
        }
        return r;
    }
}
