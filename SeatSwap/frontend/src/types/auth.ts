// 백엔드 com.seatswap.dto.request / dto.response 의 auth 관련 record와 1:1 대응 (FR-01)

/** POST /api/auth/login 요청 — LoginRequest(email @Email, password @NotBlank) */
export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * POST /api/auth/signup 요청 — SignupRequest
 * - email: 필수, 이메일 형식, 최대 100자 (서버가 trim + 소문자 정규화)
 * - password: 8~64자 + UTF-8 72바이트 이하(bcrypt 한계), trim 하지 않음
 * - nickname: trim 후 2~20자, 보이지 않는 문자 금지, 중복 허용
 * 클라이언트 검증은 api/authValidation.ts 참고.
 */
export interface SignupRequest {
  email: string;
  password: string;
  nickname: string;
  /** 이메일로 받은 6자리 인증번호 (서버에서 인증이 켜져 있으면 필수) */
  emailCode?: string;
}

/** POST /api/auth/signup/email-code 응답 — 유효 시간과 다시 보낼 수 있기까지의 대기 시간(초) */
export interface EmailCodeResponse {
  expiresInSeconds: number;
  resendAfterSeconds: number;
}

/** POST /api/auth/refresh 요청 — RefreshRequest */
export interface RefreshRequest {
  refreshToken: string;
}

/** login/refresh 응답 — TokenResponse (tokenType은 항상 "Bearer") */
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
}

/**
 * 백엔드 GlobalExceptionHandler 에러 바디.
 * - 400 @Valid 실패·필드 단위 오류(이메일 중복 등): { "<필드명>": "<메시지>", ... }
 * - 400 SeatSwapException: { "message": "..." }
 * - 500 및 기타 4xx: { "message": "..." }
 * 화면용 변환은 api/errors.ts parseApiError 참고.
 */
export type ApiErrorBody = { message: string } | Record<string, string>;

/**
 * 로그인 사용자 식별 정보.
 * 백엔드에 "내 정보 조회" API가 아직 없어 access token 클레임(sub=userId, email)에서 복원한다.
 * nickname이 필요하면 /me API 추가 후 User 타입으로 교체.
 */
export interface AuthUser {
  id: number;
  email: string;
}
