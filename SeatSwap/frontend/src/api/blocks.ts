import { apiClient } from "./client";
import type { PageResponse } from "../types/page";

// 차단 API (로그인 필요). 차단은 매칭(채팅) 화면에서만 시작하고, 해제는 내 차단 목록에서 한다. 시각은 오프셋 없는 KST 문자열.

export interface BlockResult {
  blockId: number;
  blockedNickname: string;
  /** 차단하면서 시스템 취소된 열린 매칭 수 */
  canceledMatches: number;
  alreadyBlocked: boolean;
}

export interface BlockedUser {
  id: number;
  blockedNickname: string;
  createdAt: string;
}

export const blocksApi = {
  /** POST /api/exchange/matches/{matchId}/block — 상대는 매칭에서 정해진다. 멱등(이미 차단 중이면 alreadyBlocked) */
  async blockCounterpart(matchId: number): Promise<BlockResult> {
    const { data } = await apiClient.post<BlockResult>(`/exchange/matches/${matchId}/block`);
    return data;
  },

  /** GET /api/blocks?page&size */
  async list(page: number, size = 20, signal?: AbortSignal): Promise<PageResponse<BlockedUser>> {
    const { data } = await apiClient.get<PageResponse<BlockedUser>>("/blocks", { params: { page, size }, signal });
    return data;
  },

  /** DELETE /api/blocks/{id} — 본인 행만(남의 것·없는 것 404). 해제해도 취소된 매칭은 되살아나지 않는다 */
  async unblock(blockId: number): Promise<void> {
    await apiClient.delete(`/blocks/${blockId}`);
  },
};
