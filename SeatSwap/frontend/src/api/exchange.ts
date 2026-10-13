import { apiClient } from "./client";
import type { PageResponse, SliceResponse } from "../types/page";
import type {
  ExchangeCandidate,
  ExchangeMatch,
  ExchangeRequest,
  ExchangeRequestCreatePayload,
  ExchangeRequestPayload,
  MatchListParams,
} from "../types/exchange";

// 교환 희망 조건·후보·매칭 (FR-04). 모두 로그인 필요. 계약은 SeatSwap/backend/README.md.
// 매칭 응답(제안·예약·예약 취소·거절·종료·목록·단건)은 모두 같은 ExchangeMatch 모양이다.
export const exchangeApi = {
  // ---- 희망 조건 ----
  /** GET /api/exchange/requests/me?ticketId= — 내 요청 목록 (배열, id 오름차순) */
  async listMyRequests(ticketId?: number, signal?: AbortSignal): Promise<ExchangeRequest[]> {
    const { data } = await apiClient.get<ExchangeRequest[]>("/exchange/requests/me", {
      params: { ticketId },
      signal,
    });
    return data;
  },

  /** POST /api/exchange/requests → 201 */
  async createRequest(payload: ExchangeRequestCreatePayload): Promise<ExchangeRequest> {
    const { data } = await apiClient.post<ExchangeRequest>("/exchange/requests", payload);
    return data;
  },

  /** PATCH /api/exchange/requests/{id} — 범위(범위별 추가금 포함)·희망 회차 전체 교체 */
  async updateRequest(id: number, payload: ExchangeRequestPayload): Promise<ExchangeRequest> {
    const { data } = await apiClient.patch<ExchangeRequest>(`/exchange/requests/${id}`, payload);
    return data;
  },

  /** DELETE /api/exchange/requests/{id} → 204. 요청은 소프트 삭제(채팅 단계 매칭은 자동 취소). 예약된 매칭이 있으면 409 ACTIVE_MATCH_EXISTS */
  async deleteRequest(id: number): Promise<void> {
    await apiClient.delete(`/exchange/requests/${id}`);
  },

  // ---- 후보 ----
  /** GET /api/exchange/requests/{id}/candidates?page&size */
  async candidates(
    requestId: number,
    page: number,
    size = 20,
    signal?: AbortSignal
  ): Promise<PageResponse<ExchangeCandidate>> {
    const { data } = await apiClient.get<PageResponse<ExchangeCandidate>>(
      `/exchange/requests/${requestId}/candidates`,
      { params: { page, size }, signal }
    );
    return data;
  },

  /**
   * GET /api/exchange/requests/{id}/candidates/scroll?cursor&size — keyset(커서) 방식. 전체 개수 없이 nextCursor로 이어 읽는다.
   * 후보가 많아도 뒤쪽 조각이 느려지지 않는다. 처음에는 cursor를 비운다
   */
  async candidateScroll(
    requestId: number,
    cursor: string | null,
    size = 20,
    signal?: AbortSignal
  ): Promise<SliceResponse<ExchangeCandidate>> {
    const { data } = await apiClient.get<SliceResponse<ExchangeCandidate>>(
      `/exchange/requests/${requestId}/candidates/scroll`,
      { params: cursor ? { cursor, size } : { size }, signal }
    );
    return data;
  },

  // ---- 매칭 ----
  /** POST /api/exchange/requests/{id}/proposals → 201. 후보를 골라 매칭(채팅 단계)을 만든다 */
  async propose(requestId: number, targetRequestId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/requests/${requestId}/proposals`, {
      targetRequestId,
    });
    return data;
  },

  /** POST /api/exchange/matches/{id}/reserve — 예약 제안(채팅 화면). 상대가 수락해야 RESERVED(두 티켓 잠금). 내가 이미 제안했으면 멱등 200 */
  async reserve(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/reserve`);
    return data;
  },

  /** POST /api/exchange/matches/{id}/reserve/accept — 예약 수락(제안받은 쪽). 수락하면 RESERVED */
  async acceptReservation(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/reserve/accept`);
    return data;
  },

  /** POST /api/exchange/matches/{id}/reserve/decline — 예약 거절(제안받은 쪽). 채팅은 유지 */
  async declineReservation(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/reserve/decline`);
    return data;
  },

  /** POST /api/exchange/matches/{id}/reserve/withdraw — 제안 철회(제안한 쪽) */
  async withdrawReservation(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/reserve/withdraw`);
    return data;
  },

  /** POST /api/exchange/matches/{id}/unreserve — 예약 취소. 둘 중 누구나, 매칭은 CHATTING으로 복귀 */
  async unreserve(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/unreserve`);
    return data;
  },

  /** POST /api/exchange/matches/{id}/complete — 교환 수락(본문 없음). 양쪽이 모두 누르면 COMPLETED. 내가 이미 수락했으면 멱등 200 */
  async complete(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/complete`);
    return data;
  },

  /** POST /api/exchange/matches/{id}/reject — 제안받은 쪽의 거절 (제안한 쪽이 부르면 403) */
  async reject(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/reject`);
    return data;
  },

  /** POST /api/exchange/matches/{id}/cancel — 참여자 누구나 취소 */
  async cancel(matchId: number): Promise<ExchangeMatch> {
    const { data } = await apiClient.post<ExchangeMatch>(`/exchange/matches/${matchId}/cancel`);
    return data;
  },

  /** GET /api/exchange/matches/me?role=&status=&page=&size= */
  async listMatches(params: MatchListParams, signal?: AbortSignal): Promise<PageResponse<ExchangeMatch>> {
    const { data } = await apiClient.get<PageResponse<ExchangeMatch>>("/exchange/matches/me", {
      params: { role: params.role ?? "ALL", status: params.status, page: params.page ?? 0, size: params.size ?? 20 },
      signal,
    });
    return data;
  },

  /** GET /api/exchange/matches/{id} */
  async getMatch(id: number, signal?: AbortSignal): Promise<ExchangeMatch> {
    const { data } = await apiClient.get<ExchangeMatch>(`/exchange/matches/${id}`, { signal });
    return data;
  },
};
