import type { NotificationType } from "../types/notification";

// 알림 종류별 색 원 + 아이콘 (장식, 보조기기에는 숨김 — 의미는 알림 문구가 전한다).
// 새 제안 primary / 예약 amber / 채팅 sky / 새 후보 coral / 거절·취소 gray. 글자(아이콘)색은 700, 배경은 100.

const TONE: Record<NotificationType, string> = {
  PROPOSAL_RECEIVED: "bg-primary-100 text-primary-700",
  RESERVATION_PROPOSED: "bg-accent-100 text-gray-900",
  MATCH_RESERVED: "bg-accent-100 text-gray-900",
  CHAT_MESSAGE: "bg-sky-100 text-sky-700",
  NEW_CANDIDATE: "bg-coral-100 text-coral-700",
  RESERVATION_REJECTED: "bg-gray-100 text-gray-700",
  RESERVATION_CANCELED: "bg-gray-100 text-gray-700",
};

/** 24x24 선 아이콘 경로 */
const PATH: Record<NotificationType, string> = {
  // 받은 제안(편지)
  PROPOSAL_RECEIVED: "M4 6h16v12H4zM4 7l8 6 8-6",
  // 달력(예약)
  RESERVATION_PROPOSED: "M5 6h14v13H5zM5 10h14M9 4v4M15 4v4",
  MATCH_RESERVED: "M5 12.5l4.5 4.5L19 7.5",
  CHAT_MESSAGE: "M5 5h14v10H11l-4 4v-4H5z",
  // 반짝임(새 후보)
  NEW_CANDIDATE: "M12 4v4M12 16v4M4 12h4M16 12h4M7 7l2 2M15 15l2 2M17 7l-2 2M9 15l-2 2",
  RESERVATION_REJECTED: "M7 7l10 10M17 7L7 17",
  RESERVATION_CANCELED: "M6 12h12",
};

export default function NotificationIcon({ type, muted = false }: { type: NotificationType; muted?: boolean }) {
  return (
    <span
      aria-hidden="true"
      className={`inline-flex size-10 shrink-0 items-center justify-center rounded-full ${muted ? "bg-gray-100 text-gray-500" : TONE[type]}`}
    >
      <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
        <path d={PATH[type]} />
      </svg>
    </span>
  );
}
