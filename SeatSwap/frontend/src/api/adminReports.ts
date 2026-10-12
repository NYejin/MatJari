import { apiClient } from "./client";
import type { PageResponse } from "../types/page";

// 관리자: 신고 처리·이용 정지(제재)·차단 현황. 계약: 차단/신고 설계 문서 'JSON 계약' 관리자 부분.

export type ReportReason = "ABUSE" | "FRAUD" | "NO_SHOW" | "FAKE_SEAT" | "OTHER";
export type ReportStatus = "OPEN" | "RESOLVED" | "DISMISSED";

export const REPORT_REASON_LABEL: Record<ReportReason, string> = {
  ABUSE: "욕설·괴롭힘",
  FRAUD: "사기 의심·허위 예매내역",
  NO_SHOW: "연락 두절·교환 불이행",
  FAKE_SEAT: "허위 좌석",
  OTHER: "기타",
};

export const REPORT_STATUS_LABEL: Record<ReportStatus, string> = {
  OPEN: "처리 대기",
  RESOLVED: "조치함",
  DISMISSED: "기각",
};

export interface UserRef {
  id: number;
  nickname: string;
}

export interface AdminReport {
  id: number;
  matchId: number;
  reason: ReportReason;
  detail: string | null;
  status: ReportStatus;
  createdAt: string;
  handledAt: string | null;
  handlerNickname: string | null;
  handlerNote: string | null;
  reporter: UserRef;
  reported: UserRef & {
    totalReports: number;
    openReports: number;
    activeSanction: { id: number; until: string | null } | null;
  };
}

export interface ReportMessage {
  id: number;
  senderId: number;
  senderNickname: string;
  content: string;
  createdAt: string;
}

export interface ReportMessagesResponse {
  messages: ReportMessage[];
  hasMore: boolean;
}

export interface AdminSanction {
  id: number;
  userId: number;
  nickname: string;
  type: string;
  note: string;
  startsAt: string;
  endsAt: string | null;
  revokedAt: string | null;
  active: boolean;
  createdByNickname: string;
  reportId: number | null;
}

export interface AdminBlock {
  id: number;
  blocker: UserRef;
  blocked: UserRef;
  createdAt: string;
}

export interface ResolveInput {
  note?: string;
  sanction?: { days: 7 | 30 | null; note: string };
}

export const REPORT_NOTE_MAX = 300;
export const SANCTION_NOTE_MAX = 300;

export type SanctionFilter = "true" | "all";

export const adminReportsApi = {
  async list(
    filter: { status?: ReportStatus; reason?: ReportReason },
    page: number,
    signal?: AbortSignal
  ): Promise<PageResponse<AdminReport>> {
    const { data } = await apiClient.get<PageResponse<AdminReport>>("/admin/reports", {
      params: { status: filter.status, reason: filter.reason, page, size: 20 },
      signal,
    });
    return data;
  },

  async get(id: number, signal?: AbortSignal): Promise<AdminReport> {
    const { data } = await apiClient.get<AdminReport>(`/admin/reports/${id}`, { signal });
    return data;
  },

  /** before=이미 받은 가장 오래된 메시지 id. 최신부터 위로 */
  async messages(id: number, before?: number, signal?: AbortSignal): Promise<ReportMessagesResponse> {
    const { data } = await apiClient.get<ReportMessagesResponse>(`/admin/reports/${id}/messages`, {
      params: { before, size: 50 },
      signal,
    });
    return data;
  },

  async resolve(id: number, input: ResolveInput): Promise<AdminReport> {
    const { data } = await apiClient.post<AdminReport>(`/admin/reports/${id}/resolve`, input);
    return data;
  },

  async dismiss(id: number, note?: string): Promise<AdminReport> {
    const { data } = await apiClient.post<AdminReport>(`/admin/reports/${id}/dismiss`, { note });
    return data;
  },

  async sanctions(active: SanctionFilter, page: number, signal?: AbortSignal): Promise<PageResponse<AdminSanction>> {
    const { data } = await apiClient.get<PageResponse<AdminSanction>>("/admin/sanctions", {
      params: { active: active === "true" ? true : undefined, page, size: 20 },
      signal,
    });
    return data;
  },

  async revokeSanction(id: number): Promise<AdminSanction> {
    const { data } = await apiClient.post<AdminSanction>(`/admin/sanctions/${id}/revoke`);
    return data;
  },

  async blocks(page: number, signal?: AbortSignal): Promise<PageResponse<AdminBlock>> {
    const { data } = await apiClient.get<PageResponse<AdminBlock>>("/admin/blocks", { params: { page, size: 20 }, signal });
    return data;
  },
};

/** 관리자 신고·제재 화면 오류 코드별 문구 (없으면 서버 message) */
export const ADMIN_REPORT_CODE_MESSAGES: Record<string, string> = {
  REPORT_ALREADY_HANDLED: "이미 처리된 신고예요. 화면을 새로 불러와 주세요.",
  CANNOT_SANCTION_ADMIN: "관리자 계정에는 이용 정지를 걸 수 없어요. 이용 정지 없이 처리하거나 기각해 주세요.",
  SANCTION_NOT_ACTIVE: "이미 해제됐거나 기간이 끝난 제재예요.",
  SANCTION_ALREADY_ACTIVE: "이미 이용 정지 중인 사용자예요. 정지 없이 처리하거나 제재를 먼저 해제해 주세요.",
};

/** 제재 종료 시각 표기 (오프셋 없는 KST 문자열). null=무기한 */
export function formatUntil(until: string | null): string {
  return until ? `${until.replace("T", " ").slice(0, 16)}까지` : "무기한";
}
