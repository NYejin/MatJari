import { useEffect, useSyncExternalStore } from "react";
import { useLocation } from "react-router-dom";
import { notificationsApi } from "../api/notifications";

// 안 읽은 알림 개수 저장소 (useSyncExternalStore). 화면이 보일 때만 30초마다 폴링하고,
// 탭이 다시 보이거나 포커스를 얻거나 경로가 바뀔 때, 읽음/삭제 직후에는 즉시 다시 조회한다.
// 폴링은 이 훅(헤더 벨)에서만 돌린다. 다른 화면은 refreshUnreadCount()/adjustUnreadCount()만 부른다.
// 이번 브랜치는 폴링이며 실시간(STOMP)은 다음 브랜치다.

const POLL_INTERVAL_MS = 30_000;

let count = 0;
let generation = 0;
const listeners = new Set<() => void>();

function emit(next: number) {
  if (next === count) return;
  count = next;
  listeners.forEach((l) => l());
}

/** 서버의 안 읽은 개수를 다시 읽는다. 실패하면 이전 값을 유지한다(폴링이라 조용히 넘김) */
export async function refreshUnreadCount(): Promise<void> {
  const gen = ++generation;
  try {
    const res = await notificationsApi.unreadCount();
    if (gen === generation) emit(res.total);
  } catch {
    // 일시 오류는 다음 폴링에서 회복한다
  }
}

/** 읽음 처리 직후 서버 응답을 기다리지 않고 먼저 반영한다 (이어서 refreshUnreadCount로 바로잡는다) */
export function adjustUnreadCount(delta: number): void {
  generation++; // 이미 날아간 낡은 조회 응답이 이 값을 덮어쓰지 않게
  emit(Math.max(0, count + delta));
}

/** 로그아웃 등 사용자가 바뀔 때 초기화 */
function resetUnreadCount(): void {
  generation++;
  emit(0);
}

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

/** 안 읽은 알림 개수. enabled(로그인 상태)일 때만 폴링하고, 사용자가 바뀌거나 로그아웃하면 저장소를 비운다 */
export function useUnreadNotifications(enabled: boolean, userId?: number): number {
  const value = useSyncExternalStore(subscribe, () => count);
  const { pathname } = useLocation();

  useEffect(() => {
    resetUnreadCount();
    return resetUnreadCount;
  }, [userId, enabled]);

  useEffect(() => {
    if (!enabled) return;
    const poll = () => {
      if (document.visibilityState === "visible") void refreshUnreadCount();
    };
    const timer = window.setInterval(poll, POLL_INTERVAL_MS);
    document.addEventListener("visibilitychange", poll);
    window.addEventListener("focus", poll);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", poll);
      window.removeEventListener("focus", poll);
    };
  }, [enabled, userId]);

  // 처음 열릴 때와 경로 이동 때 즉시 재조회
  useEffect(() => {
    if (enabled && document.visibilityState === "visible") void refreshUnreadCount();
  }, [pathname, enabled, userId]);

  return enabled ? value : 0;
}
