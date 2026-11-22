package com.matjari.service;

import com.matjari.exception.FieldValidationException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 채팅 사진 검증·정리: 매직 바이트, 크기·화소 상한, 메타데이터(EXIF·GPS·끝에 붙은 데이터) 제거, EXIF 방향 적용. */
class ChatImageProcessorTest {

    private static byte[] encode(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, out)).isTrue();
        return out.toByteArray();
    }

    private static BufferedImage rgb(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                img.setRGB(x, y, x < w / 2 ? Color.RED.getRGB() : Color.BLUE.getRGB());
            }
        }
        return img;
    }

    private static BufferedImage decode(byte[] data) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    private static void assertImageError(byte[] data) {
        assertThatThrownBy(() -> ChatImageProcessor.process(data)).isInstanceOfSatisfying(FieldValidationException.class,
                e -> assertThat(e.getErrors()).containsOnlyKeys("image"));
    }

    @Test
    void 정상_JPEG와_PNG는_같은_형식으로_다시_인코딩된다() throws IOException {
        ChatImageProcessor.Processed jpeg = ChatImageProcessor.process(encode(rgb(40, 20), "jpg"));
        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(decode(jpeg.bytes()).getWidth()).isEqualTo(40);

        ChatImageProcessor.Processed png = ChatImageProcessor.process(encode(rgb(40, 20), "png"));
        assertThat(png.contentType()).isEqualTo("image/png");
        assertThat(decode(png.bytes()).getHeight()).isEqualTo(20);
    }

    @Test
    void 형식은_확장자나_헤더가_아니라_매직_바이트로_판정한다() throws IOException {
        assertImageError(encode(rgb(10, 10), "gif"));    // GIF
        assertImageError(encode(rgb(10, 10), "bmp"));    // BMP
        assertImageError("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        assertImageError("RIFF....WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1));   // WebP 는 허용하지 않는다
        assertImageError(new byte[0]);
        assertImageError(new byte[]{(byte) 0xFF, (byte) 0xD8});   // JPEG 시작만 있고 본문이 없다
        // JPEG 시작 바이트만 맞고 내용이 쓰레기인 경우도 400
        byte[] fake = new byte[200];
        fake[0] = (byte) 0xFF;
        fake[1] = (byte) 0xD8;
        fake[2] = (byte) 0xFF;
        assertImageError(fake);
    }

    @Test
    void 크기가_5MiB를_넘으면_거부한다() {
        byte[] big = new byte[ChatImageProcessor.MAX_BYTES + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        assertImageError(big);
    }

    @Test
    void 한_변이_8000px을_넘거나_총_화소가_4000만을_넘으면_디코드_전에_거부한다() throws IOException {
        assertImageError(encode(new BufferedImage(8001, 1, BufferedImage.TYPE_BYTE_GRAY), "png"));
        assertImageError(encode(new BufferedImage(1, 8001, BufferedImage.TYPE_BYTE_GRAY), "png"));
        // 한 변은 8000 이하지만 7000 x 6000 = 4,200만 화소
        BufferedImage wide = new BufferedImage(7000, 6000, BufferedImage.TYPE_BYTE_BINARY);
        assertImageError(encode(wide, "png"));
        // 상한 안쪽(8000 x 1)은 통과한다
        assertThat(ChatImageProcessor.process(encode(new BufferedImage(8000, 1, BufferedImage.TYPE_BYTE_GRAY), "png")).contentType())
                .isEqualTo("image/png");
    }

    @Test
    void JPEG의_EXIF와_GPS는_저장본에_남지_않고_방향이_픽셀에_적용된다() throws IOException {
        byte[] plain = encode(rgb(40, 20), "jpg");
        byte[] withExif = insertExif(plain, 6, "GPSLATITUDE-SECRET-37.5665");
        assertThat(contains(withExif, "GPSLATITUDE-SECRET")).isTrue();
        assertThat(ChatImageProcessor.exifOrientation(withExif)).isEqualTo(6);

        ChatImageProcessor.Processed out = ChatImageProcessor.process(withExif);

        assertThat(contains(out.bytes(), "GPSLATITUDE-SECRET")).as("GPS 문자열").isFalse();
        assertThat(contains(out.bytes(), "Exif")).as("Exif 헤더").isFalse();
        assertThat(ChatImageProcessor.exifOrientation(out.bytes())).isEqualTo(1);
        BufferedImage result = decode(out.bytes());
        assertThat(result.getWidth()).as("방향 6(시계 방향 90도)이면 가로·세로가 바뀐다").isEqualTo(20);
        assertThat(result.getHeight()).isEqualTo(40);
        // 원본은 왼쪽 빨강·오른쪽 파랑 -> 시계 방향 90도 돌리면 위쪽 빨강·아래쪽 파랑
        assertThat(new Color(result.getRGB(10, 3)).getRed()).isGreaterThan(200);
        assertThat(new Color(result.getRGB(10, 36)).getBlue()).isGreaterThan(200);
    }

    @Test
    void 모든_EXIF_방향_변환은_가로세로_교환_여부가_맞다() {
        for (int o = 1; o <= 8; o++) {
            java.awt.geom.AffineTransform t = ChatImageProcessor.orientationTransform(o, 40, 20);
            java.awt.geom.Point2D p = t.transform(new java.awt.geom.Point2D.Double(40, 20), null);
            boolean swapped = o >= 5;
            double maxX = swapped ? 20 : 40;
            double maxY = swapped ? 40 : 20;
            assertThat(p.getX()).as("방향 %d 의 x", o).isBetween(-0.001, maxX + 0.001);
            assertThat(p.getY()).as("방향 %d 의 y", o).isBetween(-0.001, maxY + 0.001);
        }
    }

    @Test
    void 이미지_뒤에_붙은_데이터는_JPEG와_PNG_모두_저장본에_없다() throws IOException {
        byte[] jpeg = withTrailer(encode(rgb(20, 20), "jpg"), "TRAILER-SECRET-JPEG");
        byte[] png = withTrailer(encode(rgb(20, 20), "png"), "TRAILER-SECRET-PNG");

        assertThat(contains(ChatImageProcessor.process(jpeg).bytes(), "TRAILER-SECRET")).isFalse();
        assertThat(contains(ChatImageProcessor.process(png).bytes(), "TRAILER-SECRET")).isFalse();
    }

    @Test
    void PNG의_텍스트_청크는_저장본에_남지_않는다() throws IOException {
        byte[] png = encode(rgb(20, 20), "png");
        byte[] withText = insertPngTextChunk(png, "Comment\0owner-name-SECRET");
        assertThat(contains(withText, "owner-name-SECRET")).isTrue();

        assertThat(contains(ChatImageProcessor.process(withText).bytes(), "owner-name-SECRET")).isFalse();
    }

    // ------------------------------------------------------------------ 테스트용 바이트 조립

    /** SOI 바로 뒤에 APP1(Exif) 세그먼트를 끼워 넣는다(빅엔디안 TIFF, IFD0 에 방향 태그 하나, 뒤에 비밀 문자열). */
    private static byte[] insertExif(byte[] jpeg, int orientation, String secret) {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        byte[] head = {'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1,                 // 빅엔디안, IFD0 오프셋 8, 항목 1개
                0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0,  // 0x0112 SHORT 1개 = 방향
                0, 0, 0, 0};                                                // 다음 IFD 없음
        tiff.writeBytes(head);
        tiff.writeBytes(secret.getBytes(StandardCharsets.ISO_8859_1));
        byte[] payload = tiff.toByteArray();
        int len = 2 + 6 + payload.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF);
        out.write(0xE1);
        out.write(len >> 8);
        out.write(len & 0xFF);
        out.writeBytes(new byte[]{'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static byte[] withTrailer(byte[] image, String trailer) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(image);
        out.writeBytes(trailer.getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    /** IHDR(8 + 4 + 4 + 13 + 4 = 33바이트) 뒤에 tEXt 청크를 끼운다(CRC 는 맞춘다). */
    private static byte[] insertPngTextChunk(byte[] png, String text) {
        byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, 33);
        out.write(data.length >>> 24);
        out.write(data.length >>> 16);
        out.write(data.length >>> 8);
        out.write(data.length);
        out.writeBytes(new byte[]{'t', 'E', 'X', 't'});
        out.writeBytes(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(new byte[]{'t', 'E', 'X', 't'});
        crc.update(data);
        long c = crc.getValue();
        out.write((int) (c >>> 24));
        out.write((int) (c >>> 16));
        out.write((int) (c >>> 8));
        out.write((int) c);
        out.write(png, 33, png.length - 33);
        return out.toByteArray();
    }
}
