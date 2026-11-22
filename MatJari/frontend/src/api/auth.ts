import { apiClient } from "./client";
import { tokenStorage } from "./tokenStorage";
import { authClient, CSRF_HEADERS, requestLogout } from "./authClient";
import type { User } from "../types/user";
import type { EmailCodeResponse, EmailVerificationStatus, LoginRequest, SignupRequest, TokenResponse } from "../types/auth";

// 백엔드 AuthController (@RequestMapping("/api/auth")). baseURL에 /api가 포함되어 있다.
// 내 정보 조회(/me) API는 백엔드에 아직 없음.
export const authApi = {
  async login(payload: LoginRequest): Promise<TokenResponse> {
    const { data } = await apiClient.post<TokenResponse>("/auth/login", payload);
    return data;
  },

  async signup(payload: SignupRequest): Promise<User> {
    const { data } = await apiClient.post<User>("/auth/signup", payload);
    return data;
  },

  /** 가입 이메일 인증번호를 쓰는지 (꺼져 있으면 가입 화면이 인증 칸을 숨긴다) */
  async emailVerificationEnabled(): Promise<boolean> {
    const { data } = await apiClient.get<EmailVerificationStatus>("/auth/signup/email-verification");
    return data.enabled !== false;
  },

  /** 가입 전 이메일 인증번호 발송. 이미 가입된 이메일은 400 {email}, 너무 잦으면 429 */
  async sendEmailCode(email: string): Promise<EmailCodeResponse> {
    const { data } = await apiClient.post<EmailCodeResponse>("/auth/signup/email-code", { email });
    return data;
  },

  /** POST /api/auth/password-reset/request — 가입 여부와 무관하게 항상 204. 400 {email}(형식), 429 PASSWORD_RESET_RATE_LIMITED */
  async requestPasswordReset(email: string): Promise<void> {
    await authClient.post("/auth/password-reset/request", { email }, { headers: CSRF_HEADERS });
  },

  /** POST /api/auth/password-reset/confirm — 204. 400 INVALID_RESET_TOKEN·{newPassword}, 429. 모든 기기가 로그아웃된다 */
  async confirmPasswordReset(token: string, newPassword: string): Promise<void> {
    await authClient.post("/auth/password-reset/confirm", { token, newPassword }, { headers: CSRF_HEADERS });
  },

  /**
   * PATCH /api/auth/password — 로그인 상태에서 비밀번호 변경. 현재 기기는 로그인 유지, 다른 기기는 로그아웃.
   * 400 {currentPassword}·{newPassword}, 409 PASSWORD_LOGIN_DISABLED, 429 LOGIN_RATE_LIMITED. (/users/me/password는 현재 기기도 끊어 쓰지 않는다)
   */
  async changePassword(currentPassword: string, newPassword: string): Promise<void> {
    // 200 + 새 액세스 토큰: 옛 토큰은 무효가 되므로 바로 교체한다(토큰 변경을 구독하는 AuthProvider·실시간 연결이 새 토큰으로 다시 붙는다)
    const { data } = await apiClient.patch<TokenResponse>("/auth/password", { currentPassword, newPassword }, { headers: CSRF_HEADERS });
    if (data?.accessToken) tokenStorage.save({ accessToken: data.accessToken });
  },

  /** POST /api/auth/logout — 서버가 리프레시 토큰 계열을 폐기하고 쿠키를 지운다. 인터셉터 없는 authClient 사용 */
  logout(): Promise<void> {
    return requestLogout();
  },
};

// single-flight 재발급은 순환 import 방지를 위해 session.ts로 이동 (기존 import 경로 호환용 재export)
export { refreshSession, type RefreshResult } from "./session";
