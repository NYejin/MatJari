// 탭 사이 로그아웃 신호. 'logout'이라는 사실만 전달하고 토큰은 절대 싣지 않는다.
// BroadcastChannel을 쓰고, 없는 브라우저는 localStorage "storage" 이벤트(값은 시각뿐)로 대신한다.

const CHANNEL_NAME = "matjari-auth";
const FALLBACK_KEY = "matjari-logout-signal";

const channel: BroadcastChannel | null = typeof BroadcastChannel !== "undefined" ? new BroadcastChannel(CHANNEL_NAME) : null;

/** 다른 탭들에 로그아웃을 알린다 (이 탭에는 전달되지 않는다) */
export function broadcastLogout(): void {
  if (channel) {
    channel.postMessage("logout");
    return;
  }
  try {
    localStorage.setItem(FALLBACK_KEY, String(Date.now()));
  } catch {
    // 저장소 접근 불가 — 다른 탭은 다음 재발급 때 로그아웃을 알게 된다
  }
}

/** 다른 탭의 로그아웃 신호를 구독한다. 반환값은 구독 해제 함수 */
export function onLogoutSignal(handler: () => void): () => void {
  if (channel) {
    const listener = (e: MessageEvent) => {
      if (e.data === "logout") handler();
    };
    channel.addEventListener("message", listener);
    return () => channel.removeEventListener("message", listener);
  }
  const listener = (e: StorageEvent) => {
    if (e.key === FALLBACK_KEY) handler();
  };
  window.addEventListener("storage", listener);
  return () => window.removeEventListener("storage", listener);
}
