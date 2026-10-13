import { useLocation } from "react-router-dom";

// FR-01 로그인/회원가입 화면 공용: ProtectedRoute가 넘긴 state.from 기반 복귀 경로 + 화면 간 state 전달

/** ProtectedRoute가 넘긴 state.from에서 복귀 경로 추출. 안전하지 않거나 인증 화면이면 "/"로 대체. */
export function getRedirectPath(state: unknown): string {
  if (typeof state !== "object" || state === null || !("from" in state)) return "/";
  const from: unknown = state.from;
  if (typeof from !== "object" || from === null) return "/";
  const { pathname, search, hash } = from as Record<string, unknown>;

  if (typeof pathname !== "string") return "/";
  // 오픈 리다이렉트 방지: 내부 절대경로만 허용 ("//host" 시작, 백슬래시 포함 경로 거부)
  if (!pathname.startsWith("/") || pathname.startsWith("//") || pathname.includes("\\")) return "/";
  if (pathname === "/login" || pathname === "/signup") return "/";

  const safeSearch = typeof search === "string" && search.startsWith("?") ? search : "";
  const safeHash = typeof hash === "string" && hash.startsWith("#") ? hash : "";
  return `${pathname}${safeSearch}${safeHash}`;
}

/**
 * 로그인/회원가입 화면 간 이동 시 넘기는 location.state.
 * - from: ProtectedRoute가 넘긴 원래 위치 (그대로 보존)
 * - notice: 로그인 화면 상단 안내 문구 (예: 가입 완료 후 자동 로그인 실패)
 * - email: 로그인 화면 이메일 칸 미리 채우기
 */
export interface AuthRouteState {
  from?: unknown;
  notice?: string;
  email?: string;
}

function readString(state: unknown, key: "notice" | "email"): string | undefined {
  if (typeof state !== "object" || state === null || !(key in state)) return undefined;
  const value: unknown = (state as Record<string, unknown>)[key];
  return typeof value === "string" && value ? value : undefined;
}

export function useAuthRedirect() {
  const location = useLocation();
  const state: unknown = location.state;
  const from: unknown =
    typeof state === "object" && state !== null && "from" in state ? (state as { from: unknown }).from : undefined;

  return {
    /** 인증 완료 후 이동할 안전한 경로 */
    redirectTo: getRedirectPath(state),
    /** 다른 인증 화면으로 넘길 state (from만 유지, notice 등 일회성 값은 버림) */
    forwardState: (from === undefined ? undefined : { from }) as AuthRouteState | undefined,
    from,
    notice: readString(state, "notice"),
    email: readString(state, "email"),
  };
}
