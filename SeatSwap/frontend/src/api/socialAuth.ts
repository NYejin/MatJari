import { API_BASE_URL, authClient } from "./authClient";
import { suspensionMessage } from "./suspension";
import type { TokenResponse } from "../types/auth";

// 소셜 로그인(카카오·구글·네이버). 백엔드가 OAuth 흐름을 처리하고, 프런트는 버튼 이동과 일회용 코드 교환만 한다.
// 인터셉터가 없는 authClient를 쓴다 (토큰을 붙이지 않고, 403 정지는 호출부가 직접 처리).

export type SocialProvider = "google" | "naver" | "kakao";

/** 화면 표시 순서(백엔드가 준 활성 제공자만 이 순서로 보여 준다) */
export const SOCIAL_PROVIDER_ORDER: readonly SocialProvider[] = ["google", "naver", "kakao"];

/** GET /api/auth/social/providers — 활성 제공자만. 알 수 없는 값은 버린다 */
export async function fetchSocialProviders(): Promise<SocialProvider[]> {
  const { data } = await authClient.get<{ providers?: unknown }>("/auth/social/providers");
  const list: unknown[] = Array.isArray(data?.providers) ? data.providers : [];
  return SOCIAL_PROVIDER_ORDER.filter((p) => list.includes(p));
}

/** 브라우저를 보낼 URL (VITE_API_BASE_URL 기준, 끝의 슬래시 정리) */
export function socialAuthorizeUrl(provider: SocialProvider): string {
  return `${API_BASE_URL.replace(/\/+$/, "")}/auth/social/${provider}/authorize`;
}

/**
 * POST /api/auth/social/exchange — 일회용 로그인 코드를 토큰으로 바꾼다.
 * 코드는 1회용이므로 같은 코드로 중복 호출하지 않는다 (호출부: SocialCallbackPage의 모듈 수준 in-flight 공유).
 */
export async function exchangeSocialCode(code: string): Promise<TokenResponse> {
  const { data } = await authClient.post<TokenResponse>("/auth/social/exchange", { code });
  return data;
}

const GENERIC_ERROR = "소셜 로그인에 실패했어요. 잠시 뒤 다시 시도해 주세요.";

const ERROR_MESSAGES: Record<string, string> = {
  INVALID_STATE: "로그인 시간이 지났거나 올바르지 않은 요청이에요. 다시 시도해 주세요.",
  ACCESS_DENIED: "로그인을 취소했어요.",
  PROVIDER_ERROR: "소셜 로그인 서비스와 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.",
  EMAIL_EXISTS: "이미 같은 이메일로 가입한 계정이 있어요. 이메일과 비밀번호로 로그인해 주세요.",
};

/** `?socialError=` 코드 → 한국어 안내. ACCOUNT_SUSPENDED는 기존 정지 안내 문구(until: ISO 또는 빈값). SERVER_BUSY·그 외는 일반 문구 */
export function socialErrorMessage(code: string, until?: string | null): string {
  if (code === "ACCOUNT_SUSPENDED") {
    // until 은 쿼리라 누구나 바꿀 수 있다 — 서버가 주는 형식(yyyy-MM-ddTHH:mm...)만 반영하고 그 밖의 값은 무시한다
    const valid = !!until && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(until);
    return suspensionMessage({ until: valid ? until : null });
  }
  // 일반 객체 인덱싱은 __proto__·constructor 같은 키로 문자열이 아닌 값이 나온다 — 자기 키만 인정한다
  return Object.prototype.hasOwnProperty.call(ERROR_MESSAGES, code) ? ERROR_MESSAGES[code] : GENERIC_ERROR;
}
