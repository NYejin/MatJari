import axios from "axios";
import type { TokenResponse } from "../types/auth";

// 기본은 같은 출처의 /api (개발은 vite 프록시, 운영은 nginx). 리프레시 쿠키 mj_rt가 SameSite=Strict라 같은 출처여야 간다.
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? "/api";

/**
 * 인터셉터를 거치지 않는 인증 전용 클라이언트 (토큰 재발급·로그아웃 등).
 * apiClient의 401 재시도 인터셉터를 타지 않으므로 refresh 요청이 재귀/무한 재시도되지 않는다.
 * 쿠키(mj_rt)를 받고 보내야 하므로 withCredentials를 켠다.
 * client.ts <-> session.ts 순환 import를 피하려고 별도 모듈로 둔다.
 */
export const authClient = axios.create({ baseURL: API_BASE_URL, withCredentials: true });

/** refresh·logout은 이 헤더가 없으면 서버가 403으로 거절한다 (CSRF 방어) */
const CSRF_HEADERS = { "X-Requested-With": "XMLHttpRequest" } as const;

/** POST /api/auth/refresh — 쿠키 mj_rt로 새 액세스 토큰을 받고 쿠키는 서버가 회전한다. 실패 401(정지 403) */
export async function requestTokenRefresh(): Promise<TokenResponse> {
  const { data } = await authClient.post<TokenResponse>("/auth/refresh", null, { headers: CSRF_HEADERS });
  return data;
}

/** POST /api/auth/logout — 항상 204 */
export async function requestLogout(): Promise<void> {
  await authClient.post("/auth/logout", null, { headers: CSRF_HEADERS });
}
