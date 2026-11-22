package com.matjari.service;

import com.matjari.exception.FieldValidationException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 채팅 사진 처리 한도: 1,600만 화소 상한, 긴 변 4,000px 초과는 서브샘플링으로 줄여 디코드하고 저장 전에 4,000px 이하로 맞춘다. */
class ChatImageProcessorLimitsTest {

    private static byte[] encode(BufferedImage img, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(ImageIO.write(img, format, out)).isTrue();
        return out.toByteArray();
    }

    @Test
    void 총_화소가_1600만을_넘으면_디코드_전에_거부한다() throws IOException {
        // 4097 x 4097 = 16,785,409 화소(한 변은 8000 이하)
        byte[] data = encode(new BufferedImage(4097, 4097, BufferedImage.TYPE_BYTE_BINARY), "png");
        assertThatThrownBy(() -> ChatImageProcessor.process(data)).isInstanceOfSatisfying(FieldValidationException.class,
                e -> assertThat(e.getErrors()).containsOnlyKeys("image"));
    }

    @Test
    void 서브샘플링_배수는_긴_변이_4000_이하가_되는_가장_작은_정수다() {
        assertThat(ChatImageProcessor.subsamplingFactor(4000, 3000)).isEqualTo(1);
        assertThat(ChatImageProcessor.subsamplingFactor(10, 4000)).isEqualTo(1);
        assertThat(ChatImageProcessor.subsamplingFactor(4001, 10)).isEqualTo(2);
        assertThat(ChatImageProcessor.subsamplingFactor(8000, 100)).isEqualTo(2);
        assertThat(ChatImageProcessor.subsamplingFactor(100, 8000)).isEqualTo(2);
    }

    @Test
    void 긴_변이_4000을_넘는_사진은_줄여서_저장한다_JPEG() throws IOException {
        // 5000 x 3000 = 1,500만 화소(상한 안쪽), 긴 변 5000 -> 배수 2 -> 2500 x 1500
        ChatImageProcessor.Processed p = ChatImageProcessor.process(encode(new BufferedImage(5000, 3000, BufferedImage.TYPE_BYTE_GRAY), "jpg"));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(p.bytes()));
        assertThat(p.contentType()).isEqualTo("image/jpeg");
        assertThat(Math.max(out.getWidth(), out.getHeight())).isLessThanOrEqualTo(ChatImageProcessor.MAX_STORED_SIDE);
        assertThat(out.getWidth()).isEqualTo(2500);
        assertThat(out.getHeight()).isEqualTo(1500);
    }

    @Test
    void 긴_변이_4000을_넘는_PNG도_줄여서_저장한다_세로로_긴_경우_포함() throws IOException {
        ChatImageProcessor.Processed p = ChatImageProcessor.process(encode(new BufferedImage(1500, 8000, BufferedImage.TYPE_BYTE_GRAY), "png"));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(p.bytes()));
        assertThat(p.contentType()).isEqualTo("image/png");
        assertThat(out.getHeight()).isEqualTo(4000);
        assertThat(out.getWidth()).isEqualTo(750);
    }

    @Test
    void 줄일_필요가_없는_사진은_크기를_그대로_둔다() throws IOException {
        ChatImageProcessor.Processed p = ChatImageProcessor.process(encode(new BufferedImage(4000, 1000, BufferedImage.TYPE_BYTE_GRAY), "png"));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(p.bytes()));
        assertThat(out.getWidth()).isEqualTo(4000);
        assertThat(out.getHeight()).isEqualTo(1000);
    }
}
