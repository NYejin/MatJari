import { apiClient } from "./client";
import type { ChatMessage, ChatMessagePage, PendingChatMessage } from "../types/chat";

export const CHAT_MAX_LENGTH = 1000;

/** 글자 수는 UTF-16 길이가 아니라 코드 포인트 기준이다 (이모지 1개 = 1자) */
export function countCodePoints(text: string): number {
  return Array.from(text).length;
}

/** 코드 포인트 기준으로 max자까지만 남긴다 (서로게이트 쌍 중간에서 자르지 않는다) */
export function truncateCodePoints(text: string, max: number): string {
  const chars = Array.from(text);
  return chars.length <= max ? text : chars.slice(0, max).join("");
}

export const CHAT_PAGE_SIZE = 30;
export const CHAT_AFTER_SIZE = 100;

// 토큰은 apiClient 인터셉터가 붙인다. 오류 문구는 exchangeErrorMessage(parseApiError)로 변환한다.
export const chatApi = {
  /** POST: 신규 201, 같은 clientMessageId 재전송 200 — 둘 다 같은 메시지를 돌려준다 */
  async send(matchId: number, content: string, clientMessageId: string): Promise<ChatMessage> {
    const { data } = await apiClient.post<ChatMessage>(`/exchange/matches/${matchId}/messages`, { content, clientMessageId });
    return data;
  },

  /** 최신부터 위로 커서(before) 또는 재연결 후 빈 구간(after). 둘 다 없으면 최신 size건 */
  async list(
    matchId: number,
    params: { before?: number; after?: number; size?: number },
    signal?: AbortSignal
  ): Promise<ChatMessagePage> {
    const { data } = await apiClient.get<ChatMessagePage>(`/exchange/matches/${matchId}/messages`, { params, signal });
    return data;
  },

  /** 읽음 포인터 갱신 (204) */
  async markRead(matchId: number, lastReadMessageId: number): Promise<void> {
    await apiClient.put(`/exchange/matches/${matchId}/messages/read`, { lastReadMessageId });
  },
};

// ---- 순수 함수 (화면과 분리) ----

/** id 기준 중복 제거 + 오름차순 정렬. 같은 id가 겹치면 나중에 들어온 값을 쓴다 */
export function mergeMessages(existing: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  if (incoming.length === 0) return existing;
  const byId = new Map<number, ChatMessage>();
  for (const m of existing) byId.set(m.id, m);
  for (const m of incoming) byId.set(m.id, m);
  return [...byId.values()].sort((a, b) => a.id - b.id);
}

/** 서버에 확정된 메시지와 clientMessageId가 같은 대기 항목은 숨긴다 (푸시가 REST 응답보다 먼저 올 수 있다) */
export function visiblePending(pending: PendingChatMessage[], messages: ChatMessage[]): PendingChatMessage[] {
  if (pending.length === 0) return pending;
  const confirmed = new Set(messages.map((m) => m.clientMessageId));
  return pending.filter((p) => !confirmed.has(p.clientMessageId));
}

/** 상대가 읽은 내 마지막 메시지 id ('읽음' 한 줄을 붙일 곳). 없으면 null */
export function findReadMarkerId(messages: ChatMessage[], myUserId: number, counterpartLastReadId: number): number | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].senderId === myUserId) return messages[i].id <= counterpartLastReadId ? messages[i].id : null;
  }
  return null;
}

/** 읽음 PUT으로 보낼 id: 상대 메시지 중 아직 안 읽은 것이 있으면 목록의 마지막 id, 없으면 null */
export function nextReadTarget(messages: ChatMessage[], myUserId: number, reportedReadId: number): number | null {
  const last = messages[messages.length - 1];
  if (!last) return null;
  const hasUnreadFromCounterpart = messages.some((m) => m.senderId !== myUserId && m.id > reportedReadId);
  return hasUnreadFromCounterpart ? last.id : null;
}

/** crypto.randomUUID가 없는 환경(구형 브라우저, http 비보안 컨텍스트) 대체 생성. 서버 규칙: 8~36자 */
export function generateClientMessageId(): string {
  const c = globalThis.crypto;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  const bytes = new Uint8Array(16);
  if (c && typeof c.getRandomValues === "function") c.getRandomValues(bytes);
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
