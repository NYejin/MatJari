import { Client } from "@stomp/stompjs";
import { API_BASE_URL } from "./authClient";
import { refreshSession } from "./session";
import { isTokenExpired, tokenStorage } from "./tokenStorage";
import type { ChatPush, RealtimeStatus } from "../types/chat";

// STOMP 연결 하나를 관리한다 (채팅 화면에 있는 동안에만 연다). 토큰은 STOMP CONNECT 네이티브 헤더로만 보낸다(쿼리스트링 금지).
// 구독은 /user/queue/chat 하나뿐이고, 클라이언트 SEND는 쓰지 않는다(보내기는 REST).

export const CHAT_QUEUE = "/user/queue/chat";
const BACKOFF_MIN_MS = 1_000;
const BACKOFF_MAX_MS = 30_000;
const HEARTBEAT_MS = 10_000;

/**
 * WS URL 계산 (순수 함수). wsUrlEnv(VITE_WS_URL)가 있으면 그대로,
 * 없으면 API 기준 주소의 http→ws(https→wss), 끝의 /api→/ws.
 * apiBase가 상대 경로(/api)면 origin(예: window.location.origin)을 기준으로 푼다.
 */
export function computeWsUrl(wsUrlEnv: string | undefined, apiBase: string, origin: string): string {
  const explicit = wsUrlEnv?.trim();
  if (explicit) return explicit;
  const url = new URL(apiBase, origin);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = url.pathname.replace(/\/api\/?$/, "") + "/ws";
  url.search = "";
  url.hash = "";
  return url.toString();
}

/** 지수 백오프(1s, 2s, 4s ... 최대 30s). attempt는 0부터 */
export function backoffDelay(attempt: number): number {
  const safe = Math.max(0, Math.min(attempt, 10));
  return Math.min(BACKOFF_MAX_MS, BACKOFF_MIN_MS * 2 ** safe);
}

/** 푸시 payload 검증. 알 수 없는 모양은 null (무시) */
export function parsePush(body: string): ChatPush | null {
  try {
    const v: unknown = JSON.parse(body);
    if (typeof v !== "object" || v === null) return null;
    const o = v as Record<string, unknown>;
    if (typeof o.matchId !== "number") return null;
    if (o.type === "MESSAGE" && typeof o.message === "object" && o.message !== null) {
      const m = o.message as Record<string, unknown>;
      if (typeof m.id === "number" && typeof m.senderId === "number" && typeof m.content === "string") {
        return v as ChatPush;
      }
      return null;
    }
    if (o.type === "READ" && typeof o.lastReadMessageId === "number") return v as ChatPush;
    return null;
  } catch {
    return null;
  }
}

export interface RealtimeHandlers {
  onPush: (push: ChatPush) => void;
  onStatus: (status: RealtimeStatus) => void;
  /** 연결(재연결 포함)되어 구독이 끝난 직후 — 빈 구간 채우기용 */
  onConnected: () => void;
}

export interface RealtimeConnection {
  /** 끊고 새 토큰으로 다시 연결 (토큰이 재발급됐을 때) */
  reconnect: () => void;
  close: () => void;
}

export function openRealtime(handlers: RealtimeHandlers): RealtimeConnection {
  let closed = false;
  let attempt = 0;
  let authFailed = false;
  let authRetried = false;
  let forceRefresh = false;

  const client = new Client({
    brokerURL: computeWsUrl(import.meta.env.VITE_WS_URL, API_BASE_URL, window.location.origin),
    heartbeatIncoming: HEARTBEAT_MS,
    heartbeatOutgoing: HEARTBEAT_MS,
    connectionTimeout: 10_000,
    reconnectDelay: BACKOFF_MIN_MS,
    beforeConnect: async () => {
      let token = tokenStorage.getAccessToken();
      if (forceRefresh || !token || isTokenExpired(token)) {
        forceRefresh = false;
        await refreshSession(); // 결과는 저장소에 반영된다 (실패하면 옛 토큰으로 시도 → 서버가 거부)
        token = tokenStorage.getAccessToken();
      }
      client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
    },
    onConnect: () => {
      if (closed) return;
      attempt = 0;
      authFailed = false;
      authRetried = false;
      client.subscribe(CHAT_QUEUE, (frame) => {
        const push = parsePush(frame.body);
        if (push) handlers.onPush(push);
      });
      handlers.onStatus("connected");
      handlers.onConnected();
    },
    // 인증 실패 등 서버 ERROR 프레임: 다음 close 처리에서 한 번만 재시도한다
    onStompError: () => {
      authFailed = true;
    },
    onWebSocketClose: () => {
      if (closed) return;
      if (authFailed) {
        authFailed = false;
        if (authRetried) {
          void client.deactivate();
          handlers.onStatus("failed");
          return;
        }
        authRetried = true;
        forceRefresh = true;
        client.reconnectDelay = BACKOFF_MIN_MS;
        handlers.onStatus("reconnecting");
        return;
      }
      client.reconnectDelay = backoffDelay(attempt);
      attempt += 1;
      handlers.onStatus("reconnecting");
    },
  });

  handlers.onStatus("connecting");
  client.activate();

  return {
    reconnect: () => {
      if (closed) return;
      handlers.onStatus("connecting");
      attempt = 0;
      void client.deactivate().then(() => {
        if (!closed) client.activate();
      });
    },
    close: () => {
      closed = true;
      void client.deactivate();
    },
  };
}
