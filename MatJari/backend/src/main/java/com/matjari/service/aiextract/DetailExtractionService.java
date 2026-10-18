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
    private final ShapeSegmenter segmenter;
    private final DetailExtractor extractor;
    private final AiUsageLimiter limiter;
    private final AiProperties properties;
    private final Clock clock;
    private final Map<String, CacheEntry> cache;

    public DetailExtractionService(SourceKeyResolver sourceKeyResolver, PerformancePreviewService previewService,
                                   SessionTextParser textParser, DetailImageFetcher imageFetcher, ImageSlicer slicer,
                                   ShapeSegmenter segmenter, DetailExtractor extractor, AiUsageLimiter limiter, AiProperties properties, Clock clock) {
        this.sourceKeyResolver = sourceKeyResolver;
        this.previewService = previewService;
        this.textParser = textParser;
        this.imageFetcher = imageFetcher;
        this.slicer = slicer;
        this.segmenter = segmenter;
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
        int cropW = 0;
        int cropH = 0;
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
                    ImageSlicer.SliceImage target = merged;
                    try { // 좌석표가 있는 띠만 잘라 내면 좌표 기준이 분명해지고 모양이 더 정확하다(좌표 대신 띠 번호로 위치를 묻는다)
                        ImageSlicer.SliceResult strips = slicer.sliceWith(merged.jpeg(), 450, 0.25, 12);
                        if (strips.slices().size() >= 2) {
                            List<DetailExtractor.Part> sp = new ArrayList<>();
                            for (ImageSlicer.SliceImage st : strips.slices()) {
                                sp.add(DetailExtractor.Part.ofText("[띠 " + (st.index() + 1) + "/" + strips.slices().size() + "]"));
                                sp.add(DetailExtractor.Part.ofImage(st.jpeg()));
                            }
                            DetailExtractor.StripRange range = extractor.locate(sp);
                            if (range != null) {
                                int n = strips.slices().size();
                                ImageSlicer.SliceImage s1 = strips.slices().get(Math.min(n, range.first()) - 1);
                                ImageSlicer.SliceImage s2 = strips.slices().get(Math.min(n, range.last()) - 1);
                                int h = merged.height();
                                int yTop = Math.max(0, s1.top());
                                int yBottom = Math.min(h, s2.top() + s2.height());
                                if (yBottom - yTop >= 200) {
                                    target = slicer.crop(images.get(loc.imageIndex() - 1).bytes(), first1.top() + yTop, first1.top() + yBottom);
                                }
                            }
                        }
                    } catch (RuntimeException e) {
                        log.info("AI 좌석표 띠 찾기 실패, 합친 이미지를 그대로 사용: {}", e.getClass().getSimpleName());
                        target = merged;
                    }
                    log.info("AI 좌석표 구간: 이미지 {}, 조각 {}~{}/{}, 합친 크기 {}x{}, 사용 크기 {}x{}", loc.imageIndex(), a, b, sl.size(),
                            merged.width(), merged.height(), target.width(), target.height());
                    shapes = labelShapes(target, names); // 실제 픽셀 윤곽(ShapeSegmenter) + AI가 고른 번호
                    if (shapes.isEmpty()) {
                        shapes = extractor.second(target.jpeg(), names);
                    }
                    if (!shapeQualityOk(names, shapes)) { // 좌표 기준이 어긋나거나 형식이 틀린 응답이 가끔 오므로 한 번만 다시 시도한다
                        log.info("AI 2차 모양 품질 미달, 한 번 다시 시도");
                        shapes = extractor.second(target.jpeg(), names);
                        if (!shapeQualityOk(names, shapes)) { // 믿을 수 없는 모양은 버리고 이름만 유지한다
                            log.info("AI 2차 모양 품질 미달, 모양은 버리고 이름만 유지");
                            shapes = List.of();
                        }
                    }
                    if (log.isInfoEnabled()) {
                        int polyCount = 0, badPolys = 0;
                        for (DetailExtractor.RawZone rz : shapes) {
                            for (List<List<Integer>> rp : rz.polygons() == null ? List.<List<List<Integer>>>of() : rz.polygons()) {
                                polyCount++;
                                if (!validPolygon(rp)) {
                                    if (badPolys == 0) {
                                        log.info("AI 2차 무효 조각 예: 구역={}, 꼭짓점 수={}, 앞부분={}", rz.name(), rp == null ? -1 : rp.size(), rp == null ? "null" : rp.subList(0, Math.min(3, rp.size())));
                                    }
                                    badPolys++;
                                }
                            }
                        }
                        log.info("AI 2차 응답: 구역 {}개, 조각 {}개(무효 {}개)", shapes.size(), polyCount, badPolys);
                    }
                    if (target.height() > 0) {
                        aspect = Math.round(target.width() * 1000.0 / target.height()) / 1000.0;
                        cropW = target.width();
                        cropH = target.height();
                    }
                } catch (RuntimeException e) { // 2차 실패해도 1차 이름 목록은 유지(모양만 null)
                    log.info("AI 2차 실패, 이름만 유지: {}", e.getClass().getSimpleName());
                    shapes = List.of();
                    aspect = null;
                }
            }
            zones = mergeZones(names, shapes);
            logExtent(zones, cropW, cropH);
            if (aspect != null && cropW > 0 && cropH > 0) { // 좌석표가 잘라 낸 이미지의 일부만 차지해도 미리보기가 꽉 차도록 모양 좌표를 좌석표 영역에 맞춘다
                FitResult fit = fitToBounds(zones, cropW, cropH);
                if (fit != null) {
                    zones = fit.zones();
                    aspect = fit.aspect();
                }
            }
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

    /**
     * 좌석표에서 색 덩어리(도형 후보)를 찾아 AI가 구역 이름마다 고른 번호로 모양을 만든다. 결과가 없거나 쓸 수 없으면(AI 미지원·후보 3개 미만·
     * 구역의 30% 미만만 연결됨) 빈 목록이라 호출자가 AI 좌표 방식(2차)으로 넘어간다.
     */
    private List<DetailExtractor.RawZone> labelShapes(ImageSlicer.SliceImage target, List<String> names) {
        try {
            ShapeSegmenter.Result seg = segmenter.segment(target.jpeg());
            if (seg == null || seg.segments().size() < 3) {
                return List.of();
            }
            List<DetailExtractor.RawLabel> labels = extractor.label(target.jpeg(), seg.annotatedJpeg(), names, seg.segments().size());
            if (labels == null) {
                return List.of();
            }
            List<DetailExtractor.RawZone> zones = dropTinyOutliers(shapesFromLabels(labels, seg));
            log.info("AI 번호 방식: 도형 후보 {}개, 연결된 구역 {}개 / 이름 {}개", seg.segments().size(), zones.size(), names.size());
            if (validShapeRatio(names, zones) < 0.3) {
                return List.of();
            }
            return zones;
        } catch (RuntimeException e) {
            log.info("AI 번호 방식 실패, 좌표 방식으로 대체: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 조각 넓이가 전체 조각 넓이 중앙값의 20% 미만인 것(범례 막대·글자 조각이 구역으로 섞인 경우)을 버린다.
     * 조각이 남지 않는 구역은 목록에서 빠진다(이름은 1차 목록으로 유지).
     */
    static List<DetailExtractor.RawZone> dropTinyOutliers(List<DetailExtractor.RawZone> zones) {
        List<Double> areas = new ArrayList<>();
        for (DetailExtractor.RawZone z : zones) {
            for (List<List<Integer>> p : z.polygons()) {
                areas.add(polygonArea(p));
            }
        }
        if (areas.size() < 5) {
            return zones;
        }
        areas.sort(Double::compare);
        double min = areas.get(areas.size() / 2) * 0.2;
        List<DetailExtractor.RawZone> out = new ArrayList<>();
        for (DetailExtractor.RawZone z : zones) {
            List<List<List<Integer>>> keep = new ArrayList<>();
            for (List<List<Integer>> p : z.polygons()) {
                if (polygonArea(p) >= min) {
                    keep.add(p);
                }
            }
            if (!keep.isEmpty()) {
                out.add(new DetailExtractor.RawZone(z.name(), keep));
            }
        }
        return out;
    }

    private static double polygonArea(List<List<Integer>> p) {
        double a = 0;
        for (int i = 0; i < p.size(); i++) {
            List<Integer> q = p.get(i), r = p.get((i + 1) % p.size());
            a += (double) q.get(0) * r.get(1) - (double) r.get(0) * q.get(1);
        }
        return Math.abs(a) / 2;
    }

    /** 번호 -> 도형 윤곽(0~1000 정수 좌표)으로 바꾼다. 없는 번호·이미 쓴 번호는 무시하고 구역당 8조각까지만 쓴다. */
    static List<DetailExtractor.RawZone> shapesFromLabels(List<DetailExtractor.RawLabel> labels, ShapeSegmenter.Result seg) {
        Map<Integer, ShapeSegmenter.Segment> byId = new LinkedHashMap<>();
        for (ShapeSegmenter.Segment s : seg.segments()) {
            byId.put(s.id(), s);
        }
        java.util.Set<Integer> used = new java.util.HashSet<>();
        List<DetailExtractor.RawZone> out = new ArrayList<>();
        for (DetailExtractor.RawLabel l : labels) {
            List<List<List<Integer>>> polys = new ArrayList<>();
            for (Integer id : l.segmentIds() == null ? List.<Integer>of() : l.segmentIds()) {
                ShapeSegmenter.Segment s = id == null ? null : byId.get(id);
                if (s == null || polys.size() >= MAX_POLYGONS || !used.add(id)) {
                    continue;
                }
                List<List<Integer>> poly = new ArrayList<>();
                for (int[] pt : s.pixels()) {
                    int x = (int) Math.max(0, Math.min(1000, Math.round(pt[0] * 1000.0 / Math.max(1, seg.width() - 1))));
                    int y = (int) Math.max(0, Math.min(1000, Math.round(pt[1] * 1000.0 / Math.max(1, seg.height() - 1))));
                    poly.add(List.of(x, y));
                }
                polys.add(poly);
            }
            if (!polys.isEmpty()) {
                out.add(new DetailExtractor.RawZone(l.name(), polys));
            }
        }
        return out;
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

    /**
     * 모양이 쓸 만한지 본다: 유효한 모양을 가진 구역이 절반 이상이고, 잘라 낸 좌석표 이미지 안에서 모양들이 가로 45% 이상·세로 25% 이상을 덮는다
     * (좌표 기준이 어긋나 한쪽으로 몰리거나 찌그러진 응답을 걸러 낸다). 구역 이름이 없으면 true.
     */
    static boolean shapeQualityOk(List<String> names, List<DetailExtractor.RawZone> shapes) {
        if (names == null || names.isEmpty()) {
            return true;
        }
        if (validShapeRatio(names, shapes) < 0.5) {
            return false;
        }
        int minX = 1000, minY = 1000, maxX = 0, maxY = 0, n = 0;
        for (DetailExtractor.RawZone z : shapes) {
            for (List<List<Integer>> poly : z.polygons() == null ? List.<List<List<Integer>>>of() : z.polygons()) {
                if (!validPolygon(poly)) {
                    continue;
                }
                for (List<Integer> pt : poly) {
                    minX = Math.min(minX, pt.get(0)); maxX = Math.max(maxX, pt.get(0));
                    minY = Math.min(minY, pt.get(1)); maxY = Math.max(maxY, pt.get(1));
                    n++;
                }
            }
        }
        return n > 0 && maxX - minX >= 450 && maxY - minY >= 250;
    }

    /** 구역 이름 목록 중 유효한 모양을 가진 구역의 비율. */
    static double validShapeRatio(List<String> names, List<DetailExtractor.RawZone> shapes) {
        List<ZoneDto> z = mergeZones(names, shapes);
        if (z.isEmpty()) {
            return 1.0;
        }
        long ok = z.stream().filter(x -> x.shapePolygons() != null).count();
        return (double) ok / z.size();
    }

    private static void logExtent(List<ZoneDto> zones, int cropW, int cropH) {
        int minX = 1000, minY = 1000, maxX = 0, maxY = 0, n = 0;
        for (ZoneDto z : zones) {
            if (z.shapePolygons() == null) {
                continue;
            }
            for (List<List<Integer>> poly : z.shapePolygons()) {
                for (List<Integer> pt : poly) {
                    minX = Math.min(minX, pt.get(0)); maxX = Math.max(maxX, pt.get(0));
                    minY = Math.min(minY, pt.get(1)); maxY = Math.max(maxY, pt.get(1));
                    n++;
                }
            }
        }
        if (n > 0) {
            log.info("AI 구역 모양 범위(보정 전): x {}~{}, y {}~{}, 꼭짓점 {}개, 기준 이미지 {}x{}", minX, maxX, minY, maxY, n, cropW, cropH);
        }
    }

    record FitResult(List<ZoneDto> zones, double aspect) {}

    /**
     * 모든 구역 모양의 바깥 사각형을 구해(둘레 3% 여백, 최소 10) 그 사각형이 0~1000 전체가 되도록 좌표를 다시 계산하고,
     * 그 사각형의 실제 가로/세로 비율을 돌려준다. 모양이 하나도 없거나 너무 작으면(폭·높이 20 미만) null이다.
     * cropW·cropH 는 모양 좌표의 기준이 된 이미지의 픽셀 크기다.
     */
    static FitResult fitToBounds(List<ZoneDto> zones, int cropW, int cropH) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        boolean any = false;
        for (ZoneDto z : zones) {
            if (z.shapePolygons() == null) {
                continue;
            }
            for (List<List<Integer>> poly : z.shapePolygons()) {
                for (List<Integer> pt : poly) {
                    minX = Math.min(minX, pt.get(0));
                    maxX = Math.max(maxX, pt.get(0));
                    minY = Math.min(minY, pt.get(1));
                    maxY = Math.max(maxY, pt.get(1));
                    any = true;
                }
            }
        }
        if (!any || cropW <= 0 || cropH <= 0 || maxX - minX < 20 || maxY - minY < 20) {
            return null;
        }
        int mx = Math.max(10, (int) Math.round((maxX - minX) * 0.03));
        int my = Math.max(10, (int) Math.round((maxY - minY) * 0.03));
        double x0 = minX - mx, y0 = minY - my;
        double spanX = (maxX - minX) + 2.0 * mx, spanY = (maxY - minY) + 2.0 * my;
        List<ZoneDto> out = new ArrayList<>();
        for (ZoneDto z : zones) {
            if (z.shapePolygons() == null) {
                out.add(z);
                continue;
            }
            List<List<List<Integer>>> polys = new ArrayList<>();
            for (List<List<Integer>> poly : z.shapePolygons()) {
                List<List<Integer>> np = new ArrayList<>();
                for (List<Integer> pt : poly) {
                    int nx = (int) Math.max(0, Math.min(1000, Math.round((pt.get(0) - x0) / spanX * 1000.0)));
                    int ny = (int) Math.max(0, Math.min(1000, Math.round((pt.get(1) - y0) / spanY * 1000.0)));
                    np.add(List.of(nx, ny));
                }
                polys.add(List.copyOf(np));
            }
            out.add(new ZoneDto(z.name(), List.copyOf(polys)));
        }
        double widthPx = spanX / 1000.0 * cropW;
        double heightPx = spanY / 1000.0 * cropH;
        double aspect = Math.round(widthPx / heightPx * 1000.0) / 1000.0;
        if (aspect <= 0 || aspect > 999.999) {
            return null;
        }
        return new FitResult(List.copyOf(out), aspect);
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
