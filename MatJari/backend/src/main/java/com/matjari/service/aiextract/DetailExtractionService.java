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
                    shapes = labelShapes(target, names); // 실제 픽셀 윤곽(ShapeSegmenter) + AI가 타일에서 읽은 구역 이름
                    if (!shapes.isEmpty()) {
                        names = extendNames(names, shapes); // 1차 이름 목록에서 빠졌지만 타일에서 읽힌 구역을 되살린다
                    } else {
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
            DetailExtractor.LabelResult result = voteLabels(target, seg, names);
            if (result == null) {
                return List.of();
            }
            writeDebug(target, seg, result);
            // nonSeatSegments 는 점검용 기록이다(AI가 구역에 연결한 번호가 우선하며, 무대 같은 큰 구조물은 아래 넓이 검사가 거른다)
            List<DetailExtractor.RawLabel> labels = canonicalLabels(attachOrphans(result, seg), names);
            labels = verifySuspects(labels, seg, target, names);
            List<DetailExtractor.RawZone> zones = dropOutliers(shapesFromLabels(labels, seg));
            log.info("AI 번호 방식: 도형 후보 {}개, 연결된 구역 {}개 / 이름 {}개", seg.segments().size(), zones.size(), names.size());
            if (validShapeRatio(extendNames(names, zones), zones) < 0.3) {
                return List.of();
            }
            return zones;
        } catch (RuntimeException e) {
            log.info("AI 번호 방식 실패, 좌표 방식으로 대체: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 조각 넓이가 전체 조각 넓이 중앙값의 20% 미만(범례 막대·글자 조각)이거나 6배 초과(무대·돌출 무대처럼 아주 큰 구조물과 합쳐진 쓰레기 덩어리)인 것을 버린다.
     * 조각이 남지 않는 구역은 목록에서 빠진다(이름은 1차 목록으로 유지).
     */
    static List<DetailExtractor.RawZone> dropOutliers(List<DetailExtractor.RawZone> zones) {
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
        double median = areas.get(areas.size() / 2);
        double min = median * 0.2;
        double max = median * 6;
        List<DetailExtractor.RawZone> out = new ArrayList<>();
        for (DetailExtractor.RawZone z : zones) {
            List<List<List<Integer>>> keep = new ArrayList<>();
            for (List<List<Integer>> p : z.polygons()) {
                double a = polygonArea(p);
                if (a >= min && a <= max) {
                    keep.add(p);
                }
            }
            if (!keep.isEmpty()) {
                out.add(new DetailExtractor.RawZone(z.name(), keep));
            }
        }
        return out;
    }

    /**
     * 글자가 없어 구역을 못 정한 도형(예: 숫자가 아래쪽 조각에만 있는 블록의 진한 빨강 VIP 윗부분)을 맞닿은 이웃 구역에 붙인다:
     * 가장자리 간격이 8px 이하이고, 합친 볼록 껍질 넓이가 두 도형 넓이 합의 1.25배 이하(하나의 블록을 이룸)이며 색이 있는(채도 0.3 이상) 도형만,
     * 가장 꼭 맞는 이웃 구역에 붙인다.
     */
    static List<DetailExtractor.RawLabel> attachOrphans(DetailExtractor.LabelResult result, ShapeSegmenter.Result seg) {
        Map<Integer, ShapeSegmenter.Segment> byId = new LinkedHashMap<>();
        for (ShapeSegmenter.Segment sg : seg.segments()) {
            byId.put(sg.id(), sg);
        }
        Map<Integer, String> owner = new LinkedHashMap<>();
        for (DetailExtractor.RawLabel l : result.zones()) {
            for (Integer id : l.segmentIds() == null ? List.<Integer>of() : l.segmentIds()) {
                owner.put(id, l.name());
            }
        }
        Map<String, List<Integer>> extra = new LinkedHashMap<>();
        for (Integer orphanId : result.nonSeatSegments() == null ? List.<Integer>of() : result.nonSeatSegments()) {
            ShapeSegmenter.Segment o = byId.get(orphanId);
            if (o == null || owner.containsKey(orphanId) || o.sat() < 0.3) { // 색이 있는 글자 없는 도형만 이웃에 붙인다
                continue;
            }
            String bestZone = null;
            double bestRatio = 1.25;
            for (Map.Entry<Integer, String> e : owner.entrySet()) {
                ShapeSegmenter.Segment q = byId.get(e.getKey());
                if (q == null || gap(o, q) > 8 || o.area() > q.area() * 1.2) { // 붙일 도형은 이웃보다 작아야 한다(큰 쓰레기 덩어리를 붙이지 않는다)
                    continue;
                }
                List<int[]> pts = new ArrayList<>(o.pixels());
                pts.addAll(q.pixels());
                double hull = Math.abs(ShapeSegmenter.shoelaceArea(ShapeSegmenter.convexHull(pts)));
                double sum = Math.abs(ShapeSegmenter.shoelaceArea(o.pixels())) + Math.abs(ShapeSegmenter.shoelaceArea(q.pixels()));
                if (sum > 0 && hull / sum <= bestRatio) {
                    bestRatio = hull / sum;
                    bestZone = e.getValue();
                }
            }
            if (bestZone != null) {
                extra.computeIfAbsent(bestZone, k -> new ArrayList<>()).add(orphanId);
            }
        }
        List<DetailExtractor.RawLabel> out = new ArrayList<>();
        for (DetailExtractor.RawLabel l : result.zones()) {
            List<Integer> ids = new ArrayList<>(l.segmentIds() == null ? List.<Integer>of() : l.segmentIds());
            ids.addAll(extra.getOrDefault(l.name(), List.of()));
            out.add(new DetailExtractor.RawLabel(l.name(), ids));
        }
        return out;
    }

    /** 두 도형의 경계 상자 사이 간격(겹치면 0). */
    private static int gap(ShapeSegmenter.Segment a, ShapeSegmenter.Segment b) {
        int[] ba = box(a), bb = box(b);
        int gx = Math.max(0, Math.max(bb[0] - ba[2], ba[0] - bb[2]));
        int gy = Math.max(0, Math.max(bb[1] - ba[3], ba[1] - bb[3]));
        return Math.max(gx, gy);
    }

    private static int[] box(ShapeSegmenter.Segment s) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = 0, maxY = 0;
        for (int[] p : s.pixels()) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    /**
     * 모든 도형을 하나씩 크게(320px) 확대한 타일로 다시 읽어 이름을 정한다(8개씩 묶어 병렬로 부른다). 작은 격자 타일에서는 흰 작은 글씨(연한 파랑
     * 블록의 번호 등)를 잘못 읽는 일이 있어서다. 다시 읽은 이름이 구역 코드답거나 1차 이름 목록에 있으면 그 이름을 쓰고, 비었거나 이상하면 처음 읽은 이름을 유지한다.
     */
    private List<DetailExtractor.RawLabel> verifySuspects(List<DetailExtractor.RawLabel> labels, ShapeSegmenter.Result seg,
                                                          ImageSlicer.SliceImage target, List<String> names) {
        Map<Integer, ShapeSegmenter.Segment> byId = new LinkedHashMap<>();
        for (ShapeSegmenter.Segment sg : seg.segments()) {
            byId.put(sg.id(), sg);
        }
        Map<Integer, String> owner = new LinkedHashMap<>();
        for (DetailExtractor.RawLabel l : labels) {
            for (Integer id : l.segmentIds()) {
                owner.put(id, l.name());
            }
        }
        List<Integer> ids = new ArrayList<>(byId.keySet());
        if (ids.size() > 80) {
            ids = ids.subList(0, 80);
        }
        List<java.util.concurrent.CompletableFuture<Map<Integer, String>>> calls = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += 8) {
            List<Integer> batch = new ArrayList<>(ids.subList(from, Math.min(ids.size(), from + 8)));
            calls.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    List<byte[]> tiles = new ArrayList<>();
                    for (Integer id : batch) {
                        tiles.add(ShapeSegmenter.bigTile(target.jpeg(), byId.get(id), 320));
                    }
                    return extractor.readTiles(batch, tiles);
                } catch (RuntimeException e) {
                    log.info("AI 타일 다시 읽기 한 묶음 실패: {}", e.getClass().getSimpleName());
                    return Map.<Integer, String>of();
                }
            }));
        }
        java.util.Set<String> known = new java.util.HashSet<>();
        for (String n : names) {
            String k = keyOf(n);
            if (k != null) {
                known.add(k);
            }
        }
        int changed = 0;
        for (java.util.concurrent.CompletableFuture<Map<Integer, String>> c : calls) {
            Map<Integer, String> reread = c.join();
            for (Map.Entry<Integer, String> e : reread.entrySet()) {
                String t = e.getValue() == null ? "" : e.getValue().trim();
                if (t.isEmpty() || isStageName(t)) {
                    continue; // 비었거나 무대 표기면 처음 읽은 이름(또는 좌석 아님)을 유지
                }
                String k = keyOf(t);
                if (k != null && !k.isEmpty() && (known.contains(k) || looksLikeZoneCode(t)) && !t.equals(owner.get(e.getKey()))) {
                    owner.put(e.getKey(), t);
                    changed++;
                }
            }
        }
        log.info("AI 타일 다시 읽기로 바꾼 도형 {}개 / {}개", changed, ids.size());
        Map<String, List<Integer>> byName = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> e : owner.entrySet()) {
            byName.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        List<DetailExtractor.RawLabel> raw = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byName.entrySet()) {
            raw.add(new DetailExtractor.RawLabel(e.getKey(), e.getValue()));
        }
        return canonicalLabels(raw, names);
    }

    /** 같은 질문을 병렬로 이 횟수만큼 보내 도형마다 다수결로 구역을 정한다(AI 답이 실행마다 조금씩 흔들리는 것을 줄인다). */
    static final int LABEL_VOTES = 3;

    /**
     * 타일에서 읽은 글자를 구역 이름으로 다듬는다: 공백 정리·12자 초과(문장 조각)·무대/플로어 표기는 버리고, 1차 이름 목록과 zoneKey 가 같으면
     * 그 표기로 통일하며, 같은 이름은 한 구역으로 합친다.
     */
    static List<DetailExtractor.RawLabel> canonicalLabels(List<DetailExtractor.RawLabel> raw, List<String> names) {
        Map<String, String> known = new LinkedHashMap<>();
        for (String n : names) {
            String k = keyOf(n);
            if (k != null) {
                known.putIfAbsent(k, n);
            }
        }
        Map<String, DetailExtractor.RawLabel> out = new LinkedHashMap<>();
        for (DetailExtractor.RawLabel l : raw) {
            String c = cleanName(l.name());
            String k = c == null ? null : keyOf(c);
            if (c == null || k == null || k.isEmpty() || c.length() > 12 || isStageName(c) || (!known.containsKey(k) && !looksLikeZoneCode(c))) {
                continue;
            }
            String display = known.getOrDefault(k, c);
            DetailExtractor.RawLabel prev = out.get(k);
            List<Integer> ids = new ArrayList<>(prev == null ? List.<Integer>of() : prev.segmentIds());
            ids.addAll(l.segmentIds() == null ? List.<Integer>of() : l.segmentIds());
            out.put(k, new DetailExtractor.RawLabel(display, ids));
        }
        return new ArrayList<>(out.values());
    }

    /** 타일에서 새로 읽은 이름이 구역 코드답다: 4자 이하이고 숫자나 라틴 글자를 포함(한글만 있는 안내 글자 '좌석'·'안내'·'최신순' 등은 아니다). */
    static boolean looksLikeZoneCode(String name) {
        String n = name.replaceAll("\s+", "");
        return n.length() <= 5 && n.matches(".*[0-9A-Za-z].*");
    }

    /** names 에 없는 구역 이름(zoneKey 기준)을 shapes 에서 찾아 뒤에 붙인다(무대·플로어 표기와 100개 초과분 제외). */
    static List<String> extendNames(List<String> names, List<DetailExtractor.RawZone> shapes) {
        List<String> out = new ArrayList<>(names);
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String n : names) {
            String k = keyOf(n);
            if (k != null) {
                keys.add(k);
            }
        }
        for (DetailExtractor.RawZone z : shapes) {
            String c = cleanName(z.name());
            String k = c == null ? null : keyOf(c);
            if (c != null && k != null && !k.isEmpty() && !isStageName(c) && out.size() < MAX_ZONES && keys.add(k)) {
                out.add(c);
            }
        }
        return out;
    }

    private DetailExtractor.LabelResult voteLabels(ImageSlicer.SliceImage target, ShapeSegmenter.Result seg, List<String> names) {
        List<java.util.concurrent.CompletableFuture<DetailExtractor.LabelResult>> calls = new ArrayList<>();
        for (int i = 0; i < LABEL_VOTES; i++) {
            calls.add(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return extractor.label(target.jpeg(), seg.annotatedJpeg(), seg.tilesJpeg(), names, seg.segments().size());
                } catch (RuntimeException e) {
                    log.info("AI 번호 매기기 한 번 실패: {}", e.getClass().getSimpleName());
                    return null;
                }
            }));
        }
        List<DetailExtractor.LabelResult> runs = new ArrayList<>();
        for (java.util.concurrent.CompletableFuture<DetailExtractor.LabelResult> c : calls) {
            DetailExtractor.LabelResult r = c.join();
            if (r != null && r.zones() != null) {
                runs.add(r);
            }
        }
        return combineVotes(runs);
    }

    /** 도형 번호마다 가장 많이 나온 구역을 고른다. 표가 같으면 구역이 아닌 쪽(빈 이름)을 따른다. 결과가 없으면 null. */
    static DetailExtractor.LabelResult combineVotes(List<DetailExtractor.LabelResult> runs) {
        if (runs.isEmpty()) {
            return null;
        }
        Map<Integer, Map<String, Integer>> votes = new LinkedHashMap<>();
        for (DetailExtractor.LabelResult r : runs) {
            for (DetailExtractor.RawLabel l : r.zones()) {
                for (Integer id : l.segmentIds() == null ? List.<Integer>of() : l.segmentIds()) {
                    if (id != null) {
                        votes.computeIfAbsent(id, k -> new LinkedHashMap<>()).merge(l.name(), 1, Integer::sum);
                    }
                }
            }
            for (Integer id : r.nonSeatSegments() == null ? List.<Integer>of() : r.nonSeatSegments()) {
                if (id != null) {
                    votes.computeIfAbsent(id, k -> new LinkedHashMap<>()).merge("", 1, Integer::sum);
                }
            }
        }
        Map<String, List<Integer>> byName = new LinkedHashMap<>();
        List<Integer> nonSeat = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Integer>> e : votes.entrySet()) {
            String best = "";
            int bestCount = 0;
            boolean tie = false;
            for (Map.Entry<String, Integer> v : e.getValue().entrySet()) {
                if (v.getValue() > bestCount) {
                    best = v.getKey();
                    bestCount = v.getValue();
                    tie = false;
                } else if (v.getValue() == bestCount) {
                    tie = true;
                }
            }
            if (tie || best.isEmpty()) {
                nonSeat.add(e.getKey());
            } else {
                byName.computeIfAbsent(best, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        List<DetailExtractor.RawLabel> zones = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : byName.entrySet()) {
            zones.add(new DetailExtractor.RawLabel(e.getKey(), e.getValue()));
        }
        return new DetailExtractor.LabelResult(zones, nonSeat);
    }

    private static int linkedCount(DetailExtractor.LabelResult r) {
        int n = 0;
        for (DetailExtractor.RawLabel l : r.zones()) {
            if (l.segmentIds() != null && !l.segmentIds().isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private void writeDebug(ImageSlicer.SliceImage target, ShapeSegmenter.Result seg, DetailExtractor.LabelResult result) {
        String dir = properties.getDebugDir();
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            java.nio.file.Path d = java.nio.file.Path.of(dir);
            java.nio.file.Files.createDirectories(d);
            java.nio.file.Files.write(d.resolve("target.jpg"), target.jpeg());
            java.nio.file.Files.write(d.resolve("numbered.jpg"), seg.annotatedJpeg());
            java.nio.file.Files.write(d.resolve("tiles.jpg"), seg.tilesJpeg());
            StringBuilder sb = new StringBuilder("{\"width\":" + seg.width() + ",\"height\":" + seg.height() + ",\"nonSeat\":" + result.nonSeatSegments() + ",\"zones\":{");
            boolean first = true;
            for (DetailExtractor.RawLabel l : result.zones()) {
                sb.append(first ? "" : ",").append('"').append(l.name().replace("\"", "")).append("\":").append(l.segmentIds());
                first = false;
            }
            sb.append("}}");
            java.nio.file.Files.writeString(d.resolve("labels.json"), sb.toString());
        } catch (java.io.IOException | RuntimeException e) {
            log.info("AI 디버그 저장 실패: {}", e.getClass().getSimpleName());
        }
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
                out.add(new DetailExtractor.RawZone(l.name(), joinParts(polys)));
            }
        }
        return out;
    }

    /**
     * 한 구역이 색으로 갈라진 조각들(예: VIP 부분 + 일반석 부분)이면 틈 없는 하나의 매끄러운 블록으로 합친다:
     * 조각들의 볼록 껍질 넓이가 조각 넓이 합의 1.6배 이하(조각들이 서로 붙어 한 블록을 이룸, 숫자 원이 가운데를 가르는 경우 포함)일 때만, 아니면 그대로 둔다.
     */
    static List<List<List<Integer>>> joinParts(List<List<List<Integer>>> polys) {
        if (polys.size() < 2) {
            return polys;
        }
        List<int[]> pts = new ArrayList<>();
        double sum = 0;
        for (List<List<Integer>> p : polys) {
            sum += polygonArea(p);
            for (List<Integer> pt : p) {
                pts.add(new int[]{pt.get(0), pt.get(1)});
            }
        }
        List<int[]> hull = ShapeSegmenter.convexHull(pts);
        if (hull.size() < 3 || hull.size() > 40) {
            return polys;
        }
        List<List<Integer>> joined = new ArrayList<>();
        for (int[] p : hull) {
            joined.add(List.of(p[0], p[1]));
        }
        return polygonArea(joined) <= sum * 1.6 ? List.of(joined) : polys;
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

    /** 무대·설비(콘솔·F.O.H·PA·카메라·휠체어)와 'FLOOR'라고만 적힌 안내 글자는 구역이 아니다(사용자 결정 2026-10-19). FLOOR 안의 좌석 블록(THE·RED·LINE·F3 등)은 객석 구역이라 남긴다. */
    static boolean isStageName(String name) {
        String n = name.toUpperCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Z}_\\-]+", "");
        return n.contains("STAGE") || n.contains("무대") || n.contains("스테이지") || n.equals("FLOOR") || n.equals("플로어") || n.equals("플로어석")
                || n.contains("CONSOLE") || n.contains("콘솔") || n.contains("F.O.H") || n.equals("FOH") || n.equals("PA") || n.contains("CAMERA") || n.contains("카메라")
                || n.contains("MIXER") || n.contains("휠체어");
    }

    /** 공백 정리·30자 이하·zoneKey 중복 제거(첫 표기 유지)·최대 100개. */
    static List<String> cleanNames(List<String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String r : raw == null ? List.<String>of() : raw) {
            String name = cleanName(r);
            String key = name == null ? null : keyOf(name);
            if (key != null && !key.isEmpty() && !isStageName(name) && out.size() < MAX_ZONES) {
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
