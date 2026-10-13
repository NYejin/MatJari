import { apiClient } from "./client";
import type { PageResponse } from "../types/page";

// 내 교환 이력 API (로그인 필요, 본인 이력만). 시각은 오프셋 없는 KST 문자열.

export interface HistorySeat {
  zone: string;
  row: string;
  col: string;
}

/** GET /api/exchange/history 항목: (기존 자리) -> (바꾼 자리) 스냅샷. 교환 당시 값이라 이후 공연이 바뀌어도 변하지 않는다 */
export interface ExchangeHistory {
  id: number;
  matchId: number;
  performanceId: number;
  performanceTitle: string;
  venueName: string;
  oldStartsAt: string;
  newStartsAt: string;
  oldSeat: HistorySeat;
  newSeat: HistorySeat;
  completedAt: string;
}

export const exchangeHistoryApi = {
  /** GET /api/exchange/history?page&size — 최신 교환 먼저 */
  async list(page: number, size = 20, signal?: AbortSignal): Promise<PageResponse<ExchangeHistory>> {
    const { data } = await apiClient.get<PageResponse<ExchangeHistory>>("/exchange/history", {
      params: { page, size },
      signal,
    });
    return data;
  },
};
