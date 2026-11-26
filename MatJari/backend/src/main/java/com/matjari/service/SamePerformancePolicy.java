package com.matjari.service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * "같은 공연일 수 있는가" 판정의 순수 함수 모음(공연장 이름·회차 기간·제목 유사도). 점수화된 추천이 아니라 후보 안내용 보조 판정이다.
 * 공연장 + 기간이 맞으면 제목 유사도가 낮아도 후보이고, 기간이 없을 때만 제목 유사도가 {@link #TITLE_THRESHOLD} 이상이어야 한다.
 */
final class SamePerformancePolicy {

    /** 링크에 기간이 없을 때 같은 공연으로 보는 제목 유사도 하한. */
    static final double TITLE_THRESHOLD = 0.5;
    /** 한쪽이 다른 쪽을 포함해도 같은 공연장으로 볼 때 포함되는 쪽의 최소 길이(너무 짧은 이름은 포함 일치를 쓰지 않는다). */
    static final int MIN_CONTAINED_VENUE_LENGTH = 3;

    private static final Pattern BRACKETS = Pattern.compile("\\([^)]*\\)|\\[[^\\]]*]|<[^>]*>");
    private static final Pattern NOT_LETTER_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern YEAR = Pattern.compile("(?<![0-9])(19|20)[0-9]{2}(?![0-9])");

    private SamePerformancePolicy() {}

    /** 공연장 이름 비교 키: NFKC·소문자·괄호 안 부연 제거·글자와 숫자만(공백·기호 제거). 괄호만 있는 이름이면 괄호 안을 그대로 쓴다. */
    static String venueKey(String venueName) {
        if (venueName == null) {
            return "";
        }
        String n = Normalizer.normalize(venueName, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String key = NOT_LETTER_DIGIT.matcher(BRACKETS.matcher(n).replaceAll(" ")).replaceAll("");
        return key.isEmpty() ? NOT_LETTER_DIGIT.matcher(n).replaceAll("") : key;
    }

    /** 같은 공연장인지: 키가 같거나, 한쪽이 다른 쪽을 포함(짧은 쪽이 {@link #MIN_CONTAINED_VENUE_LENGTH}자 이상). */
    static boolean sameVenue(String a, String b) {
        String ka = venueKey(a);
        String kb = venueKey(b);
        if (ka.isEmpty() || kb.isEmpty()) {
            return false;
        }
        if (ka.equals(kb)) {
            return true;
        }
        String shorter = ka.length() <= kb.length() ? ka : kb;
        String longer = shorter == ka ? kb : ka;
        return shorter.length() >= MIN_CONTAINED_VENUE_LENGTH && longer.contains(shorter);
    }

    /**
     * 링크 기간(날짜)과 공연 회차 범위(첫~마지막 회차의 날짜)가 하루라도 겹치는지. 기간의 한쪽만 있으면 그 하루로 본다.
     * 기간이 둘 다 없으면 판정할 수 없으므로 false(호출자가 제목 유사도로 대신한다).
     */
    static boolean periodOverlaps(LocalDate periodStart, LocalDate periodEnd, LocalDateTime firstSession, LocalDateTime lastSession) {
        if (firstSession == null || lastSession == null || (periodStart == null && periodEnd == null)) {
            return false;
        }
        LocalDate start = periodStart != null ? periodStart : periodEnd;
        LocalDate end = periodEnd != null ? periodEnd : periodStart;
        if (end.isBefore(start)) {
            LocalDate t = start;
            start = end;
            end = t;
        }
        return !firstSession.toLocalDate().isAfter(end) && !lastSession.toLocalDate().isBefore(start);
    }

    static boolean hasPeriod(LocalDate periodStart, LocalDate periodEnd) {
        return periodStart != null || periodEnd != null;
    }

    /**
     * 제목 유사도 0~1: 연도·기호를 뺀 단어 집합의 Jaccard 와, 공백 차이에 강한 글자 2-gram Dice, 한쪽 포함(3자 이상 0.9) 중 가장 큰 값.
     */
    static double titleSimilarity(String a, String b) {
        String ca = compact(a);
        String cb = compact(b);
        if (ca.isEmpty() || cb.isEmpty()) {
            return 0;
        }
        double best = Math.max(jaccard(tokens(a), tokens(b)), dice(ca, cb));
        String shorter = ca.length() <= cb.length() ? ca : cb;
        String longer = shorter == ca ? cb : ca;
        if (shorter.length() >= 3 && longer.contains(shorter)) {
            best = Math.max(best, 0.9);
        }
        return best;
    }

    private static String normalizeTitle(String title) {
        String n = title == null ? "" : Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return YEAR.matcher(n).replaceAll(" ");
    }

    private static String compact(String title) {
        return NOT_LETTER_DIGIT.matcher(normalizeTitle(title)).replaceAll("");
    }

    private static Set<String> tokens(String title) {
        Set<String> set = new HashSet<>();
        for (String t : NOT_LETTER_DIGIT.matcher(normalizeTitle(title)).replaceAll(" ").trim().split("\\s+")) {
            if (!t.isEmpty()) {
                set.add(t);
            }
        }
        return set;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private static double dice(String a, String b) {
        if (a.length() < 2 || b.length() < 2) {
            return a.equals(b) ? 1 : 0;
        }
        java.util.Map<String, Integer> bigramsA = bigrams(a);
        java.util.Map<String, Integer> bigramsB = bigrams(b);
        int overlap = 0;
        for (var e : bigramsA.entrySet()) {
            overlap += Math.min(e.getValue(), bigramsB.getOrDefault(e.getKey(), 0));
        }
        return 2.0 * overlap / ((a.length() - 1) + (b.length() - 1));
    }

    private static java.util.Map<String, Integer> bigrams(String s) {
        java.util.Map<String, Integer> map = new java.util.HashMap<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            map.merge(s.substring(i, i + 2), 1, Integer::sum);
        }
        return map;
    }
}
