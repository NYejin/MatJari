// API 시각 문자열 변환·표시 (공연 회차 등).
// 백엔드 계약: 회차 시각은 오프셋 없는 KST 현지 시각 "yyyy-MM-ddTHH:mm".
// 사용자 기기 시간대와 무관하게 KST로 해석·표시한다 (해외 접속이어도 공연 현지 시각 기준).

const LOCAL_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/;
const KST_OFFSET_HOURS = 9;

/** "yyyy-MM-ddTHH:mm[:ss]" → 분 단위 "yyyy-MM-ddTHH:mm". 형식이 아니면 null */
export function normalizeLocalDateTime(value: string): string | null {
  const m = LOCAL_DATE_TIME.exec(value.trim());
  if (!m) return null;
  const [, y, mo, d, h, mi] = m;
  const epoch = toEpoch(+y, +mo, +d, +h, +mi);
  // 2월 30일 같은 존재하지 않는 날짜 거르기
  const check = new Date(epoch + KST_OFFSET_HOURS * 3_600_000);
  if (check.getUTCFullYear() !== +y || check.getUTCMonth() + 1 !== +mo || check.getUTCDate() !== +d) return null;
  if (+h > 23 || +mi > 59) return null;
  return `${y}-${mo}-${d}T${h}:${mi}`;
}

function toEpoch(y: number, mo: number, d: number, h: number, mi: number): number {
  return Date.UTC(y, mo - 1, d, h - KST_OFFSET_HOURS, mi);
}

/** KST 현지 시각 문자열의 epoch ms. 형식이 아니면 null */
export function kstEpoch(value: string): number | null {
  const normalized = normalizeLocalDateTime(value);
  if (!normalized) return null;
  const [date, time] = normalized.split("T");
  const [y, mo, d] = date.split("-").map(Number);
  const [h, mi] = time.split(":").map(Number);
  return toEpoch(y, mo, d, h, mi);
}

const MINUTE_MS = 60_000;

/**
 * 지금보다 이전 시각인지 (형식 오류는 false).
 * 백엔드와 같이 분 단위로 절삭해 비교한다 — 현재 분(예: 19:00:30에 "19:00")은 지난 시각이 아니다.
 */
export function isPastKst(value: string, now: number = Date.now()): boolean {
  const epoch = kstEpoch(value);
  return epoch !== null && epoch < Math.floor(now / MINUTE_MS) * MINUTE_MS;
}

/**
 * 회차 마감 여부: 회차 당일이 끝나면(다음날 0시 KST) 마감. 날짜만 비교한다.
 * 서버(SessionTimePolicy)가 최종 판정하고, 화면은 미리 선택을 막는 용도다.
 */
export function isSessionClosed(startsAt: string, now: number = Date.now()): boolean {
  return startsAt.slice(0, 10) < kstMinuteString(now).slice(0, 10);
}

/** 지금(또는 주어진 epoch)을 KST 분 단위 "yyyy-MM-ddTHH:mm"으로 (목록 기준 시각 asOf 등) */
export function kstMinuteString(now: number = Date.now()): string {
  const d = new Date(Math.floor(now / MINUTE_MS) * MINUTE_MS + KST_OFFSET_HOURS * 3_600_000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

const KST_FORMAT = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "long",
  day: "numeric",
  weekday: "short",
  hour: "numeric",
  minute: "2-digit",
});

/** 한국어 표기: "2026년 11월 1일 (일) 오후 7:00". 형식이 아니면 원문 그대로 */
export function formatKstDateTime(value: string): string {
  const epoch = kstEpoch(value);
  if (epoch === null) return value;
  const parts = KST_FORMAT.formatToParts(new Date(epoch));
  const get = (type: Intl.DateTimeFormatPartTypes) => parts.find((p) => p.type === type)?.value ?? "";
  return `${get("year")}년 ${get("month")} ${get("day")}일 (${get("weekday")}) ${get("dayPeriod")} ${get("hour")}:${get("minute")}`;
}

/** 시각 오름차순 비교 */
export function compareLocalDateTime(a: string, b: string): number {
  return (kstEpoch(a) ?? 0) - (kstEpoch(b) ?? 0);
}

// ---- 회차 시각 입력 규칙 (10분 단위) ----

/** 회차 시각 입력 단위(분). 입력칸의 step(초) = SESSION_TIME_STEP_MINUTES * 60 */
export const SESSION_TIME_STEP_MINUTES = 10;
/** <input type="datetime-local">의 step 속성값 (초 단위) */
export const SESSION_TIME_STEP_SECONDS = SESSION_TIME_STEP_MINUTES * 60;
// TODO: 지우기
// /** 입력칸 옆 안내 문구 (aria-describedby로 연결) */
// export const SESSION_TIME_STEP_HINT = `${SESSION_TIME_STEP_MINUTES}분 단위로 선택해 주세요.`;
/** 백엔드가 같은 규칙을 400 {"startsAt": "..."} 로 내려준다 — 같은 문구 */
export const SESSION_TIME_STEP_ERROR = `회차 시각은 ${SESSION_TIME_STEP_MINUTES}분 단위로 입력해주세요.`;

/**
 * 회차 시각 입력값 검증(직접 타이핑·붙여넣기 대비). 형식 오류·10분 단위가 아니면 오류 문구, 통과하면 null.
 * 새로 추가하거나 수정할 때만 적용한다 — 이미 저장된 회차의 표시·삭제에는 쓰지 않는다.
 */
export function validateSessionTimeStep(value: string): string | null {
  const normalized = normalizeLocalDateTime(value);
  if (!normalized) return "날짜와 시간을 입력해주세요.";
  const minute = Number(normalized.slice(-2));
  return minute % SESSION_TIME_STEP_MINUTES === 0 ? null : SESSION_TIME_STEP_ERROR;
}
