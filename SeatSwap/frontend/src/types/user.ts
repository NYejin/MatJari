export interface User {
  id: number;
  /** 소셜 가입자의 자리표시 이메일은 서버가 null로 내린다(emailPlaceholder=true). 항상 null 가능성을 처리할 것 */
  email: string | null;
  /** 이메일이 자리표시(소셜 가입, 이메일 없음)인가. 서버가 안 주면 false로 간주 */
  emailPlaceholder?: boolean;
  /** 비밀번호로 로그인할 수 있는 계정인가. 서버가 안 주면 true로 간주 */
  passwordLoginEnabled?: boolean;
  nickname: string;
  /** 권한. UI 분기용일 뿐 최종 권한 판정은 서버가 한다. 서버가 안 주면 일반 사용자로 간주 */
  role?: "USER" | "ADMIN";
}

/** DELETE /api/users/me 본문 — 비밀번호 계정은 password, 소셜 전용 계정은 confirmText("탈퇴합니다") */
export interface WithdrawRequest {
  password?: string;
  confirmText?: string;
}
