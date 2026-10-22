package com.matjari.service.aiextract;

import com.matjari.service.SourceKeyResolver;
import com.matjari.service.linkinfo.PageFetcher;
import com.matjari.service.linkinfo.PerformancePreviewService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 오케스트레이션·캐시·제한·후처리·병합. AI 호출은 가짜 DetailExtractor, 네트워크 호출 없음. */
class DetailExtractionServiceTest {

    private static final String MELON = "https://ticket.melon.com/performance/index.htm?prodId=111";
    private static final String MELON2 = "https://ticket.melon.com/performance/index.htm?prodId=222";
    private static final List<List<Integer>> TRI = List.of(List.of(0, 0), List.of(500, 0), List.of(0, 500));

    static class FakeExtractor implements DetailExtractor {
        boolean available = true;
        boolean failFirst;
        boolean failSecond;
        int firstCalls;
        int secondCalls;
        int lastPartCount;
        List<Part> firstParts = List.of();
        FirstPass firstResult;
        List<RawZone> secondResult = List.of();

        public boolean available() {
            return available;
        }

        public FirstPass first(List<Part> parts) {
            firstCalls++;
            lastPartCount = parts.size();
            firstParts = parts;
            if (failFirst) {
                throw new ExtractionFailedException("x");
            }
            return firstResult;
        }

        public List<RawZone> second(byte[] jpeg, List<String> names) {
            secondCalls++;
            if (failSecond) {
                throw new ExtractionFailedException("x");
            }
            return secondResult;
        }
    }

    private AiProperties props;
    private FakeExtractor fake;
    private PerformancePreviewService preview;
    private SessionTextParser parser;
    private DetailImageFetcher fetcher;
    private DetailExtractionService service;

    private static byte[] png(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @BeforeEach
    void setUp() throws IOException {
        props = new AiProperties();
        props.setMonthlyPerUser(1);
        fake = new FakeExtractor();
        fake.firstResult = new DetailExtractor.FirstPass(
                List.of(new DetailExtractor.RawSession("2026-11-01", "19:00"), new DetailExtractor.RawSession("2026-11-01", "19:05")),
                List.of("A구역", " B 구역 "), new DetailExtractor.SeatMapLocation(true, 1, 1, 2));
        // 모양이 좌석표 영역을 충분히 덮어야 품질 검사를 통과한다(가로 45%·세로 25% 이상)
        fake.secondResult = List.of(new DetailExtractor.RawZone("A구역", List.of(List.of(List.of(0, 0), List.of(1000, 0), List.of(0, 1000)))));
        preview = mock(PerformancePreviewService.class);
        when(preview.fetchPage(anyString(), anyString())).thenReturn(
                new PerformancePreviewService.FetchedPage(Jsoup.parse("<html><body>x</body></html>"), null, null));
        parser = mock(SessionTextParser.class);
        when(parser.parse(any(), any(), any())).thenReturn(List.of());
        fetcher = mock(DetailImageFetcher.class);
        when(fetcher.fetch(anyString(), any())).thenReturn(List.of(new DetailImageFetcher.DetailImage("u", png(1000, 3000), 1000, 3000)));
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        service = new DetailExtractionService(new SourceKeyResolver(), preview, parser, fetcher, new ImageSlicer(props), new ShapeSegmenter(), fake,
                new AiUsageLimiter(clock, props), props, clock);
    }

    @Test
    void unsupportedSiteIs400() {
        assertThatThrownBy(() -> service.extract(1L, "https://example.com/show/1"))
                .isInstanceOfSatisfying(DetailExtractionService.AiExtractException.class,
                        e -> {
                            assertThat(e.getStatus()).isEqualTo(400);
                            assertThat(e.getCode()).isEqualTo("UNSUPPORTED_SITE");
                        });
    }

    @Test
    void aiMergesSessionsZonesAndAspect() {
        DetailExtractionService.Response r = service.extract(1L, MELON);
        assertThat(r.aiEnabled()).isTrue();
        assertThat(r.aiError()).isFalse();
        assertThat(r.sessionsSource()).isEqualTo("AI");
        assertThat(r.sessions()).containsExactly(new DetailExtractionService.SessionDto("2026-11-01", "19:00")); // 19:05 는 10분 단위 아님
        assertThat(r.zones()).hasSize(2);
        assertThat(r.zones().get(0).name()).isEqualTo("A구역");
        // 좌석표 영역에 맞춰 좌표가 다시 계산되므로 조각 수·꼭짓점 수와 범위만 확인한다
        assertThat(r.zones().get(0).shapePolygons()).hasSize(1);
        assertThat(r.zones().get(0).shapePolygons().get(0)).hasSize(3);
        assertThat(r.zones().get(0).shapePolygons().get(0)).allSatisfy(pt -> assertThat(pt).allSatisfy(v -> assertThat(v).isBetween(0, 1000)));
        assertThat(r.zones().get(1).shapePolygons()).isNull();
        assertThat(r.seatmapAspect()).isNotNull().isGreaterThan(0.0);
        assertThat(fake.secondCalls).isEqualTo(1);
        assertThat(fake.lastPartCount % 2).isZero(); // 조각마다 텍스트+이미지
    }

    @Test
    void rulesWinOverAiSessions() {
        when(parser.parse(any(), any(), any())).thenReturn(List.of(new ExtractedSession(LocalDate.of(2026, 11, 2), LocalTime.of(14, 0))));
        DetailExtractionService.Response r = service.extract(1L, MELON);
        assertThat(r.sessionsSource()).isEqualTo("TEXT_RULE");
        assertThat(r.sessions()).containsExactly(new DetailExtractionService.SessionDto("2026-11-02", "14:00"));
    }

    @Test
    void cacheHitIsNotCountedAgainstLimit() {
        DetailExtractionService.Response a = service.extract(1L, MELON);
        DetailExtractionService.Response b = service.extract(1L, MELON); // dailyPerUser=1 이지만 캐시라 통과
        assertThat(a.cached()).isFalse();
        assertThat(b.cached()).isTrue();
        assertThat(fake.firstCalls).isEqualTo(1);
    }

    @Test
    void storedResultSurvivesRestartWithoutAiCallAndUsefulResultIsStored() throws Exception {
        // 배포·재시작 뒤(새 서비스 = 빈 메모리 캐시)에도 DB 보관 결과를 AI 없이 돌려준다
        AiResultStore store = mock(AiResultStore.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        DetailExtractionService.Response saved = new DetailExtractionService.Response(
                List.of(new DetailExtractionService.SessionDto("2026-11-01", "19:00")), "AI",
                List.of(new DetailExtractionService.ZoneDto("A구역", null)), null, true, false, false, false);
        when(store.find("melon:111")).thenReturn(java.util.Optional.of(saved));
        when(store.find("melon:222")).thenReturn(java.util.Optional.empty());
        DetailExtractionService restarted = new DetailExtractionService(new SourceKeyResolver(), preview, parser, fetcher,
                new ImageSlicer(props), new ShapeSegmenter(), fake, new AiUsageLimiter(clock, props), props, clock, store);

        DetailExtractionService.Response hit = restarted.extract(1L, MELON);
        assertThat(hit.cached()).isTrue();
        assertThat(hit.zones()).extracting("name").containsExactly("A구역");
        assertThat(fake.firstCalls).isZero();
        verify(preview, never()).fetchPage(anyString(), anyString());

        DetailExtractionService.Response fresh = restarted.extract(1L, MELON2);
        assertThat(fresh.cached()).isFalse();
        verify(store).save("melon:222", fresh, 168L * 3_600_000L);

        // 저장된 JSON 이 다시 같은 응답으로 읽힌다(레코드 직렬화)
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(om.readValue(om.writeValueAsString(fresh), DetailExtractionService.Response.class)).isEqualTo(fresh);
    }

    @Test
    void monthlyLimitIs429WithRetryAfterUntilNextMonth() {
        service.extract(1L, MELON);
        assertThat(service.quota(1L).remaining()).isZero();
        assertThatThrownBy(() -> service.extract(1L, MELON2))
                .isInstanceOfSatisfying(DetailExtractionService.AiExtractException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(429);
                    assertThat(e.getCode()).isEqualTo("AI_MONTHLY_LIMIT_REACHED");
                    // 시험 시계 2026-10-18 12:00 KST → 11월 1일 0시까지
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(java.time.Duration.ofDays(13).plusHours(12).toSeconds());
                });
        assertThat(fake.firstCalls).isEqualTo(1);
        assertThat(service.quota(1L).resetsAt()).isEqualTo(java.time.LocalDateTime.of(2026, 11, 1, 0, 0));
        // 다른 사용자는 별개
        assertThat(service.extract(2L, MELON2).cached()).isFalse();
    }

    @Test
    void bigVenueHintIsGivenToFirstPassAndCopiedNamesWithoutShapeAreDropped() throws IOException {
        VenueZoneHints hints = mock(VenueZoneHints.class);
        when(hints.namesFor("고척스카이돔")).thenReturn(List.of("A구역", "Z구역"));
        when(preview.fetchPage(anyString(), anyString())).thenReturn(new PerformancePreviewService.FetchedPage(
                Jsoup.parse("<html><body>x</body></html>"), null, null, "고척스카이돔"));
        // 1차가 참고 목록의 Z구역을 베꼈지만 지도에는 A구역 모양만 있다
        fake.firstResult = new DetailExtractor.FirstPass(List.of(new DetailExtractor.RawSession("2026-11-01", "19:00")),
                List.of("A구역", "B구역", "Z구역"), new DetailExtractor.SeatMapLocation(true, 1, 1, 2));
        fake.secondResult = List.of(
                new DetailExtractor.RawZone("A구역", List.of(List.of(List.of(0, 0), List.of(1000, 0), List.of(0, 1000)))),
                new DetailExtractor.RawZone("B구역", List.of(List.of(List.of(1000, 1000), List.of(1000, 0), List.of(0, 1000)))));
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        DetailExtractionService svc = new DetailExtractionService(new SourceKeyResolver(), preview, parser, fetcher,
                new ImageSlicer(props), new ShapeSegmenter(), fake, new AiUsageLimiter(clock, props), props, clock, null, hints);

        DetailExtractionService.Response r = svc.extract(1L, MELON);
        assertThat(fake.firstParts.get(0).text()).startsWith("[참고] 같은 공연장").contains("A구역, Z구역");
        // 지도에서 모양이 붙은 A·B는 남고, 참고 목록에서 베꼈고 모양이 없는 Z구역만 빠진다
        assertThat(r.zones()).extracting(DetailExtractionService.ZoneDto::name).containsExactly("A구역", "B구역");
    }

    private DetailExtractionService serviceWithRealImageCheck() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        return new DetailExtractionService(new SourceKeyResolver(), preview, parser, new DetailImageFetcher(props), new ImageSlicer(props),
                new ShapeSegmenter(), fake, new AiUsageLimiter(clock, props), props, clock);
    }

    @Test
    void seatMapUploadReadsZonesCountsMonthlyAndCachesByImage() throws IOException {
        DetailExtractionService svc = serviceWithRealImageCheck();
        byte[] img = png(800, 600);
        DetailExtractionService.Response r = svc.extractSeatMap(1L, img);
        assertThat(r.sessions()).isEmpty();
        assertThat(r.zones()).extracting(DetailExtractionService.ZoneDto::name).containsExactly("A구역", "B 구역");
        assertThat(fake.firstParts.get(0).text()).contains("사용자가 올린 좌석표");
        assertThat(svc.quota(1L).remaining()).isZero(); // [불러오기]와 같은 월 횟수
        assertThat(svc.extractSeatMap(1L, img).cached()).isTrue(); // 같은 이미지는 다시 세지 않는다
        assertThat(fake.firstCalls).isEqualTo(1);
        assertThatThrownBy(() -> svc.extractSeatMap(1L, png(800, 601)))
                .isInstanceOfSatisfying(DetailExtractionService.AiExtractException.class, e -> assertThat(e.getCode()).isEqualTo("AI_MONTHLY_LIMIT_REACHED"));
    }

    @Test
    void seatMapUploadRejectsNonImageAndRefundsOnAiFailure() throws IOException {
        DetailExtractionService svc = serviceWithRealImageCheck();
        assertThatThrownBy(() -> svc.extractSeatMap(1L, "not an image".getBytes()))
                .isInstanceOfSatisfying(DetailExtractionService.AiExtractException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_UNSUPPORTED"));
        assertThatThrownBy(() -> svc.extractSeatMap(1L, new byte[0]))
                .isInstanceOfSatisfying(DetailExtractionService.AiExtractException.class, e -> assertThat(e.getCode()).isEqualTo("IMAGE_REQUIRED"));
        fake.failFirst = true;
        assertThatThrownBy(() -> svc.extractSeatMap(1L, png(800, 600)))
                .isInstanceOfSatisfying(DetailExtractionService.AiExtractException.class, e -> assertThat(e.getStatus()).isEqualTo(503));
        assertThat(svc.quota(1L).remaining()).isEqualTo(1);
    }

    @Test
    void adminReextractIgnoresCacheAndMonthlyLimitAndSkipsVenueHint() throws IOException {
        service.extract(1L, MELON); // 일반 사용자 결과가 보관됨, 월 1회 소진
        DetailExtractionService.Response again = service.extractForAdmin(MELON);
        assertThat(again.cached()).isFalse();
        assertThat(fake.firstCalls).isEqualTo(2);
        service.extractForAdmin(MELON2); // 관리자는 월 횟수와 무관
        assertThat(fake.firstCalls).isEqualTo(3);
        assertThat(serviceWithRealImageCheck().extractSeatMapForAdmin(png(500, 500)).cached()).isFalse();
    }

    @Test
    void noHintMeansNoExtraPart() {
        service.extract(1L, MELON);
        assertThat(fake.firstParts.get(0).text()).startsWith("[이미지 1");
    }

    @Test
    void aiFailureRefundsMonthlyCount() {
        fake.failFirst = true;
        assertThatThrownBy(() -> service.extract(1L, MELON)).isInstanceOf(DetailExtractionService.AiExtractException.class);
        assertThat(service.quota(1L).used()).isZero(); // 503 은 횟수에서 빼 준다
        when(parser.parse(any(), any(), any())).thenReturn(List.of(new ExtractedSession(LocalDate.of(2026, 11, 2), LocalTime.of(14, 0))));
        assertThat(service.extract(1L, MELON).aiError()).isTrue(); // 규칙 결과만 온 경우도 AI 는 못 읽었으니 반환
        assertThat(service.quota(1L).used()).isZero();
        fake.failFirst = false;
        assertThat(service.extract(1L, MELON2).aiError()).isFalse(); // 그래서 상한 1이어도 통과
        assertThat(service.quota(1L).used()).isEqualTo(1);
    }

    @Test
    void noKeyReturnsRulesOnlyWithoutAiCall() {
        fake.available = false;
        when(parser.parse(any(), any(), any())).thenReturn(List.of(new ExtractedSession(LocalDate.of(2026, 11, 2), LocalTime.of(14, 0))));
        DetailExtractionService.Response r = service.extract(1L, MELON);
        assertThat(r.aiEnabled()).isFalse();
        assertThat(r.sessionsSource()).isEqualTo("TEXT_RULE");
        assertThat(r.zones()).isEmpty();
        assertThat(fake.firstCalls).isZero();
    }

    @Test
    void disabledByPropertyBehavesLikeNoKey() {
        props.setEnabled(false);
        assertThat(service.extract(1L, MELON).aiEnabled()).isFalse();
        assertThat(fake.firstCalls).isZero();
    }

    @Test
    void noImagesMeansNoAiCallAndNoCount() throws IOException {
        when(fetcher.fetch(anyString(), any())).thenReturn(List.of());
        DetailExtractionService.Response r = service.extract(1L, MELON);
        assertThat(r.aiEnabled()).isTrue();
        assertThat(r.sessionsSource()).isEqualTo("NONE");
        assertThat(fake.firstCalls).isZero();
        // 횟수에 세지 않았으니 이미지가 생기면 첫 호출이 통과한다
        when(fetcher.fetch(anyString(), any())).thenReturn(List.of(new DetailImageFetcher.DetailImage("u", png(1000, 3000), 1000, 3000)));
        assertThat(service.extract(1L, MELON2).sessionsSource()).isEqualTo("AI");
    }

    @Test
    void aiFailureWithRulesIsAiErrorAndNotCached() {
        fake.failFirst = true;
        when(parser.parse(any(), any(), any())).thenReturn(List.of(new ExtractedSession(LocalDate.of(2026, 11, 2), LocalTime.of(14, 0))));
        DetailExtractionService.Response r = service.extract(1L, MELON);
        assertThat(r.aiError()).isTrue();
        assertThat(r.sessionsSource()).isEqualTo("TEXT_RULE");
        assertThat(r.cached()).isFalse();
    }

    @Test
    void aiFailureWithoutRulesIs503() {
        fake.failFirst = true;
        assertThatThrownBy(() -> service.extract(1L, MELON))
                .isInstanceOfSatisfying(DetailExtractionService.AiExtractException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("AI_UNAVAILABLE");
                });
    }

    @Test
    void secondPassFailureKeepsNames() {
        fake.failSecond = true;
        DetailExtractionService.Response r = service.extract(1L, MELON);
        assertThat(r.aiError()).isFalse();
        assertThat(r.zones()).extracting(DetailExtractionService.ZoneDto::name).containsExactly("A구역", "B 구역");
        assertThat(r.zones()).allMatch(z -> z.shapePolygons() == null);
        assertThat(r.seatmapAspect()).isNull();
    }

    @Test
    void secondPassSkippedWhenSeatMapNotFound() {
        fake.firstResult = new DetailExtractor.FirstPass(List.of(), List.of("A구역"), new DetailExtractor.SeatMapLocation(false, 0, 0, 0));
        DetailExtractionService.Response r = service.extract(1L, MELON);
        assertThat(fake.secondCalls).isZero();
        assertThat(r.zones()).hasSize(1);
    }

    @Test
    void pageFetchFailureIs503() {
        when(preview.fetchPage(anyString(), anyString())).thenThrow(new PageFetcher.FetchFailedException("x"));
        assertThatThrownBy(() -> service.extract(1L, MELON))
                .isInstanceOfSatisfying(DetailExtractionService.AiExtractException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(503));
    }

    // ---- 후처리 ----

    @Test
    void cleanSessionsFiltersByFormatStepPeriodAndDedupes() {
        List<DetailExtractor.RawSession> raw = List.of(
                new DetailExtractor.RawSession("2026-11-02", "19:00"),
                new DetailExtractor.RawSession("2026-11-02", "19:00"),
                new DetailExtractor.RawSession("2026-11-01", "14:30"),
                new DetailExtractor.RawSession("", "19:00"),
                new DetailExtractor.RawSession("2026-11-03", "7pm"),
                new DetailExtractor.RawSession("2026-12-30", "19:00"),
                new DetailExtractor.RawSession("2026-02-30", "19:00"),
                new DetailExtractor.RawSession("2026-11-01", "19:10"));
        List<DetailExtractionService.SessionDto> out = DetailExtractionService.cleanSessions(raw,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30));
        assertThat(out).extracting(s -> s.date() + " " + s.time())
                .containsExactly("2026-11-01 14:30", "2026-11-01 19:10", "2026-11-02 19:00");
    }

    @Test
    void cleanSessionsCapsAt100() {
        List<DetailExtractor.RawSession> raw = new ArrayList<>();
        for (int d = 0; d < 150; d++) {
            raw.add(new DetailExtractor.RawSession(LocalDate.of(2026, 1, 1).plusDays(d).toString(), "19:00"));
        }
        assertThat(DetailExtractionService.cleanSessions(raw, null, null)).hasSize(100);
    }

    @Test
    void mergeZonesJoinsSameKeyAndValidatesPolygons() {
        List<List<Integer>> square = List.of(List.of(0, 0), List.of(1000, 0), List.of(1000, 1000), List.of(0, 1000));
        List<DetailExtractor.RawZone> shapes = List.of(
                new DetailExtractor.RawZone("a 구역", List.of(TRI)),
                new DetailExtractor.RawZone("A구역", List.of(square)),
                new DetailExtractor.RawZone("B", List.of(List.of(List.of(0, 0), List.of(1001, 0), List.of(5, 5)))), // 범위 밖
                new DetailExtractor.RawZone("C", List.of(List.of(List.of(0, 0), List.of(5, 5)))), // 꼭짓점 2개
                new DetailExtractor.RawZone("처음 보는 이름", List.of(TRI)));
        List<DetailExtractionService.ZoneDto> out = DetailExtractionService.mergeZones(List.of("A구역", "A 구역", "B", "C", "D"), shapes);
        assertThat(out).extracting(DetailExtractionService.ZoneDto::name).containsExactly("A구역", "B", "C", "D");
        assertThat(out.get(0).shapePolygons()).containsExactly(TRI, square);
        assertThat(out.get(1).shapePolygons()).isNull();
        assertThat(out.get(2).shapePolygons()).isNull();
        assertThat(out.get(3).shapePolygons()).isNull();
    }

    @Test
    void cleanNamesDropsLongBlankAndKeepsFirstSpelling() {
        List<String> out = DetailExtractionService.cleanNames(
                List.of(" VIP  석 ", "vip석", "", "가".repeat(31), "나".repeat(30)));
        assertThat(out).containsExactly("VIP 석", "나".repeat(30));
    }

    @Test
    void mergeZonesKeepsAtMostEightPolygons() {
        List<List<List<Integer>>> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(TRI);
        }
        List<DetailExtractionService.ZoneDto> out = DetailExtractionService.mergeZones(List.of("A"), List.of(new DetailExtractor.RawZone("A", many)));
        assertThat(out.get(0).shapePolygons()).hasSize(8);
    }
}
