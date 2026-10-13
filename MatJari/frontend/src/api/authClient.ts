import axios from "axios";
import type { RefreshRequest, TokenResponse } from "../types/auth";

export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api";

/**
 * 인터셉터를 거치지 않는 인증 전용 클라이언트 (토큰 재발급 등).
 * apiClient의 401 재시도 인터셉터를 타지 않으므로 refresh 요청이 재귀/무한 재시도되지 않는다.
 * client.ts <-> session.ts 순환 import를 피하려고 별도 모듈로 둔다.
 */
export const authClient = axios.create({ baseURL: API_BASE_URL });

/** POST /api/auth/refresh — 성공 시 access+refresh 재발급, 무효 refresh token이면 400 */
export async function requestTokenRefresh(refreshToken: string): Promise<TokenResponse> {
  const body: RefreshRequest = { refreshToken };
  const { data } = await authClient.post<TokenResponse>("/auth/refresh", body);
  return data;
}
