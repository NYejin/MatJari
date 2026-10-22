package com.matjari.service.aiextract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 사람이 검수한 구역 정답(정답검수 탭, 멜론 38장, 리소스 aiextract/verified-zones.json)을 그대로 쓴다(사용자 지시 2026-10-22).
 * 링크의 상품 번호(SourceKeyResolver 키)가 같고, 지금 페이지의 상세 이미지 주소가 검수 때 쓴 이미지 주소를 모두 포함할 때만
 * (= 좌석표 사진이 같을 때만) 일치로 본다. 일치하면 AI 를 부르지 않는다. 좌표는 검수 좌석표 픽셀 기준이라 AI 결과와 같은 방식으로
 * 0~1000 · 좌석표 영역 맞춤(fitToBounds)으로 바꾼다. 이미지는 저장소에 두지 않고 주소·좌표만 둔다.
 */
@Component
public class VerifiedZones {

    private static final Logger log = LoggerFactory.getLogger(VerifiedZones.class);
    private static final String RESOURCE = "/aiextract/verified-zones.json";

    record Entry(String key, List<String> images, int width, int height, List<Zone> zones) {}

    record Zone(String name, List<List<List<Integer>>> polygons) {}

    /** 검수 정답 구역과 지도 비율. */
    public record Match(List<DetailExtractionService.ZoneDto> zones, Double aspect) {}

    private final Map<String, Entry> byKey;
    private final DetailImageFetcher imageFetcher;

    @org.springframework.beans.factory.annotation.Autowired
    public VerifiedZones(DetailImageFetcher imageFetcher) {
        this(imageFetcher, load());
    }

    VerifiedZones(DetailImageFetcher imageFetcher, List<Entry> entries) {
        this.imageFetcher = imageFetcher;
        Map<String, Entry> m = new HashMap<>();
        for (Entry e : entries) {
            m.put(e.key(), e);
        }
        this.byKey = Map.copyOf(m);
    }

    private static List<Entry> load() {
        try (InputStream in = VerifiedZones.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return List.of();
            }
            List<Entry> list = new ObjectMapper().readValue(in, new TypeReference<List<Entry>>() {});
            log.info("검수 구역 정답 {}장 불러옴", list.size());
            return list;
        } catch (Exception e) {
            log.warn("검수 구역 정답을 읽지 못함: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    public boolean has(String sourceKey) {
        return byKey.containsKey(sourceKey);
    }

    public Optional<Match> find(String site, String sourceKey, Document page) {
        Entry e = byKey.get(sourceKey);
        if (e == null || page == null) {
            return Optional.empty();
        }
        Set<String> onPage = new HashSet<>();
        for (URI u : imageFetcher.candidateUris(page)) { // 지금은 멜론만(검수 정답도 멜론뿐)
            onPage.add(imageKey(u));
        }
        for (String img : e.images()) {
            URI u = URI.create(img);
            if (!onPage.contains(imageKey(u))) {
                log.info("검수 정답 있음, 좌석표 이미지가 달라 쓰지 않음 ({})", sourceKey);
                return Optional.empty();
            }
        }
        List<DetailExtractionService.ZoneDto> zones = new ArrayList<>();
        for (Zone z : e.zones()) {
            List<List<List<Integer>>> polys = new ArrayList<>();
            for (List<List<Integer>> p : z.polygons()) {
                List<List<Integer>> np = new ArrayList<>();
                for (List<Integer> pt : p) {
                    np.add(List.of(Math.round(pt.get(0) * 1000f / e.width()), Math.round(pt.get(1) * 1000f / e.height())));
                }
                polys.add(List.copyOf(np));
            }
            zones.add(new DetailExtractionService.ZoneDto(z.name(), polys.isEmpty() ? null : List.copyOf(polys)));
        }
        Double aspect = Math.round(e.width() * 1000.0 / e.height()) / 1000.0;
        DetailExtractionService.FitResult fit = DetailExtractionService.fitToBounds(zones, e.width(), e.height());
        if (fit != null) {
            zones = fit.zones();
            aspect = fit.aspect();
        }
        log.info("검수 구역 정답 사용 ({}): 구역 {}개", sourceKey, zones.size());
        return Optional.of(new Match(List.copyOf(zones), aspect));
    }

    /** 호스트+경로. 멜론은 같은 파일에 변환 꼬리(.jpg/melon/quality/90/optimize 등)를 붙이므로 확장자 뒤는 떼고 비교한다. */
    static String imageKey(URI u) {
        String path = u.getPath() == null ? "" : u.getPath();
        java.util.regex.Matcher m = FILE_END.matcher(path);
        if (m.find()) {
            path = path.substring(0, m.end());
        }
        return (u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT)) + path;
    }

    private static final java.util.regex.Pattern FILE_END =
            java.util.regex.Pattern.compile("\\.(?:jpe?g|png|gif)(?=/|$)", java.util.regex.Pattern.CASE_INSENSITIVE);
}
