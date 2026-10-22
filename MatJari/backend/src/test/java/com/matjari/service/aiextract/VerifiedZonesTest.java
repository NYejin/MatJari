package com.matjari.service.aiextract;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 검수 정답 리소스(실제 파일) — 상품 번호 + 좌석표 이미지 주소가 같을 때만 쓴다. 네트워크 없음. */
class VerifiedZonesTest {

    private static final String IMG = "//cdnticket.melon.co.kr/resource/image/upload/product/2020/01/202001311552525389dd7c-1d8e-4cac-9875-ea9bb15ae919.jpg";

    private final VerifiedZones verified = new VerifiedZones(new DetailImageFetcher(new AiProperties()));

    private static Document page(String imgSrc) {
        return Jsoup.parse("<html><body><img src=\"" + imgSrc + "\"></body></html>");
    }

    @Test
    void sameProductAndSameSeatmapImageUsesReviewedZones() {
        Optional<VerifiedZones.Match> m = verified.find("melon", "melon:205008", page(IMG));
        assertThat(m).isPresent();
        assertThat(m.get().zones()).hasSize(8);
        // 검수본 그대로: 모양 없이 이름만 둔 구역(OP)도 유지한다
        assertThat(m.get().zones()).filteredOn(z -> z.shapePolygons() == null).extracting(DetailExtractionService.ZoneDto::name).containsExactly("OP");
        assertThat(m.get().zones().get(0).shapePolygons().get(0))
                .allSatisfy(pt -> assertThat(pt).allSatisfy(v -> assertThat(v).isBetween(0, 1000)));
        assertThat(m.get().aspect()).isPositive();
    }

    @Test
    void melonTransformSuffixOnSameFileStillMatches() {
        assertThat(verified.find("melon", "melon:205008", page(IMG + "/melon/quality/90/optimize"))).isPresent();
    }

    @Test
    void differentSeatmapImageOrUnknownProductIsNotUsed() {
        assertThat(verified.find("melon", "melon:205008",
                page("//cdnticket.melon.co.kr/resource/image/upload/product/2026/10/other.jpg"))).isEmpty();
        assertThat(verified.find("melon", "melon:999999", page(IMG))).isEmpty();
        assertThat(verified.has("melon:205008")).isTrue();
        assertThat(verified.has("melon:213836")).isFalse(); // 고척: 사람 검수본의 모양이 일부뿐이라 제외
        assertThat(verified.has("melon:211358")).isFalse();
    }
}
