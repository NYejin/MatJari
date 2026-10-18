package com.matjari.service.aiextract;

import com.anthropic.models.messages.MessageCreateParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnthropicDetailExtractorTest {

    @Test
    void requestBodyHasJsonSchemaFormatAndNoUnsupportedKeywords() {
        MessageCreateParams p = AnthropicDetailExtractor.buildParams("claude-haiku-5-5", "sys",
                AnthropicDetailExtractor.toBlocks(List.of(DetailExtractor.Part.ofText("t"), DetailExtractor.Part.ofImage(new byte[]{1, 2, 3}))),
                AnthropicDetailExtractor.firstSchema(), 4000);
        String s = p.toString();
        assertThat(s).contains("json_schema").contains("claude-haiku-5-5").contains("additionalProperties");
        assertThat(s).doesNotContain("minItems").doesNotContain("maxItems");
        assertThat(AnthropicDetailExtractor.secondSchema().toString()).doesNotContain("minItems").doesNotContain("maxItems");
    }

    @Test
    void availableDoesNotThrowWithoutKey() {
        new AnthropicDetailExtractor(new AiProperties()).available();
    }

    /** 실제 API 시험: ANTHROPIC_API_KEY 와 MATJARI_AI_LIVE_TEST=true 가 있을 때만. 합성 이미지만 쓴다. */
    @Test
    @EnabledIfEnvironmentVariable(named = "MATJARI_AI_LIVE_TEST", matches = "true")
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    void liveCall() throws IOException {
        BufferedImage img = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 1000, 700);
        g.setColor(Color.BLACK);
        g.setFont(g.getFont().deriveFont(36f));
        g.drawString("2026-11-01 19:00 SHOW", 40, 60);
        g.setColor(new Color(200, 220, 255));
        g.fill(new Polygon(new int[]{100, 500, 100}, new int[]{200, 200, 600}, 3));
        g.setColor(Color.BLACK);
        g.drawString("A", 150, 300);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        AnthropicDetailExtractor ex = new AnthropicDetailExtractor(new AiProperties());
        DetailExtractor.FirstPass r = ex.first(List.of(DetailExtractor.Part.ofText("[이미지 1 · 조각 1/1]"),
                DetailExtractor.Part.ofImage(out.toByteArray())));
        assertThat(r).isNotNull();
        assertThat(ex.second(out.toByteArray(), List.of("A"))).isNotNull();
    }
}
