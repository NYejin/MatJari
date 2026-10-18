package com.matjari.service.aiextract;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** AI 공연 정보 추출 설정(matjari.ai.*). 지금은 입력부(상세 이미지 수집·조각내기) 항목만 있다. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "matjari.ai")
public class AiProperties {

    /** 상세 이미지를 받아도 되는 호스트(정확히 일치). */
    private List<String> imageHosts = new ArrayList<>(List.of("cdnticket.melon.co.kr"));
    /** 한 번에 내려받아 보는 이미지 후보 수 상한. */
    private int maxCandidates = 4;
    /** 상세 이미지로 남기는 최대 개수. */
    private int maxDetailImages = 3;
    /** 이미지 한 장 최대 바이트. */
    private long maxImageBytes = 10L * 1024 * 1024;
    private long imageConnectTimeoutMs = 5000;
    /** 이미지 한 장을 받는 전체 제한(헤더 대기 + 본문 읽기). */
    private long imageReadTimeoutMs = 15000;
    /** 이 높이 이상, 폭의 2배 이상이어야 상세 이미지로 본다. */
    private int minDetailHeight = 2000;
    /** 디코드 허용 최대 픽셀 수(압축 폭탄 방지). */
    private long maxImagePixels = 60_000_000L;

    /** 이 폭을 넘으면 비율을 유지해 이 폭으로 줄인 뒤 자른다. */
    private int sliceMaxWidth = 1000;
    private int sliceHeight = 1400;
    /** 조각 높이 대비 겹침 비율. */
    private double sliceOverlap = 0.2;
    private int maxSlices = 24;
    /** crop 결과 높이 상한(px). */
    private int cropMaxHeight = 2400;
}
