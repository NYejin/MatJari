// FR-01 회원가입/로그인 — 백엔드 auth DTO 제약(@Email/@Size 등)을 클라이언트에서 동일하게 검증한다.
// 로그인/회원가입이 같은 이메일 규칙을 쓰도록 이 모듈 하나로 공유한다 (리뷰 L-6).
// 규칙이 바뀌면 백엔드 SignupRequest/LoginRequest와 함께 갱신할 것.

export const EMAIL_MAX_LENGTH = 100;
export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 64;
/** bcrypt 입력 한계 — 백엔드와 동일하게 UTF-8 바이트 기준으로 검사 */
export const PASSWORD_MAX_BYTES = 72;
export const NICKNAME_MIN_LENGTH = 2;
export const NICKNAME_MAX_LENGTH = 20;

export const EMAIL_LOCAL_PART_MAX_LENGTH = 64;

// 형식은 서버(@Email)가 최종 판정한다(필드 오류로 표시됨). 클라이언트는 명백한 오류만 먼저 거른다.
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Java String.trim()과 동일: 앞뒤 U+0000~U+0020 제거 (JS trim()과 범위가 다름) */
const JAVA_TRIM_PATTERN = /^[\x00-\x20]+|[\x00-\x20]+$/g;

/** 닉네임 금지 문자: 서식(Cf)·제어(Cc) 문자 + 한글 채움 문자(U+3164, U+115F, U+1160, U+FFA0). 백엔드와 동일 규칙 */
const NICKNAME_FORBIDDEN_PATTERN = /[\p{Cf}\p{Cc}\u{3164}\u{115F}\u{1160}\u{FFA0}]/u;

/** 서버의 "필수" 판정(@NotBlank 대신 Java trim 후 빈 값 여부)과 동일하게 비어 있는지 확인 */
function isBlankByJavaTrim(value: string): boolean {
  return value.replace(JAVA_TRIM_PATTERN, "") === "";
}

/** 서버와 동일한 정규화: 앞뒤 공백 제거 + 소문자 */
export function normalizeEmail(email: string): string {
  return email.trim().toLowerCase();
}

/** 닉네임은 trim 후 저장된다 (중복 허용) */
export function normalizeNickname(nickname: string): string {
  return nickname.trim();
}

/** 오류 메시지 또는 undefined(통과) */
export function validateEmail(email: string): string | undefined {
  const value = normalizeEmail(email);
  if (!value) return "이메일을 입력해주세요.";
  if (value.length > EMAIL_MAX_LENGTH) return `이메일은 ${EMAIL_MAX_LENGTH}자 이하로 입력해주세요.`;
  const localPart = value.slice(0, value.lastIndexOf("@"));
  if (
    !EMAIL_PATTERN.test(value) ||
    value.includes("..") ||
    localPart.length > EMAIL_LOCAL_PART_MAX_LENGTH
  ) {
    return "올바른 이메일 형식이 아닙니다.";
  }
  return undefined;
}

export const EMAIL_CODE_LENGTH = 6;

/** 이메일 인증번호: 숫자 6자리 */
export function validateEmailCode(code: string): string | undefined {
  const value = code.trim();
  if (!value) return "이메일로 받은 인증번호를 입력해주세요.";
  if (!new RegExp(`^\\d{${EMAIL_CODE_LENGTH}}$`).test(value)) return "인증번호는 숫자 6자리예요.";
  return undefined;
}

/** 로그인용: 비어 있는지만 확인 (기존 계정 규칙이 달라도 서버 판단에 맡김). 전송 값은 trim 하지 않는다. */
export function validateLoginPassword(password: string): string | undefined {
  return isBlankByJavaTrim(password) ? "비밀번호를 입력해주세요." : undefined;
}

/** 회원가입용: 길이 8~64자 + UTF-8 72바이트 이하. 전송 값은 trim 하지 않음 (공백도 비밀번호의 일부) */
export function validateSignupPassword(password: string): string | undefined {
  if (isBlankByJavaTrim(password)) return "비밀번호를 입력해주세요.";
  if (password.length < PASSWORD_MIN_LENGTH || password.length > PASSWORD_MAX_LENGTH) {
    return `비밀번호는 ${PASSWORD_MIN_LENGTH}~${PASSWORD_MAX_LENGTH}자로 입력해주세요.`;
  }
  // 한글 등 멀티바이트 문자는 64자 이내여도 72바이트를 넘을 수 있다
  if (new TextEncoder().encode(password).length > PASSWORD_MAX_BYTES) {
    return "비밀번호가 너무 깁니다. 영문 기준 64자 이하로 입력해주세요.";
  }
  return undefined;
}

export function validatePasswordConfirm(password: string, confirm: string): string | undefined {
  if (!confirm) return "비밀번호를 한 번 더 입력해주세요.";
  if (password !== confirm) return "비밀번호가 일치하지 않습니다.";
  return undefined;
}

export function validateNickname(nickname: string): string | undefined {
  const value = normalizeNickname(nickname);
  // 검사 순서: 필수 → 길이 → 문자 (백엔드와 동일)
  if (isBlankByJavaTrim(value)) return "닉네임을 입력해주세요.";
  if (value.length < NICKNAME_MIN_LENGTH || value.length > NICKNAME_MAX_LENGTH) {
    return `닉네임은 ${NICKNAME_MIN_LENGTH}~${NICKNAME_MAX_LENGTH}자로 입력해주세요.`;
  }
  if (NICKNAME_FORBIDDEN_PATTERN.test(value)) return "닉네임에 사용할 수 없는 문자가 포함되어 있습니다.";
  return undefined;
}

/** undefined 값을 제거해 "오류가 있는 필드만" 남긴다 */
export function compactErrors<K extends string>(errors: Partial<Record<K, string | undefined>>): Partial<Record<K, string>> {
  const result: Partial<Record<K, string>> = {};
  for (const key of Object.keys(errors) as K[]) {
    const message = errors[key];
    if (message) result[key] = message;
  }
  return result;
}

/**
 * 서버 @Valid 필드 오류 중 화면에 입력칸이 있는 필드만 추린다.
 * 하나라도 있으면 상단 오류는 중복이므로 표시하지 않는다(LoginPage/SignupPage 공통 규칙).
 */
export function pickFieldErrors<K extends string>(
  serverErrors: Record<string, string>,
  fields: readonly K[]
): { fieldErrors: Partial<Record<K, string>>; hasFieldError: boolean } {
  const fieldErrors: Partial<Record<K, string>> = {};
  for (const field of fields) {
    const message = serverErrors[field];
    if (message) fieldErrors[field] = message;
  }
  return { fieldErrors, hasFieldError: Object.keys(fieldErrors).length > 0 };
}
