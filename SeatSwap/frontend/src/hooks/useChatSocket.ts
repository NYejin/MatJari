import { useEffect, useRef, useState } from "react";
import { openRealtime } from "../api/realtime";
import { tokenStorage } from "../api/tokenStorage";
import type { ChatPush, RealtimeStatus } from "../types/chat";

interface Options {
  /** false면 연결하지 않는다 */
  enabled: boolean;
  onPush: (push: ChatPush) => void;
  /** 연결/재연결 직후 (빈 구간 채우기) */
  onConnected: () => void;
}

/**
 * 채팅 화면에 있는 동안만 STOMP 연결을 유지한다. 핸들러는 ref로 들고 있어 바뀌어도 재연결하지 않는다.
 * 같은 탭에서 토큰이 재발급되면(AuthProvider가 만료 60초 전 선제 재발급) 새 토큰으로 다시 연결한다.
 */
export function useChatSocket({ enabled, onPush, onConnected }: Options): RealtimeStatus {
  const [status, setStatus] = useState<RealtimeStatus>("connecting");
  const pushRef = useRef(onPush);
  const connectedRef = useRef(onConnected);
  pushRef.current = onPush;
  connectedRef.current = onConnected;

  useEffect(() => {
    if (!enabled) return;
    const conn = openRealtime({
      onPush: (p) => pushRef.current(p),
      onStatus: setStatus,
      onConnected: () => connectedRef.current(),
    });
    let lastToken = tokenStorage.getAccessToken();
    const unsubscribe = tokenStorage.subscribe(() => {
      const next = tokenStorage.getAccessToken();
      if (next === lastToken) return;
      lastToken = next;
      if (next) conn.reconnect(); // 로그아웃(null)이면 ProtectedRoute가 화면을 벗어나며 close된다
    });
    return () => {
      unsubscribe();
      conn.close();
    };
  }, [enabled]);

  return status;
}
