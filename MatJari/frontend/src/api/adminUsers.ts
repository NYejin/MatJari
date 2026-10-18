import { apiClient } from "./client";
import type { PageResponse } from "../types/page";

// 관리자: 이용자 관리·조치 기록. 계약: 관리자 권한 확장 API 규약 B절.

export type UserStatusFilter = "ALL" | "ACTIVE" | "SUSPENDED" | "WITHDRAWN";
export type UserRoleFilter = "ALL" | "USER" | "ADMIN";
export type UserRole = "USER" | "ADMIN";

export interface AdminUserItem {
  id: number;
  email: string;
  emailPlaceholder: boolean;
  nickname: string;
  role: UserRole;
  suspended: boolean;
  suspendedUntil: string | null;
  withdrawn: boolean;
  createdAt: string;
}

export interface AdminUserSanction {
  id: number;
  note: string;
  startsAt: string;
  endsAt: string | null;
  revokedAt: string | null;
  createdByNickname: string;
  reportId: number | null;
}

export interface AdminUserDetail extends AdminUserItem {
  passwordLoginEnabled: boolean;
  socialProviders: string[];
  activeTicketCount: number;
  openMatchCount: number;
  openReportsAgainstCount: number;
  withdrawnAt: string | null;
  sanctions: AdminUserSanction[];
}

export interface AdminActionLog {
  id: number;
  adminId: number;
  adminNickname: string;
  action: string;
  targetType: string;
  targetId: number;
  reason: string | null;
  detail: string | null;
  createdAt: string;
}

export const FORCE_WITHDRAW_CONFIRM = "강제 탈퇴";
export const ACTION_REASON_MAX = 300;

export const adminUsersApi = {
  async list(
    f: { query: string; status: UserStatusFilter; role: UserRoleFilter },
    page: number,
    signal?: AbortSignal
  ): Promise<PageResponse<AdminUserItem>> {
    const { data } = await apiClient.get<PageResponse<AdminUserItem>>("/admin/users", {
      params: { query: f.query || undefined, status: f.status, role: f.role, page, size: 20 },
      signal,
    });
    return data;
  },

  async get(id: number, signal?: AbortSignal): Promise<AdminUserDetail> {
    const { data } = await apiClient.get<AdminUserDetail>(`/admin/users/${id}`, { signal });
    return data;
  },

  async suspend(id: number, days: 7 | 30 | null, reason: string): Promise<void> {
    await apiClient.post(`/admin/users/${id}/suspend`, { days, reason });
  },

  async unsuspend(id: number, reason?: string): Promise<void> {
    await apiClient.post(`/admin/users/${id}/unsuspend`, { reason: reason || undefined });
  },

  async changeRole(id: number, role: UserRole, reason: string): Promise<void> {
    await apiClient.put(`/admin/users/${id}/role`, { role, reason });
  },

  async forceWithdraw(id: number, confirmText: string, reason: string): Promise<void> {
    await apiClient.post(`/admin/users/${id}/force-withdraw`, { confirmText, reason });
  },

  async actionLogs(
    f: { action: string; targetType: string; targetId: string },
    page: number,
    signal?: AbortSignal
  ): Promise<PageResponse<AdminActionLog>> {
    const { data } = await apiClient.get<PageResponse<AdminActionLog>>("/admin/action-logs", {
      params: {
        action: f.action || undefined,
        targetType: f.targetType || undefined,
        targetId: f.targetId || undefined,
        page,
        size: 20,
      },
      signal,
    });
    return data;
  },
};

/** 이용자 조치 오류 코드별 문구 (없으면 서버 message) */
export const ADMIN_USER_CODE_MESSAGES: Record<string, string> = {
  LAST_ADMIN: "마지막 관리자는 일반 사용자로 내릴 수 없어요. 다른 관리자를 먼저 지정해 주세요.",
  CANNOT_CHANGE_OWN_ROLE: "내 역할은 직접 바꿀 수 없어요.",
  CANNOT_SANCTION_ADMIN: "관리자 계정에는 이용 정지를 걸 수 없어요. 먼저 일반 사용자로 내려 주세요.",
  ADMIN_CANNOT_WITHDRAW: "관리자는 탈퇴시킬 수 없어요. 먼저 일반 사용자로 내려 주세요.",
  TARGET_WITHDRAWN: "이미 탈퇴한 사용자예요. 화면을 새로 불러와 주세요.",
  CANNOT_PROMOTE_SUSPENDED: "이용 정지 중인 사용자는 관리자로 올릴 수 없어요. 정지를 먼저 해제해 주세요.",
  SANCTION_ALREADY_ACTIVE: "이미 이용 정지 중인 사용자예요.",
  CANNOT_WITHDRAW_SELF: "자기 자신은 강제 탈퇴시킬 수 없어요.",
};

export const ACTION_LABEL: Record<string, string> = {
  PERFORMANCE_HIDE: "공연 내리기",
  PERFORMANCE_UNHIDE: "공연 다시 올리기",
  PERFORMANCE_EDIT: "공연 정보 수정",
  PERFORMANCE_SESSIONS: "공연 회차 수정",
  PERFORMANCE_ZONES: "공연 구역 수정",
  USER_SUSPEND: "이용 정지",
  USER_UNSUSPEND: "정지 해제",
  USER_ROLE_CHANGE: "역할 변경",
  USER_FORCE_WITHDRAW: "강제 탈퇴",
};

export const TARGET_TYPE_LABEL: Record<string, string> = {
  PERFORMANCE: "공연",
  USER: "이용자",
};
