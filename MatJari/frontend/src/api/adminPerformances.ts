import { apiClient } from "./client";
import type { PageResponse } from "../types/page";
import type { AiExtractResult, PerformanceSource, PerformanceZone, SeatmapImage } from "../types/performance";
import type { SessionChangeRequest } from "./editProposals";

// 관리자 공연 관리 (/api/admin/performances). 규약: 관리자 권한 확장 API 규약 'A. 공연 관리'.
// 시각은 오프셋 없는 KST 문자열.

export type HiddenFilter = "ALL" | "VISIBLE" | "HIDDEN";

export interface AdminPerformanceItem {
  id: number;
  title: string;
  venueName: string;
  sourceUrl: string;
  registrantNickname: string;
  sessionCount: number;
  zoneCount: number;
  hidden: boolean;
  hiddenAt: string | null;
  createdAt: string;
}

export interface AdminPerformanceSession {
  id: number;
  startsAt: string;
  /** 티켓·교환 희망 조건·좌석 인증 요청이 걸려 삭제할 수 없는 회차 */
  inUse: boolean;
}

export interface AdminPerformanceDetail {
  id: number;
  title: string;
  venueName: string;
  sourceUrl: string;
  sources?: PerformanceSource[];
  hidden: boolean;
  hiddenAt: string | null;
  hiddenReason: string | null;
  hiddenByNickname: string | null;
  registrant: { id: number; nickname: string };
  sessions: AdminPerformanceSession[];
  zones: PerformanceZone[];
  seatmapAspect: number | null;
  seatmapImage?: SeatmapImage | null;
  createdAt: string;
}

export const ADMIN_REASON_MAX = 200;

export type MatchStatus = "CHATTING" | "RESERVED" | "COMPLETED" | "CANCELED";

export interface AdminExchangeSide {
  userId: number;
  nickname: string;
  zone: string;
  row: string;
  col: string;
  sessionStartsAt: string;
  /** 이 쪽이 [교환 수락]을 누른 시각 */
  completedAt: string | null;
}

/** 공연 상세의 교환(매칭) 한 건. a = 채팅을 연 쪽, 좌석은 교환 전 자리 */
export interface AdminExchangeItem {
  matchId: number;
  status: MatchStatus;
  a: AdminExchangeSide;
  b: AdminExchangeSide;
  messageCount: number;
  createdAt: string;
  reservedAt: string | null;
  canceledAt: string | null;
  updatedAt: string;
}

export interface AdminExchangePage {
  page: PageResponse<AdminExchangeItem>;
  statusCounts: Record<MatchStatus, number>;
}

export const adminPerformancesApi = {
  async list(
    params: { query: string; hidden: HiddenFilter; page: number },
    signal?: AbortSignal
  ): Promise<PageResponse<AdminPerformanceItem>> {
    const { data } = await apiClient.get<PageResponse<AdminPerformanceItem>>("/admin/performances", {
      params: { query: params.query || undefined, hidden: params.hidden, page: params.page, size: 20 },
      signal,
    });
    return data;
  },

  async get(id: number, signal?: AbortSignal): Promise<AdminPerformanceDetail> {
    const { data } = await apiClient.get<AdminPerformanceDetail>(`/admin/performances/${id}`, { signal });
    return data;
  },

  /** GET /api/admin/performances/{id}/exchanges — 이 공연의 교환(매칭) 목록, 최근 변경순 50개씩 + 상태별 개수 */
  async exchanges(id: number, params: { status: MatchStatus | "ALL"; page: number }, signal?: AbortSignal): Promise<AdminExchangePage> {
    const { data } = await apiClient.get<AdminExchangePage>(`/admin/performances/${id}/exchanges`, { params, signal });
    return data;
  },

  async hide(id: number, reason: string): Promise<void> {
    await apiClient.post(`/admin/performances/${id}/hide`, { reason });
  },

  /** POST /api/admin/performances/{id}/delete — 숨긴 공연 완전 삭제(연결 기록이 있으면 409 PERFORMANCE_IN_USE, 숨김 아님 422) */
  async remove(id: number, reason: string): Promise<void> {
    await apiClient.post(`/admin/performances/${id}/delete`, { reason });
  },

  async unhide(id: number, reason?: string): Promise<void> {
    await apiClient.post(`/admin/performances/${id}/unhide`, { reason: reason || undefined });
  },

  async edit(id: number, body: { title?: string; venueName?: string; reason: string }): Promise<AdminPerformanceDetail> {
    const { data } = await apiClient.patch<AdminPerformanceDetail>(`/admin/performances/${id}`, body);
    return data;
  },

  /** POST /api/admin/performances/{id}/sources — 예매처 링크 추가. 409 SOURCE_ALREADY_REGISTERED·PERFORMANCE_HIDDEN, 422 SOURCE_NOT_SAME_PERFORMANCE·SOURCE_LIMIT_REACHED */
  async addSource(id: number, sourceUrl: string, reason: string): Promise<{ sources: PerformanceSource[] }> {
    const { data } = await apiClient.post<{ sources: PerformanceSource[] }>(`/admin/performances/${id}/sources`, { sourceUrl, reason });
    return data;
  },

  /** DELETE /api/admin/performances/{id}/sources/{sourceId} — 사유는 본문. 대표 링크는 409 PRIMARY_SOURCE */
  async removeSource(id: number, sourceId: number, reason: string): Promise<void> {
    await apiClient.delete(`/admin/performances/${id}/sources/${sourceId}`, { data: { reason } });
  },

  async changeSessions(id: number, changes: SessionChangeRequest[], reason: string): Promise<AdminPerformanceDetail> {
    const { data } = await apiClient.post<AdminPerformanceDetail>(`/admin/performances/${id}/session-changes`, { changes, reason });
    return data;
  },

  async replaceZones(
    id: number,
    body: {
      zones: PerformanceZone[];
      seatmapAspect: number | null;
      reason: string;
      /** 없으면 KEEP. 링크에서 다시 인식한 결과는 LINK, 이미지로 인식한 결과는 CLEAR */
      seatmapImageMode?: "KEEP" | "LINK" | "CLEAR";
    }
  ): Promise<AdminPerformanceDetail> {
    const { data } = await apiClient.put<AdminPerformanceDetail>(`/admin/performances/${id}/zones`, body);
    return data;
  },

  /** POST /api/admin/performances/{id}/zones/ai-extract — 링크에서 구역 다시 인식(보관 결과 무시, 월 횟수 없음, 저장 안 함) */
  async reextractZones(id: number, signal?: AbortSignal): Promise<AiExtractResult> {
    const { data } = await apiClient.post<AiExtractResult>(`/admin/performances/${id}/zones/ai-extract`, null, { signal });
    return data;
  },

  /** POST /api/admin/performances/{id}/zones/seatmap (multipart image) — 좌석표 이미지로 구역 인식(저장 안 함) */
  async seatmapZones(id: number, image: File, signal?: AbortSignal): Promise<AiExtractResult> {
    const form = new FormData();
    form.append("image", image);
    const { data } = await apiClient.post<AiExtractResult>(`/admin/performances/${id}/zones/seatmap`, form, { signal });
    return data;
  },
};

/** 오류 코드별 문구 (없으면 서버 message) */
export const ADMIN_PERFORMANCE_CODE_MESSAGES: Record<string, string> = {
  SESSION_IN_USE: "티켓이나 교환 희망 조건이 걸려 있는 회차는 삭제할 수 없어요.",
  SOURCE_ALREADY_REGISTERED: "이미 등록된 예매처 링크예요.",
  PERFORMANCE_HIDDEN: "내려간 공연에는 링크를 추가할 수 없어요.",
  SOURCE_NOT_SAME_PERFORMANCE: "같은 공연으로 확인되지 않아 링크를 추가하지 못했어요.",
  SOURCE_LIMIT_REACHED: "예매처 링크를 더 추가할 수 없어요.",
  PRIMARY_SOURCE: "대표 링크는 삭제할 수 없어요.",
  SESSION_EDIT_CONFLICT: "그 사이 회차 정보가 달라져 반영할 수 없어요. 화면을 새로 불러온 뒤 다시 시도해 주세요.",
};
