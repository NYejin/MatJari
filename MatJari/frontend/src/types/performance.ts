// 공연·회차 (FR-02 공연 등록). 공연장은 별도 엔티티 없이 공연의 텍스트 속성 venueName. 백엔드 계약(2026-10-08 기준)과 1:1 대응.
// 시각(startsAt 등)은 KST 현지 시각 문자열 "yyyy-MM-ddTHH:mm" (초가 붙어 와도 api/dateTime.ts가 허용).

export interface PerformanceSession {
  id: number;
  startsAt: string;
}

/** GET /api/performances 목록 항목 */
export interface PerformanceSummary {
  id: number;
  title: string;
  venueName: string;
  /** 지금 이후 가장 이른 회차. 없으면 null */
  nextSessionStartsAt: string | null;
  sessionCount: number;
}

/** GET /api/performances/{id} */
export interface PerformanceDetail {
  id: number;
  title: string;
  sourceUrl: string;
  venueName: string;
  registrant: { id: number; nickname: string };
  sessions: PerformanceSession[];
  /** 공연 상세에 구역이 포함될 수 있다(없을 수 있음). 드롭다운은 전용 zones 조회를 쓴다 */
  zones?: PerformanceZone[];
  /** 구역 지도 가로/세로 비율(없을 수 있음) */
  seatmapAspect?: number | null;
  /** 관리자가 내린 공연이면 true (새 티켓 등록·교환 요청 불가) */
  hidden?: boolean;
  createdAt: string;
}

/**
 * 구역 한 개. shapePolygons 는 다각형 조각 배열(조각마다 꼭짓점 [x, y]), 좌표는 0~1000 정규화(지도 폭·높이를 각각 1000으로 본다).
 * 모양이 없으면 null
 */
export interface PerformanceZone {
  name: string;
  shapePolygons: number[][][] | null;
}

/** GET /api/performances/{id}/zones */
export interface PerformanceZonesResponse {
  seatmapAspect: number | null;
  zones: PerformanceZone[];
}

/** POST /api/performances/ai-extract 응답 */
export type AiSessionsSource = "TEXT_RULE" | "AI" | "NONE";
export interface AiExtractResult {
  sessions: { date: string; time: string }[];
  sessionsSource: AiSessionsSource;
  zones: PerformanceZone[];
  /** 지도 가로/세로 비율(폭÷높이). 없으면 null */
  seatmapAspect: number | null;
  aiEnabled: boolean;
  aiError: string | null;
  cached: boolean;
  /** 페이지가 길어 못 읽은 조각 수(0이면 전부 읽음) */
  truncatedSlices: number;
}

/** GET /api/performances/lookup — 없으면 performanceId가 null이거나 생략될 수 있다 */
export interface PerformanceLookup {
  exists: boolean;
  performanceId?: number | null;
}

/** GET /api/performances/preview — 링크 페이지에서 읽은 공연 정보 (항상 200) */
export type PerformancePreviewStatus = "OK" | "UNSUPPORTED_SITE" | "NO_INFO" | "FETCH_FAILED";
export interface PerformancePreview {
  status: PerformancePreviewStatus;
  site: "yes24" | "ticketlink" | "melon" | null;
  title: string | null;
  venueName: string | null;
  /** yyyy-MM-dd */
  periodStart: string | null;
  periodEnd: string | null;
  /** 포스터 이미지 주소(허용 호스트의 https). 서버는 이미지를 받지 않는다. 없으면 null */
  posterUrl: string | null;
  /** 회차 날짜 후보(공연 기간이 하루·이틀일 때만). time 은 지금은 항상 null — 시각은 직접 입력 */
  sessionCandidates: { date: string; time: string | null }[];
}

export interface PerformanceListParams {
  query?: string;
  /** 0부터 */
  page?: number;
  /**
   * 목록 기준 시각(KST "yyyy-MM-ddTHH:mm"). 첫 페이지 요청 때 고정해 "더 보기"에도 같은 값을 보내
   * 페이지 사이에 "다음 회차" 판정 기준이 바뀌지 않게 한다 (서버가 모르면 무시)
   */
  asOf?: string;
}

/** POST /api/performances */
export interface PerformanceCreateRequest {
  sourceUrl: string;
  title: string;
  /** 공연장 이름 (필수, 100자 이하) */
  venueName: string;
  /** 회차 시작 시각 목록 (1개 이상) */
  sessions: string[];
  /** 구역(선택, 개수 상한 없음·안전 한도 1,000개). 없어도 등록된다 */
  zones?: PerformanceZone[];
  /** 지도 가로/세로 비율(선택) */
  seatmapAspect?: number | null;
  /** 등록자의 티켓(필수). 공연은 티켓 등록과 함께만 등록되고, 공연에는 이 티켓이 연결·표시되지 않는다 */
  ticket: PerformanceTicketInput;
}

/** 공연 등록과 함께 만드는 내 티켓. sessionStartsAt 은 sessions 중 하나 */
export interface PerformanceTicketInput {
  sessionStartsAt: string;
  zone: string;
  row: string;
  col: string;
}
