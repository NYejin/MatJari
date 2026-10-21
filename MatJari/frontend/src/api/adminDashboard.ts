import { apiClient } from "./client";

// 관리자 운영 대시보드 (GET /api/admin/dashboard, ADMIN 전용). 시각은 KST 현지 시각 문자열.
export type Row = Record<string, string | number | boolean | null>;

export interface DashboardDaily {
  date: string;
  signups: number;
  tickets: number;
  matches: number;
  completed: number;
  messages: number;
  reports: number;
  aiCalls: number;
}

export interface DashboardAlert {
  level: "danger" | "warn" | "info" | "ok";
  text: string;
  link: string | null;
}

export interface AdminDashboard {
  generatedAt: string;
  kpis: {
    users: Record<string, number>;
    performances: Record<string, number>;
    tickets: Record<string, number>;
    requests: Record<string, number>;
    matches: Record<string, number>;
    chat: Record<string, number>;
    queues: Record<string, number | string | null>;
    ai: Record<string, number>;
  };
  daily: DashboardDaily[];
  openReportReasons: Record<string, number>;
  matchStatus: Record<string, number>;
  topPerformances: Row[];
  upcomingSessions: Row[];
  openReports: Row[];
  pendingClaims: Row[];
  pendingProposals: Row[];
  recentActions: Row[];
  recentUsers: Row[];
  alerts: DashboardAlert[];
}

export const adminDashboardApi = {
  async get(signal?: AbortSignal): Promise<AdminDashboard> {
    const { data } = await apiClient.get<AdminDashboard>("/admin/dashboard", { signal });
    return data;
  },
};
