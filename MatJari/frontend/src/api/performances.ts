import axios from "axios";
import { apiClient } from "./client";
import type { PageResponse } from "../types/page";
import type {
  AiExtractResult,
  AiQuota,
  PerformanceCreateRequest,
  PerformanceDetail,
  PerformanceListParams,
  PerformanceLookup,
  PerformancePreview,
  PerformanceSummary,
  PerformanceZonesResponse,
} from "../types/performance";

// 공연·회차 (FR-02). 모두 로그인 필요 — 401은 client.ts 인터셉터가 재발급·재시도 처리.
// 오류: 400(필드/메시지), 404, 409({message} 또는 {message, performanceId}).
export const performancesApi = {
  /** GET /api/performances?query=&page= */
  async list(params: PerformanceListParams, signal?: AbortSignal): Promise<PageResponse<PerformanceSummary>> {
    const query = params.query?.trim();
    const { data } = await apiClient.get<PageResponse<PerformanceSummary>>("/performances", {
      // 빈 값은 보내지 않는다
      params: { query: query || undefined, page: params.page ?? 0, asOf: params.asOf },
      signal,
    });
    return data;
  },

  async get(id: number, signal?: AbortSignal): Promise<PerformanceDetail> {
    const { data } = await apiClient.get<PerformanceDetail>(`/performances/${id}`, { signal });
    return data;
  },

  /** GET /api/performances/lookup?sourceUrl= — 같은 티켓팅 링크로 이미 등록된 공연이 있는지 */
  async lookup(sourceUrl: string): Promise<PerformanceLookup> {
    const { data } = await apiClient.get<PerformanceLookup>("/performances/lookup", { params: { sourceUrl } });
    return data;
  },

  /** GET /api/performances/preview?sourceUrl= — 링크에서 제목·공연장·기간 읽기. 400/429는 오류, 읽기 실패는 status로 온다 */
  async preview(sourceUrl: string, signal?: AbortSignal): Promise<PerformancePreview> {
    const { data } = await apiClient.get<PerformancePreview>("/performances/preview", { params: { sourceUrl }, signal });
    return data;
  },

  /** GET /api/performances/{id}/poster — 포스터 이미지 주소(없으면 null). 실패해도 화면은 포스터만 숨긴다 */
  async poster(id: number, signal?: AbortSignal): Promise<string | null> {
    const { data } = await apiClient.get<{ posterUrl: string | null }>(`/performances/${id}/poster`, { signal });
    return data.posterUrl ?? null;
  },

  /**
   * POST /api/performances/ai-extract — 링크 페이지를 AI로 읽어 회차·구역 제안. 10~40초 걸릴 수 있어 signal로 취소할 수 있다.
   * 오류 본문 {code, message[, retryAfterSeconds]}: 400 UNSUPPORTED_SITE, 429 AI_RATE_LIMITED·AI_MONTHLY_LIMIT_REACHED, 503 AI_UNAVAILABLE·PAGE_FETCH_FAILED
   */
  async aiExtract(sourceUrl: string, signal?: AbortSignal): Promise<AiExtractResult> {
    const { data } = await apiClient.post<AiExtractResult>("/performances/ai-extract", { sourceUrl }, { signal });
    return data;
  },

  /** GET /api/performances/ai-extract/quota — 이번 달 공연정보 불러오기 사용 현황(매월 1일 초기화) */
  async aiQuota(signal?: AbortSignal): Promise<AiQuota> {
    const { data } = await apiClient.get<AiQuota>("/performances/ai-extract/quota", { signal });
    return data;
  },

  /** GET /api/performances/{id}/zones — 구역 이름·모양(sortOrder 순) */
  async zones(id: number, signal?: AbortSignal): Promise<PerformanceZonesResponse> {
    const { data } = await apiClient.get<PerformanceZonesResponse>(`/performances/${id}/zones`, { signal });
    return data;
  },

  /** POST /api/performances → 201 상세. 이미 등록된 공연이면 409 {message, performanceId} */
  async create(payload: PerformanceCreateRequest): Promise<PerformanceDetail> {
    const { data } = await apiClient.post<PerformanceDetail>("/performances", payload);
    return data;
  },

  // 공연은 등록 후 아무도 수정·삭제할 수 없다 (수정은 추후 관리자 수정 제안으로만)
};

/** 409 응답이 이미 등록된 공연을 가리키면 그 공연 id, 아니면 null */
export function getConflictPerformanceId(error: unknown): number | null {
  if (!axios.isAxiosError(error) || error.response?.status !== 409) return null;
  const data: unknown = error.response.data;
  if (typeof data !== "object" || data === null) return null;
  const id: unknown = (data as Record<string, unknown>).performanceId;
  return typeof id === "number" && Number.isFinite(id) ? id : null;
}

/** 응답 상태 코드 (axios 오류가 아니거나 응답이 없으면 null) */
export function getErrorStatus(error: unknown): number | null {
  return axios.isAxiosError(error) ? (error.response?.status ?? null) : null;
}
