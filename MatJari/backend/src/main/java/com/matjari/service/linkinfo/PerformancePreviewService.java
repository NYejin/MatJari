package com.matjari.service.linkinfo;

import lombok.extern.slf4j.Slf4j;
import com.matjari.dto.response.PerformancePreviewResponse;
import com.matjari.exception.MatJariException;
import com.matjari.service.SourceKeyResolver;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 링크 기반 공연 정보 미리보기 (후속 요구사항: 링크 자동 입력). 순서: 사용자 속도 제한(429) -> 링크 형식 검증(400, SourceKeyResolver)
 * -> 키 판별 -> 캐시 -> 사이트 속도 제한 -> 동시 실행 제한 -> fetch -> parse.
 *
 * 사용자가 준 URL은 절대 요청하지 않는다. SourceKeyResolver 가 만든 키가 yes24/ticketlink/melon:{숫자 id} 일 때만
 * 진행하고, 요청 URL은 여기 고정된 템플릿 3종으로 새로 만든다(m. 모바일·http·쿼리 변형 링크도 같은 템플릿으로 수렴).
 * 인터파크와 일반 url: 키는 UNSUPPORTED_SITE 로 끝나며 어떤 요청도 하지 않는다.
 * 사이트 속도 제한·동시 실행 제한에 걸리면 대기하지 않고 즉시 FETCH_FAILED 를 돌려준다(429 는 사용자 제한만).
 * 캐시는 OK·NO_INFO 만, 키 "site:id", TTL·최대 개수 LRU. 서버 한 대 기준 메모리 구조이며 재시작하면 비워진다.
 */
@Slf4j
@Service
public class PerformancePreviewService {

    public static final String STATUS_UNSUPPORTED = "UNSUPPORTED_SITE";
    public static final String STATUS_NO_INFO = "NO_INFO";
    public static final String STATUS_FETCH_FAILED = "FETCH_FAILED";
    static final String RATE_CODE = "PREVIEW_RATE_LIMITED";
    static final String RATE_MESSAGE = "링크 확인 요청이 너무 많아요. 잠시 후 다시 시도해주세요.";
    private static final long WINDOW_MILLIS = 60_000;
    private static final Pattern KEY = Pattern.compile("^(yes24|ticketlink|melon):(\\d{1,20})$");

    /** 429 PREVIEW_RATE_LIMITED. 응답 본문·Retry-After 는 GlobalExceptionHandler 가 만든다. */
    public static class PreviewRateLimitedException extends MatJariException {
        private final long retryAfterSeconds;

        public PreviewRateLimitedException(long retryAfterSeconds) {
            super(RATE_MESSAGE);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String getCode() {
            return RATE_CODE;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private record CacheEntry(PerformancePreviewResponse response, long expiresAtMillis) {}

    private final SourceKeyResolver sourceKeyResolver;
    private final PageFetcher pageFetcher;
    private final Clock clock;
    private final long cacheTtlMillis;
    private final int userPerMinute;
    private final int sitePerMinute;
    private final Semaphore concurrency;
    private final boolean posterEnabled;
    private final int posterPerMinute;
    private final Map<Object, ArrayDeque<Long>> userWindows = new ConcurrentHashMap<>();
    private final Map<Object, ArrayDeque<Long>> siteWindows = new ConcurrentHashMap<>();
    private final Map<Object, ArrayDeque<Long>> posterWindows = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> cache;

    public PerformancePreviewService(SourceKeyResolver sourceKeyResolver, PageFetcher pageFetcher, Clock clock,
                                     @Value("${matjari.linkinfo.cache-ttl-seconds:600}") long cacheTtlSeconds,
                                     @Value("${matjari.linkinfo.cache-max-entries:200}") int cacheMaxEntries,
                                     @Value("${matjari.linkinfo.user-per-minute:10}") int userPerMinute,
                                     @Value("${matjari.linkinfo.site-per-minute:30}") int sitePerMinute,
                                     @Value("${matjari.linkinfo.max-concurrent:4}") int maxConcurrent,
                                     @Value("${matjari.poster.enabled:true}") boolean posterEnabled,
                                     @Value("${matjari.poster.user-per-minute:60}") int posterPerMinute) {
        this.posterEnabled = posterEnabled;
        this.posterPerMinute = posterPerMinute;
        this.sourceKeyResolver = sourceKeyResolver;
        this.pageFetcher = pageFetcher;
        this.clock = clock;
        this.cacheTtlMillis = cacheTtlSeconds * 1000;
        this.userPerMinute = userPerMinute;
        this.sitePerMinute = sitePerMinute;
        this.concurrency = new Semaphore(maxConcurrent);
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > cacheMaxEntries;
            }
        };
    }

    public PerformancePreviewResponse preview(Long userId, String sourceUrl) {
        acquireUserSlot(userId);
        return load(sourceUrl);
    }

    /**
     * 공연 상세 화면용 포스터 주소. 미리보기와 같은 캐시·사이트 속도 제한·동시 실행 제한을 쓰되 사용자 제한은 따로(분당 posterPerMinute)다.
     * 반환은 이미지 주소 문자열 하나뿐이며(없으면 null) 서버는 이미지를 요청·저장하지 않는다. 설정 {@code matjari.poster.enabled=false}면 아무 요청도 없이 null.
     */
    public String poster(Long userId, String sourceUrl) {
        if (!posterEnabled) {
            return null;
        }
        long retryAfter = acquireWindow(posterWindows, userId, posterPerMinute);
        if (retryAfter > 0) {
            return null; // 포스터는 부가 정보라 제한에 걸리면 조용히 없는 것으로 둔다
        }
        try {
            return load(sourceUrl).posterUrl();
        } catch (MatJariException e) {
            return null;
        }
    }

    private PerformancePreviewResponse load(String sourceUrl) {
        PerformancePreviewResponse r = loadRaw(sourceUrl);
        return posterEnabled ? r : r.withoutPoster();
    }

    private PerformancePreviewResponse loadRaw(String sourceUrl) {
        String key = sourceKeyResolver.resolve(sourceUrl); // 형식 오류는 FieldValidationException(400)
        Matcher m = KEY.matcher(key);
        if (!m.matches()) {
            return PerformancePreviewResponse.empty(STATUS_UNSUPPORTED, null);
        }
        String site = m.group(1);
        String id = m.group(2);

        PerformancePreviewResponse cached = cacheGet(key);
        if (cached != null) {
            return cached;
        }
        if (!concurrency.tryAcquire()) {
            return PerformancePreviewResponse.empty(STATUS_FETCH_FAILED, site);
        }
        try {
            if (!tryWindow(siteWindows, site, sitePerMinute)) { // 세마포어를 못 얻은 호출이 사이트 한도를 소모하지 않게 permit을 먼저 잡는다
                return PerformancePreviewResponse.empty(STATUS_FETCH_FAILED, site);
            }
            Document doc = pageFetcher.fetch(URI.create(template(site, id)));
            LinkInfoParsers.ParsedInfo info = LinkInfoParsers.parse(site, doc);
            PerformancePreviewResponse response = info.title() == null
                    ? PerformancePreviewResponse.empty(STATUS_NO_INFO, site)
                    : PerformancePreviewResponse.ok(site, info.title(), info.venue(), info.periodStart(), info.periodEnd(),
                            LinkInfoParsers.posterUrl(site, doc));
            cachePut(key, response);
            return response;
        } catch (RuntimeException e) { // FetchFailedException 및 예상 밖 파싱 오류: 미리보기는 실패해도 등록을 막지 않는다
            log.info("링크 미리보기 실패 (site={}, id={}): {}", site, id, e.toString());
            return PerformancePreviewResponse.empty(STATUS_FETCH_FAILED, site);
        } finally {
            concurrency.release();
        }
    }

    /** 고정 템플릿. id 는 정규식으로 숫자만 통과한 값이라 URL을 바꿀 수 없다. */
    static String template(String site, String id) {
        return switch (site) {
            case "yes24" -> "https://ticket.yes24.com/Perf/" + id;
            case "ticketlink" -> "https://www.ticketlink.co.kr/product/" + id;
            case "melon" -> "https://ticket.melon.com/performance/index.htm?prodId=" + id;
            default -> throw new IllegalArgumentException("unknown site");
        };
    }

    private void acquireUserSlot(Long userId) {
        long retryAfter = acquireWindow(userWindows, userId, userPerMinute);
        if (retryAfter > 0) {
            throw new PreviewRateLimitedException(retryAfter);
        }
    }

    private boolean tryWindow(Map<Object, ArrayDeque<Long>> windows, Object key, int limit) {
        return acquireWindow(windows, key, limit) == 0;
    }

    /** 슬라이딩 윈도(최근 60초에 limit 건). 허용하면 0, 거절하면 다음 허용까지 남은 초(거절은 기록하지 않는다). */
    private long acquireWindow(Map<Object, ArrayDeque<Long>> windows, Object key, int limit) {
        long now = clock.millis();
        if (windows.size() > 1_000) {
            windows.entrySet().removeIf(e -> synchronizedIdle(e.getValue(), now));
        }
        if (limit <= 0) {
            return 60; // 한도 0 이하(기능 끄기)는 항상 거절
        }
        ArrayDeque<Long> w = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (w) {
            while (!w.isEmpty() && now - w.peekFirst() >= WINDOW_MILLIS) {
                w.pollFirst();
            }
            if (w.size() >= limit) {
                return Math.max(1, (w.peekFirst() + WINDOW_MILLIS - now + 999) / 1000);
            }
            w.addLast(now);
            return 0;
        }
    }

    private static boolean synchronizedIdle(ArrayDeque<Long> w, long now) {
        synchronized (w) {
            return w.isEmpty() || now - w.peekLast() >= WINDOW_MILLIS;
        }
    }

    private PerformancePreviewResponse cacheGet(String key) {
        synchronized (cache) {
            CacheEntry e = cache.get(key);
            if (e == null) {
                return null;
            }
            if (e.expiresAtMillis() <= clock.millis()) {
                cache.remove(key);
                return null;
            }
            return e.response();
        }
    }

    private void cachePut(String key, PerformancePreviewResponse response) {
        synchronized (cache) {
            cache.put(key, new CacheEntry(response, clock.millis() + cacheTtlMillis));
        }
    }
}
