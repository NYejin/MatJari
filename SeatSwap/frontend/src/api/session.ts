import axios from "axios";
import { requestTokenRefresh } from "./authClient";
import { parseSuspension, saveSuspensionNotice } from "./suspension";
import { getUserFromAccessToken, isTokenExpired, tokenStorage } from "./tokenStorage";
import type { TokenResponse } from "../types/auth";

/**
 * - ok: 재발급 성공, 새 토큰 저장 완료
 * - invalid: refresh token 없음/무효/만료(백엔드 400 또는 로컬 만료) — 토큰 삭제 완료
 * - unavailable: 네트워크 오류/5xx 등 일시 장애 — 토큰 보존. cause는 원인 에러(있으면)
 * - superseded: 요청 중 다른 곳(로그아웃/재로그인/다른 탭)에서 토큰이 바뀜 — 결과 버리고 저장소 기준으로 판단
 */
export type RefreshResult =
  | { status: "ok"; tokens: TokenResponse }
  | { status: "unavailable"; cause?: unknown }
  | { status: "invalid" | "superseded" };

/** 일시 장애로 재발급에 실패한 뒤 같은 refresh token으로 재요청하지 않는 시간 */
export const REFRESH_UNAVAILABLE_COOLDOWN_MS = 5_000;

let inflight: Promise<RefreshResult> | null = null;
/** 마지막 unavailable 결과와 그때의 refresh token. 토큰이 바뀌면(재로그인 등) 쿨다운은 적용되지 않는다 */
let cooldown: { refreshToken: string; until: number; result: RefreshResult } | null = null;

/**
 * 저장된 refresh token으로 세션을 재발급한다. 모듈 단위 single-flight —
 * 초기화, 만료 타이머(AuthProvider), 401 인터셉터(client.ts)가 동시에 호출해도 요청은 1회만 나간다.
 * unavailable로 끝나면 REFRESH_UNAVAILABLE_COOLDOWN_MS 동안은 요청 없이 같은 결과를 바로 돌려준다
 * (장애 중 401이 몰릴 때 refresh 요청 폭주 방지).
 */
export function refreshSession(): Promise<RefreshResult> {
  if (inflight) return inflight;

  if (cooldown) {
    if (cooldown.until > Date.now() && cooldown.refreshToken === tokenStorage.getRefreshToken()) {
      return Promise.resolve(cooldown.result);
    }
    cooldown = null;
  }

  inflight = (async (): Promise<RefreshResult> => {
    const refreshToken = tokenStorage.getRefreshToken();
    if (!refreshToken || isTokenExpired(refreshToken)) {
      tokenStorage.clear();
      return { status: "invalid" };
    }

    const unavailable = (cause?: unknown): RefreshResult => {
      const result: RefreshResult = { status: "unavailable", cause };
      cooldown = { refreshToken, until: Date.now() + REFRESH_UNAVAILABLE_COOLDOWN_MS, result };
      return result;
    };

    try {
      const tokens = await requestTokenRefresh(refreshToken);
      if (tokenStorage.getRefreshToken() !== refreshToken) return { status: "superseded" };
      if (!getUserFromAccessToken(tokens.accessToken)) return unavailable();
      tokenStorage.save(tokens);
      return { status: "ok", tokens };
    } catch (err) {
      if (tokenStorage.getRefreshToken() !== refreshToken) return { status: "superseded" };
      // 정지된 계정(403 ACCOUNT_SUSPENDED): 재시도하지 않고 로그아웃 처리 (안내는 로그인 화면)
      const suspension = parseSuspension(err);
      if (suspension) {
        saveSuspensionNotice(suspension);
        tokenStorage.clear();
        return { status: "invalid" };
      }
      // 백엔드는 무효/만료 refresh token을 SeatSwapException(400)으로 응답한다
      if (axios.isAxiosError(err) && err.response?.status === 400) {
        tokenStorage.clear();
        return { status: "invalid" };
      }
      return unavailable(err);
    }
  })().finally(() => {
    inflight = null;
  });

  return inflight;
}
