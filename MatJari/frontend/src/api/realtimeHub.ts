import { openRealtime, type RealtimeConnection } from "./realtime";
import { tokenStorage } from "./tokenStorage";
import type { ChatPush, RealtimeStatus } from "../types/chat";
import type { NotificationPush } from "../types/notification";

// 앱 전체가 공유하는 STOMP 연결 하나. 헤더 벨(알림 푸시)과 채팅 화면이 각자 acquireRealtime()으로 참조를 잡고,
// 마지막 참조가 풀리면 연결을 닫는다. 사용자당 동시 연결 한도(5개)를 탭마다 1개만 쓰기 위해 연결을 나누지 않는다.
// 푸시 종류별 구독자는 subscribe*로 등록하고, 연결(재연결 포함)이 완료되면 onConnected 구독자를 부른다.

let connection: RealtimeConnection | null = null;
let refs = 0;
let status: RealtimeStatus = "connecting";
let unwatchToken: (() => void) | null = null;

const statusListeners = new Set<() => void>();
const chatListeners = new Set<(push: ChatPush) => void>();
const notificationListeners = new Set<(push: NotificationPush) => void>();
const connectedListeners = new Set<() => void>();

function setStatus(next: RealtimeStatus) {
  if (next === status) return;
  status = next;
  statusListeners.forEach((l) => l());
}

export function getRealtimeStatus(): RealtimeStatus {
  return status;
}

export function subscribeRealtimeStatus(listener: () => void): () => void {
  statusListeners.add(listener);
  return () => {
    statusListeners.delete(listener);
  };
}

function open() {
  setStatus("connecting");
  connection = openRealtime({
    onPush: (p) => chatListeners.forEach((l) => l(p)),
    onNotification: (p) => notificationListeners.forEach((l) => l(p)),
    onStatus: setStatus,
    onConnected: () => connectedListeners.forEach((l) => l()),
  });
  // 같은 탭에서 토큰이 재발급되면(만료 60초 전 선제 재발급) 새 토큰으로 다시 연결한다.
  // 로그아웃(null)이면 화면이 사라지며 참조가 풀려 닫힌다.
  let lastToken = tokenStorage.getAccessToken();
  unwatchToken = tokenStorage.subscribe(() => {
    const next = tokenStorage.getAccessToken();
    if (next === lastToken) return;
    lastToken = next;
    if (next) connection?.reconnect();
  });
}

/** 연결 참조를 잡는다(첫 참조가 연결을 연다). 반환된 함수로 한 번만 풀어 준다 */
export function acquireRealtime(): () => void {
  if (refs++ === 0) open();
  let released = false;
  return () => {
    if (released) return;
    released = true;
    if (--refs === 0) {
      unwatchToken?.();
      unwatchToken = null;
      connection?.close();
      connection = null;
      setStatus("connecting");
    }
  };
}

export function subscribeChatPush(listener: (push: ChatPush) => void): () => void {
  chatListeners.add(listener);
  return () => {
    chatListeners.delete(listener);
  };
}

export function subscribeNotificationPush(listener: (push: NotificationPush) => void): () => void {
  notificationListeners.add(listener);
  return () => {
    notificationListeners.delete(listener);
  };
}

/** 연결 완료 때마다 부른다. 이미 연결돼 있으면 등록 직후 한 번 바로 부른다(늦게 합류한 화면이 빈 구간을 채우도록) */
export function subscribeConnected(listener: () => void): () => void {
  connectedListeners.add(listener);
  let active = true;
  if (status === "connected") {
    window.setTimeout(() => {
      if (active) listener();
    }, 0);
  }
  return () => {
    active = false;
    connectedListeners.delete(listener);
  };
}
