import { apiClient } from "./client";
import { formatSeat } from "./seat";
import { kstEpoch } from "./dateTime";
import type { PageResponse } from "../types/page";
import type { AppNotification, NotificationType, UnreadCount } from "../types/notification";

// 알림 API (모두 로그인 필요, 본인 것만, 남의 알림은 404)
export const notificationsApi = {
  /** GET /api/notifications?page&size&unreadOnly — id 내림차순(최신순) */
  async list(page: number, size = 20, signal?: AbortSignal): Promise<PageResponse<AppNotification>> {
    const { data } = await apiClient.get<PageResponse<AppNotification>>("/notifications", {
      params: { page, size, unreadOnly: false },
      signal,
    });
    return data;
  },

  /** GET /api/notifications/unread-count — 닫힌 매칭의 알림도 포함한 안 읽은 개수 */
  async unreadCount(signal?: AbortSignal): Promise<UnreadCount> {
    const { data } = await apiClient.get<UnreadCount>("/notifications/unread-count", { signal });
    return data;
  },

  /** POST /api/notifications/{id}/read → 204 (멱등) */
  async markRead(id: number): Promise<void> {
    await apiClient.post(`/notifications/${id}/read`);
  },

  /** POST /api/notifications/read-all?type= → 204 (type 생략 = 전체) */
  async markAllRead(type?: NotificationType): Promise<void> {
    await apiClient.post("/notifications/read-all", null, { params: type ? { type } : undefined });
  },

  /** DELETE /api/notifications/read → 200 {deleted}. 읽은 알림만 지우고 안 읽은 알림은 지우지 않는다 */
  async deleteRead(): Promise<number> {
    const { data } = await apiClient.delete<{ deleted: number }>("/notifications/read");
    return data.deleted;
  },
};

/** 알림 문구 (이름·공연·좌석은 조회 때 조인한 값) */
export function notificationText(n: AppNotification): string {
  const name = n.counterpartNickname;
  switch (n.type) {
    case "PROPOSAL_RECEIVED":
      return `${name}님이 교환을 제안했어요 · ${n.performanceTitle} ${formatSeat(n.counterpartSeat)}`;
    case "MATCH_RESERVED":
      return `${name}님이 교환을 예약했어요. 내 티켓도 예약 중으로 잠겼어요.`;
    case "RESERVATION_CANCELED":
      return `${name}님이 예약을 취소했어요. 다시 채팅 중이에요.`;
  }
}

/** 알림을 누르면 이동할 내 매칭 주소 (제안 알림은 받은 제안 탭, 해당 카드로) */
export function notificationPath(n: AppNotification): string {
  const query = new URLSearchParams();
  if (n.type === "PROPOSAL_RECEIVED") query.set("role", "RECEIVED");
  query.set("matchId", String(n.matchId));
  return `/exchange/matches?${query.toString()}`;
}

/** "방금 전 / N분 전 / N시간 전 / N일 전". createdAt은 오프셋 없는 KST 현지 시각으로 본다(회차 시각과 같은 규칙) */
export function formatRelativeTime(createdAt: string, now: number = Date.now()): string {
  const epoch = kstEpoch(createdAt);
  if (epoch === null) return createdAt;
  const minutes = Math.floor(Math.max(0, now - epoch) / 60_000);
  if (minutes < 1) return "방금 전";
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
}
