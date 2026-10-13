/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  /** 채팅 WebSocket 주소. 없으면 VITE_API_BASE_URL에서 계산 (http→ws, /api→/ws) */
  readonly VITE_WS_URL?: string;
  /** 개인정보처리방침 운영자 정보 (미설정이면 페이지에 설정 필요 표시) */
  readonly VITE_PRIVACY_OPERATOR?: string;
  readonly VITE_PRIVACY_CONTACT_EMAIL?: string;
  /** 시행일 (예: 2026-10-13) */
  readonly VITE_PRIVACY_EFFECTIVE_DATE?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
