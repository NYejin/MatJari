import axios from "axios";
import { apiClient } from "./client";
import type { PageResponse } from "../types/page";

// 좌석 소유 인증 요청 (seat-claim). 계약: 관리자 설계 문서 'JSON 계약'.
// 이미지는 인증 헤더가 필요해 <img src>로 못 쓴다 — blob으로 받아 object URL로 미리본다.

export type SeatClaimStatus = "PENDING" | "APPROVED" | "REJECTED";
export type HolderTicketStatus = "ACTIVE" | "RESERVED_LOCKED" | "EXCHANGED" | "INACTIVE";

export interface SeatClaim {
  id: number;
  status: SeatClaimStatus;
  performanceId: number;
  performanceTitle: string;
  sessionId: number;
  sessionStartsAt: string;
  zone: string;
  row: string;
  col: string;
  memo: string | null;
  imageAvailable: boolean;
  reviewNote: string | null;
  createdAt: string;
  reviewedAt: string | null;
}

export interface AdminSeatClaim extends SeatClaim {
  claimantId: number;
  claimantNickname: string;
  reviewerNickname: string | null;
  holder: { ticketStatus: HolderTicketStatus; holderNickname: string } | null;
}

export interface SeatClaimCreateInput {
  sessionId: number;
  zone: string;
  row: string;
  col: string;
  memo?: string;
  image: File;
}

export const SEAT_CLAIM_ALLOWED_TYPES = ["image/jpeg", "image/png", "image/webp"] as const;
export const SEAT_CLAIM_MAX_BYTES = 5 * 1024 * 1024;
export const SEAT_CLAIM_MEMO_MAX = 300;
export const SEAT_CLAIM_NOTE_MAX = 300;

export const seatClaimsApi = {
  /** POST /api/seat-claims (multipart) → 201. Content-Type(boundary 포함)은 axios가 FormData에서 정한다 */
  async create(input: SeatClaimCreateInput): Promise<SeatClaim> {
    const form = new FormData();
    form.append("sessionId", String(input.sessionId));
    form.append("zone", input.zone);
    form.append("row", input.row);
    form.append("col", input.col);
    if (input.memo) form.append("memo", input.memo);
    form.append("image", input.image);
    const { data } = await apiClient.post<SeatClaim>("/seat-claims", form);
    return data;
  },

  async listMine(page: number, signal?: AbortSignal): Promise<PageResponse<SeatClaim>> {
    const { data } = await apiClient.get<PageResponse<SeatClaim>>("/seat-claims/me", { params: { page, size: 20 }, signal });
    return data;
  },

  /** 이미지 바이트. 삭제됐으면 410 IMAGE_DELETED */
  async fetchImage(id: number, signal?: AbortSignal): Promise<Blob> {
    const { data } = await apiClient.get<Blob>(`/seat-claims/${id}/image`, { responseType: "blob", signal });
    return data;
  },

  async adminList(status: SeatClaimStatus, page: number, signal?: AbortSignal): Promise<PageResponse<AdminSeatClaim>> {
    const { data } = await apiClient.get<PageResponse<AdminSeatClaim>>("/admin/seat-claims", {
      params: { status, page, size: 20 },
      signal,
    });
    return data;
  },

  async approve(id: number): Promise<AdminSeatClaim> {
    const { data } = await apiClient.post<AdminSeatClaim>(`/admin/seat-claims/${id}/approve`);
    return data;
  },

  async reject(id: number, note: string | null): Promise<AdminSeatClaim> {
    const { data } = await apiClient.post<AdminSeatClaim>(`/admin/seat-claims/${id}/reject`, { note });
    return data;
  },
};

/** 오류의 HTTP 상태 (blob 응답은 본문이 Blob이라 상태만 본다) */
export function httpStatus(error: unknown): number | null {
  return axios.isAxiosError(error) ? (error.response?.status ?? null) : null;
}

/** 파일 선택 검증 메시지 (통과하면 null). 서버가 매직 넘버로 다시 검증한다 */
export function validateClaimImage(file: File): string | null {
  if (!(SEAT_CLAIM_ALLOWED_TYPES as readonly string[]).includes(file.type)) return "JPG, PNG, WEBP 형식의 사진만 올릴 수 있어요.";
  if (file.size > SEAT_CLAIM_MAX_BYTES) return "사진은 5MB 이하여야 해요.";
  if (file.size === 0) return "빈 파일이에요. 다른 사진을 선택해 주세요.";
  return null;
}

export const CLAIM_STATUS_LABEL: Record<SeatClaimStatus, string> = {
  PENDING: "확인 중",
  APPROVED: "승인됨",
  REJECTED: "거절됨",
};

/** 상태 뱃지 클래스 (PENDING 노랑 / APPROVED 초록 계열 / REJECTED 빨강) */
export const CLAIM_BADGE: Record<SeatClaimStatus, string> = {
  PENDING: "inline-flex shrink-0 items-center rounded-full bg-accent-100 px-2.5 py-0.5 text-[0.8125rem]/[normal] text-gray-900",
  APPROVED: "inline-flex shrink-0 items-center rounded-full bg-primary-50 px-2.5 py-0.5 text-[0.8125rem]/[normal] text-primary-800",
  REJECTED: "inline-flex shrink-0 items-center rounded-full bg-red-50 px-2.5 py-0.5 text-[0.8125rem]/[normal] text-red-700",
};
