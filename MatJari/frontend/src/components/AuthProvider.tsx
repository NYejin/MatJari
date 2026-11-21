import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { authApi } from "../api/auth";
import { refreshSession, type RefreshResult } from "../api/session";
import { broadcastLogout, onLogoutSignal } from "../api/authSignal";
import {
  getTokenExpiry,
  getUserFromAccessToken,
  isTokenExpired,
  removeLegacyStoredTokens,
  tokenStorage,
} from "../api/tokenStorage";
import { parseApiError } from "../api/errors";
import { usersApi } from "../api/users";
import { AuthContext, type AuthContextValue, type ProfileStatus } from "../hooks/useAuth";
import type { AuthUser, LoginRequest } from "../types/auth";
import type { User } from "../types/user";

interface Session {
  user: AuthUser;
  accessToken: string;
}

/** 프로필 조회 상태 — userId로 어느 세션의 결과인지 표시 */
interface ProfileState {
  userId: number;
  status: Exclude<ProfileStatus, "idle">;
  profile: User | null;
  error: string | null;
}

const PROFILE_ERROR_FALLBACK = "내 정보를 불러오지 못했습니다.";

/** access token 만료 이 시간 전에 선제 재발급 (백엔드 access 유효기간 1시간 기준) */
const REFRESH_LEAD_MS = 60_000;
/** setTimeout 최대 지연(약 24.8일) 초과 시 즉시 실행되는 브라우저 동작 방지 */
const MAX_TIMEOUT_MS = 2_147_483_647;

/** 저장된 access token이 유효하면 세션을, 아니면 null (토큰은 건드리지 않음) */
function readValidSession(): Session | null {
  const accessToken = tokenStorage.getAccessToken();
  if (!accessToken || isTokenExpired(accessToken)) return null;
  const user = getUserFromAccessToken(accessToken);
  return user ? { user, accessToken } : null;
}

// 마이그레이션: 예전 버전이 localStorage에 남긴 액세스·리프레시 토큰을 앱 시작 때 한 번 지운다
removeLegacyStoredTokens();

/**
 * refreshSession 결과를 세션 상태로 변환.
 * - ok: 새 토큰으로 세션 갱신
 * - invalid: 토큰은 refreshSession에서 이미 삭제됨 → 비로그인
 * - unavailable/superseded: 토큰 보존, 저장소의 access token이 아직 유효한지로 판단
 */
function sessionFromRefreshResult(result: RefreshResult): Session | null {
  if (result.status === "ok") {
    const user = getUserFromAccessToken(result.tokens.accessToken);
    return user ? { user, accessToken: result.tokens.accessToken } : null;
  }
  if (result.status === "invalid") return null;
  return readValidSession();
}

export default function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(readValidSession);
  // 액세스 토큰은 메모리에만 있어 새로고침·새 탭에서는 비어 있다. 쿠키(mj_rt)로 재발급을 시도하는 동안 초기화 상태를 유지해
  // 보호 라우트가 로그인 화면으로 튕기지 않게 한다 (쿠키가 없으면 401 → 비로그인).
  const [isInitializing, setIsInitializing] = useState<boolean>(() => readValidSession() === null);

  // 초기화: 쿠키로 재발급 시도.
  // refreshSession이 single-flight라 StrictMode 이중 실행에서도 요청은 1회이고,
  // 토큰 삭제 여부는 refreshSession이 응답 상태(401)로만 결정하므로 cancelled와 무관하다.
  useEffect(() => {
    if (!isInitializing) return;
    let cancelled = false;
    refreshSession().then((result) => {
      if (cancelled) return;
      setSession(sessionFromRefreshResult(result));
      setIsInitializing(false);
    });
    return () => {
      cancelled = true;
    };
    // 최초 마운트 시 1회만 수행
  }, []);

  // access token 만료 타이머: 만료 REFRESH_LEAD_MS 전에 선제 재발급.
  // 토큰이 바뀌거나(로그인/재발급/다른 탭) 로그아웃·언마운트되면 cleanup으로 타이머 정리 후 재설정.
  const accessToken = session?.accessToken ?? null;
  useEffect(() => {
    if (!accessToken) return;
    const expiry = getTokenExpiry(accessToken);
    if (expiry === null) return;

    let cancelled = false;
    let expiryTimer: number | undefined;

    const refreshTimer = window.setTimeout(async () => {
      const result = await refreshSession();
      if (cancelled) return;
      const next = sessionFromRefreshResult(result);
      setSession(next);

      // 일시 장애로 재발급 실패 + access가 아직 유효: 토큰은 보존하되 실제 만료 시점에 비로그인 처리
      if (result.status === "unavailable" && next?.accessToken === accessToken) {
        expiryTimer = window.setTimeout(() => {
          if (!cancelled) setSession(null);
        }, Math.min(Math.max(expiry - Date.now(), 0), MAX_TIMEOUT_MS));
      }
    }, Math.min(Math.max(expiry - REFRESH_LEAD_MS - Date.now(), 0), MAX_TIMEOUT_MS));

    return () => {
      cancelled = true;
      window.clearTimeout(refreshTimer);
      if (expiryTimer !== undefined) window.clearTimeout(expiryTimer);
    };
  }, [accessToken]);

  // 다른 탭에서 로그아웃하면 이 탭도 로그아웃한다 (신호만 받고 토큰은 공유하지 않는다).
  // 로그인·재발급은 탭마다 쿠키로 따로 처리한다.
  useEffect(
    () =>
      onLogoutSignal(() => {
        tokenStorage.clear();
        setSession(null);
      }),
    []
  );

  // 같은 탭에서 토큰이 바뀌면(401 인터셉터의 재발급/토큰 삭제 등) 상태 동기화.
  // 삭제되면 session이 null이 되어 ProtectedRoute가 state.from과 함께 /login으로 보낸다.
  // 일시 장애(unavailable)는 토큰을 건드리지 않으므로 알림도 없고 세션도 유지된다 (L-2 정책).
  useEffect(
    () =>
      tokenStorage.subscribe(() => {
        // accessToken과 user id가 그대로면 이전 객체를 유지해 불필요한 리렌더를 막는다
        setSession((prev) => {
          const next = readValidSession();
          if (prev === null && next === null) return prev;
          if (prev && next && prev.accessToken === next.accessToken && prev.user.id === next.user.id) return prev;
          return next;
        });
      }),
    []
  );

  const login = useCallback(async (payload: LoginRequest): Promise<AuthUser> => {
    const tokens = await authApi.login(payload);
    const user = getUserFromAccessToken(tokens.accessToken);
    if (!user) throw new Error("서버에서 받은 토큰 형식이 올바르지 않습니다.");
    tokenStorage.save({ accessToken: tokens.accessToken });
    setSession({ user, accessToken: tokens.accessToken });
    return user;
  }, []);

  const logout = useCallback(async (): Promise<void> => {
    // 서버가 리프레시 토큰 계열을 폐기하고 쿠키를 지운다. 실패해도 로컬 상태는 지운다.
    try {
      await authApi.logout();
    } catch {
      // 네트워크 오류 등 — 로컬 로그아웃은 계속 진행
    }
    tokenStorage.clear();
    setSession(null);
    broadcastLogout();
  }, []);

  // ---- 내 정보(/api/users/me) — 표시용 프로필. 세션 user id 단위로 보관한다.
  const userId = session?.user.id ?? null;
  const [profileState, setProfileState] = useState<ProfileState | null>(null);
  const [profileReloadKey, setProfileReloadKey] = useState(0);

  useEffect(() => {
    if (userId === null) {
      setProfileState(null);
      return;
    }
    let cancelled = false;
    // 같은 사용자 재조회면 기존 프로필은 유지한 채 로딩 표시
    setProfileState((prev) => ({
      userId,
      status: "loading",
      profile: prev?.userId === userId ? prev.profile : null,
      error: null,
    }));
    usersApi
      .me()
      .then((profile) => {
        // 로그아웃·재로그인으로 세션이 바뀌었으면(cleanup) 늦게 온 응답은 버린다
        if (cancelled) return;
        if (profile.id !== userId) {
          // 요청 사이 토큰이 다른 사용자로 바뀐 경우 — 이 세션의 프로필이 아니므로 버림
          setProfileState({ userId, status: "error", profile: null, error: PROFILE_ERROR_FALLBACK });
          return;
        }
        setProfileState({ userId, status: "success", profile, error: null });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setProfileState((prev) => ({
          userId,
          status: "error",
          profile: prev?.userId === userId ? prev.profile : null,
          error: parseApiError(err, PROFILE_ERROR_FALLBACK).message,
        }));
      });
    return () => {
      cancelled = true;
    };
  }, [userId, profileReloadKey]);

  // 프로필은 세션 user id가 바뀔 때만 자동으로 다시 불러온다. 닉네임 수정·리뷰 반영(신뢰도 변경) 등
  // 프로필이 바뀌는 기능을 만들면 성공 후 reloadProfile()을 호출해야 화면(헤더·마이페이지)이 갱신된다.
  const reloadProfile = useCallback(() => setProfileReloadKey((k) => k + 1), []);

  const applyProfile = useCallback(
    (next: User) => {
      if (userId === null || next.id !== userId) return;
      setProfileState({ userId, status: "success", profile: next, error: null });
    },
    [userId]
  );

  const user = session?.user ?? null;
  // 렌더 시점에도 현재 세션 사용자의 것만 노출 (상태 갱신 전 한 프레임의 이전 사용자 정보 방지)
  const current = profileState && user && profileState.userId === user.id ? profileState : null;
  const profile = current?.profile ?? null;
  const profileStatus: ProfileStatus = user === null ? "idle" : (current?.status ?? "loading");
  const profileError = current?.error ?? null;

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated: user !== null,
      isInitializing,
      profile,
      profileStatus,
      profileError,
      reloadProfile,
      applyProfile,
      login,
      logout,
    }),
    [user, isInitializing, profile, profileStatus, profileError, reloadProfile, applyProfile, login, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
