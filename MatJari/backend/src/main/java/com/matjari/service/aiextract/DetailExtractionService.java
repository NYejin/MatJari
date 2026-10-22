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
    private static final Pattern KEY = Pattern.compile("^(yes24|ticketlink|melon|interpark):(\\d{1,20})$");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    static final int MAX_SESSIONS = 100;
    static final int MAX_ZONES = 1000; // 구역 개수 상한은 없다(사용자 결정 2026-10-20). 등록 요청의 안전 한도와 같은 값
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

    /** verifiedZones: 구역이 사람이 검수한 정답에서 왔다(AI 결과 아님, VerifiedZones). */
    public record Response(List<SessionDto> sessions, String sessionsSource, List<ZoneDto> zones, Double seatmapAspect,
                           boolean aiEnabled, boolean aiError, boolean cached, boolean truncatedSlices, boolean verifiedZones) {
        public Response(List<SessionDto> sessions, String sessionsSource, List<ZoneDto> zones, Double seatmapAspect,
                        boolean aiEnabled, boolean aiError, boolean cached, boolean truncatedSlices) {
            this(sessions, sessionsSource, zones, seatmapAspect, aiEnabled, aiError, cached, truncatedSlices, false);
        }

        Response asCached() {
            return new Response(sessions, sessionsSource, zones, seatmapAspect, aiEnabled, aiError, true, truncatedSlices, verifiedZones);
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
    /** 링크별 결과 DB 보관(V23). null 이면 메모리 캐시만 쓴다(단위 시험·평가 도구). */
    private final AiResultStore store;
    /** 같은 공연장의 등록된 구역 이름(구역이 많은 공연장만). null 이면 참고 없이 읽는다. */
    private final VenueZoneHints venueHints;
    /** 크레딧 소진 판단. null 이면 보지 않는다(단위 시험). */
    private AiCredit credit;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCredit(AiCredit credit) {
        this.credit = credit;
    }

    /** 티켓링크 상세 화면 그리기(renderer 컨테이너). null 이거나 꺼져 있으면 티켓링크는 링크 HTML 글자만 쓴다. */
    private DetailPageRenderer renderer;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRenderer(DetailPageRenderer renderer) {
        this.renderer = renderer;
    }

    /** 사람이 검수한 구역 정답(정답검수 탭). null 이면 쓰지 않는다. */
    private VerifiedZones verifiedZones;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setVerifiedZones(VerifiedZones verifiedZones) {
        this.verifiedZones = verifiedZones;
    }

    public DetailExtractionService(SourceKeyResolver sourceKeyResolver, PerformancePreviewService previewService,
                                   SessionTextParser textParser, DetailImageFetcher imageFetcher, ImageSlicer slicer,
                                   ShapeSegmenter segmenter, DetailExtractor extractor, AiUsageLimiter limiter, AiProperties properties, Clock clock) {
        this(sourceKeyResolver, previewService, textParser, imageFetcher, slicer, segmenter, extractor, limiter, properties,
                clock, null, null);
    }

    public DetailExtractionService(SourceKeyResolver sourceKeyResolver, PerformancePreviewService previewService,
                                   SessionTextParser textParser, DetailImageFetcher imageFetcher, ImageSlicer slicer,
                                   ShapeSegmenter segmenter, DetailExtractor extractor, AiUsageLimiter limiter, AiProperties properties,
                                   Clock clock, AiResultStore store) {
        this(sourceKeyResolver, previewService, textParser, imageFetcher, slicer, segmenter, extractor, limiter, properties,
                clock, store, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DetailExtractionService(SourceKeyResolver sourceKeyResolver, PerformancePreviewService previewService,
                                   SessionTextParser textParser, DetailImageFetcher imageFetcher, ImageSlicer slicer,
                                   ShapeSegmenter segmenter, DetailExtractor extractor, AiUsageLimiter limiter, AiProperties properties,
                                   Clock clock, AiResultStore store, VenueZoneHints venueHints) {
        this.store = store;
        this.venueHints = venueHints;
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

    /** 이번 달 공연정보 불러오기 사용 현황(화면의 남은 횟수 표시용). */
    public AiUsageLimiter.Quota quota(Long userId) {
        return limiter.quota(userId).withAiAvailable(aiUsable());
    }

    /** 관리자: 월 횟수 없음(사용자 지시 2026-10-22). */
    public AiUsageLimiter.Quota quota(Long userId, boolean admin) {
        AiUsageLimiter.Quota q = quota(userId);
        return admin ? q.asUnlimited() : q;
    }

    /** 관리자의 공연 등록 중 [불러오기]: 보관 결과·공연장 참고는 그대로 쓰고 월 횟수만 세지 않는다. */
    public Response extractUnlimited(String sourceUrl) {
        return extract(null, sourceUrl, false, true);
    }

    /** 관리자의 등록 중 좌석표 이미지 올리기: 월 횟수를 세지 않는다. */
    public Response extractSeatMapUnlimited(byte[] bytes) {
        return extractSeatMap(null, bytes, true);
    }

    /** AI 를 지금 부를 수 있나: 켜짐 + 키 있음 + 크레딧이 남음(AiCredit). */
    private boolean aiUsable() {
        return properties.isEnabled() && extractor.available() && (credit == null || !credit.exhausted());
    }

    public Response extract(Long userId, String sourceUrl) {
        return extract(userId, sourceUrl, false);
    }

    /**
     * 관리자 다시 인식(공연 관리 → 구역 다시 불러오기): 보관 결과를 읽지 않고 새로 읽으며, 월 횟수는 세지 않는다.
     * 성공하면 보관 결과를 새 결과로 덮는다(잘못 읽힌 결과가 다른 등록자에게 다시 나가지 않게). 같은 공연장 참고 이름은 쓰지 않는다
     * (다시 읽는 공연 자신의 잘못된 구역이 참고로 들어갈 수 있다). 결과는 화면에서 확인한 뒤 구역 교체 API 로만 저장된다.
     */
    public Response extractForAdmin(String sourceUrl) {
        return extract(null, sourceUrl, true, true);
    }

    private Response extract(Long userId, String sourceUrl, boolean admin) {
        return extract(userId, sourceUrl, admin, admin);
    }

    /** admin: 보관 결과를 쓰지 않고 새로 읽음(다시 인식). unlimited: 월 횟수를 세지 않음(관리자). */
    private Response extract(Long userId, String sourceUrl, boolean admin, boolean unlimited) {
        String key = sourceKeyResolver.resolve(sourceUrl); // 형식 오류는 400(FieldValidationException)
        Matcher m = KEY.matcher(key);
        if (!m.matches()) {
            throw new AiExtractException(400, "UNSUPPORTED_SITE", "지원하지 않는 사이트예요. YES24·티켓링크·멜론티켓·인터파크(NOL) 링크만 읽을 수 있어요.", 0);
        }
        String site = m.group(1);
        // 검수 정답이 있는 상품은 예전 AI 보관 결과보다 검수 정답을 먼저 확인한다
        boolean hasVerified = verifiedZones != null && verifiedZones.has(key);
        Response hit = admin || hasVerified ? null : cacheGet(key);
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

        // 링크 상품 번호와 좌석표 이미지가 검수 정답과 같으면 사람이 검수한 구역을 그대로 쓰고 AI 는 부르지 않는다(횟수에 세지 않음)
        if (verifiedZones != null) {
            try {
                java.util.Optional<VerifiedZones.Match> vm = verifiedZones.find(site, key, page.document());
                if (vm.isPresent()) {
                    Response b = build(ruleSessions, List.of(), vm.get().zones(), vm.get().aspect(), false, false, false);
                    return new Response(b.sessions(), b.sessionsSource(), b.zones(), b.seatmapAspect(), b.aiEnabled(), false, false, false, true);
                }
            } catch (RuntimeException e) {
                log.info("검수 정답 확인 실패: {}", e.getClass().getSimpleName());
            }
        }

        boolean aiOn = aiUsable();
        if (!aiOn) {
            return build(ruleSessions, List.of(), List.of(), null, false, false, false);
        }

        // 티켓링크는 상세 내용이 화면이 뜬 뒤에 채워져서 renderer(헤드리스 크롬)로 그린 화면의 글자·이미지를 쓴다
        DetailPageRenderer.Rendered rendered = null;
        if ("ticketlink".equals(site) && renderer != null && renderer.available()) {
            rendered = renderer.renderTicketlink(m.group(2));
        }
        // AI 에 함께 보낼 상세 본문 글자(회차 맥락 읽기용, 사용자 지시 2026-10-22)
        String detailText = detailText(rendered != null ? rendered.text() : page.document().body() == null ? "" : page.document().body().text());

        // 이미지 수집·조각내기(AI 호출 전이라 횟수에 세지 않는다). 멜론은 받은 페이지, 티켓링크는 그린 화면에서 찾는다.
        List<DetailImageFetcher.DetailImage> images = new ArrayList<>();
        List<ImageSlicer.SliceResult> sliced = new ArrayList<>();
        org.jsoup.nodes.Document imagePage = "melon".equals(site) || "interpark".equals(site) ? page.document()
                : rendered != null ? rendered.document() : null;
        if (imagePage != null) {
            try {
                for (DetailImageFetcher.DetailImage img : imageFetcher.fetch(site, imagePage)) {
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
        if (images.isEmpty() && detailText.isBlank()) {
            Response r = build(ruleSessions, List.of(), List.of(), null, true, false, false);
            cacheIfUseful(key, r);
            return r;
        }

        boolean truncated = sliced.stream().anyMatch(ImageSlicer.SliceResult::truncated);
        List<DetailExtractor.Part> parts = new ArrayList<>();
        // 구역이 많은 공연장: 같은 공연장에 등록된 공연의 구역 이름을 참고로 준다(배치가 다를 수 있어 이미지에 보이는 것만 적게 한다)
        List<String> hint = admin || venueHints == null ? List.of() : venueHints.namesFor(page.venue());
        if (!hint.isEmpty()) {
            parts.add(DetailExtractor.Part.ofText("[참고] 같은 공연장에서 이전에 등록된 공연의 좌석 구역 이름: " + String.join(", ", hint)
                    + " / 공연마다 무대·좌석 배치가 다를 수 있으니 이 목록은 철자와 번호 체계 참고용이다. 이미지의 좌석표에 실제로 보이는 구역만 적어라."));
            log.info("AI 1차 읽기: 같은 공연장 구역 이름 {}개 참고", hint.size());
        }
        if (!detailText.isBlank()) {
            parts.add(DetailExtractor.Part.ofText(contextHeader(page) + "\n[상세 본문 글자]\n" + detailText));
        }
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
        AiUsageLimiter.Permit permit = unlimited ? limiter.acquireAdmin(key) : limiter.acquire(userId, key);
        try (permit) {
            DetailExtractor.FirstPass first = extractor.first(parts);
            aiSessions = cleanSessions(first.sessions(), page.periodStart(), page.periodEnd());
            List<String> names = images.isEmpty() ? List.of() : cleanNames(first.zones()); // 글자만 읽었으면 구역은 쓰지 않는다
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
                    shapes = ShapeRegularizer.regularize(shapes); // 꼭짓점 8개 이하·사각형 직각/평행 맞추기·좌우대칭
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
            zones = dropHintOnly(zones, hint, shapes);
            zones = dropUnshapedNumbers(zones);
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
        if (aiError) {
            permit.refund(); // AI 가 읽지 못한 호출은 이번 달 횟수에서 빼 준다(사용자 결정 2026-10-21)
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
     * 사용자가 올린 좌석표 이미지 한 장에서 구역 이름·모양을 읽는다(등록 2단계에서 구역 지도를 자동으로 얻지 못했을 때, 사용자 결정 2026-10-22).
     * 이미지는 저장하지 않고 요청 처리 중 메모리에서만 쓴다(결과 보관 키는 이미지 SHA-256 이라 같은 이미지는 다시 세지 않는다).
     * 횟수는 [불러오기]와 같은 월 3회에서 세고, AI 가 읽지 못하면 돌려준다. 회차는 읽지 않는다.
     */
    public Response extractSeatMap(Long userId, byte[] bytes) {
        return extractSeatMap(userId, bytes, false);
    }

    /** 관리자 좌석표 이미지 인식(공연 관리): 월 횟수를 세지 않고 보관 결과를 읽지 않는다. */
    public Response extractSeatMapForAdmin(byte[] bytes) {
        return extractSeatMap(null, bytes, true);
    }

    private Response extractSeatMap(Long userId, byte[] bytes, boolean admin) {
        if (bytes == null || bytes.length == 0) {
            throw new AiExtractException(400, "IMAGE_REQUIRED", "좌석표 이미지를 골라 주세요.", 0);
        }
        if (bytes.length > properties.getMaxImageBytes()) {
            throw new AiExtractException(400, "IMAGE_TOO_LARGE", "이미지가 너무 커요.", 0);
        }
        int[] size = imageFetcher.readSize(bytes); // JPEG·PNG 헤더만 읽는다(픽셀 수 상한 포함)
        if (size == null) {
            throw new AiExtractException(400, "IMAGE_UNSUPPORTED", "JPEG·PNG 이미지만 읽을 수 있어요.", 0);
        }
        if (!aiUsable()) {
            throw new AiExtractException(503, "AI_UNAVAILABLE", "지금은 AI로 읽을 수 없어요. 구역을 직접 입력해 주세요.", 0);
        }
        String key = "upload:" + sha256Hex(bytes);
        Response hit = admin ? null : cacheGet(key);
        if (hit != null) {
            return hit.asCached();
        }
        ImageSlicer.SliceImage target;
        try {
            target = slicer.crop(bytes, 0, size[1]); // 폭을 줄이고 JPEG 로 다시 만든다(높이는 cropMaxHeight 까지)
        } catch (RuntimeException e) {
            throw new AiExtractException(400, "IMAGE_UNSUPPORTED", "JPEG·PNG 이미지만 읽을 수 있어요.", 0);
        }

        long started = System.nanoTime();
        List<ZoneDto> zones = List.of();
        Double aspect = null;
        boolean aiError = false;
        AiUsageLimiter.Permit permit = admin ? limiter.acquireAdmin(key) : limiter.acquire(userId, key);
        try (permit) {
            DetailExtractor.FirstPass first = extractor.first(List.of(
                    DetailExtractor.Part.ofText("[이미지 1 · 조각 1/1] 사용자가 올린 좌석표 이미지"),
                    DetailExtractor.Part.ofImage(target.jpeg())));
            List<String> names = cleanNames(first.zones());
            List<DetailExtractor.RawZone> shapes = List.of();
            if (!names.isEmpty()) {
                try {
                    shapes = labelShapes(target, names);
                    if (!shapes.isEmpty()) {
                        names = extendNames(names, shapes);
                    } else {
                        shapes = extractor.second(target.jpeg(), names);
                    }
                    if (!shapeQualityOk(names, shapes)) {
                        shapes = extractor.second(target.jpeg(), names);
                        if (!shapeQualityOk(names, shapes)) {
                            shapes = List.of();
                        }
                    }
                    shapes = ShapeRegularizer.regularize(shapes);
                    aspect = Math.round(target.width() * 1000.0 / target.height()) / 1000.0;
                } catch (RuntimeException e) { // 모양 실패해도 이름은 유지
                    log.info("좌석표 이미지 모양 읽기 실패, 이름만 유지: {}", e.getClass().getSimpleName());
                    shapes = List.of();
                    aspect = null;
                }
            }
            zones = mergeZones(names, shapes);
            if (aspect != null) {
                FitResult fit = fitToBounds(zones, target.width(), target.height());
                if (fit != null) {
                    zones = fit.zones();
                    aspect = fit.aspect();
                }
            }
            if (aspect != null && zones.stream().noneMatch(z -> z.shapePolygons() != null)) {
                aspect = null;
            }
        } catch (AiExtractException e) {
            throw e;
        } catch (RuntimeException e) {
            aiError = true;
            log.info("좌석표 이미지 AI 읽기 실패: {}", e.getClass().getSimpleName());
        }
        log.info("좌석표 이미지 읽기 완료: {}ms, {}x{}, zones={}, aiError={}",
                (System.nanoTime() - started) / 1_000_000, target.width(), target.height(), zones.size(), aiError);
        if (aiError) {
            permit.refund();
            throw new AiExtractException(503, "AI_UNAVAILABLE", "AI로 좌석표를 읽지 못했어요. 잠시 후 다시 시도하거나 구역을 직접 입력해 주세요.", 0);
        }
        Response r = build(List.of(), List.of(), zones, aspect, true, false, false);
        cacheIfUseful(key, r);
        return r;
    }

    /** AI 에 보낼 본문 글자: 공백을 줄이고 최대 MAX_DETAIL_TEXT 자. */
    static String detailText(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.replaceAll("[\\s\\u00a0]+", " ").trim();
        return t.length() > MAX_DETAIL_TEXT ? t.substring(0, MAX_DETAIL_TEXT) : t;
    }

    static final int MAX_DETAIL_TEXT = 8000;

    /** 연도·기간 판단을 돕는 머리말: 오늘 날짜(KST)와 링크에서 읽은 공연 기간. */
    private String contextHeader(PerformancePreviewService.FetchedPage page) {
        StringBuilder sb = new StringBuilder("[참고] 오늘(KST): ").append(LocalDate.now(clock));
        if (page.periodStart() != null) {
            sb.append(" / 링크에 적힌 공연 기간: ").append(page.periodStart()).append(" ~ ")
                    .append(page.periodEnd() == null ? page.periodStart() : page.periodEnd());
        }
        return sb.toString();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 같은 공연장 참고 이름을 그대로 베낀 구역을 뺀다: 모양이 하나라도 있는 지도에서, 모양 없이 이름만 남은 구역 중 참고 목록에 있는 이름.
     * (실제로 지도에 있으면 도형 판독에서 모양이 붙는다.) 참고가 없었으면 그대로다.
     */
    static List<ZoneDto> dropHintOnly(List<ZoneDto> zones, List<String> hint, List<DetailExtractor.RawZone> shapes) {
        if (hint.isEmpty() || shapes.isEmpty() || zones.stream().noneMatch(z -> z.shapePolygons() != null)) {
            return zones;
        }
        java.util.Set<String> hinted = new java.util.HashSet<>();
        for (String h : hint) {
            hinted.add(hintKey(h));
        }
        List<ZoneDto> out = new ArrayList<>();
        int dropped = 0;
        for (ZoneDto z : zones) {
            if (z.shapePolygons() == null && hinted.contains(hintKey(z.name()))) {
                dropped++;
                continue;
            }
            out.add(z);
        }
        if (dropped > 0) {
            log.info("같은 공연장 참고 이름 중 지도에 없는 구역 {}개 제외", dropped);
        }
        return out;
    }

    /**
     * 모양이 충분히 있는 지도(모양 있는 구역 5개 이상)에서 어느 도형에도 붙지 못한 '숫자 번호' 이름은 뺀다.
     * 번호가 건너뛴 자리를 AI 가 순서로 채운 가짜 구역(KSPO DOME 16~23 등, 2026-10-22)을 사용자에게 보이지 않기 위해서다.
     * 글자 이름(FLOOR·OP 등)은 모양이 없어도 남긴다.
     */
    static List<ZoneDto> dropUnshapedNumbers(List<ZoneDto> zones) {
        long shaped = zones.stream().filter(z -> z.shapePolygons() != null).count();
        if (shaped < 5) {
            return zones;
        }
        List<ZoneDto> out = new ArrayList<>();
        int dropped = 0;
        for (ZoneDto z : zones) {
            if (z.shapePolygons() == null && z.name() != null && z.name().trim().matches("[A-Za-z]{0,2}\\d{1,4}")) {
                dropped++;
                continue;
            }
            out.add(z);
        }
        if (dropped > 0) {
            log.info("모양 있는 지도에서 도형이 없는 번호 구역 {}개 제외", dropped);
        }
        return out;
    }

    private static String hintKey(String name) {
        try {
            return com.matjari.service.SeatKeyNormalizer.zoneKey(name);
        } catch (RuntimeException e) { // 비었거나 너무 긴 이름은 그대로 비교
            return String.valueOf(name);
        }
    }

    /** 평가 결과: 1차 이름 목록, 좌석표 기준 0~1000 좌표의 구역(fitToBounds 전), AI 좌표 방식으로 대체했는지. */
    record SeatMapEval(List<String> firstNames, List<ZoneDto> zones, boolean usedFallback) {}

    /**
     * 모델 정확도 평가 전용: 이미 잘라 낸 좌석표 한 장으로 이름 목록 → 모양 → 병합을 운영 경로와 같은 순서로 실행한다. 모델마다 같은 좌석표를 넣어
     * 비교하려고 좌석표 위치 찾기와 fitToBounds 는 하지 않는다(좌표가 좌석표 이미지 기준으로 남는다). 호출 제한·캐시를 거치지 않으므로 운영에서 부르지 않는다.
     */
    SeatMapEval evalSeatMap(byte[] jpeg, int width, int height) {
        ImageSlicer.SliceImage target = new ImageSlicer.SliceImage(0, jpeg, width, height, 0);
        DetailExtractor.FirstPass first = extractor.first(List.of(DetailExtractor.Part.ofText("[이미지 1 · 조각 1/1]"), DetailExtractor.Part.ofImage(jpeg)));
        List<String> names = cleanNames(first.zones());
        List<String> firstNames = List.copyOf(names);
        if (names.isEmpty()) {
            return new SeatMapEval(firstNames, List.of(), false);
        }
        boolean fallback = false;
        List<DetailExtractor.RawZone> shapes = labelShapes(target, names);
        if (!shapes.isEmpty()) {
            names = extendNames(names, shapes);
        } else {
            shapes = extractor.second(jpeg, names);
            fallback = true;
        }
        if (!shapeQualityOk(names, shapes)) {
            shapes = extractor.second(jpeg, names);
            fallback = true;
            if (!shapeQualityOk(names, shapes)) {
                shapes = List.of();
            }
        }
        return new SeatMapEval(firstNames, mergeZones(names, ShapeRegularizer.regularize(shapes)), fallback);
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
            if (legendWithoutGray(result, seg)) { // 범례에 회색 등급이 없으면 회색 부분을 빼고 다시 나눠 이름을 옮긴다
                ShapeSegmenter.Result colored = segmenter.segment(target.jpeg(), true);
                DetailExtractor.LabelResult moved = colored == null || colored.segments().size() < 3 ? null : remapLabels(result, seg, colored);
                log.info("범례에 회색 없음: 회색 빼고 다시 나눈 조각 {}개, 이름 옮기기 {}", colored == null ? 0 : colored.segments().size(), moved != null);
                if (moved != null) {
                    Remapped r = addInnerColor(result, seg, colored, moved, target.jpeg());
                    seg = r.seg();
                    result = r.labels();
                }
            }
            // nonSeatSegments 는 점검용 기록이다(AI가 구역에 연결한 번호가 우선하며, 무대 같은 큰 구조물은 아래 넓이 검사가 거른다)
            List<DetailExtractor.RawLabel> labels = canonicalLabels(attachOrphans(result, seg), names);
            Map<Integer, String> reread = new LinkedHashMap<>();
            labels = verifySuspects(labels, seg, target, names, reread);
            ZoneConsistency.Checked checked = ZoneConsistency.check(labels, seg, target.jpeg(), names, reread, extractor); // 범례 색·좌우대칭으로 빠진 구역과 이상한 구역 점검
            labels = checked.labels();
            seg = checked.seg();
            writeFinalDebug(seg, labels);
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

    /** 좌석 등급 범례 칩(이름이 범례 글자인 구역의 조각)이 있고 모두 색이 있으며(회색 등급 없음), 구역에 연결된 조각 중 회색이 섞인 것이 있다. */
    static boolean legendWithoutGray(DetailExtractor.LabelResult result, ShapeSegmenter.Result seg) {
        Map<Integer, ShapeSegmenter.Segment> byId = new LinkedHashMap<>();
        for (ShapeSegmenter.Segment s : seg.segments()) {
            byId.put(s.id(), s);
        }
        int legend = 0;
        boolean grayZone = false;
        for (DetailExtractor.RawLabel l : result.zones()) {
            String c = cleanName(l.name());
            boolean isLegend = c != null && (isLegendName(c) || c.replaceAll("\\s+", "").matches(".*[가-힣]석"));
            for (Integer id : l.segmentIds() == null ? List.<Integer>of() : l.segmentIds()) {
                ShapeSegmenter.Segment s = byId.get(id);
                if (s == null) {
                    continue;
                }
                if (isLegend) { // 범례 칩은 흰 글자가 섞여 채도가 0.2 안팎으로 낮게 잡힌다
                    if (s.sat() < 0.12) {
                        return false; // 회색 등급이 범례에 있다
                    }
                    legend++;
                } else if (s.sat() < 0.12 && c != null && !isStageName(c)) {
                    grayZone = true;
                }
            }
        }
        if (legend == 0) { // AI가 범례 칩 글자를 못 읽은 실행: 지도 아래(또는 위)에 한 줄로 늘어선 작은 알약 도형 2개 이상을 범례로 본다
            List<ShapeSegmenter.Segment> pills = legendPills(result, seg);
            for (ShapeSegmenter.Segment p : pills) {
                if (p.sat() < 0.12) {
                    return false;
                }
            }
            legend = pills.size();
        }
        return legend > 0 && grayZone;
    }

    /** 구역에 연결되지 않은 도형 중 가로로 긴(2배 이상, 글자에 갈린 칩 조각 포함) 작은(구역 조각 중앙값의 30% 미만) 알약이, 구역 조각들보다 아래나 위에서 같은 높이(±8px)로 2개 이상 늘어선 것. */
    static List<ShapeSegmenter.Segment> legendPills(DetailExtractor.LabelResult result, ShapeSegmenter.Result seg) {
        java.util.Set<Integer> linked = new java.util.HashSet<>();
        result.zones().forEach(l -> {
            String c = cleanName(l.name());
            if (l.segmentIds() != null && c != null && !isStageName(c)) {
                linked.addAll(l.segmentIds());
            }
        });
        List<ShapeSegmenter.Segment> zoneSegs = seg.segments().stream().filter(s -> linked.contains(s.id())).toList();
        if (zoneSegs.size() < 3) {
            return List.of();
        }
        int[] areas = zoneSegs.stream().mapToInt(ShapeSegmenter.Segment::area).sorted().toArray();
        int median = areas[areas.length / 2];
        int top = Integer.MAX_VALUE, bottom = 0;
        for (ShapeSegmenter.Segment s : zoneSegs) {
            int[] b = box(s);
            top = Math.min(top, b[1]);
            bottom = Math.max(bottom, b[3]);
        }
        List<ShapeSegmenter.Segment> cand = new ArrayList<>();
        for (ShapeSegmenter.Segment s : seg.segments()) {
            int[] b = box(s);
            int bw = b[2] - b[0] + 1, bh = b[3] - b[1] + 1;
            if (!linked.contains(s.id()) && bw >= 2 * bh && s.area() < median * 0.3 && (b[1] > bottom || b[3] < top)) {
                cand.add(s);
            }
        }
        List<ShapeSegmenter.Segment> out = new ArrayList<>();
        for (ShapeSegmenter.Segment s : cand) {
            if (cand.stream().anyMatch(o -> o != s && Math.abs(o.cy() - s.cy()) <= 8)) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * 회색을 뺀 새 조각마다, 넓이의 절반 이상이 겹치는 예전 조각의 구역 이름을 물려준다(겹치는 예전 조각이 구역이 없으면 글자 없는 도형으로 둔다).
     * 이름이 있던 구역의 80% 이상이 조각을 유지할 때만 쓰고, 아니면 null(예전 결과 유지).
     */
    static DetailExtractor.LabelResult remapLabels(DetailExtractor.LabelResult result, ShapeSegmenter.Result oldSeg, ShapeSegmenter.Result newSeg) {
        Map<Integer, String> owner = new LinkedHashMap<>();
        for (DetailExtractor.RawLabel l : result.zones()) {
            for (Integer id : l.segmentIds() == null ? List.<Integer>of() : l.segmentIds()) {
                owner.put(id, l.name());
            }
        }
        List<java.awt.geom.Area> oldAreas = new ArrayList<>();
        for (ShapeSegmenter.Segment s : oldSeg.segments()) {
            oldAreas.add(new java.awt.geom.Area(polygonOf(s)));
        }
        Map<String, List<Integer>> zones = new LinkedHashMap<>();
        List<Integer> orphans = new ArrayList<>();
        for (ShapeSegmenter.Segment n : newSeg.segments()) {
            java.awt.geom.Area na = new java.awt.geom.Area(polygonOf(n));
            double nArea = Math.abs(ShapeSegmenter.shoelaceArea(n.pixels())), best = 0;
            ShapeSegmenter.Segment from = null;
            for (int i = 0; i < oldAreas.size(); i++) {
                java.awt.geom.Area ix = new java.awt.geom.Area(na);
                ix.intersect(oldAreas.get(i));
                double a = ix.isEmpty() ? 0 : ShapeRegularizer.areaOf(ix);
                if (a > best) {
                    best = a;
                    from = oldSeg.segments().get(i);
                }
            }
            String zone = from == null || best < 0.5 * nArea ? null : owner.get(from.id());
            if (zone == null) {
                orphans.add(n.id());
            } else {
                zones.computeIfAbsent(zone, k -> new ArrayList<>()).add(n.id());
            }
        }
        // 글자가 회색 블록 위에 있던 구역(I·F)은 회색이 빠져 조각이 없어진다: 그 회색 블록에 가장 가까운(간격이 짧은 변의 절반 이하) 이름 없는 색 조각을 물려준다
        Map<Integer, ShapeSegmenter.Segment> newById = new LinkedHashMap<>();
        newSeg.segments().forEach(s -> newById.put(s.id(), s));
        for (DetailExtractor.RawLabel l : result.zones()) {
            if (zones.containsKey(l.name()) || l.segmentIds() == null) {
                continue;
            }
            for (Integer id : l.segmentIds()) {
                ShapeSegmenter.Segment o = oldSeg.segments().stream().filter(s -> s.id() == id).findFirst().orElse(null);
                if (o == null) {
                    continue;
                }
                Integer best = null;
                double bestDist = Double.MAX_VALUE;
                for (Integer cand : orphans) {
                    ShapeSegmenter.Segment c = newById.get(cand);
                    double d = Math.hypot(c.cx() - o.cx(), c.cy() - o.cy()); // 간격 안의 후보 중 중심이 가장 가까운 것(옆 블록의 띠를 가져오지 않게)
                    if (gap(o, c) <= Math.max(8, shortSide(o) / 2) && d < bestDist) {
                        bestDist = d;
                        best = cand;
                    }
                }
                if (best != null) {
                    orphans.remove(best);
                    zones.computeIfAbsent(l.name(), k -> new ArrayList<>()).add(best);
                }
            }
        }
        long before = result.zones().stream().filter(l -> l.segmentIds() != null && !l.segmentIds().isEmpty()).count();
        if (zones.size() < 0.8 * before) {
            return null;
        }
        List<DetailExtractor.RawLabel> out = new ArrayList<>();
        zones.forEach((k, v) -> out.add(new DetailExtractor.RawLabel(k, v)));
        return new DetailExtractor.LabelResult(out, orphans);
    }

    record Remapped(ShapeSegmenter.Result seg, DetailExtractor.LabelResult labels) {}

    /**
     * 이름이 회색 블록에만 붙어 있던 구역(화정체육관 I·F: 회색 블록 아래 귀퉁이에 분홍 삼각형만 좌석)은, 그 회색 블록 안의 색 있는 픽셀(30개 이상)을
     * 볼록 껍질로 묶어 그 구역의 조각으로 더한다. 조각 찾기의 최소 크기보다 작은 귀퉁이 좌석까지 살리려는 것이다(사용자 지시 2026-10-20).
     */
    static Remapped addInnerColor(DetailExtractor.LabelResult old, ShapeSegmenter.Result oldSeg, ShapeSegmenter.Result newSeg,
                                  DetailExtractor.LabelResult moved, byte[] jpeg) {
        java.awt.image.BufferedImage img;
        try {
            img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        } catch (java.io.IOException e) {
            img = null;
        }
        if (img == null) {
            return new Remapped(newSeg, moved);
        }
        Map<Integer, ShapeSegmenter.Segment> oldById = new LinkedHashMap<>();
        oldSeg.segments().forEach(s -> oldById.put(s.id(), s));
        List<ShapeSegmenter.Segment> segs = new ArrayList<>(newSeg.segments());
        int nextId = segs.stream().mapToInt(ShapeSegmenter.Segment::id).max().orElse(0) + 1;
        Map<String, List<Integer>> zones = new LinkedHashMap<>();
        moved.zones().forEach(l -> zones.put(l.name(), new ArrayList<>(l.segmentIds())));
        for (DetailExtractor.RawLabel l : old.zones()) {
            String c = cleanName(l.name());
            if (c == null || isStageName(c) || isLegendName(c) || l.segmentIds() == null
                    || l.segmentIds().stream().anyMatch(i -> oldById.containsKey(i) && oldById.get(i).sat() >= 0.12)) {
                continue; // 색 있는 조각이 이미 있는 구역(E 등)은 회색 이웃 조각 안을 뒤지지 않는다
            }
            for (Integer id : l.segmentIds()) {
                ShapeSegmenter.Segment o = oldById.get(id);
                if (o == null || o.sat() >= 0.12) {
                    continue;
                }
                // 회색 블록의 외곽선은 분홍 귀퉁이를 홈으로 비켜 가고 귀퉁이가 블록 밖으로 1~3px 삐져나오므로, 경계 상자를 3px 넓혀 찾는다
                java.awt.Rectangle b = polygonOf(o).getBounds();
                b.grow(3, 3);
                List<int[]> pts = new ArrayList<>();
                for (int y = Math.max(0, b.y); y < Math.min(img.getHeight(), b.y + b.height); y++) {
                    for (int x = Math.max(0, b.x); x < Math.min(img.getWidth(), b.x + b.width); x++) {
                        int p = img.getRGB(x, y), r = (p >> 16) & 255, g = (p >> 8) & 255, bl = p & 255;
                        int mx = Math.max(r, Math.max(g, bl)), mn = Math.min(r, Math.min(g, bl));
                        if (mx >= 70 && (double) (mx - mn) / mx >= 0.2) {
                            pts.add(new int[]{x, y});
                        }
                    }
                }
                if (pts.size() < 30) {
                    continue;
                }
                List<int[]> hull = ShapeSegmenter.convexHull(pts);
                double cx = 0, cy = 0;
                for (int[] p : pts) {
                    cx += p[0];
                    cy += p[1];
                }
                segs.add(new ShapeSegmenter.Segment(nextId, hull, pts.size(), (int) (cx / pts.size()), (int) (cy / pts.size()), 0.4, 200));
                zones.computeIfAbsent(l.name(), k -> new ArrayList<>()).add(nextId++);
            }
        }
        List<DetailExtractor.RawLabel> out = new ArrayList<>();
        zones.forEach((k, v) -> out.add(new DetailExtractor.RawLabel(k, v)));
        ShapeSegmenter.Result seg = new ShapeSegmenter.Result(newSeg.width(), newSeg.height(), List.copyOf(segs), newSeg.annotatedJpeg(), newSeg.tilesJpeg());
        return new Remapped(seg, new DetailExtractor.LabelResult(out, moved.nonSeatSegments()));
    }

    private static java.awt.Polygon polygonOf(ShapeSegmenter.Segment s) {
        java.awt.Polygon p = new java.awt.Polygon();
        for (int[] v : s.pixels()) {
            p.addPoint(v[0], v[1]);
        }
        return p;
    }

    /**
     * 조각 넓이가 전체 조각 넓이 중앙값의 12% 미만(범례 막대·글자 조각)이거나 6배 초과(무대·돌출 무대처럼 아주 큰 구조물과 합쳐진 쓰레기 덩어리)인 것을 버린다.
     * 단 조각이 하나뿐인 구역은 25배까지 둔다(넓은 스탠딩 구역).
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
        double min = median * 0.12; // 돔 경기장 안쪽 300번대 띠 블록은 바깥 블록의 15% 정도로 작다
        double max = median * 6;
        List<DetailExtractor.RawZone> out = new ArrayList<>();
        for (DetailExtractor.RawZone z : zones) {
            List<List<List<Integer>>> keep = new ArrayList<>();
            for (List<List<Integer>> p : z.polygons()) {
                double a = polygonArea(p);
                // 이름이 읽힌 넓은 플로어 구역(STANDING R 등)은 조각이 하나뿐이므로 25배까지 남긴다(무대 쓰레기 덩어리는 보통 다른 조각과 함께 붙는다)
                boolean wideFloor = z.polygons().size() == 1 && a <= median * 25;
                if (a >= min && (a <= max || wideFloor)) {
                    keep.add(p);
                }
            }
            // 같은 구역의 남은 조각 바로 옆(간격 25 이하)의 작은 귀퉁이 좌석(화정체육관 I·F 분홍 삼각형)은 범례 막대가 아니므로 둔다
            if (!keep.isEmpty()) {
                List<List<List<Integer>>> small = new ArrayList<>();
                for (List<List<Integer>> p : z.polygons()) {
                    double a = polygonArea(p);
                    if (a < min && a >= median * 0.02 && keep.stream().anyMatch(k -> boxGap(k, p) <= 25)) {
                        small.add(p);
                    }
                }
                keep.addAll(small);
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
            // 색이 있는 글자 없는 도형, 또는 회색이라도 블록 옆에 붙은 좁은 띠(화정체육관 2층 블록 옆 띠, 사용자 검수 2026-10-19)만 이웃에 붙인다
            if (o == null || owner.containsKey(orphanId) || (o.sat() < 0.3 && !strip(o))) {
                continue;
            }
            String bestZone = null;
            double bestRatio = strip(o) ? 1.45 : 1.25; // 띠는 비스듬한 블록 옆에 간격을 두고 서므로 볼록 껍질이 더 넓어진다
            for (Map.Entry<Integer, String> e : owner.entrySet()) {
                ShapeSegmenter.Segment q = byId.get(e.getKey());
                // 띠는 자기 폭의 60%까지 떨어져 있어도 붙인다(화정체육관 띠-블록 간격 11px, 띠 폭 23px)
                if (q == null || gap(o, q) > (strip(o) ? Math.max(10, 0.6 * shortSide(o)) : 8) || o.area() > q.area() * 1.2) { // 붙일 도형은 이웃보다 작아야 한다(큰 쓰레기 덩어리를 붙이지 않는다)
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
        attachFrontStrips(byId, owner, extra);
        List<DetailExtractor.RawLabel> out = new ArrayList<>();
        for (DetailExtractor.RawLabel l : result.zones()) {
            List<Integer> ids = new ArrayList<>(l.segmentIds() == null ? List.<Integer>of() : l.segmentIds());
            ids.addAll(extra.getOrDefault(l.name(), List.of()));
            out.add(new DetailExtractor.RawLabel(l.name(), ids));
        }
        return out;
    }

    /**
     * 블록 앞에 그림자 틈을 두고 나란히 선 좁은 띠(화정체육관 2층 블록 옆 세로 띠·아래 블록 앞 가로 띠, 사용자 검수 2026-10-19·20)를 그 블록에 붙인다.
     * 띠의 긴 변 80% 이상이 블록의 마주 보는 변과 겹치고 간격이 띠 폭 또는 블록 짧은 변의 30% 이하면, 간격이 가장 작은 블록에 붙인다.
     * 붙은 띠도 다음 띠의 이웃이 된다(휠체어석 막대 너머의 띠). 글자가 없어 AI가 언급하지 않은 도형도 대상이다.
     */
    private static void attachFrontStrips(Map<Integer, ShapeSegmenter.Segment> byId, Map<Integer, String> owner, Map<String, List<Integer>> extra) {
        Map<Integer, String> taken = new LinkedHashMap<>(owner);
        extra.forEach((zone, ids) -> ids.forEach(id -> taken.put(id, zone)));
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, Integer> zoneArea = new LinkedHashMap<>(); // 붙을 띠가 이웃 띠보다 커도, 그 띠가 속한 구역 전체보다 작으면 된다(휠체어석 막대 옆의 더 넓은 띠)
            taken.forEach((id, zone) -> {
                if (byId.containsKey(id)) {
                    zoneArea.merge(zone, byId.get(id).area(), Integer::sum);
                }
            });
            // 비교 대상: 구역에 붙은 조각 하나하나와, 서로 맞닿은 조각을 묶은 구역 블록(분홍 삼각형+파란 조각으로 갈라진 화정체육관 J)
            List<Neighbor> cands = new ArrayList<>();
            taken.forEach((id, zone) -> {
                ShapeSegmenter.Segment q = byId.get(id);
                if (q != null) {
                    cands.add(new Neighbor(zone, box(q), q.area(), q.sat()));
                }
            });
            cands.addAll(zoneBlocks(byId, taken));
            for (ShapeSegmenter.Segment o : byId.values()) {
                // 비스듬히 잘린 띠(I·F 옆)는 상자 비율이 2.5보다 조금 작다. 블록 바로 앞의 납작한 앞줄 조각(화정체육관 M·B 앞 사다리꼴)도 본다
                boolean isStrip = strip(o, 2.2);
                if (taken.containsKey(o.id()) || (!isStrip && !flatFront(o))) {
                    continue;
                }
                int[] a = box(o);
                int along = Math.max(a[2] - a[0], a[3] - a[1]) + 1;
                String bestZone = null;
                int bestGap = Integer.MAX_VALUE;
                double bestCover = -1;
                for (Neighbor q : cands) {
                    if (o.area() > zoneArea.getOrDefault(q.zone(), q.area()) * 1.2) {
                        continue;
                    }
                    int[] b = q.box();
                    int gx = Math.max(0, Math.max(b[0] - a[2], a[0] - b[2]));
                    int gy = Math.max(0, Math.max(b[1] - a[3], a[1] - b[3]));
                    boolean vertical = isStrip && a[3] - a[1] >= a[2] - a[0];
                    // 세로 띠는 옆(x 간격)에, 가로 띠는 위아래(y 간격)에 있어야 하고 긴 변 방향으로 겹쳐야 한다
                    int g = vertical ? gx : gy;
                    int overlap = vertical ? Math.min(a[3], b[3]) - Math.max(a[1], b[1]) : Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
                    if ((vertical ? gy : gx) > 0 || overlap < (isStrip ? 0.8 : 0.95) * along) {
                        continue;
                    }
                    if (!isStrip && (o.area() > q.area() * 0.5 || Math.abs(o.sat() - q.sat()) > 0.25)) {
                        continue; // 앞줄 조각은 블록보다 훨씬 작고 같은 색 계열이어야 한다
                    }
                    int qShort = Math.min(b[2] - b[0], b[3] - b[1]) + 1;
                    if (g > Math.max(shortSide(o), 0.3 * qShort)) {
                        continue;
                    }
                    // 띠가 마주 보는 변을 얼마나 덮는지로 고른다(J 옆 띠는 J의 변을 다 덮고 F1의 긴 변은 40%만 덮는다). 비슷하면 가까운 쪽
                    int side = (vertical ? b[3] - b[1] : b[2] - b[0]) + 1;
                    double cover = Math.min(1.0, (double) overlap / side);
                    if (cover > bestCover + 0.15 || (cover > bestCover - 0.15 && g < bestGap)) {
                        bestCover = Math.max(bestCover, cover);
                        bestGap = g;
                        bestZone = q.zone();
                    }
                }
                if (bestZone != null) {
                    taken.put(o.id(), bestZone);
                    extra.computeIfAbsent(bestZone, k -> new ArrayList<>()).add(o.id());
                    changed = true;
                }
            }
        }
    }

    /** 띠를 붙일 이웃 후보: 구역 이름, 경계 상자, 넓이, 채도. */
    private record Neighbor(String zone, int[] box, int area, double sat) {}

    /** 구역마다 가장 큰 조각과 상자가 맞닿은(간격 2 이하) 조각들을 묶은 블록. 조각이 하나뿐인 구역은 빼고(조각 후보와 같다) 채도는 가장 큰 조각의 것. */
    private static List<Neighbor> zoneBlocks(Map<Integer, ShapeSegmenter.Segment> byId, Map<Integer, String> taken) {
        Map<String, List<ShapeSegmenter.Segment>> byZone = new LinkedHashMap<>();
        taken.forEach((id, zone) -> {
            if (byId.containsKey(id)) {
                byZone.computeIfAbsent(zone, k -> new ArrayList<>()).add(byId.get(id));
            }
        });
        List<Neighbor> out = new ArrayList<>();
        byZone.forEach((zone, segs) -> {
            if (segs.size() < 2) {
                return;
            }
            ShapeSegmenter.Segment main = segs.stream().max(java.util.Comparator.comparingInt(ShapeSegmenter.Segment::area)).orElseThrow();
            int[] u = box(main).clone();
            int area = 0, parts = 0;
            for (ShapeSegmenter.Segment q : segs) {
                int[] b = box(q), m = box(main);
                if (Math.max(Math.max(0, Math.max(b[0] - m[2], m[0] - b[2])), Math.max(0, Math.max(b[1] - m[3], m[1] - b[3]))) <= 2) {
                    u = new int[]{Math.min(u[0], b[0]), Math.min(u[1], b[1]), Math.max(u[2], b[2]), Math.max(u[3], b[3])};
                    area += q.area();
                    parts++;
                }
            }
            if (parts >= 2) {
                out.add(new Neighbor(zone, u, area, main.sat()));
            }
        });
        return out;
    }

    private static int boxArea(ShapeSegmenter.Segment s) {
        int[] b = box(s);
        return (b[2] - b[0] + 1) * (b[3] - b[1] + 1);
    }

    /** 가로가 세로의 1.4배 이상인 납작한 조각(블록 앞줄). */
    private static boolean flatFront(ShapeSegmenter.Segment s) {
        int[] b = box(s);
        return b[2] - b[0] + 1 >= 1.4 * (b[3] - b[1] + 1);
    }

    /** 경계 상자의 긴 변이 짧은 변의 2.5배 이상인 좁은 띠. */
    private static boolean strip(ShapeSegmenter.Segment s) {
        return strip(s, 2.5);
    }

    private static boolean strip(ShapeSegmenter.Segment s, double ratio) {
        int[] b = box(s);
        int bw = b[2] - b[0] + 1, bh = b[3] - b[1] + 1;
        return Math.max(bw, bh) >= ratio * Math.min(bw, bh);
    }

    private static int shortSide(ShapeSegmenter.Segment s) {
        int[] b = box(s);
        return Math.min(b[2] - b[0], b[3] - b[1]) + 1;
    }

    /**
     * 확대 타일 재판독이 두 자리 번호의 한 글자를 빠뜨린 것(213836 T11→T1, T13→T3: 타일 가장자리에 걸린 '1')을 되돌리고 되돌린 개수를 돌려준다.
     * 처음 이름 X에서 한 글자를 뺀 이름 Y로 바뀐 도형은, Y가 처음부터 자기 도형을 가진 구역이면 X로 되돌린다. 그래도 X 구역에 도형이 하나도 남지 않으면
     * Y로 바뀐 도형 중 가장 큰 것을 X로 되돌린다(X의 실제 블록).
     */
    private static int keepDroppedDigit(Map<Integer, String> owner, List<DetailExtractor.RawLabel> labels, Map<Integer, ShapeSegmenter.Segment> byId) {
        Map<Integer, String> first = new java.util.HashMap<>();
        labels.forEach(l -> l.segmentIds().forEach(id -> first.put(id, l.name())));
        // 회색 구조물(콘솔)을 처음에 Y로 읽은 것은 Y의 자기 도형으로 치지 않는다(213836 T3)
        java.util.Set<String> hadOwn = new java.util.HashSet<>();
        first.forEach((id, n) -> {
            if (byId.containsKey(id) && byId.get(id).sat() >= 0.2) {
                hadOwn.add(n);
            }
        });
        int reverted = 0;
        Map<String, List<Integer>> lost = new java.util.HashMap<>();
        for (Map.Entry<Integer, String> e : owner.entrySet()) {
            String x = first.get(e.getKey()), y = e.getValue();
            if (x == null || !droppedOne(x, y)) {
                continue;
            }
            if (hadOwn.contains(y)) {
                e.setValue(x);
                reverted++;
            } else {
                lost.computeIfAbsent(x, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        for (Map.Entry<String, List<Integer>> l : lost.entrySet()) {
            if (owner.containsValue(l.getKey())) {
                continue;
            }
            l.getValue().stream().filter(byId::containsKey).max(java.util.Comparator.comparingInt(id -> byId.get(id).area())).ifPresent(id -> {
                owner.put(id, l.getKey());
                log.info("타일 재판독이 빠뜨린 글자 되돌림: 도형 {} → {}", id, l.getKey());
            });
            reverted++;
        }
        return reverted;
    }

    /** y가 x에서 숫자 한 글자만 뺀 이름이다(T11 → T1, 213 → 23). */
    static boolean droppedOne(String x, String y) {
        if (x.length() != y.length() + 1) {
            return false;
        }
        for (int i = 0; i < x.length(); i++) {
            if (Character.isDigit(x.charAt(i)) && (x.substring(0, i) + x.substring(i + 1)).equals(y)) {
                return true;
            }
        }
        return false;
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
     * 모든 도형(최대 MAX_SEGMENTS개)을 하나씩 크게(320px) 확대한 타일로 다시 읽어 이름을 정한다(8개씩 묶어 병렬로 부른다). 작은 격자 타일에서는 흰 작은 글씨(연한 파랑
     * 블록의 번호 등)를 잘못 읽는 일이 있어서다. 다시 읽은 이름이 구역 코드답거나 1차 이름 목록에 있으면 그 이름을 쓰고, 비었거나 이상하면 처음 읽은 이름을 유지한다.
     */
    private List<DetailExtractor.RawLabel> verifySuspects(List<DetailExtractor.RawLabel> labels, ShapeSegmenter.Result seg,
                                                          ImageSlicer.SliceImage target, List<String> names, Map<Integer, String> rereadOut) {
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
        if (ids.size() > ShapeSegmenter.MAX_SEGMENTS) { // 돔 경기장은 구역이 100개 가까이라 80개로 자르면 뒤쪽(300·400번대)이 다시 읽히지 않는다
            ids = ids.subList(0, ShapeSegmenter.MAX_SEGMENTS);
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
            rereadOut.putAll(reread);
            for (Map.Entry<Integer, String> e : reread.entrySet()) {
                String t = e.getValue() == null ? "" : e.getValue().trim();
                if (t.isEmpty() || isStageName(t)) {
                    continue; // 비었거나 무대 표기면 처음 읽은 이름(또는 좌석 아님)을 유지
                }
                String k = keyOf(t);
                if (k != null && !k.isEmpty() && (known.contains(k) || looksLikeZoneCode(t)) && !t.equals(owner.get(e.getKey()))
                        && !farFromZone(byId.get(e.getKey()), k, owner, byId)) {
                    owner.put(e.getKey(), t);
                    changed++;
                }
            }
        }
        changed -= keepDroppedDigit(owner, labels, byId);
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

    /**
     * 도형을 타일에서 읽은 이름 k 로 바꾸려 할 때, 그 구역의 기존 조각이 있는데 모두 8px 넘게 떨어져 있으면 true. 글자 없는 띠의 확대 타일
     * 가장자리에 옆 구역 글자가 걸려 그 이름으로 읽힌 것이다(화정체육관 J 옆 띠가 F1로 읽힘). 이런 띠는 앞쪽 띠 규칙이 맞는 블록에 붙인다.
     */
    private static boolean farFromZone(ShapeSegmenter.Segment s, String k, Map<Integer, String> owner, Map<Integer, ShapeSegmenter.Segment> byId) {
        if (s == null) {
            return false;
        }
        boolean any = false;
        for (Map.Entry<Integer, String> o : owner.entrySet()) {
            ShapeSegmenter.Segment q = byId.get(o.getKey());
            if (q == null || !k.equals(keyOf(o.getValue()))) {
                continue;
            }
            any = true;
            if (gap(s, q) <= 8) {
                return false;
            }
        }
        return any;
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
            if (c == null || k == null || k.isEmpty() || c.length() > 12 || isStageName(c) || isLegendName(c) || (!known.containsKey(k) && !looksLikeZoneCode(c))) {
                continue;
            }
            // 목록에 없는 이름이 목록 이름의 앞부분일 뿐이면(D01·D02가 있는데 'D') 글자가 잘려 읽힌 것이라 버린다
            if (!known.containsKey(k) && known.keySet().stream().anyMatch(n -> n.length() > k.length() && n.startsWith(k))) {
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

    /** 좌석 종류 범례 글자('2층 지정석'·'1층 스탠딩석' 등). 범례 칩 도형은 구역 모양이 아니므로 짝짓지 않는다(이름은 목록에 남을 수 있다). */
    static boolean isLegendName(String name) {
        String n = name.replaceAll("\\s+", "").replaceAll("[)\\]}]+$", "");
        // '~석'으로 끝나는 이름(VIP석·시야제한석·'♿(VIP석)' 등)은 좌석 등급 범례 표기다(등급 인식은 하지 않는다, 사용자 결정 2026-10-19)
        return n.matches("(B?\\d+층|\\d+F)?(스탠딩석|지정석|일반석|입석|좌석)") || n.endsWith("석");
    }

    /** 타일에서 새로 읽은 이름이 구역 코드답다: 4자 이하이고 숫자나 라틴 글자를 포함(한글만 있는 안내 글자 '좌석'·'안내'·'최신순' 등은 아니다). */
    static boolean looksLikeZoneCode(String name) {
        String n = name.replaceAll("\s+", "");
        // 이름 목록에 빠진 넓은 스탠딩 구역(STANDING R·스탠딩 A 등)도 구역으로 본다
        return (n.length() <= 5 && n.matches(".*[0-9A-Za-z].*")) || n.toUpperCase(java.util.Locale.ROOT).matches("(STANDING|스탠딩).{0,4}");
    }

    /** names 에 없는 구역 이름(zoneKey 기준)을 shapes 에서 찾아 뒤에 붙인다(무대·플로어 표기와 안전 한도 초과분 제외). */
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

    /** 점검용: 후처리(회색 빼고 다시 나누기·이웃 붙이기·타일 다시 읽기)가 끝난 도형 윤곽과 구역별 번호를 final.json 에 남긴다. */
    private void writeFinalDebug(ShapeSegmenter.Result seg, List<DetailExtractor.RawLabel> labels) {
        String dir = properties.getDebugDir();
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder("{\"segments\":{");
            boolean first = true;
            for (ShapeSegmenter.Segment s : seg.segments()) {
                sb.append(first ? "" : ",").append('"').append(s.id()).append("\":[");
                for (int i = 0; i < s.pixels().size(); i++) {
                    sb.append(i == 0 ? "" : ",").append('[').append(s.pixels().get(i)[0]).append(',').append(s.pixels().get(i)[1]).append(']');
                }
                sb.append(']');
                first = false;
            }
            sb.append("},\"zones\":{");
            first = true;
            for (DetailExtractor.RawLabel l : labels) {
                sb.append(first ? "" : ",").append('"').append(l.name().replace("\"", "")).append("\":").append(l.segmentIds());
                first = false;
            }
            sb.append("}}");
            java.nio.file.Files.writeString(java.nio.file.Path.of(dir).resolve("final.json"), sb.toString());
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
        // 구역에 연결된 조각 대부분(70% 이상)이 색 있는 블록인 지도에서는, 색 있는 조각 없이 크고 회색인 조각만 가진 구역의 그 조각은 통로·구조물이다
        List<ShapeSegmenter.Segment> linked = new ArrayList<>();
        for (DetailExtractor.RawLabel l : labels) {
            for (Integer id : l.segmentIds() == null ? List.<Integer>of() : l.segmentIds()) {
                if (id != null && byId.containsKey(id)) {
                    linked.add(byId.get(id));
                }
            }
        }
        long colored = linked.stream().filter(s -> s.sat() >= 0.3).count();
        boolean colorfulMap = !linked.isEmpty() && colored >= 0.7 * linked.size();
        int[] linkedAreas = linked.stream().mapToInt(ShapeSegmenter.Segment::area).sorted().toArray();
        int linkedMedian = linkedAreas.length == 0 ? 0 : linkedAreas[linkedAreas.length / 2];
        for (DetailExtractor.RawLabel l : labels) {
            List<List<List<Integer>>> polys = new ArrayList<>();
            ShapeSegmenter.Segment main = null; // 구역의 대표 조각(색 있는 가장 큰 조각)
            for (Integer id : l.segmentIds() == null ? List.<Integer>of() : l.segmentIds()) {
                ShapeSegmenter.Segment s = id == null ? null : byId.get(id);
                if (s != null && s.sat() >= 0.3 && (main == null || s.area() > main.area())) {
                    main = s;
                }
            }
            for (Integer id : l.segmentIds() == null ? List.<Integer>of() : l.segmentIds()) {
                ShapeSegmenter.Segment s = id == null ? null : byId.get(id);
                if (s == null || polys.size() >= MAX_POLYGONS || !used.add(id)) {
                    continue;
                }
                // 색 있는 블록 구역에 떨어져 붙은 회색 덩어리(고척돔 회색 통로가 옆 글자로 'T12'로 읽힘)는 버린다. 블록 옆에 붙은 회색 띠는 남는다
                if (main != null && s != main && s.sat() < 0.2 && !strip(s) && (gap(s, main) > 10 || s.area() > main.area())) {
                    continue;
                }
                if (main == null && colorfulMap && s.sat() < 0.2 && s.area() > linkedMedian * 2) {
                    continue;
                }
                // 경계 상자의 35%도 못 채우는 가는 선 모양 조각(고척돔 무대 둘레의 빛 번짐 선이 옆 블록 이름으로 읽힘)은 블록이 있는 구역에 더하지 않는다
                // 띠가 아닌데 대표 조각보다 훨씬 어두운(밝기 60% 미만) 조각도 같은 이유로 뺀다(빛 번짐 선은 블록보다 어둡다)
                if (main != null && s != main && (ShapeSegmenter.loose(s) || s.area() < 0.35 * boxArea(s) || (!strip(s) && s.val() < 0.6 * main.val()))) {
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
                out.add(new DetailExtractor.RawZone(l.name(), joinParts(dropSlivers(polys))));
            }
        }
        return out;
    }

    /**
     * 한 구역이 색으로 갈라진 조각들(예: VIP 부분 + 일반석 부분, 화정체육관 J의 분홍 삼각형 + 파란 부분)을 틈 없는 하나의 블록으로 합친다:
     * 서로 맞닿은 조각 묶음마다, 볼록 껍질 넓이가 조각 넓이 합의 1.6배 이하(한 블록을 이룸)이면 조각들을 합친 외곽선(색 경계 틈은 메우고 블록의 홈은
     * 남긴다)으로 바꾼다. 틈을 두고 떨어진 조각(블록 옆·앞 띠)은 따로 둔다(사용자 검수: 띠는 블록과 별도 조각).
     */
    static List<List<List<Integer>>> joinParts(List<List<List<Integer>>> polys) {
        if (polys.size() < 2) {
            return polys;
        }
        int n = polys.size();
        int[] group = new int[n];
        java.util.Arrays.fill(group, -1);
        int groups = 0;
        for (int i = 0; i < n; i++) {
            if (group[i] >= 0) {
                continue;
            }
            group[i] = groups;
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(List.of(i));
            while (!queue.isEmpty()) {
                int a = queue.poll();
                for (int b = 0; b < n; b++) {
                    if (group[b] < 0 && boxGap(polys.get(a), polys.get(b)) <= 12) {
                        group[b] = groups;
                        queue.add(b);
                    }
                }
            }
            groups++;
        }
        List<List<List<Integer>>> out = new ArrayList<>();
        for (int g = 0; g < groups; g++) {
            List<List<List<Integer>>> part = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (group[i] == g) {
                    part.add(polys.get(i));
                }
            }
            out.addAll(part.size() < 2 ? part : joinTouching(part));
        }
        return out;
    }

    /** 맞닿은 조각 묶음을 한 블록으로 합친다(한 블록답지 않으면 그대로 돌려준다). */
    private static List<List<List<Integer>>> joinTouching(List<List<List<Integer>>> polys) {
        // 가는 띠(상자 2.2배 이상)가 다른 조각과 맞닿지 않고 틈(흰 선)을 두고 있으면 앞쪽 띠라 합치지 않는다(화정체육관 E 옆 연두 띠, 블록과 2px 틈)
        List<List<List<Integer>>> strips = new ArrayList<>(), rest = new ArrayList<>();
        for (List<List<Integer>> p : polys) {
            boolean apart = polys.stream().allMatch(q -> q == p || boxGap(p, q) >= 2);
            (apart && thinBox(p) ? strips : rest).add(p);
        }
        if (!strips.isEmpty()) {
            List<List<List<Integer>>> out = new ArrayList<>(rest.size() < 2 ? rest : joinTouching(rest));
            out.addAll(strips);
            return out;
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
        if (hull.size() < 3 || polygonArea(toList(hull)) > sum * 1.6) {
            return polys;
        }
        List<List<Integer>> union = unionOutline(polys);
        if (union != null) {
            return List.of(union);
        }
        // 합집합이 갈라지면(블록과 틈을 둔 앞줄 조각) 따로 두되, 상자가 서로 겹치는 조각들(숫자 원이 가른 블록)은 볼록 껍질로 합친다
        for (int i = 0; i < polys.size(); i++) {
            for (int j = i + 1; j < polys.size(); j++) {
                if (boxGap(polys.get(i), polys.get(j)) > 3) {
                    return polys;
                }
            }
        }
        return List.of(toList(hull));
    }

    /**
     * 조각들의 합집합 외곽선. 색 경계의 얇은 틈은 조각마다 테두리를 1.5(0~1000 좌표)씩 덧칠해 메우고(흰 선으로 나뉜 앞줄 띠는 이어지지 않는다), 바깥 외곽선(가장 넓은 고리)만 꼭짓점 40개 이하로 줄여 쓴다.
     * 합집합이 두 덩어리 이상으로 갈리면 null.
     */
    static List<List<Integer>> unionOutline(List<List<List<Integer>>> polys) {
        java.awt.geom.Area area = new java.awt.geom.Area();
        java.awt.BasicStroke edge = new java.awt.BasicStroke(3f, java.awt.BasicStroke.CAP_SQUARE, java.awt.BasicStroke.JOIN_MITER);
        double total = 0;
        for (List<List<Integer>> p : polys) {
            java.awt.geom.Path2D.Double path = new java.awt.geom.Path2D.Double();
            for (int i = 0; i < p.size(); i++) {
                if (i == 0) {
                    path.moveTo(p.get(i).get(0), p.get(i).get(1));
                } else {
                    path.lineTo(p.get(i).get(0), p.get(i).get(1));
                }
            }
            path.closePath();
            area.add(new java.awt.geom.Area(path));
            area.add(new java.awt.geom.Area(edge.createStrokedShape(path)));
            total += polygonArea(p);
        }
        List<List<int[]>> rings = new ArrayList<>();
        List<int[]> cur = new ArrayList<>();
        double[] c = new double[6];
        for (java.awt.geom.PathIterator it = area.getPathIterator(null, 0.5); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == java.awt.geom.PathIterator.SEG_MOVETO) {
                cur = new ArrayList<>();
                rings.add(cur);
            }
            if (t != java.awt.geom.PathIterator.SEG_CLOSE) {
                cur.add(new int[]{(int) Math.round(c[0]), (int) Math.round(c[1])});
            }
        }
        List<int[]> best = null;
        double bestArea = 0;
        for (List<int[]> r : rings) {
            double a = Math.abs(ShapeSegmenter.shoelaceArea(r));
            if (a > bestArea) {
                bestArea = a;
                best = r;
            }
        }
        if (best == null || best.size() < 3 || bestArea < 0.85 * total) {
            return null;
        }
        java.awt.Polygon outer = new java.awt.Polygon();
        best.forEach(pt -> outer.addPoint(pt[0], pt[1]));
        for (List<int[]> r : rings) { // 바깥 고리 밖에 다른 고리가 있으면 두 덩어리다(안쪽 고리는 블록의 구멍)
            if (r != best && Math.abs(ShapeSegmenter.shoelaceArea(r)) > 0.02 * total && !outer.contains(r.get(0)[0], r.get(0)[1])) {
                return null;
            }
        }
        List<int[]> simple = ShapeSegmenter.simplify(best);
        if (simple.size() < 3) {
            return null;
        }
        List<List<Integer>> out = toList(simple);
        for (List<Integer> pt : out) {
            pt.set(0, Math.max(0, Math.min(1000, pt.get(0))));
            pt.set(1, Math.max(0, Math.min(1000, pt.get(1))));
        }
        return out;
    }

    private static List<List<Integer>> toList(List<int[]> pts) {
        List<List<Integer>> out = new ArrayList<>();
        for (int[] p : pts) {
            out.add(new ArrayList<>(List.of(p[0], p[1])));
        }
        return out;
    }

    /**
     * 같은 구역의 큰 조각에 맞붙은(경계 상자가 닿거나 겹치고 큰 조각 상자 안쪽 6 이내) 아주 작은 조각(큰 조각의 15% 미만)은 블록 가장자리 그림자·색 경계
     * 찌꺼기다(화정체육관 N 윗변의 가는 조각). 떨어져 있는 작은 조각(I·F 귀퉁이 삼각형, 띠)은 남긴다.
     */
    static List<List<List<Integer>>> dropSlivers(List<List<List<Integer>>> polys) {
        if (polys.size() < 2) {
            return polys;
        }
        List<List<List<Integer>>> out = new ArrayList<>();
        for (List<List<Integer>> p : polys) {
            double a = polygonArea(p);
            int[] pb = boxOf(p);
            boolean sliver = false;
            for (List<List<Integer>> q : polys) {
                int[] qb = boxOf(q);
                if (q != p && a < polygonArea(q) * 0.15 && boxGap(p, q) == 0
                        && pb[0] >= qb[0] - 6 && pb[1] >= qb[1] - 6 && pb[2] <= qb[2] + 6 && pb[3] <= qb[3] + 6) {
                    sliver = true;
                    break;
                }
            }
            if (!sliver) {
                out.add(p);
            }
        }
        return out;
    }

    /** 두 다각형(0~1000 좌표)의 경계 상자 간격(겹치면 0). */
    private static int boxGap(List<List<Integer>> a, List<List<Integer>> b) {
        int[] p = boxOf(a), q = boxOf(b);
        return Math.max(Math.max(0, Math.max(q[0] - p[2], p[0] - q[2])), Math.max(0, Math.max(q[1] - p[3], p[1] - q[3])));
    }

    private static boolean thinBox(List<List<Integer>> poly) {
        int[] b = boxOf(poly);
        int w = b[2] - b[0] + 1, h = b[3] - b[1] + 1;
        return Math.max(w, h) >= 2.2 * Math.min(w, h);
    }

    private static int[] boxOf(List<List<Integer>> poly) {
        int x0 = 1000, y0 = 1000, x1 = 0, y1 = 0;
        for (List<Integer> pt : poly) {
            x0 = Math.min(x0, pt.get(0));
            y0 = Math.min(y0, pt.get(1));
            x1 = Math.max(x1, pt.get(0));
            y1 = Math.max(y1, pt.get(1));
        }
        return new int[]{x0, y0, x1, y1};
    }

    private Response build(List<SessionDto> rule, List<SessionDto> ai, List<ZoneDto> zones, Double aspect,
                           boolean aiEnabled, boolean aiError, boolean truncated) {
        List<SessionDto> sessions;
        String source;
        // AI 가 본문 글자와 이미지의 맥락으로 읽은 회차를 먼저 쓰고, 규칙(정규식) 결과는 AI 가 못 줄 때만 쓴다(사용자 지시 2026-10-22)
        if (!ai.isEmpty()) {
            sessions = ai;
            source = "AI";
        } else if (!rule.isEmpty()) {
            sessions = rule;
            source = "TEXT_RULE";
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

    static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (s.isEmpty() || s.codePointCount(0, s.length()) > MAX_ZONE_NAME) {
            return null;
        }
        return s;
    }

    static String keyOf(String name) {
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
                || n.contains("MIXER") || n.contains("휠체어")
                || n.matches("B?\\d+F|\\d+층"); // 층 표시(2F·3F·B1F·2층) 원 표지
    }

    /** 공백 정리·30자 이하·zoneKey 중복 제거(첫 표기 유지)·안전 한도(1,000개)까지. */
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

    /** 메모리 캐시 → DB 보관 순으로 찾는다. DB 에서 찾으면 남은 기간 동안 메모리에도 둔다. */
    private Response cacheGet(String key) {
        synchronized (cache) {
            CacheEntry e = cache.get(key);
            if (e != null && e.expiresAtMillis() > clock.millis()) {
                return e.response();
            }
            if (e != null) {
                cache.remove(key);
            }
        }
        if (store == null) {
            return null;
        }
        Response stored = store.find(key).orElse(null);
        if (stored != null) {
            synchronized (cache) {
                cache.put(key, new CacheEntry(stored, clock.millis() + ttlMillis()));
            }
        }
        return stored;
    }

    /** 성공 결과 중 읽은 것이 있을 때만 보관한다(빈 결과를 붙들지 않는다). 메모리와 DB(V23) 둘 다. */
    private void cacheIfUseful(String key, Response r) {
        if (r.sessions().isEmpty() && r.zones().isEmpty()) {
            return;
        }
        synchronized (cache) {
            cache.put(key, new CacheEntry(r, clock.millis() + ttlMillis()));
        }
        if (store != null) {
            store.save(key, r, ttlMillis());
        }
    }

    private long ttlMillis() {
        return properties.getCacheTtlHours() * 3_600_000L;
    }
}
