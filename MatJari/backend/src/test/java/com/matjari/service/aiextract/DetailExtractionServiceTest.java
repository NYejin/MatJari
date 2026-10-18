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
        FirstPass firstResult;
        List<RawZone> secondResult = List.of();

        public boolean available() {
            return available;
        }

        public FirstPass first(List<Part> parts) {
            firstCalls++;
            lastPartCount = parts.size();
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
        props.setDailyPerUser(1);
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
        service = new DetailExtractionService(new SourceKeyResolver(), preview, parser, fetcher, new ImageSlicer(props), fake,
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
    void dailyLimitIs429WithRetryAfter() {
        service.extract(1L, MELON);
        assertThatThrownBy(() -> service.extract(1L, MELON2))
                .isInstanceOfSatisfying(DetailExtractionService.AiExtractException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(429);
                    assertThat(e.getCode()).isEqualTo("AI_RATE_LIMITED");
                    assertThat(e.getRetryAfterSeconds()).isPositive();
                });
        assertThat(fake.firstCalls).isEqualTo(1);
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
