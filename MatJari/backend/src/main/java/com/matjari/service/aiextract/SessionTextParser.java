package com.matjari.service.aiextract;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 페이지 본문 텍스트(태그 제거 후, 줄바꿈 유지 권장)에서 회차 날짜·시각을 규칙으로 읽는다. 읽지 못하면 빈 목록이다(예외 없음).
 *
 * 기준점은 '공연시간' 문구다. 그 뒤부터 같은 줄과 이어지는 최대 4줄(1500자 이내, 공연장소·관람연령 같은 다른 항목 줄 앞까지)만 본다.
 * 지원 형태: (1) 날짜 하나 + 시각 여러 개("2026년 10월 3일(토) 오후 2시, 7시"), (2) 날짜 범위 + 요일별 시각
 * ("6월 19일(금) ~ 6월 21일(일) 금 오후 8시 / 토 오후 5시 / 일 오후 4시": 요일이 없는 날은 회차 없음), (3) 날짜+시각 나열(YES24).
 * 공백이 섞인 표기("2025 년 1 월 18 일 ( 토 ) 오후 6 시")와 "(KST)"는 허용한다. 24시간 표기("19:30")도 읽는다.
 *
 * 추측하지 않는 규칙: 오전/오후가 없는 시각은 24시간 표기가 확실할 때(13~23시, 두 자리 "HH:mm")만 쓰고, 그 앞 시각과 같은 오전/오후를
 * 이어받은 결과가 앞 시각보다 늦을 때만 쓴다("오후 2시, 7시" -> 14:00, 19:00. "오전 11시, 1시"는 버린다). 날짜 옆 요일이 실제 요일과
 * 다르거나 연도를 알 수 없으면 그 날짜를 버린다. 10분 단위가 아닌 시각, 공연 기간 밖 날짜, 100개 초과(앞에서부터 100개만 남김)는 버린다.
 * '입장·리허설·티켓오픈·예매'가 든 구간(같은 줄에서 / | ; 와 다른 날짜 표기로 나뉜 한 조각)의 시각은 제외한다.
 */
@Component
public class SessionTextParser {

    static final int MAX_SESSIONS = 100;
    private static final int MAX_REGION_LINES = 4;
    private static final int MAX_REGION_CHARS = 1500;

    private static final Pattern ANCHOR = Pattern.compile("공연\\s*시간");
    private static final Pattern STOP_LABEL = Pattern.compile(
            "^(공연\\s*장소|공연장|장소|관람\\s*연령|관람\\s*등급|관람\\s*시간|티켓\\s*가격|가격|주최|주관|기획|할인|유의|공지|공연\\s*기간)");
    private static final Pattern EXCLUDE = Pattern.compile("입장|리허설|티켓\\s*오픈|예매");
    private static final Pattern TOKEN = Pattern.compile(
            "(?<date>(?<![\\d])(?:(?<y>\\d{4})\\s*년\\s*)?(?<mo>\\d{1,2})\\s*월\\s*(?<d>\\d{1,2})\\s*일"
                    + "(?:\\s*\\(\\s*(?<dw>[월화수목금토일])\\s*(?:요일)?\\s*\\))?)"
                    + "|(?<time>(?<![\\d])(?:(?<ap>오전|오후)\\s*)?(?:(?<h>\\d{1,2})\\s*시(?:\\s*(?<m>\\d{1,2})\\s*분)?"
                    + "|(?<h2>\\d{1,2})\\s*:\\s*(?<m2>\\d{2})(?![\\d])))"
                    + "|(?<tilde>[~〜∼～])"
                    + "|(?<wd>(?<![가-힣0-9])[월화수목금토일](?:요일)?(?![가-힣]))");

    private enum Type { DATE, TIME, TILDE, WD }

    private static final class Token {
        final Type type;
        final int start;
        final int end;
        String y;
        String mo;
        String d;
        String dw;
        String ap;
        String h;
        String min;
        boolean colon;
        String wd;

        Token(Type type, int start, int end) {
            this.type = type;
            this.start = start;
            this.end = end;
        }
    }

    private static final class Bucket {
        final Set<DayOfWeek> days; // null 이면 요일 지정 없음
        final List<LocalTime> times = new ArrayList<>();

        Bucket(Set<DayOfWeek> days) {
            this.days = days;
        }
    }

    private static final class Group {
        final List<LocalDate> dates;
        final Bucket plain = new Bucket(null);
        final List<Bucket> weekdayBuckets = new ArrayList<>();

        Group(List<LocalDate> dates) {
            this.dates = dates;
        }
    }

    public List<ExtractedSession> parse(String text, LocalDate periodStart, LocalDate periodEnd) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            return doParse(text, periodStart, periodEnd);
        } catch (RuntimeException e) {
            return List.of(); // 읽지 못하면 빈 목록(예외 아님)
        }
    }

    private List<ExtractedSession> doParse(String text, LocalDate periodStart, LocalDate periodEnd) {
        String[] lines = normalize(text).split("\n");
        TreeSet<ExtractedSession> out = new TreeSet<>(
                Comparator.comparing(ExtractedSession::date).thenComparing(ExtractedSession::time));
        for (int i = 0; i < lines.length; i++) {
            Matcher a = ANCHOR.matcher(lines[i]);
            if (!a.find()) {
                continue;
            }
            StringBuilder region = new StringBuilder(lines[i].substring(a.end()));
            int added = 0;
            for (int j = i + 1; j < lines.length && added < MAX_REGION_LINES && region.length() < MAX_REGION_CHARS; j++) {
                String line = lines[j];
                if (line.isEmpty()) {
                    continue;
                }
                if (STOP_LABEL.matcher(line).find()) {
                    break;
                }
                region.append('\n').append(line);
                added++;
            }
            String r = region.length() > MAX_REGION_CHARS ? region.substring(0, MAX_REGION_CHARS) : region.toString();
            collect(r, periodStart, periodEnd, out);
        }
        List<ExtractedSession> list = new ArrayList<>(out);
        return list.size() > MAX_SESSIONS ? List.copyOf(list.subList(0, MAX_SESSIONS)) : List.copyOf(list);
    }

    private static String normalize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        s = s.replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replaceAll("\\(\\s*KST\\s*\\)", " ");
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            sb.append(line.replaceAll("[ \\t\\u00A0\\u3000]+", " ").trim()).append('\n');
        }
        return sb.toString();
    }

    private static List<Token> tokenize(String r) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(r);
        while (m.find()) {
            Token t;
            if (m.group("date") != null) {
                t = new Token(Type.DATE, m.start(), m.end());
                t.y = m.group("y");
                t.mo = m.group("mo");
                t.d = m.group("d");
                t.dw = m.group("dw");
            } else if (m.group("time") != null) {
                t = new Token(Type.TIME, m.start(), m.end());
                t.ap = m.group("ap");
                t.colon = m.group("h2") != null;
                t.h = t.colon ? m.group("h2") : m.group("h");
                t.min = t.colon ? m.group("m2") : m.group("m");
            } else if (m.group("tilde") != null) {
                t = new Token(Type.TILDE, m.start(), m.end());
            } else {
                t = new Token(Type.WD, m.start(), m.end());
                t.wd = m.group("wd").substring(0, 1);
            }
            tokens.add(t);
        }
        return tokens;
    }

    private void collect(String region, LocalDate periodStart, LocalDate periodEnd, TreeSet<ExtractedSession> out) {
        List<Token> tokens = tokenize(region);
        List<Group> groups = new ArrayList<>();
        Group cur = null;
        Bucket bucket = null;
        int lastYear = -1;
        for (int i = 0; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            switch (t.type) {
                case DATE -> {
                    if (t.y != null) {
                        lastYear = Integer.parseInt(t.y);
                    }
                    Integer fallback = lastYear > 0 ? lastYear : (periodStart != null ? Integer.valueOf(periodStart.getYear()) : null);
                    LocalDate d1 = resolve(t, fallback, null);
                    LocalDate d2 = null;
                    boolean range = i + 2 < tokens.size() && tokens.get(i + 1).type == Type.TILDE
                            && tokens.get(i + 2).type == Type.DATE;
                    boolean rangeOk = true;
                    if (range) {
                        Token e = tokens.get(i + 2);
                        i += 2;
                        if (e.y != null) {
                            lastYear = Integer.parseInt(e.y);
                        }
                        d2 = d1 == null ? null : resolve(e, d1.getYear(), d1);
                        rangeOk = d2 != null;
                    }
                    if (d1 == null || !rangeOk) {
                        cur = null;
                        bucket = null;
                        break;
                    }
                    List<LocalDate> dates = new ArrayList<>();
                    if (d2 == null) {
                        dates.add(d1);
                    } else {
                        long days = ChronoUnit.DAYS.between(d1, d2) + 1;
                        if (days < 1 || days > MAX_SESSIONS) {
                            cur = null;
                            bucket = null;
                            break;
                        }
                        for (LocalDate d = d1; !d.isAfter(d2); d = d.plusDays(1)) {
                            dates.add(d);
                        }
                    }
                    cur = new Group(dates);
                    groups.add(cur);
                    bucket = cur.plain;
                }
                case WD -> {
                    if (cur == null) {
                        break;
                    }
                    DayOfWeek dow = dayOf(t.wd);
                    if (bucket != null && bucket.days != null && bucket.times.isEmpty()) {
                        bucket.days.add(dow);
                    } else {
                        bucket = new Bucket(EnumSet.of(dow));
                        cur.weekdayBuckets.add(bucket);
                    }
                }
                case TIME -> {
                    if (cur == null || excluded(region, tokens, i)) {
                        break;
                    }
                    LocalTime time = toTime(t, bucket);
                    if (time != null) {
                        bucket.times.add(time);
                    }
                }
                default -> {
                    // TILDE: 날짜 범위가 아닌 곳의 물결표는 무시
                }
            }
        }
        for (Group g : groups) {
            for (LocalDate date : g.dates) {
                if ((periodStart != null && date.isBefore(periodStart)) || (periodEnd != null && date.isAfter(periodEnd))) {
                    continue;
                }
                List<LocalTime> times = new ArrayList<>(g.plain.times);
                for (Bucket b : g.weekdayBuckets) {
                    if (b.days.contains(date.getDayOfWeek())) {
                        times.addAll(b.times);
                    }
                }
                for (LocalTime t : times) {
                    out.add(new ExtractedSession(date, t));
                }
            }
        }
    }

    /** 이 시각 토큰이 속한 구간(구분자 / | ; 줄바꿈, 이웃 날짜 토큰 사이)에 제외 문구가 있는가. */
    private static boolean excluded(String region, List<Token> tokens, int idx) {
        Token t = tokens.get(idx);
        int from = 0;
        for (int i = idx - 1; i >= 0; i--) {
            if (tokens.get(i).type == Type.DATE) {
                from = tokens.get(i).end;
                break;
            }
        }
        int to = region.length();
        for (int i = idx + 1; i < tokens.size(); i++) {
            if (tokens.get(i).type == Type.DATE) {
                to = tokens.get(i).start;
                break;
            }
        }
        int s = t.start;
        while (s > from && "/|;\n".indexOf(region.charAt(s - 1)) < 0) {
            s--;
        }
        int e = t.end;
        while (e < to && "/|;\n".indexOf(region.charAt(e)) < 0) {
            e++;
        }
        return EXCLUDE.matcher(region.substring(s, Math.max(s, e))).find();
    }

    /** 날짜 토큰을 날짜로. 연도를 모르거나 날짜가 틀리거나 옆 요일이 실제와 다르면 null. after 가 있으면 그보다 이르지 않게 연도를 올린다. */
    private static LocalDate resolve(Token t, Integer fallbackYear, LocalDate after) {
        try {
            Integer year = t.y != null ? Integer.valueOf(t.y) : fallbackYear;
            if (year == null) {
                return null;
            }
            int month = Integer.parseInt(t.mo);
            int day = Integer.parseInt(t.d);
            LocalDate d = LocalDate.of(year, month, day);
            if (t.y == null && after != null && d.isBefore(after)) {
                d = LocalDate.of(year + 1, month, day);
            }
            if (t.dw != null && d.getDayOfWeek() != dayOf(t.dw)) {
                return null;
            }
            return d;
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static DayOfWeek dayOf(String ko) {
        return switch (ko) {
            case "월" -> DayOfWeek.MONDAY;
            case "화" -> DayOfWeek.TUESDAY;
            case "수" -> DayOfWeek.WEDNESDAY;
            case "목" -> DayOfWeek.THURSDAY;
            case "금" -> DayOfWeek.FRIDAY;
            case "토" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    /** 시각 토큰을 시각으로. 확실하지 않거나 10분 단위가 아니면 null. */
    private static LocalTime toTime(Token t, Bucket bucket) {
        int h = Integer.parseInt(t.h);
        int m = t.min == null ? 0 : Integer.parseInt(t.min);
        if (m > 59 || m % 10 != 0) {
            return null;
        }
        if (t.ap != null) {
            if (h < 1 || h > 12) {
                return null;
            }
            int hour = "오후".equals(t.ap) ? (h % 12) + 12 : h % 12; // 오전 12시=0시, 오후 12시=12시
            return LocalTime.of(hour, m);
        }
        if (h >= 13 && h <= 23) {
            return LocalTime.of(h, m);
        }
        if (t.colon && t.h.length() == 2 && h <= 23) {
            return LocalTime.of(h, m); // "07:30" 같은 두 자리 24시간 표기
        }
        if (h < 1 || h > 12 || bucket == null || bucket.times.isEmpty()) {
            return null; // 첫 시각이 오전/오후 없이 모호하면 추측하지 않는다
        }
        LocalTime last = bucket.times.get(bucket.times.size() - 1);
        int hour = last.getHour() >= 12 ? (h % 12) + 12 : h % 12; // 앞 시각과 같은 오전/오후
        LocalTime cand = LocalTime.of(hour, m);
        return cand.isAfter(last) ? cand : null;
    }
}
