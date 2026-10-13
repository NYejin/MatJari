import { apiClient } from "./client";
import { requestTokenRefresh } from "./authClient";
import type { User } from "../types/user";
import type { EmailCodeResponse, LoginRequest, SignupRequest, TokenResponse } from "../types/auth";

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

  /** 가입 전 이메일 인증번호 발송. 이미 가입된 이메일은 400 {email}, 너무 잦으면 429 */
  async sendEmailCode(email: string): Promise<EmailCodeResponse> {
    const { data } = await apiClient.post<EmailCodeResponse>("/auth/signup/email-code", { email });
    return data;
  },

  /** 인터셉터 없는 authClient 사용 (authClient.ts 참고) */
  refreshToken(refreshToken: string): Promise<TokenResponse> {
    return requestTokenRefresh(refreshToken);
  },
};

// single-flight 재발급은 순환 import 방지를 위해 session.ts로 이동 (기존 import 경로 호환용 재export)
export { refreshSession, type RefreshResult } from "./session";
