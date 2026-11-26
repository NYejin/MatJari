package com.matjari.dto.response;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 원본 좌석표 이미지 위치(V31). url 은 원 사이트의 이미지 주소(브라우저가 직접 불러온다, 서버는 이미지를 저장·프록시하지 않는다)이고
 * left·top·width·height 는 **원본 이미지 전체 크기에 대한 비율**(0~1)의 사각형이다. 이 사각형이 구역 좌표(0~1000)와
 * seatmapAspect 의 기준 영역과 정확히 같다. AI 보관 결과 JSON 에도 같은 모양으로 들어간다.
 */
public record SeatmapImageResponse(String url, BigDecimal left, BigDecimal top, BigDecimal width, BigDecimal height) {

    public static final int SCALE = 6;
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.000001");

    /** 비율 사각형(0~1 실수)을 소수 6자리로 만든다. 범위를 벗어나거나 계산할 수 없으면 null. */
    public static SeatmapImageResponse ofRatios(String url, double left, double top, double width, double height) {
        if (url == null || !Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(width) || !Double.isFinite(height)) {
            return null;
        }
        SeatmapImageResponse r = new SeatmapImageResponse(url, scale(left), scale(top), scale(width), scale(height));
        return r.rectValid() ? r : null;
    }

    private static BigDecimal scale(double v) {
        return BigDecimal.valueOf(v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /** DB CHECK(V31)와 같은 범위 검사. */
    public boolean rectValid() {
        if (url == null || left == null || top == null || width == null || height == null) {
            return false;
        }
        return left.signum() >= 0 && left.compareTo(ONE) < 0
                && top.signum() >= 0 && top.compareTo(ONE) < 0
                && width.signum() > 0 && width.compareTo(ONE) <= 0
                && height.signum() > 0 && height.compareTo(ONE) <= 0
                && left.add(width).compareTo(ONE.add(TOLERANCE)) <= 0
                && top.add(height).compareTo(ONE.add(TOLERANCE)) <= 0;
    }
}
