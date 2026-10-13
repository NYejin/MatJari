import { apiClient } from "./client";
import { authClient } from "./authClient";
import type { User, WithdrawRequest } from "../types/user";

// 백엔드 UserController (@RequestMapping("/api/users")). baseURL에 /api가 포함되어 있다.
export const usersApi = {
  /** GET /api/users/me — 로그인 사용자 프로필. 미인증 401은 client.ts 인터셉터가 재발급·재시도 처리 */
  async me(): Promise<User> {
    const { data } = await apiClient.get<User>("/users/me");
    return data;
  },

  /** PATCH /api/users/me {nickname} — 닉네임 수정. 같은 값이면 멱등 200. 400은 필드 오류({nickname: 메시지}) */
  async updateNickname(nickname: string): Promise<User> {
    const { data } = await apiClient.patch<User>("/users/me", { nickname });
    return data;
  },

  /**
   * DELETE /api/users/me — 회원탈퇴(익명화). 본인 확인: 비밀번호 계정은 password, 소셜 전용은 confirmText.
   * 성공 204. 400 확인 불일치, 409 ADMIN_CANNOT_WITHDRAW, 429. axios.delete의 본문은 data 옵션으로 보낸다.
   */
  async withdraw(body: WithdrawRequest): Promise<void> {
    await apiClient.delete("/users/me", { data: body });
  },

  /**
   * 이용 정지 중 로그인(403 ACCOUNT_SUSPENDED의 withdrawToken)으로 받은 탈퇴 전용 토큰으로 회원탈퇴한다.
   * 로그인 상태가 아니므로 저장된 토큰·재발급 인터셉터를 거치지 않는 authClient로 Authorization을 직접 붙인다.
   */
  async withdrawSuspended(withdrawToken: string, body: WithdrawRequest): Promise<void> {
    await authClient.delete("/users/me", { data: body, headers: { Authorization: `Bearer ${withdrawToken}` } });
  },
};
