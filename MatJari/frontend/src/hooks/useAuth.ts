import { createContext, useContext } from "react";
import type { AuthUser, LoginRequest } from "../types/auth";
import type { User } from "../types/user";

/** 내 정보(/api/users/me) 조회 상태. 로그아웃 상태면 idle */
export type ProfileStatus = "idle" | "loading" | "success" | "error";

export interface AuthContextValue {
  /** 토큰에서 복원한 식별 정보 (id, email) — 인증 판단 기준 */
  user: AuthUser | null;
  isAuthenticated: boolean;
  /** 저장된 토큰 확인(만료 시 refresh 시도)이 끝나기 전에는 true — 이 동안 리다이렉트 판단을 보류한다 */
  isInitializing: boolean;
  /** 표시용 프로필 (nickname 등). 현재 세션 user와 id가 같은 것만 노출된다 */
  profile: User | null;
  profileStatus: ProfileStatus;
  /** 프로필 조회 실패 시 화면용 메시지 */
  profileError: string | null;
  /** 프로필 다시 불러오기 (오류 재시도용) */
  reloadProfile: () => void;
  /** 서버가 돌려준 최신 프로필로 즉시 교체 (닉네임 수정 직후 헤더·마이페이지 갱신용). 현재 세션 사용자의 것만 반영된다 */
  applyProfile: (profile: User) => void;
  /** 실패 시 axios 에러를 그대로 throw 한다 (화면에서 parseApiError로 메시지 변환) */
  login: (payload: LoginRequest) => Promise<AuthUser>;
  logout: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

/** 로그인 상태·사용자 정보 전역 접근 훅. <AuthProvider> 하위에서만 사용 가능. */
export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth는 <AuthProvider> 안에서만 사용할 수 있습니다.");
  return ctx;
}

const PLACEHOLDER_EMAIL_SUFFIX = "@social.matjari.invalid";

/** 소셜 가입자의 자리표시 이메일인가 (토큰 클레임 email에는 원문이 올 수 있어 화면에 쓰지 않는다) */
export function isPlaceholderEmail(email: string | null | undefined): boolean {
  return !!email && email.toLowerCase().endsWith(PLACEHOLDER_EMAIL_SUFFIX);
}

/** 화면에 보여도 되는 이메일 (없거나 자리표시면 null). 프로필이 있으면 프로필 기준, 없으면 토큰의 email */
export function getVisibleEmail(user: AuthUser, profile: User | null): string | null {
  if (profile) {
    if (profile.emailPlaceholder || !profile.email || isPlaceholderEmail(profile.email)) return null;
    return profile.email;
  }
  return isPlaceholderEmail(user.email) ? null : user.email || null;
}

/** 화면 표시 이름: 프로필 닉네임 우선, 로딩 중·실패 시 email (이메일이 없거나 자리표시면 "회원") */
export function getDisplayName(user: AuthUser, profile: User | null): string {
  return profile?.nickname || getVisibleEmail(user, profile) || "회원";
}
