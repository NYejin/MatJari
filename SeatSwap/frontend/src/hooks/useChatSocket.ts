import { useEffect, useRef, useSyncExternalStore } from "react";
import { acquireRealtime, getRealtimeStatus, subscribeChatPush, subscribeConnected, subscribeRealtimeStatus } from "../api/realtimeHub";
import type { ChatPush, RealtimeStatus } from "../types/chat";

interface Options {
  /** false면 구독하지 않는다 */
  enabled: boolean;
  onPush: (push: ChatPush) => void;
  /** 연결/재연결 직후 (빈 구간 채우기). 이미 연결돼 있으면 구독 직후 한 번 불린다 */
  onConnected: () => void;
}

/**
 * 채팅 화면의 푸시 수신. 연결 자체는 앱 전체가 공유하므로(realtimeHub) 이 화면은 참조를 잡고 구독만 한다.
 * 핸들러는 ref로 들고 있어 바뀌어도 다시 구독하지 않는다. 토큰 재발급 시 재연결은 허브가 처리한다.
 */
export function useChatSocket({ enabled, onPush, onConnected }: Options): RealtimeStatus {
  const status = useSyncExternalStore(subscribeRealtimeStatus, getRealtimeStatus);
  const pushRef = useRef(onPush);
  const connectedRef = useRef(onConnected);
  pushRef.current = onPush;
  connectedRef.current = onConnected;

  useEffect(() => {
    if (!enabled) return;
    const release = acquireRealtime();
    const offPush = subscribeChatPush((p) => pushRef.current(p));
    const offConnected = subscribeConnected(() => connectedRef.current());
    return () => {
      offConnected();
      offPush();
      release();
    };
  }, [enabled]);

  return enabled ? status : "connecting";
}
