import { apiClient } from "./client";
import { API_BASE_URL } from "./authClient";
import { SOCIAL_PROVIDER_ORDER, type SocialProvider } from "./socialAuth";
import { suspensionMessage } from "./suspension";

// 마이페이지 소셜 계정 연결·해제 (인증 필요 — apiClient가 토큰을 붙인다).
// 연결은 link API로 일회용 ticket이 담긴 authorizeUrl을 받아 브라우저를 보내고, 결과는 /me?socialLinked=·socialLinkError= 로 돌아온다.

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
  const path = data?.authorizeUrl;
  if (typeof path !== "string" || !/^\/api\/auth\/social\/[a-z]+\/authorize\?link=[^\s#]+$/.test(path)) {
    throw new Error("연결 주소를 받지 못했어요.");
  }
  return `${API_BASE_URL.replace(/\/+$/, "").replace(/\/api$/, "")}${path}`;
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

const LINK_ERROR_MESSAGES: Record<string, string> = {
  ALREADY_LINKED_OTHER: "이미 다른 계정에 연결된 소셜 계정이에요.",
  ALREADY_LINKED: "이미 연결된 제공자예요.",
  ACCESS_DENIED: "연결을 취소했어요.",
};
const LINK_GENERIC_ERROR = "소셜 계정을 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요.";

/** `?socialLinkError=` 코드 → 한국어 안내. ACCOUNT_SUSPENDED는 기존 정지 안내 문구 */
export function socialLinkErrorMessage(code: string): string {
  if (code === "ACCOUNT_SUSPENDED") return suspensionMessage({ until: null });
  return Object.prototype.hasOwnProperty.call(LINK_ERROR_MESSAGES, code) ? LINK_ERROR_MESSAGES[code] : LINK_GENERIC_ERROR;
}
