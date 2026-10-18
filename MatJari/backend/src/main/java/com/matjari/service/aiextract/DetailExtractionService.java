package com.matjari.service.aiextract;

import com.matjari.exception.MatJariException;
import com.matjari.service.SeatKeyNormalizer;
import com.matjari.service.SourceKeyResolver;
import com.matjari.service.linkinfo.PerformancePreviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 상세 정보 AI 추출 오케스트레이션: 키 판별 -> 24시간 캐시(성공 결과만) -> 고정 템플릿 페이지 받기 -> 본문 규칙으로 회차 읽기 ->
 * (멜론만) 상세 이미지 조각내기 -> 1차 AI(회차·구역 이름·좌석표 위치) -> 2차 AI(구역 윤곽, 조건부) -> 후처리·병합.
 *
 * 사용자 URL은 요청하지 않는다(SourceKeyResolver 의 사이트·숫자 id 로 PerformancePreviewService 가 고정 템플릿을 만든다).
 * 캐시 적중과 AI를 부르지 않은 경우는 호출 횟수(AiUsageLimiter)에 세지 않는다. 캐시는 메모리 구조라 재시작하면 비워진다.
 * AI가 꺼져 있거나 키가 없으면 규칙 결과만 돌려주고 aiEnabled=false, AI 호출이 실패하면 규칙 결과에 aiError=true
 * (규칙 결과도 없으면 503 AI_UNAVAILABLE). 로그에는 개수·소요 시간만 남긴다.
 */
@Service
public class DetailExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DetailExtractionService.class);
    private static final Pattern KEY = Pattern.compile("^(yes24|ticketlink|melon):(\\d{1,20})$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final int MAX_SESSIONS = 100;
    static final int MAX_ZONES = 100;
    static final int MAX_ZONE_NAME = 30;
    static final int MAX_POLYGONS = 8;

    /** 상태 코드가 있는 오류. 응답 {code, message[, retryAfterSeconds]} 는 GlobalExceptionHandler 가 만든다. */
    public static class AiExtractException extends MatJariException {
        private final int status;
        private final String code;
        private final long retryAfterSeconds;

        public AiExtractException(int status, String code, String message, long retryAfterSeconds) {
            super(message);
            this.status = status;
            this.code = code;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int getStatus() {
            return status;
        }

        public String getCode() {
            return code;
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    static AiExtractException rateLimited(long retryAfterSeconds) {
        return new AiExtractException(429, "AI_RATE_LIMITED", "AI 읽기 요청이 많아요. 잠시 후 다시 시도해주세요.", retryAfterSeconds);
    }

    public record SessionDto(String date, String time) {}

    public record ZoneDto(String name, List<List<List<Integer>>> shapePolygons) {}

    public record Response(List<SessionDto> sessions, String sessionsSource, List<ZoneDto> zones, Double seatmapAspect,
                           boolean aiEnabled, boolean aiError, boolean cached, boolean truncatedSlices) {
        Response asCached() {
            return new Response(sessions, sessionsSource, zones, seatmapAspect, aiEnabled, aiError, true, truncatedSlices);
        }
    }

    private record CacheEntry(Response response, long expiresAtMillis) {}

    private final SourceKeyResolver sourceKeyResolver;
    private final PerformancePreviewService previewService;
    private final SessionTextParser textParser;
    private final DetailImageFetcher imageFetcher;
    private final ImageSlicer slicer;
    private final DetailExtractor extractor;
    private final AiUsageLimiter limiter;
    private final AiProperties properties;
    private final Clock clock;
    private final Map<String, CacheEntry> cache;

    public DetailExtractionService(SourceKeyResolver sourceKeyResolver, PerformancePreviewService previewService,
                                   SessionTextParser textParser, DetailImageFetcher imageFetcher, ImageSlicer slicer,
                                   DetailExtractor extractor, AiUsageLimiter limiter, AiProperties properties, Clock clock) {
        this.sourceKeyResolver = sourceKeyResolver;
        this.previewService = previewService;
        this.textParser = textParser;
        this.imageFetcher = imageFetcher;
        this.slicer = slicer;
        this.extractor = extractor;
        this.limiter = limiter;
        this.properties = properties;
        this.clock = clock;
        int max = Math.max(1, properties.getCacheMaxEntries());
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > max;
            }
        };
    }

    public Response extract(Long userId, String sourceUrl) {
        String key = sourceKeyResolver.resolve(sourceUrl); // 형식 오류는 400(FieldValidationException)
        Matcher m = KEY.matcher(key);
        if (!m.matches()) {
            throw new AiExtractException(400, "UNSUPPORTED_SITE", "지원하지 않는 사이트예요. YES24·티켓링크·멜론티켓 링크만 읽을 수 있어요.", 0);
        }
        String site = m.group(1);
        Response hit = cacheGet(key);
        if (hit != null) {
            return hit.asCached();
        }

        PerformancePreviewService.FetchedPage page;
        try {
            page = previewService.fetchPage(site, m.group(2));
        } catch (RuntimeException e) {
            log.info("AI 추출용 페이지 읽기 실패 (site={}): {}", site, e.getClass().getSimpleName());
            throw new AiExtractException(503, "PAGE_FETCH_FAILED", "링크 페이지를 읽지 못했어요. 잠시 후 다시 시도해주세요.", 0);
        }

        List<ExtractedSession> rules = List.of();
        try {
            rules = textParser.parse(page.document().wholeText(), page.periodStart(), page.periodEnd());
        } catch (RuntimeException e) {
            log.info("본문 회차 규칙 실패 (site={}): {}", site, e.getClass().getSimpleName());
        }
        List<SessionDto> ruleSessions = new ArrayList<>();
        for (ExtractedSession s : rules) {
            if (s.date() != null) {
                ruleSessions.add(new SessionDto(DATE.format(s.date()), s.time() == null ? null : TIME.format(s.time())));
            }
        }

        boolean aiOn = properties.isEnabled() && extractor.available();
        if (!aiOn) {
            return build(ruleSessions, List.of(), List.of(), null, false, false, false);
        }

        // 이미지 수집·조각내기(AI 호출 전이라 횟수에 세지 않는다). 멜론만 지원한다.
        List<DetailImageFetcher.DetailImage> images = new ArrayList<>();
        List<ImageSlicer.SliceResult> sliced = new ArrayList<>();
        if ("melon".equals(site)) {
            try {
                for (DetailImageFetcher.DetailImage img : imageFetcher.fetch(site, page.document())) {
                    try {
                        ImageSlicer.SliceResult r = slicer.slice(img.bytes());
                        if (!r.slices().isEmpty()) {
                            images.add(img);
                            sliced.add(r);
                        }
                    } catch (RuntimeException e) {
                        log.info("상세 이미지 조각내기 실패: {}", e.getClass().getSimpleName());
                    }
                }
            } catch (RuntimeException e) {
                log.info("상세 이미지 수집 실패: {}", e.getClass().getSimpleName());
            }
        }
        if (images.isEmpty()) {
            Response r = build(ruleSessions, List.of(), List.of(), null, true, false, false);
            cacheIfUseful(key, r);
            return r;
        }

        boolean truncated = sliced.stream().anyMatch(ImageSlicer.SliceResult::truncated);
        List<DetailExtractor.Part> parts = new ArrayList<>();
        int sliceCount = 0;
        for (int n = 0; n < sliced.size(); n++) {
            List<ImageSlicer.SliceImage> sl = sliced.get(n).slices();
            for (int i = 0; i < sl.size(); i++) {
                parts.add(DetailExtractor.Part.ofText("[이미지 " + (n + 1) + " · 조각 " + (i + 1) + "/" + sl.size() + "]"));
                parts.add(DetailExtractor.Part.ofImage(sl.get(i).jpeg()));
                sliceCount++;
            }
        }

        long started = System.nanoTime();
        List<SessionDto> aiSessions = List.of();
        List<ZoneDto> zones = List.of();
        Double aspect = null;
        boolean aiError = false;
        try (AiUsageLimiter.Permit ignored = limiter.acquire(userId)) {
            DetailExtractor.FirstPass first = extractor.first(parts);
            aiSessions = cleanSessions(first.sessions(), page.periodStart(), page.periodEnd());
            List<String> names = cleanNames(first.zones());
            List<DetailExtractor.RawZone> shapes = List.of();
            DetailExtractor.SeatMapLocation loc = first.seatMap();
            if (loc != null && loc.found() && !names.isEmpty() && loc.imageIndex() >= 1 && loc.imageIndex() <= images.size()) {
                try {
                    List<ImageSlicer.SliceImage> sl = sliced.get(loc.imageIndex() - 1).slices();
                    int a = Math.max(1, Math.min(sl.size(), Math.min(loc.firstSlice(), loc.lastSlice())));
                    int b = Math.max(1, Math.min(sl.size(), Math.max(loc.firstSlice(), loc.lastSlice())));
                    ImageSlicer.SliceImage first1 = sl.get(a - 1);
                    ImageSlicer.SliceImage last1 = sl.get(b - 1);
                    ImageSlicer.SliceImage merged = slicer.crop(images.get(loc.imageIndex() - 1).bytes(),
                            first1.top(), last1.top() + last1.height());
                    shapes = extractor.second(merged.jpeg(), names);
                    if (merged.height() > 0) {
                        aspect = Math.round(merged.width() * 1000.0 / merged.height()) / 1000.0;
                    }
                } catch (RuntimeException e) { // 2차 실패해도 1차 이름 목록은 유지(모양만 null)
                    log.info("AI 2차 실패, 이름만 유지: {}", e.getClass().getSimpleName());
                    shapes = List.of();
                    aspect = null;
                }
            }
            zones = mergeZones(names, shapes);
            if (aspect != null && zones.stream().noneMatch(z -> z.shapePolygons() != null)) {
                aspect = null;
            }
        } catch (AiExtractException e) {
            throw e; // 429 등
        } catch (RuntimeException e) {
            aiError = true;
            log.info("AI 추출 실패: {}", e.getClass().getSimpleName());
        }
        log.info("AI 추출 완료: {}ms, slices={}, aiSessions={}, zones={}, aiError={}",
                (System.nanoTime() - started) / 1_000_000, sliceCount, aiSessions.size(), zones.size(), aiError);

        if (aiError && ruleSessions.isEmpty()) {
            throw new AiExtractException(503, "AI_UNAVAILABLE", "AI로 정보를 읽지 못했어요. 잠시 후 다시 시도하거나 직접 입력해주세요.", 0);
        }
        Response r = build(ruleSessions, aiSessions, zones, aspect, true, aiError, truncated);
        if (!aiError) {
            cacheIfUseful(key, r);
        }
        return r;
    }

    private Response build(List<SessionDto> rule, List<SessionDto> ai, List<ZoneDto> zones, Double aspect,
                           boolean aiEnabled, boolean aiError, boolean truncated) {
        List<SessionDto> sessions;
        String source;
        if (!rule.isEmpty()) {
            sessions = rule;
            source = "TEXT_RULE";
        } else if (!ai.isEmpty()) {
            sessions = ai;
            source = "AI";
        } else {
            sessions = List.of();
            source = "NONE";
        }
        return new Response(List.copyOf(sessions), source, List.copyOf(zones), aspect, aiEnabled, aiError, false, truncated);
    }

    // ---- 후처리 (테스트에서 직접 호출) ----

    /** 날짜 yyyy-MM-dd·시각 HH:mm·10분 단위·공연 기간 안(있으면)·중복 제거·최대 100개, 날짜·시각 순 정렬. */
    static List<SessionDto> cleanSessions(List<DetailExtractor.RawSession> raw, LocalDate start, LocalDate end) {
        Map<String, SessionDto> out = new LinkedHashMap<>();
        for (DetailExtractor.RawSession s : raw == null ? List.<DetailExtractor.RawSession>of() : raw) {
            try {
                if (s.date() == null || s.time() == null) {
                    continue;
                }
                LocalDate d = LocalDate.parse(s.date().trim(), DATE);
                LocalTime t = LocalTime.parse(s.time().trim(), TIME);
                if (t.getMinute() % 10 != 0) {
                    continue;
                }
                if ((start != null && d.isBefore(start)) || (end != null && d.isAfter(end))) {
                    continue;
                }
                out.putIfAbsent(d + " " + t, new SessionDto(DATE.format(d), TIME.format(t)));
            } catch (RuntimeException e) {
                // 형식이 다른 항목은 버린다
            }
        }
        List<SessionDto> list = new ArrayList<>(out.values());
        list.sort(Comparator.comparing(SessionDto::date).thenComparing(SessionDto::time));
        return list.size() > MAX_SESSIONS ? new ArrayList<>(list.subList(0, MAX_SESSIONS)) : list;
    }

    private static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (s.isEmpty() || s.codePointCount(0, s.length()) > MAX_ZONE_NAME) {
            return null;
        }
        return s;
    }

    private static String keyOf(String name) {
        try {
            return SeatKeyNormalizer.zoneKey(name);
        } catch (RuntimeException e) {
            return null; // 제어·제로폭 문자 등
        }
    }

    /** 공백 정리·30자 이하·zoneKey 중복 제거(첫 표기 유지)·최대 100개. */
    static List<String> cleanNames(List<String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String r : raw == null ? List.<String>of() : raw) {
            String name = cleanName(r);
            String key = name == null ? null : keyOf(name);
            if (key != null && !key.isEmpty() && out.size() < MAX_ZONES) {
                out.putIfAbsent(key, name);
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * 1차 이름 목록을 기준으로 2차 윤곽을 붙인다(같은 zoneKey 는 한 구역, 조각 이어 붙임·8조각 초과 시 앞 8개).
     * 조각은 꼭짓점 3~40개·[x,y] 0~1000 정수만 허용하고 하나라도 어긋나면 그 구역의 모양은 null이다. 1차에 없는 2차 이름은 버린다.
     */
    static List<ZoneDto> mergeZones(List<String> names, List<DetailExtractor.RawZone> shapes) {
        Map<String, String> display = new LinkedHashMap<>();
        for (String n : names) {
            String c = cleanName(n);
            String k = c == null ? null : keyOf(c);
            if (k != null && !k.isEmpty() && display.size() < MAX_ZONES) {
                display.putIfAbsent(k, c);
            }
        }
        Map<String, List<List<List<Integer>>>> polys = new LinkedHashMap<>();
        Map<String, Boolean> invalid = new LinkedHashMap<>();
        for (DetailExtractor.RawZone z : shapes == null ? List.<DetailExtractor.RawZone>of() : shapes) {
            String c = cleanName(z.name());
            String k = c == null ? null : keyOf(c);
            if (k == null || !display.containsKey(k)) {
                continue;
            }
            List<List<List<Integer>>> list = polys.computeIfAbsent(k, x -> new ArrayList<>());
            for (List<List<Integer>> p : z.polygons() == null ? List.<List<List<Integer>>>of() : z.polygons()) {
                if (!validPolygon(p)) {
                    invalid.put(k, true);
                } else {
                    list.add(p);
                }
            }
        }
        List<ZoneDto> out = new ArrayList<>();
        for (Map.Entry<String, String> e : display.entrySet()) {
            List<List<List<Integer>>> p = polys.get(e.getKey());
            boolean ok = p != null && !p.isEmpty() && !invalid.containsKey(e.getKey());
            out.add(new ZoneDto(e.getValue(), ok ? List.copyOf(p.size() > MAX_POLYGONS ? p.subList(0, MAX_POLYGONS) : p) : null));
        }
        return out;
    }

    private static boolean validPolygon(List<List<Integer>> p) {
        if (p == null || p.size() < 3 || p.size() > 40) {
            return false;
        }
        for (List<Integer> pt : p) {
            if (pt == null || pt.size() != 2) {
                return false;
            }
            for (Integer v : pt) {
                if (v == null || v < 0 || v > 1000) {
                    return false;
                }
            }
        }
        return true;
    }

    // ---- 캐시 ----

    private Response cacheGet(String key) {
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

    /** 성공 결과 중 읽은 것이 있을 때만 캐시한다(빈 결과를 24시간 붙들지 않는다). */
    private void cacheIfUseful(String key, Response r) {
        if (r.sessions().isEmpty() && r.zones().isEmpty()) {
            return;
        }
        synchronized (cache) {
            cache.put(key, new CacheEntry(r, clock.millis() + properties.getCacheTtlHours() * 3_600_000L));
        }
    }
}
