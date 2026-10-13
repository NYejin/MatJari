import axios, { type AxiosResponse, type InternalAxiosRequestConfig } from "axios";
import { SessionRefreshUnavailableError } from "./errors";
import type { RefreshResult } from "./session";
import { isTokenExpired } from "./tokenStorage";

/** 원 요청 1회 재시도 여부 표시 */
type RetriableConfig = InternalAxiosRequestConfig & { _retry?: boolean };

/** 의존성을 주입받는 순수 로직 (client.ts에서 실제 구현을 연결, 테스트에서는 목으로 대체) */
export interface AuthRefreshDeps {
  /** 재시도 요청 실행 (request 인터셉터를 다시 타도록 apiClient 자체를 넘긴다) */
  retry: (config: InternalAxiosRequestConfig) => Promise<AxiosResponse>;
  refreshSession: () => Promise<RefreshResult>;
  getAccessToken: () => string | null;
  /** 토큰 삭제 — tokenStorage 구독을 통해 AuthProvider가 로그아웃 상태로 전환된다 */
  clearTokens: () => void;
}

const BEARER = "Bearer ";

/** 재발급 대상에서 제외하는 인증 엔드포인트 (baseURL의 /api 유무 모두 허용) */
const AUTH_ENDPOINT_PATH = /^(\/api)?\/auth\/(login|signup|refresh)$/;

/**
 * login/signup/refresh 요청인지 판정. 쿼리·해시는 제거하고, 절대 URL이면 pathname 기준으로 본다.
 * 화이트리스트 방식이라 "/auth/..."로 시작하는 다른 보호 API가 생겨도 재발급 대상에서 빠지지 않는다.
 */
export function isAuthEndpoint(url: string | undefined): boolean {
  if (!url) return false;
  let pathname: string;
  try {
    // 상대 경로도 임의 base로 해석해 pathname만 취한다 (쿼리/해시 자동 분리)
    pathname = new URL(url, "http://relative.invalid").pathname;
  } catch {
    return false;
  }
  return AUTH_ENDPOINT_PATH.test(pathname);
}

function tokenSentWith(config: InternalAxiosRequestConfig): string | null {
  const header = config.headers?.Authorization;
  return typeof header === "string" && header.startsWith(BEARER) ? header.slice(BEARER.length) : null;
}

/**
 * apiClient response 인터셉터의 onRejected 핸들러를 만든다.
 * 정책:
 * - 401만 대상 (403 권한 없음, 400, 5xx, 네트워크 오류는 그대로 reject)
 * - login/signup/refresh 요청, 이미 재시도한 요청(_retry)은 재발급하지 않음. 재시도 후에도 401이면 토큰 삭제(=로그아웃)
 * - 그 사이 토큰이 이미 바뀌었고 아직 유효하면(다른 요청의 재발급/다른 탭/재로그인) refresh 없이 최신 토큰으로 재시도.
 *   바뀐 토큰도 만료됐으면 refresh 경로로 간다
 * - refreshSession() 결과:
 *   ok → 최신 토큰으로 1회 재시도 / invalid → (refreshSession이 토큰 삭제) 원 401 reject
 *   unavailable → 토큰 유지, SessionRefreshUnavailableError로 reject (화면에 "로그인 필요" 대신 일시 장애 문구)
 *   superseded → 저장소에 토큰이 있으면 그걸로 재시도, 없으면(로그아웃) 원 401 reject
 */
export function createAuthRefreshHandler(deps: AuthRefreshDeps) {
  return async function onRejected(error: unknown): Promise<AxiosResponse> {
    if (!axios.isAxiosError(error) || !error.config || error.response?.status !== 401) {
      throw error;
    }
    const config = error.config as RetriableConfig;
    if (isAuthEndpoint(config.url)) throw error;

    const sentToken = tokenSentWith(config);

    if (config._retry) {
      // 재발급한 토큰으로도 401: 세션 복구 불가 → 로그아웃. 단, 그 사이 다시 로그인했다면 새 세션은 건드리지 않는다
      if (sentToken !== null && deps.getAccessToken() === sentToken) deps.clearTokens();
      throw error;
    }

    const current = deps.getAccessToken();
    if (current === null) throw error; // 비로그인(또는 그 사이 로그아웃) — 재발급할 세션 없음

    let nextToken: string | null = current;
    if (current === sentToken || isTokenExpired(current)) {
      const result = await deps.refreshSession();
      if (result.status === "invalid") throw error;
      if (result.status === "unavailable") throw new SessionRefreshUnavailableError(result.cause, error);
      // ok/superseded 모두 요청 시점의 저장소 최신값을 쓴다 (superseded+로그아웃이면 null)
      nextToken = deps.getAccessToken();
      if (nextToken === null) throw error;
    }

    config._retry = true;
    config.headers.Authorization = `${BEARER}${nextToken}`;
    return deps.retry(config);
  };
}
