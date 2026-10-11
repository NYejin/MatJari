package com.seatswap.service.linkinfo;

import com.seatswap.dto.response.PerformancePreviewResponse;
import com.seatswap.exception.FieldValidationException;
import com.seatswap.service.SourceKeyResolver;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PerformancePreviewServiceTest {

    private static final String YES24_HTML = "<html><head><script type=\"application/ld+json\">"
            + "{\"@type\":\"Event\",\"name\":\"공연 A\",\"location\":{\"name\":\"홀\"}}</script></head></html>";

    /** 시각을 직접 움직이는 Clock. */
    private static class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(1_000_000);

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    private PageFetcher fetcher;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        fetcher = mock(PageFetcher.class);
        clock = new MutableClock();
        when(fetcher.fetch(any())).thenAnswer(i -> Jsoup.parse(YES24_HTML));
    }

    private PerformancePreviewService service(int cacheMax, int userPerMin, int sitePerMin, int concurrent) {
        return new PerformancePreviewService(new SourceKeyResolver(), fetcher, clock, 600, cacheMax,
                userPerMin, sitePerMin, concurrent);
    }

    @Test
    void knownSitesFetchOnlyFixedTemplateUrls() {
        PerformancePreviewService s = service(200, 100, 100, 4);
        // 사용자 URL의 호스트·쿼리·모바일·http 변형이 어떻든 fetcher 에는 고정 템플릿만 간다
        s.preview(1L, "http://m.ticket.yes24.com/Perf/Detail/PerfInfo.aspx?IdPerf=111&evil=http://169.254.169.254/");
        s.preview(1L, "https://www.ticketlink.co.kr/product/222?utm_source=x#frag");
        s.preview(1L, "https://ticket.melon.com/performance/index.htm?prodId=0333&x=http://127.0.0.1");
        ArgumentCaptor<URI> captor = ArgumentCaptor.forClass(URI.class);
        verify(fetcher, times(3)).fetch(captor.capture());
        assertEquals("https://ticket.yes24.com/Perf/111", captor.getAllValues().get(0).toString());
        assertEquals("https://www.ticketlink.co.kr/product/222", captor.getAllValues().get(1).toString());
        assertEquals("https://ticket.melon.com/performance/index.htm?prodId=333", captor.getAllValues().get(2).toString());
        assertTrue(captor.getAllValues().stream().allMatch(u -> PageFetcher.ALLOWED_HOSTS.contains(u.getHost())));
    }

    @Test
    void unsupportedSitesNeverFetch() {
        PerformancePreviewService s = service(200, 100, 100, 4);
        for (String url : new String[]{
                "https://tickets.interpark.com/goods/24013928",
                "http://169.254.169.254/latest/meta-data",
                "https://example.com/show/1",
                "https://ticket.yes24.com/Perf/abc",
                "https://ticket.melon.com/performance/index.htm?prodId=12x"}) {
            PerformancePreviewResponse r = s.preview(1L, url);
            assertEquals("UNSUPPORTED_SITE", r.status(), url);
            assertNull(r.site());
        }
        verifyNoInteractions(fetcher);
    }

    @Test
    void malformedUrlIsFieldValidationError() {
        assertThrows(FieldValidationException.class, () -> service(200, 100, 100, 4).preview(1L, "not a url"));
        verifyNoInteractions(fetcher);
    }

    @Test
    void okResponseAndCachingOfOkAndNoInfoButNotFailure() {
        PerformancePreviewService s = service(200, 100, 100, 4);
        PerformancePreviewResponse r = s.preview(1L, "https://ticket.yes24.com/Perf/1");
        assertEquals("OK", r.status());
        assertEquals("yes24", r.site());
        assertEquals("공연 A", r.title());
        s.preview(2L, "https://ticket.yes24.com/Perf/1"); // 캐시 적중
        verify(fetcher, times(1)).fetch(any());

        when(fetcher.fetch(any())).thenAnswer(i -> Jsoup.parse("<html></html>"));
        assertEquals("NO_INFO", s.preview(1L, "https://ticket.yes24.com/Perf/2").status());
        s.preview(1L, "https://ticket.yes24.com/Perf/2");
        verify(fetcher, times(2)).fetch(any()); // id 1 한 번 + id 2 한 번 (NO_INFO 캐시됨)

        when(fetcher.fetch(any())).thenThrow(new PageFetcher.FetchFailedException("x"));
        assertEquals("FETCH_FAILED", s.preview(1L, "https://ticket.yes24.com/Perf/3").status());
        s.preview(1L, "https://ticket.yes24.com/Perf/3");
        verify(fetcher, times(4)).fetch(any()); // 실패는 캐시하지 않아 다시 호출
    }

    @Test
    void cacheExpiresAfterTtlAndEvictsLeastRecentlyUsed() {
        PerformancePreviewService s = service(2, 1000, 1000, 4);
        s.preview(1L, "https://ticket.yes24.com/Perf/1");
        s.preview(1L, "https://ticket.yes24.com/Perf/2");
        s.preview(1L, "https://ticket.yes24.com/Perf/3"); // 1번 밀려남
        verify(fetcher, times(3)).fetch(any());
        s.preview(1L, "https://ticket.yes24.com/Perf/3");
        verify(fetcher, times(3)).fetch(any());
        s.preview(1L, "https://ticket.yes24.com/Perf/1");
        verify(fetcher, times(4)).fetch(any());
        clock.millis.addAndGet(601_000); // TTL 600초 경과
        s.preview(1L, "https://ticket.yes24.com/Perf/1");
        verify(fetcher, times(5)).fetch(any());
    }

    @Test
    void userRateLimitThrowsWithRetryAfterAndRecoversAfterWindow() {
        PerformancePreviewService s = service(200, 3, 1000, 4);
        for (int i = 0; i < 3; i++) {
            s.preview(1L, "https://ticket.yes24.com/Perf/" + i);
        }
        PerformancePreviewService.PreviewRateLimitedException e = assertThrows(
                PerformancePreviewService.PreviewRateLimitedException.class,
                () -> s.preview(1L, "https://ticket.yes24.com/Perf/9"));
        assertEquals("PREVIEW_RATE_LIMITED", e.getCode());
        assertEquals(60, e.getRetryAfterSeconds());
        s.preview(2L, "https://ticket.yes24.com/Perf/9"); // 다른 사용자는 영향 없음
        clock.millis.addAndGet(60_000);
        s.preview(1L, "https://ticket.yes24.com/Perf/9");
    }

    @Test
    void siteRateLimitReturnsFetchFailedWithoutFetching() {
        PerformancePreviewService s = service(200, 1000, 2, 4);
        s.preview(1L, "https://ticket.yes24.com/Perf/1");
        s.preview(1L, "https://ticket.yes24.com/Perf/2");
        assertEquals("FETCH_FAILED", s.preview(1L, "https://ticket.yes24.com/Perf/3").status());
        verify(fetcher, times(2)).fetch(any());
        // 다른 사이트는 별도 한도
        assertEquals("OK", s.preview(1L, "https://www.ticketlink.co.kr/product/5").status());
    }

    @Test
    void concurrencyLimitReturnsImmediatelyWithoutWaiting() throws Exception {
        PerformancePreviewService s = service(200, 1000, 1000, 1);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(fetcher.fetch(any())).thenAnswer(i -> {
            inside.countDown();
            release.await(5, TimeUnit.SECONDS);
            return Jsoup.parse(YES24_HTML);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<PerformancePreviewResponse> first = pool.submit(() -> s.preview(1L, "https://ticket.yes24.com/Perf/1"));
            assertTrue(inside.await(5, TimeUnit.SECONDS));
            assertEquals("FETCH_FAILED", s.preview(2L, "https://ticket.yes24.com/Perf/2").status());
            release.countDown();
            assertEquals("OK", first.get(5, TimeUnit.SECONDS).status());
            verify(fetcher, times(1)).fetch(any());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        // 세마포어가 반환돼 다시 진행 가능
        when(fetcher.fetch(any())).thenAnswer(i -> Jsoup.parse(YES24_HTML));
        assertEquals("OK", s.preview(2L, "https://ticket.yes24.com/Perf/2").status());
        verify(fetcher, never()).fetch(URI.create("http://169.254.169.254/"));
    }
}
