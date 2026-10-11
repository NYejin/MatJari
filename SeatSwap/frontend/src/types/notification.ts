import type { MatchRole, MatchStatus } from "./exchange";

// 알림 (feature/notifications). 계약: 알림 설계 문서(V10 notification, /api/notifications).
export type NotificationType = "PROPOSAL_RECEIVED" | "MATCH_RESERVED" | "RESERVATION_CANCELED";

/** 알림에 걸린 자리 (교환 전 자리). 시각은 없다 */
export interface NotificationSeat {
  zone: string;
  row: string;
  col: string;
}

/** GET /api/notifications 항목. 닉네임·좌석은 조회 때 조인한 값이다 */
export interface AppNotification {
  id: number;
  type: NotificationType;
  matchId: number;
  read: boolean;
  readAt: string | null;
  createdAt: string;
  matchStatus: MatchStatus;
  /** 매칭이 CANCELED/COMPLETED. 알림을 지우거나 개수에서 빼지 않고 회색 '종료된 매칭'으로만 표시한다 */
  matchClosed: boolean;
  role: Exclude<MatchRole, "ALL">;
  counterpartNickname: string;
  performanceTitle: string;
  mySeat: NotificationSeat;
  counterpartSeat: NotificationSeat;
}

/** GET /api/notifications/unread-count. 종류별 키는 0이어도 항상 포함 */
export interface UnreadCount {
  total: number;
  byType: Record<NotificationType, number>;
}
