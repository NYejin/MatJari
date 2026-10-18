package com.matjari.service.aiextract;

import java.util.List;

/** AI 호출부 추상화. 실제 구현은 AnthropicDetailExtractor, 테스트는 가짜 구현을 쓴다. */
public interface DetailExtractor {

    /** 입력 조각: text 또는 jpeg 중 하나만 채운다. */
    record Part(String text, byte[] jpeg) {
        public static Part ofText(String text) {
            return new Part(text, null);
        }

        public static Part ofImage(byte[] jpeg) {
            return new Part(null, jpeg);
        }
    }

    /** AI가 말한 회차 한 건(문자열 그대로, 검증은 후처리에서). */
    record RawSession(String date, String time) {}

    /** 좌석 배치도 위치(조각 번호는 1부터). */
    record SeatMapLocation(boolean found, int imageIndex, int firstSlice, int lastSlice) {}

    record FirstPass(List<RawSession> sessions, List<String> zones, SeatMapLocation seatMap) {}

    /** 2차 결과 구역 하나: polygons 의 각 조각은 [x,y] 꼭짓점 목록(0~1000 정수 좌표, 검증은 후처리에서). */
    record RawZone(String name, List<List<List<Integer>>> polygons) {}

    /** 띠(작은 높이 조각) 번호 범위(1부터, last 포함). 좌석 배치도의 구역 도형이 모두 들어 있는 띠들이다. */
    record StripRange(int first, int last) {}

    /** AI 호출 실패(SDK 예외·타임아웃·거절·잘림·파싱 실패). 메시지에 키·SDK 내부 정보를 넣지 않는다. */
    class ExtractionFailedException extends RuntimeException {
        public ExtractionFailedException(String message) {
            super(message);
        }
    }

    /** 키가 설정돼 호출 가능한 상태인가. */
    boolean available();

    /** 1차: 모든 조각을 한 번에 보내 회차·구역 이름·좌석표 위치를 받는다. */
    FirstPass first(List<Part> parts) throws ExtractionFailedException;

    /** 합친 이미지를 띠로 나눠 보내 좌석 배치도가 있는 띠 범위를 찾는다. 찾지 못하면 null(호출자는 합친 이미지를 그대로 쓴다). 기본 구현은 null. */
    default StripRange locate(List<Part> strips) throws ExtractionFailedException {
        return null;
    }

    /** 2차: 합친 좌석표 한 장에서 구역 이름과 윤곽을 받는다(knownNames 는 1차 이름 목록). */
    List<RawZone> second(byte[] jpeg, List<String> knownNames) throws ExtractionFailedException;
}
