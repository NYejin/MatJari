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

    /** false면 AI를 호출하지 않는다(키(ANTHROPIC_API_KEY)가 없어도 실질 비활성). */
    private boolean enabled = true;
    /** 모델 이름. 날짜 접미사를 붙이지 않는다. */
    private String model = "claude-haiku-5-5";
    private int requestTimeoutSeconds = 60;
    /** 사용자당 한 달(KST 달력 월) AI 호출 상한. DB(ai_extract_usage, V24)에 기록해 재시작해도 유지된다. */
    private int monthlyPerUser = 3;
    /** 같은 공연장의 등록된 구역 이름을 AI 1차 읽기에 참고로 주는 기준(그 공연의 구역 수가 이 이상일 때만, 돔 경기장 등). */
    private int venueHintMinZones = 50;
    private int dailyGlobal = 40;
    private int maxConcurrent = 2;
    private int cacheTtlHours = 168;
    private int cacheMaxEntries = 200;
    private int firstMaxTokens = 4000;
    private int secondMaxTokens = 12000;
    /** 비어 있지 않으면 구역 모양 만들기 과정(좌석표 이미지·번호 이미지·AI 번호 응답)을 이 폴더에 저장한다(정확도 점검용, 운영에서는 비운다). */
    private String debugDir = "";
    /** 도형 번호와 구역 이름을 짝짓는 호출에 쓸 모델(비우면 model). 그림 속 작은 글자를 읽는 판단이라 더 큰 모델을 쓸 수 있다. */
    private String labelModel = "";
}
