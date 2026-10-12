import { apiClient } from "./client";
import type { User } from "../types/user";

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
};
