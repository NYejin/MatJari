package com.matjari.service.aiextract;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/**
 * 구역 인식 모델 비교 평가 도구(기본은 건너뜀). 환경변수 EVAL_DIR(공연별 폴더가 든 데이터 폴더)·EVAL_MODE·EVAL_MODEL 이 있을 때만 실행한다.
 * <ul>
 *   <li>locate: 공연 폴더의 상세 이미지(img*.jpg|png)에서 운영과 같은 방식으로 좌석표를 찾아 seatmap.jpg 로 저장한다(정답 작성용, 한 번만).</li>
 *   <li>predict: 공연 폴더의 seatmap.jpg 로 {@link DetailExtractionService#evalSeatMap}을 실행해 pred/{모델}.json 에 구역·시간·토큰을 저장한다.</li>
 * </ul>
 * EVAL_IDS(쉼표 구분)로 공연을 고를 수 있다. 실제 API 를 부르므로 비용이 든다.
 */
class ZoneModelEvalTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOKENS = Pattern.compile("inputTokens=(\\d+), outputTokens=(\\d+)");

    @Test
    @EnabledIfEnvironmentVariable(named = "EVAL_DIR", matches = ".+")
    void run() throws Exception {
        Path dir = Path.of(System.getenv("EVAL_DIR"));
        String mode = System.getenv().getOrDefault("EVAL_MODE", "predict");
        String model = System.getenv().getOrDefault("EVAL_MODEL", "claude-haiku-5-5");
        AiProperties props = new AiProperties();
        props.setModel(model);
        // ocr: Haiku 대신 로컬 무료 OCR 서버(EVAL_OCR_URL, 기본 http://127.0.0.1:8765)로 이름을 읽는다(비용 없음)
        DetailExtractor extractor = model.startsWith("gemini") ? new GeminiDetailExtractor(model)
                : model.startsWith("ocr") ? new OcrDetailExtractor(System.getenv().getOrDefault("EVAL_OCR_URL", "http://127.0.0.1:8765"))
                : new AnthropicDetailExtractor(props);
        String cacheDir = System.getenv("EVAL_CACHE"); // 주면 AI 응답을 저장·재사용한다(EVAL_CACHE_ONLY=1 이면 저장본이 없을 때 API 를 부르지 않는다)
        CachingDetailExtractor cache = cacheDir == null || cacheDir.isBlank() ? null
                : new CachingDetailExtractor(extractor, Path.of(cacheDir, model), "1".equals(System.getenv("EVAL_CACHE_ONLY")));
        if (cache != null) {
            extractor = cache;
        }
        ImageSlicer slicer = new ImageSlicer(props);
        DetailExtractionService service = new DetailExtractionService(null, null, null, null, slicer, new ShapeSegmenter(), extractor,
                null, props, Clock.systemUTC());

        Logger aiLog = (Logger) LoggerFactory.getLogger(AnthropicDetailExtractor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        aiLog.addAppender(appender);

        List<Path> perf = new ArrayList<>();
        String ids = System.getenv("EVAL_IDS");
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory).sorted().forEach(perf::add);
        }
        for (Path p : perf) {
            if (ids != null && !ids.isBlank() && !List.of(ids.split(",")).contains(p.getFileName().toString())) {
                continue;
            }
            appender.list.clear();
            int missesBefore = 0;
            if (cache != null) {
                cache.resetCounters();
                missesBefore = cache.misses.get();
            }
            long started = System.nanoTime();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("model", model);
            try {
                if ("locate".equals(mode)) {
                    locate(p, slicer, extractor, out);
                } else if ("regularizeGt".equals(mode)) { // 정답 도형에 다듬기·병합만 적용해 그 단계의 모양 손실을 잰다(API 호출 없음)
                    com.fasterxml.jackson.databind.JsonNode gt = JSON.readTree(p.resolve("gt.json").toFile());
                    double gw = gt.get("width").asDouble(), gh = gt.get("height").asDouble();
                    List<DetailExtractor.RawZone> raw = new ArrayList<>();
                    List<String> names = new ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode z : gt.get("zones")) {
                        String name = z.get("name").asText().split("\\|")[0];
                        List<List<List<Integer>>> polys = new ArrayList<>();
                        for (com.fasterxml.jackson.databind.JsonNode poly : z.path("polygons")) {
                            List<List<Integer>> pts = new ArrayList<>();
                            poly.forEach(pt -> pts.add(List.of((int) Math.round(pt.get(0).asDouble() * 1000 / gw), (int) Math.round(pt.get(1).asDouble() * 1000 / gh))));
                            polys.add(pts);
                        }
                        if (!polys.isEmpty()) {
                            names.add(name);
                            raw.add(new DetailExtractor.RawZone(name, polys));
                        }
                    }
                    boolean skip = "1".equals(System.getenv("EVAL_NO_REG"));
                    String steps = System.getenv("EVAL_REG_STEPS"); // 쉼표 목록 shape,sym,align: 다듬기 단계별 손실을 따로 잰다
                    out.put("zones", DetailExtractionService.mergeZones(names, skip ? raw : steps != null ? regularizeSteps(raw, steps) : ShapeRegularizer.regularize(raw)));
                } else if ("regularize".equals(mode)) { // 저장된 예측(EVAL_FROM 태그)에 도형 다듬기만 다시 적용한다(API 호출 없음)
                    Path from = p.resolve("pred").resolve(model + "@" + System.getenv("EVAL_FROM") + ".json");
                    if (!Files.exists(from)) {
                        continue;
                    }
                    com.fasterxml.jackson.databind.JsonNode src = JSON.readTree(from.toFile());
                    List<DetailExtractor.RawZone> raw = new ArrayList<>();
                    List<String> names = new ArrayList<>();
                    for (com.fasterxml.jackson.databind.JsonNode z : src.get("zones")) {
                        names.add(z.get("name").asText());
                        List<List<List<Integer>>> polys = new ArrayList<>();
                        for (com.fasterxml.jackson.databind.JsonNode poly : z.path("shapePolygons")) {
                            List<List<Integer>> pts = new ArrayList<>();
                            poly.forEach(pt -> pts.add(List.of(pt.get(0).asInt(), pt.get(1).asInt())));
                            polys.add(pts);
                        }
                        raw.add(new DetailExtractor.RawZone(z.get("name").asText(), polys));
                    }
                    out.put("zones", DetailExtractionService.mergeZones(names, ShapeRegularizer.regularize(raw)));
                } else {
                    String debug = System.getenv("EVAL_DEBUG"); // 주면 공연별 하위 폴더에 번호 이미지·AI 연결 결과를 남긴다
                    props.setDebugDir(debug == null || debug.isBlank() ? "" : Path.of(debug, p.getFileName().toString()).toString());
                    byte[] jpeg = Files.readAllBytes(p.resolve("seatmap.jpg"));
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
                    DetailExtractionService run = service;
                    String segSrc = System.getenv("EVAL_SEG"); // gt: 정답 도형(상한 측정) / 그 밖: seg/{값}.json 의 도형(학습 모델 등)
                    if (segSrc != null && !segSrc.isBlank()) {
                        List<List<int[]>> polys = externalPolygons(p, segSrc);
                        boolean fill = "1".equals(System.getenv("EVAL_SEG_FILL")); // 바깥 도형 먼저, 그것이 못 덮은 자리는 Java 도형으로 채운다
                        ShapeSegmenter fixed = new ShapeSegmenter() {
                            @Override
                            public Result segment(byte[] j, boolean dropGray) {
                                if (!fill) {
                                    return fromPolygons(j, polys);
                                }
                                Result own = super.segment(j, dropGray);
                                List<List<int[]>> merged = new ArrayList<>(polys);
                                List<java.awt.Polygon> ext = polys.stream().map(ZoneModelEvalTest::awt).toList();
                                for (Segment s : own == null ? List.<Segment>of() : own.segments()) {
                                    if (ext.stream().noneMatch(e -> e.contains(s.cx(), s.cy()))) {
                                        merged.add(s.pixels());
                                    }
                                }
                                return fromPolygons(j, merged);
                            }
                        };
                        run = new DetailExtractionService(null, null, null, null, slicer, fixed, extractor, null, props, Clock.systemUTC());
                    }
                    DetailExtractionService.SeatMapEval r = run.evalSeatMap(jpeg, img.getWidth(), img.getHeight());
                    out.put("firstNames", r.firstNames());
                    out.put("zones", r.zones());
                    out.put("usedFallback", r.usedFallback());
                }
            } catch (RuntimeException e) {
                out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            long in = 0, outTok = 0;
            int calls = 0;
            for (ILoggingEvent ev : List.copyOf(appender.list)) {
                Matcher m = TOKENS.matcher(ev.getFormattedMessage());
                if (m.find()) {
                    in += Long.parseLong(m.group(1));
                    outTok += Long.parseLong(m.group(2));
                    calls++;
                }
            }
            out.put("ms", (System.nanoTime() - started) / 1_000_000);
            out.put("calls", calls);
            out.put("inputTokens", in);
            out.put("outputTokens", outTok);
            String tag = System.getenv().getOrDefault("EVAL_TAG", ""); // 같은 모델을 여러 번 돌릴 때 결과 파일을 나눈다(예: r1, r2)
            out.put("rep", tag);
            if (cache != null) {
                out.put("cacheMisses", cache.misses.get() - missesBefore);
            }
            Path target = "locate".equals(mode) ? p.resolve("locate.json") : p.resolve("pred").resolve(model + (tag.isEmpty() ? "" : "@" + tag) + ".json");
            Files.createDirectories(target.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), out);
            System.out.println("EVAL " + mode + " " + model + " " + p.getFileName() + " ms=" + out.get("ms") + " calls=" + calls
                    + (cache != null ? " cacheMisses=" + out.get("cacheMisses") : "")
                    + (out.containsKey("error") ? " error=" + out.get("error") : ""));
        }
    }

    private static java.awt.Polygon awt(List<int[]> pts) {
        java.awt.Polygon p = new java.awt.Polygon();
        pts.forEach(v -> p.addPoint(v[0], v[1]));
        return p;
    }

    /** ShapeRegularizer.regularize 와 같은 순서로, 고른 단계만 적용한다. */
    private static List<DetailExtractor.RawZone> regularizeSteps(List<DetailExtractor.RawZone> zones, String steps) {
        List<String> on = List.of(steps.split(","));
        List<int[][]> all = new ArrayList<>();
        List<Integer> zoneOf = new ArrayList<>();
        for (int zi = 0; zi < zones.size(); zi++) {
            for (List<List<Integer>> poly : zones.get(zi).polygons()) {
                int[][] a = poly.stream().map(pt -> new int[]{pt.get(0), pt.get(1)}).toArray(int[][]::new);
                all.add(on.contains("shape") ? ShapeRegularizer.shape(a) : a);
                zoneOf.add(zi);
            }
        }
        if (on.contains("sym")) {
            ShapeRegularizer.symmetrize(all, zoneOf);
        }
        if (on.contains("align")) {
            ShapeRegularizer.alignNeighbors(all, zoneOf);
        }
        List<DetailExtractor.RawZone> out = new ArrayList<>();
        int k = 0;
        for (DetailExtractor.RawZone z : zones) {
            List<List<List<Integer>>> polys = new ArrayList<>();
            for (int i = 0; i < z.polygons().size(); i++) {
                List<List<Integer>> pts = new ArrayList<>();
                for (int[] v : all.get(k)) {
                    pts.add(List.of(v[0], v[1]));
                }
                k++;
                polys.add(pts);
            }
            out.add(new DetailExtractor.RawZone(z.name(), polys));
        }
        return out;
    }

    /** gt.json 의 구역 조각(gt) 또는 seg/{이름}.json 의 polygons([[[x,y],...],...], 원본 픽셀)를 읽는다. */
    private static List<List<int[]>> externalPolygons(Path p, String src) throws Exception {
        List<List<int[]>> out = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(("gt".equals(src) ? p.resolve("gt.json") : p.resolve("seg").resolve(src + ".json")).toFile());
        List<com.fasterxml.jackson.databind.JsonNode> polys = new ArrayList<>();
        if ("gt".equals(src)) {
            root.get("zones").forEach(z -> z.path("polygons").forEach(polys::add));
        } else {
            root.get("polygons").forEach(polys::add);
        }
        for (com.fasterxml.jackson.databind.JsonNode poly : polys) {
            List<int[]> pts = new ArrayList<>();
            poly.forEach(pt -> pts.add(new int[]{pt.get(0).asInt(), pt.get(1).asInt()}));
            out.add(pts);
        }
        return out;
    }

    /** 운영 extract()의 좌석표 찾기와 같은 순서: 1차 호출로 이미지·조각 범위 → 합치기 → 띠 단위로 다시 좁히기. */
    private static void locate(Path p, ImageSlicer slicer, DetailExtractor extractor, Map<String, Object> out) throws Exception {
        List<byte[]> images = new ArrayList<>();
        try (Stream<Path> s = Files.list(p)) {
            for (Path f : s.filter(f -> f.getFileName().toString().matches("img\\d+\\.(jpg|png)")).sorted().toList()) {
                images.add(Files.readAllBytes(f));
            }
        }
        List<ImageSlicer.SliceResult> sliced = new ArrayList<>();
        List<DetailExtractor.Part> parts = new ArrayList<>();
        for (int n = 0; n < images.size(); n++) {
            ImageSlicer.SliceResult r = slicer.slice(images.get(n));
            sliced.add(r);
            for (int i = 0; i < r.slices().size(); i++) {
                parts.add(DetailExtractor.Part.ofText("[이미지 " + (n + 1) + " · 조각 " + (i + 1) + "/" + r.slices().size() + "]"));
                parts.add(DetailExtractor.Part.ofImage(r.slices().get(i).jpeg()));
            }
        }
        DetailExtractor.FirstPass first = extractor.first(parts);
        out.put("firstNames", first.zones());
        DetailExtractor.SeatMapLocation loc = first.seatMap();
        out.put("found", loc != null && loc.found());
        if (loc == null || !loc.found() || loc.imageIndex() < 1 || loc.imageIndex() > images.size()) {
            return;
        }
        List<ImageSlicer.SliceImage> sl = sliced.get(loc.imageIndex() - 1).slices();
        int a = Math.max(1, Math.min(sl.size(), Math.min(loc.firstSlice(), loc.lastSlice())));
        int b = Math.max(1, Math.min(sl.size(), Math.max(loc.firstSlice(), loc.lastSlice())));
        byte[] src = images.get(loc.imageIndex() - 1);
        ImageSlicer.SliceImage merged = slicer.crop(src, sl.get(a - 1).top(), sl.get(b - 1).top() + sl.get(b - 1).height());
        ImageSlicer.SliceImage target = merged;
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
                int yTop = Math.max(0, s1.top());
                int yBottom = Math.min(merged.height(), s2.top() + s2.height());
                if (yBottom - yTop >= 200) {
                    target = slicer.crop(src, sl.get(a - 1).top() + yTop, sl.get(a - 1).top() + yBottom);
                }
            }
        }
        Files.write(p.resolve("seatmap.jpg"), target.jpeg());
        out.put("image", loc.imageIndex());
        out.put("size", List.of(target.width(), target.height()));
    }
}
