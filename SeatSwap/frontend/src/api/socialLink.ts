import { apiClient } from "./client";
import { API_BASE_URL } from "./authClient";
import { SOCIAL_PROVIDER_ORDER, type SocialProvider } from "./socialAuth";
import { suspensionMessage } from "./suspension";

// 마이페이지 소셜 계정 연결·해제 (인증 필요 — apiClient가 토큰을 붙인다).
// 연결은 link API로 일회용 ticket이 담긴 authorizeUrl을 받아 브라우저를 보내고, 성공하면 /me#linkCode= 로 돌아와 confirm으로 확정하고, 실패는 /me?socialLinkError= 로 돌아온다.

export interface LinkedSocialAccount {
  provider: SocialProvider;
  /** ISO 일시 */
  linkedAt: string;
}

export interface SocialLinkStatus {
  accounts: LinkedSocialAccount[];
  /** 활성 제공자(SOCIAL_PROVIDER_ORDER 순서로 정렬, 알 수 없는 값 제외) */
  providers: SocialProvider[];
  /** 마지막 로그인 수단이 아니라서 해제할 수 있는가 */
  canUnlink: boolean;
}

export function isSocialProvider(v: unknown): v is SocialProvider {
  return typeof v === "string" && (SOCIAL_PROVIDER_ORDER as readonly string[]).includes(v);
}

/** GET /api/users/me/social */
export async function fetchSocialLinks(): Promise<SocialLinkStatus> {
  const { data } = await apiClient.get<{ accounts?: unknown; providers?: unknown; canUnlink?: unknown }>("/users/me/social");
  const rawAccounts: unknown[] = Array.isArray(data?.accounts) ? data.accounts : [];
  const accounts: LinkedSocialAccount[] = [];
  for (const a of rawAccounts) {
    const item = a as { provider?: unknown; linkedAt?: unknown } | null;
    if (item && isSocialProvider(item.provider)) {
      accounts.push({ provider: item.provider, linkedAt: typeof item.linkedAt === "string" ? item.linkedAt : "" });
    }
  }
  const list: unknown[] = Array.isArray(data?.providers) ? data.providers : [];
  return {
    accounts,
    providers: SOCIAL_PROVIDER_ORDER.filter((p) => list.includes(p)),
    canUnlink: data?.canUnlink === true,
  };
}

/**
 * POST /api/users/me/social/{provider}/link → 브라우저를 보낼 절대 URL.
 * 서버는 "/api/auth/social/{provider}/authorize?link={ticket}" 상대 경로를 주므로, API base(끝이 /api)에 붙인다.
 * 서버가 준 값이 예상한 경로가 아니면 이동하지 않고 오류로 처리한다(오픈 리다이렉트 방지).
 */
export async function requestSocialLink(provider: SocialProvider): Promise<string> {
  const { data } = await apiClient.post<{ authorizeUrl?: unknown }>(`/users/me/social/${provider}/link`);
  return absoluteAuthorizeUrl(data?.authorizeUrl);
}

function absoluteAuthorizeUrl(path: unknown): string {
  if (typeof path !== "string" || !/^\/api\/auth\/social\/[a-z]+\/authorize\?link=[^\s#]+$/.test(path)) {
    throw new Error("연결 주소를 받지 못했어요.");
  }
  return `${API_BASE_URL.replace(/\/+$/, "").replace(/\/api$/, "")}${path}`;
}

// ---- 회원탈퇴 재인증 (소셜 전용 계정) ----
// 이미 연결된 제공자로 연결과 같은 authorize 흐름을 한 번 더 거치고, 돌아온 /me#linkCode= 를 연결 확정이 아니라 탈퇴 요청의 reauthCode로 쓴다.
// 제공자로 떠나기 전에 sessionStorage에 표시를 남겨, 돌아왔을 때 SocialLinkCard가 그 코드로 연결을 확정하려 하지 않게 한다(10분 안에서만 유효).

const REAUTH_KEY = "withdrawReauthPending";
const REAUTH_WINDOW_MS = 10 * 60 * 1000;

/** POST /api/users/me/social/{provider}/reauth → 브라우저를 보낼 절대 URL. 연결돼 있지 않은 제공자는 409 SOCIAL_NOT_LINKED */
export async function requestSocialReauth(provider: SocialProvider): Promise<string> {
  const { data } = await apiClient.post<{ authorizeUrl?: unknown }>(`/users/me/social/${provider}/reauth`);
  const url = absoluteAuthorizeUrl(data?.authorizeUrl);
  try {
    sessionStorage.setItem(REAUTH_KEY, String(Date.now()));
  } catch {
    // 저장하지 못하면 돌아와서 재인증 코드를 알아볼 수 없다 — 그래도 이동은 막지 않는다(연결 카드가 오류로 안내한다)
  }
  return url;
}

export function clearWithdrawReauthPending(): void {
  try {
    sessionStorage.removeItem(REAUTH_KEY);
  } catch {
    // 무시
  }
}

/** 탈퇴 재인증을 위해 제공자에 다녀오는 중인가(10분 이내) */
export function isWithdrawReauthPending(): boolean {
  try {
    const at = Number(sessionStorage.getItem(REAUTH_KEY));
    return Number.isFinite(at) && at > 0 && Date.now() - at < REAUTH_WINDOW_MS;
  } catch {
    return false;
  }
}

// StrictMode의 이중 초기화에도 같은 코드를 돌려주려고 한 번 읽은 코드를 모듈에 보관한다(탈퇴 모달이 닫히면 clear).
let takenReauthCode: string | null = null;

/**
 * 재인증을 하고 돌아온 `/me#linkCode=` 를 읽어 주소에서 지우고 코드를 돌려준다(표시도 지운다). 해당하지 않으면 null.
 * 코드는 1회용이고 짧게(60초) 살아 있으므로 바로 탈퇴 모달에 넘긴다.
 */
export function takeWithdrawReauthCode(): string | null {
  if (takenReauthCode) return takenReauthCode;
  if (!isWithdrawReauthPending()) return null;
  const hash = window.location.hash;
  if (!hash) return null;
  const code = new URLSearchParams(hash.replace(/^#/, "")).get("linkCode");
  if (code === null) return null;
  window.history.replaceState(window.history.state, "", window.location.pathname + window.location.search);
  clearWithdrawReauthPending();
  takenReauthCode = code.trim() || null;
  return takenReauthCode;
}

export function clearTakenWithdrawReauthCode(): void {
  takenReauthCode = null;
}

/** DELETE /api/users/me/social/{provider} — 204(연결이 없어도 멱등). 마지막 로그인 수단이면 409 LAST_LOGIN_METHOD */
export async function unlinkSocial(provider: SocialProvider): Promise<void> {
  await apiClient.delete(`/users/me/social/${provider}`);
}

export const PROVIDER_LABELS: Record<SocialProvider, string> = {
  kakao: "카카오",
  google: "구글",
  naver: "네이버",
};

/**
 * POST /api/users/me/social/confirm {code} — 콜백이 `/me#linkCode=`로 넘긴 일회용 코드로 연결을 확정한다.
 * 코드는 1회용이라 호출부가 중복 호출하지 않도록 한다. 성공 시 서버가 provider를 주면 돌려준다.
 */
export async function confirmSocialLink(code: string): Promise<SocialProvider | null> {
  const res = await apiClient.post<{ provider?: unknown } | undefined>("/users/me/social/confirm", { code });
  const provider = res.data?.provider;
  return isSocialProvider(provider) ? provider : null;
}

const LINK_ERROR_MESSAGES: Record<string, string> = {
  // 호환용: 예전 콜백 쿼리 코드
  ALREADY_LINKED_OTHER: "이미 다른 계정에 연결된 소셜 계정이에요.",
  ALREADY_LINKED: "이미 연결된 제공자예요.",
  ACCESS_DENIED: "연결을 취소했어요.",
  // confirm 오류 코드
  INVALID_LINK_CODE: "연결 요청이 만료되었거나 올바르지 않아요. 다시 시도해 주세요.",
  SOCIAL_ALREADY_LINKED: "이미 연결된 제공자예요.",
  SOCIAL_LINKED_OTHER: "이미 다른 계정에 연결된 소셜 계정이에요.",
};
const LINK_GENERIC_ERROR = "소셜 계정을 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.";

/**
 * `?socialLinkError=` 코드 → 한국어 안내. ACCOUNT_SUSPENDED는 기존 정지 안내 문구.
 * until은 쿼리라 위조될 수 있어 서버 형식(yyyy-MM-ddTHH:mm...)만 반영하고 그 밖의 값은 무시한다.
 */
export function socialLinkErrorMessage(code: string, until?: string | null): string {
  if (code === "ACCOUNT_SUSPENDED") {
    const valid = !!until && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(until);
    return suspensionMessage({ until: valid ? until : null });
  }
  return Object.prototype.hasOwnProperty.call(LINK_ERROR_MESSAGES, code) ? LINK_ERROR_MESSAGES[code] : LINK_GENERIC_ERROR;
}
