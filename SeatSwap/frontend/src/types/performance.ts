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
  createdAt: string;
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
}
