import { apiClient } from "./client";
import type { PageResponse } from "../types/page";

// 공연 수정 제안. 공연은 등록 후 직접 수정할 수 없고, 제안 → 관리자 승인으로만 바뀐다.
// 시각은 오프셋 없는 KST 문자열. 오류 코드: 422 EDIT_NO_CHANGE / EDIT_PROPOSAL_LIMIT_REACHED,
// 409 EDIT_PROPOSAL_ALREADY_PENDING / PROPOSAL_ALREADY_REVIEWED.

export type EditProposalStatus = "PENDING" | "APPROVED" | "REJECTED";

export interface EditProposal {
  id: number;
  performanceId: number;
  performanceTitle: string;
  currentTitle: string;
  currentVenueName: string;
  newTitle: string | null;
  newVenueName: string | null;
  reason: string | null;
  status: EditProposalStatus;
  reviewNote: string | null;
  createdAt: string;
  reviewedAt: string | null;
}

export interface AdminEditProposal extends EditProposal {
  proposerId: number;
  proposerNickname: string;
  oldTitle: string | null;
  oldVenueName: string | null;
  reviewerNickname: string | null;
}

export interface EditProposalRequest {
  newTitle?: string | null;
  newVenueName?: string | null;
  reason?: string | null;
}

export const EDIT_TITLE_MAX = 200;
export const EDIT_VENUE_MAX = 100;
export const EDIT_REASON_MAX = 500;
export const REVIEW_NOTE_MAX = 300;

/** 오류 코드별 사용자용 문구 (없으면 서버 메시지 사용) */
export const EDIT_ERROR_MESSAGES: Record<string, string> = {
  EDIT_NO_CHANGE: "현재 정보와 같아요. 바뀐 내용만 적어 주세요.",
  EDIT_PROPOSAL_ALREADY_PENDING: "이 공연에 이미 검토 중인 제안이 있어요. 처리된 뒤에 다시 제안해 주세요.",
  EDIT_PROPOSAL_LIMIT_REACHED: "검토 중인 제안이 너무 많아요. 처리된 뒤에 다시 제안해 주세요.",
  PROPOSAL_ALREADY_REVIEWED: "이미 처리된 제안이에요. 목록을 새로 불러왔어요.",
};

export const STATUS_LABEL: Record<EditProposalStatus, string> = {
  PENDING: "검토 중",
  APPROVED: "승인됨",
  REJECTED: "거절됨",
};

export const editProposalsApi = {
  /** POST /api/performances/{id}/edit-proposals → 201 */
  async create(performanceId: number, payload: EditProposalRequest): Promise<EditProposal> {
    const { data } = await apiClient.post<EditProposal>(`/performances/${performanceId}/edit-proposals`, payload);
    return data;
  },

  /** GET /api/performances/edit-proposals/me */
  async listMine(page: number, size: number, signal?: AbortSignal): Promise<PageResponse<EditProposal>> {
    const { data } = await apiClient.get<PageResponse<EditProposal>>("/performances/edit-proposals/me", {
      params: { page, size },
      signal,
    });
    return data;
  },

  /** GET /api/admin/performance-edit-proposals */
  async adminList(
    status: EditProposalStatus,
    page: number,
    size: number,
    signal?: AbortSignal
  ): Promise<PageResponse<AdminEditProposal>> {
    const { data } = await apiClient.get<PageResponse<AdminEditProposal>>("/admin/performance-edit-proposals", {
      params: { status, page, size },
      signal,
    });
    return data;
  },

  async approve(id: number): Promise<AdminEditProposal> {
    const { data } = await apiClient.post<AdminEditProposal>(`/admin/performance-edit-proposals/${id}/approve`);
    return data;
  },

  async reject(id: number, note: string | null): Promise<AdminEditProposal> {
    const { data } = await apiClient.post<AdminEditProposal>(`/admin/performance-edit-proposals/${id}/reject`, { note });
    return data;
  },
};
