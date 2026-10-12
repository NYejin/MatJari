import axios from "axios";

// 이용 정지 계정: 서버가 모든 인증 API에서 403 {code:"ACCOUNT_SUSPENDED", until}로 응답한다.
// 전역 인터셉터(client.ts)·토큰 재발급(session.ts)·로그인 화면이 이 모듈로 판정하고, 안내 문구는 로그인 화면에 보여 준다.

const NOTICE_KEY = "suspensionNotice";

export interface Suspension {
  /** "yyyy-MM-ddTHH:mm:ss"(KST) 또는 null(무기한) */
  until: string | null;
}

/** 오류가 ACCOUNT_SUSPENDED 403이면 until을, 아니면 null */
export function parseSuspension(error: unknown): Suspension | null {
  if (!axios.isAxiosError(error) || error.response?.status !== 403) return null;
  const data: unknown = error.response.data;
  if (typeof data !== "object" || data === null) return null;
  const body = data as Record<string, unknown>;
  if (body.code !== "ACCOUNT_SUSPENDED") return null;
  return { until: typeof body.until === "string" && body.until ? body.until : null };
}

/** "이용이 정지된 계정입니다. 2026년 10월 19일 오후 3:05까지" / "이용이 정지된 계정입니다. 무기한" */
export function suspensionMessage(s: Suspension): string {
  const base = "이용이 정지된 계정입니다.";
  if (!s.until) return `${base} 무기한`;
  const [y, m, d] = s.until.slice(0, 10).split("-").map(Number);
  const h = Number(s.until.slice(11, 13));
  const mm = s.until.slice(14, 16);
  if (!y || !m || !d || Number.isNaN(h)) return `${base} ${s.until}까지`;
  return `${base} ${y}년 ${m}월 ${d}일 ${h < 12 ? "오전" : "오후"} ${h % 12 === 0 ? 12 : h % 12}:${mm}까지`;
}

export function saveSuspensionNotice(s: Suspension): void {
  try {
    sessionStorage.setItem(NOTICE_KEY, suspensionMessage(s));
  } catch {
    // 저장에 실패해도 로그아웃 처리는 계속된다
  }
}

/** 읽기만 한다(지우지 않음 — StrictMode 이중 초기화에도 안전). 지우는 것은 clearSuspensionNotice */
export function peekSuspensionNotice(): string | null {
  try {
    return sessionStorage.getItem(NOTICE_KEY);
  } catch {
    return null;
  }
}

export function clearSuspensionNotice(): void {
  try {
    sessionStorage.removeItem(NOTICE_KEY);
  } catch {
    // 무시
  }
}
