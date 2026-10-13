import axios from "axios";
import type { ApiErrorBody } from "../types/auth";

export interface ParsedApiError {
  /** 화면 상단에 보여줄 대표 메시지 */
  message: string;
  /** @Valid 실패 시 필드별 메시지 (필드명 -> 메시지) */
  fieldErrors: Record<string, string>;
}

export const NETWORK_ERROR_MESSAGE = "서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.";
export const SERVER_ERROR_MESSAGE = "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

/**
 * 401 후 토큰 재발급이 일시 장애(네트워크 오류/5xx)로 실패했을 때 원 요청 대신 reject하는 에러.
 * 원 401 응답("로그인이 필요합니다.")을 그대로 보여주면 세션이 끊긴 것처럼 보이므로 구분한다.
 * 토큰은 유지된 상태다.
 */
export class SessionRefreshUnavailableError extends Error {
  /** 재발급 실패 원인 (네트워크 오류면 response 없는 AxiosError) */
  readonly refreshError: unknown;
  /** 재발급을 촉발한 원 401 에러 */
  readonly originalError: unknown;

  constructor(refreshError: unknown, originalError: unknown) {
    super("세션 재발급이 일시적으로 불가능합니다.");
    this.name = "SessionRefreshUnavailableError";
    this.refreshError = refreshError;
    this.originalError = originalError;
  }

  /** 응답 자체를 받지 못한 경우(네트워크 오류). 5xx 등 응답이 있었으면 false */
  get isNetworkError(): boolean {
    return axios.isAxiosError(this.refreshError) && !this.refreshError.response;
  }
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value) &&
    Object.values(value).every((v) => typeof v === "string")
  );
}

/** 백엔드 GlobalExceptionHandler 포맷({message} 또는 {field: msg})을 화면용으로 변환 */
export function parseApiError(error: unknown, fallback = "요청을 처리하지 못했습니다."): ParsedApiError {
  if (error instanceof SessionRefreshUnavailableError) {
    return { message: error.isNetworkError ? NETWORK_ERROR_MESSAGE : SERVER_ERROR_MESSAGE, fieldErrors: {} };
  }

  if (!axios.isAxiosError(error)) return { message: fallback, fieldErrors: {} };

  if (!error.response) {
    return { message: NETWORK_ERROR_MESSAGE, fieldErrors: {} };
  }

  const data: unknown = error.response.data;
  // {message, ...details} — 409 등은 message 외에 숫자 필드(예: performanceId)가 함께 와도 message를 쓴다
  if (typeof data === "object" && data !== null && !Array.isArray(data)) {
    const message: unknown = (data as Record<string, unknown>).message;
    if (typeof message === "string" && message) return { message, fieldErrors: {} };
  }
  if (isStringRecord(data)) {
    const body: ApiErrorBody = data;
    if ("message" in body && typeof body.message === "string") {
      return { message: body.message, fieldErrors: {} };
    }
    const fieldErrors: Record<string, string> = { ...body };
    const first = Object.values(fieldErrors)[0];
    return { message: first ?? fallback, fieldErrors };
  }

  if (error.response.status >= 500) {
    return { message: SERVER_ERROR_MESSAGE, fieldErrors: {} };
  }
  return { message: fallback, fieldErrors: {} };
}

/** 422/409 본문의 `code` (없으면 null) */
export function getErrorCode(error: unknown): string | null {
  if (!axios.isAxiosError(error)) return null;
  const data: unknown = error.response?.data;
  if (typeof data !== "object" || data === null) return null;
  const code: unknown = (data as Record<string, unknown>).code;
  return typeof code === "string" ? code : null;
}

/** 오류 본문의 숫자 상세값 (예: count, limit, matchId) */
export function getErrorNumber(error: unknown, key: string): number | null {
  if (!axios.isAxiosError(error)) return null;
  const data: unknown = error.response?.data;
  if (typeof data !== "object" || data === null) return null;
  const value: unknown = (data as Record<string, unknown>)[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}
