/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  /** 채팅 WebSocket 주소. 없으면 VITE_API_BASE_URL에서 계산 (http→ws, /api→/ws) */
  readonly VITE_WS_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
