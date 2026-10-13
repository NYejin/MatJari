import type { AuthUser, TokenResponse } from "../types/auth";

// localStorage 키는 여기 한 곳에서만 정의한다 (client.ts 인터셉터와 AuthProvider가 공유)
export const ACCESS_TOKEN_KEY = "accessToken";
export const REFRESH_TOKEN_KEY = "refreshToken";

/**
 * 같은 탭 안의 토큰 변경 알림. (window "storage" 이벤트는 다른 탭에만 오므로 별도 구독 경로가 필요)
 * 401 인터셉터/refreshSession이 토큰을 저장·삭제하면 AuthProvider가 이를 받아 세션 상태를 동기화한다.
 */
type TokenListener = () => void;
const listeners = new Set<TokenListener>();

/** 리스너별로 예외를 격리해 한 구독자의 오류가 다른 구독자나 토큰 저장/삭제 흐름을 깨지 않게 한다 */
function notify(): void {
  listeners.forEach((listener) => {
    try {
      listener();
    } catch (err) {
      console.error("[tokenStorage] listener error", err);
    }
  });
}

// 메서드는 this에 의존하지 않는다 (client.ts에서 함수 참조로 넘김)
export const tokenStorage = {
  getAccessToken: (): string | null => localStorage.getItem(ACCESS_TOKEN_KEY),
  getRefreshToken: (): string | null => localStorage.getItem(REFRESH_TOKEN_KEY),
  save(tokens: Pick<TokenResponse, "accessToken" | "refreshToken">): void {
    localStorage.setItem(ACCESS_TOKEN_KEY, tokens.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, tokens.refreshToken);
    notify();
  },
  clear(): void {
    const hadTokens =
      localStorage.getItem(ACCESS_TOKEN_KEY) !== null || localStorage.getItem(REFRESH_TOKEN_KEY) !== null;
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    if (hadTokens) notify();
  },
  /** 같은 탭 토큰 변경 구독. 반환값은 구독 해제 함수 */
  subscribe(listener: TokenListener): () => void {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
};

interface JwtPayload {
  sub?: string;
  email?: string;
  type?: string;
  exp?: number;
}

/** 서명 검증 없이 payload만 디코딩한다 (검증은 백엔드 책임, 프론트는 표시/만료 판단용). */
function decodePayload(token: string): JwtPayload | null {
  const part = token.split(".")[1];
  if (!part) return null;
  try {
    const base64 = part.replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64 + "=".repeat((4 - (base64.length % 4)) % 4);
    const bytes = Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as JwtPayload;
  } catch {
    return null;
  }
}

/** 만료 직전 토큰을 유효로 오판하지 않도록 둔 여유 시간 */
const EXPIRY_SKEW_MS = 10_000;

/** 토큰 만료 시각(epoch ms). exp 클레임이 없거나 형식이 깨졌으면 null. */
export function getTokenExpiry(token: string): number | null {
  const payload = decodePayload(token);
  return typeof payload?.exp === "number" ? payload.exp * 1000 : null;
}

export function isTokenExpired(token: string): boolean {
  const expiry = getTokenExpiry(token);
  if (expiry === null) return true;
  return expiry - EXPIRY_SKEW_MS <= Date.now();
}

/** access token 클레임(sub, email)에서 사용자 정보를 복원. 형식이 맞지 않으면 null. */
export function getUserFromAccessToken(token: string): AuthUser | null {
  const payload = decodePayload(token);
  if (!payload || payload.type !== "access" || !payload.sub || !payload.email) return null;
  const id = Number(payload.sub);
  if (!Number.isFinite(id)) return null;
  return { id, email: payload.email };
}
