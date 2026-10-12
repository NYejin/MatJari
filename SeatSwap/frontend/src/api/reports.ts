import { apiClient } from "./client";
import type { PageResponse } from "../types/page";

// 신고 API (로그인 필요). 신고는 매칭(채팅) 화면에서만 낸다. 신고자에게는 처리 상태만 보인다(메모·제재 내용은 숨김).

export type ReportReason = "ABUSE" | "FRAUD" | "NO_SHOW" | "FAKE_SEAT" | "OTHER";
export type ReportStatus = "OPEN" | "RESOLVED" | "DISMISSED";

export const REPORT_DETAIL_MAX = 500;

export const REPORT_REASONS: { value: ReportReason; label: string; help: string }[] = [
  { value: "ABUSE", label: "욕설·괴롭힘", help: "모욕, 협박, 원치 않는 연락 등" },
  { value: "FRAUD", label: "사기 의심·허위 예매내역", help: "예매내역을 조작했거나 사기가 의심돼요" },
  { value: "NO_SHOW", label: "연락 두절·교환 불이행", help: "약속한 교환을 하지 않거나 연락이 끊겼어요" },
  { value: "FAKE_SEAT", label: "허위 좌석", help: "없는 좌석이거나 본인 좌석이 아니에요" },
  { value: "OTHER", label: "기타", help: "아래에 내용을 적어 주세요 (필수)" },
];

export const REASON_LABEL: Record<ReportReason, string> = {
  ABUSE: "욕설·괴롭힘",
  FRAUD: "사기 의심·허위 예매내역",
  NO_SHOW: "연락 두절·교환 불이행",
  FAKE_SEAT: "허위 좌석",
  OTHER: "기타",
};

export const REPORT_STATUS_LABEL: Record<ReportStatus, string> = {
  OPEN: "접수됨",
  RESOLVED: "처리 완료",
  DISMISSED: "검토 완료",
};

export interface ReportCreated {
  id: number;
  status: "OPEN";
  createdAt: string;
}

export interface MyReport {
  id: number;
  matchId: number;
  reportedNickname: string;
  reason: ReportReason;
  status: ReportStatus;
  createdAt: string;
  handledAt: string | null;
}

export const reportsApi = {
  /** POST /api/exchange/matches/{matchId}/report — 같은 매칭에 OPEN 신고는 1건(409), 사용자당 OPEN 10건(422) */
  async create(matchId: number, body: { reason: ReportReason; detail?: string }): Promise<ReportCreated> {
    const { data } = await apiClient.post<ReportCreated>(`/exchange/matches/${matchId}/report`, body);
    return data;
  },

  /** GET /api/reports/me?page&size */
  async listMine(page: number, size = 20, signal?: AbortSignal): Promise<PageResponse<MyReport>> {
    const { data } = await apiClient.get<PageResponse<MyReport>>("/reports/me", { params: { page, size }, signal });
    return data;
  },
};
