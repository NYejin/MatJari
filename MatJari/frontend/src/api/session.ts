import axios from "axios";
import { requestTokenRefresh } from "./authClient";
import { parseSuspension, saveSuspensionNotice } from "./suspension";
import { getUserFromAccessToken, tokenStorage } from "./tokenStorage";
import type { TokenResponse } from "../types/auth";

/**
 * - ok: 재발급 성공, 새 액세스 토큰을 메모리에 저장 완료
 * - invalid: 쿠키 없음/무효/만료/재사용(백엔드 401) 또는 정지(403) — 메모리 토큰 삭제 완료
 * - unavailable: 네트워크 오류/5xx 등 일시 장애 — 토큰 보존. cause는 원인 에러(있으면)
 * - superseded: 요청 중 다른 곳(로그아웃/재로그인)에서 토큰이 바뀜 — 결과 버리고 저장소 기준으로 판단
 */
export type RefreshResult =
  | { status: "ok"; tokens: TokenResponse }
  | { status: "unavailable"; cause?: unknown }
  | { status: "invalid" | "superseded" };

/** 일시 장애로 재발급에 실패한 뒤 다시 요청하지 않는 시간 */
export const REFRESH_UNAVAILABLE_COOLDOWN_MS = 5_000;

/** 탭 사이 직렬화용 Web Lock 이름 */
const REFRESH_LOCK_NAME = "matjari-refresh";

let inflight: Promise<RefreshResult> | null = null;
let cooldown: { until: number; result: RefreshResult } | null = null;

async function doRefresh(): Promise<RefreshResult> {
  const before = tokenStorage.getAccessToken();
  const unavailable = (cause?: unknown): RefreshResult => {
    const result: RefreshResult = { status: "unavailable", cause };
    cooldown = { until: Date.now() + REFRESH_UNAVAILABLE_COOLDOWN_MS, result };
    return result;
  };

  try {
    // 락을 얻은 뒤 호출하므로, 다른 탭이 먼저 회전했어도 브라우저 쿠키는 이미 새 값이다
    const tokens = await requestTokenRefresh();
    if (tokenStorage.getAccessToken() !== before) return { status: "superseded" };
    if (!getUserFromAccessToken(tokens.accessToken)) return unavailable();
    tokenStorage.save(tokens);
    return { status: "ok", tokens };
  } catch (err) {
    if (tokenStorage.getAccessToken() !== before) return { status: "superseded" };
    // 정지된 계정(403 ACCOUNT_SUSPENDED): 재시도하지 않고 로그아웃 처리 (안내는 로그인 화면)
    const suspension = parseSuspension(err);
    if (suspension) {
      saveSuspensionNotice(suspension);
      tokenStorage.clear();
      return { status: "invalid" };
    }
    // 쿠키 없음·만료·폐기·재사용·탈퇴는 401
    if (axios.isAxiosError(err) && err.response?.status === 401) {
      tokenStorage.clear();
      return { status: "invalid" };
    }
    return unavailable(err);
  }
}

/**
 * HttpOnly 쿠키(mj_rt)로 액세스 토큰을 재발급한다.
 * - 탭 안: 모듈 단위 single-flight — 시작 시 재발급, 만료 타이머, 401 인터셉터, STOMP 재연결이 동시에 불러도 요청은 1회.
 * - 탭 사이: navigator.locks('matjari-refresh')로 직렬화한다 (같은 쿠키로 동시에 refresh하면 서버가 재사용으로 보고 계열을 폐기한다).
 *   지원하지 않는 브라우저는 탭 내 single-flight만 적용한다.
 * unavailable로 끝나면 REFRESH_UNAVAILABLE_COOLDOWN_MS 동안은 요청 없이 같은 결과를 돌려준다.
 */
export function refreshSession(): Promise<RefreshResult> {
  if (inflight) return inflight;

  if (cooldown) {
    if (cooldown.until > Date.now()) return Promise.resolve(cooldown.result);
    cooldown = null;
  }

  const locks = typeof navigator !== "undefined" ? navigator.locks : undefined;
  const run: Promise<RefreshResult> = locks
    ? (async () => await locks.request(REFRESH_LOCK_NAME, doRefresh))()
    : doRefresh();
  const tracked = run.finally(() => {
    inflight = null;
  });
  inflight = tracked;
  return tracked;
}
