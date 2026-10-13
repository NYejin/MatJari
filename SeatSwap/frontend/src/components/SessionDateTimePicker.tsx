import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { normalizeLocalDateTime } from "../api/dateTime";
import { input, ui } from "./ui";

// 회차 날짜·시각 선택 (날짜 + 시 select + 분 select, 분은 10분 단위).
// 값은 "yyyy-MM-ddTHH:mm" (KST 현지 시각), 세 칸이 모두 채워지기 전에는 "".
// 내부에 부분 선택 상태를 들고 있어서, 부모가 리렌더링돼도 선택 중인 값이 사라지지 않는다.
// 반드시 모듈 최상위 컴포넌트로 쓴다 (페이지 함수 안에 정의하면 렌더마다 재마운트된다).

const HOURS = Array.from({ length: 24 }, (_, i) => String(i).padStart(2, "0"));
const MINUTES = ["00", "10", "20", "30", "40", "50"];

type Parts = { date: string; hour: string; minute: string };

const EMPTY: Parts = { date: "", hour: "", minute: "" };

/** 저장된 값이 10분 단위가 아닌지 (분 select에는 보여줄 수 없는 값) */
function isOffStep(value: string): boolean {
  const normalized = normalizeLocalDateTime(value);
  return normalized !== null && Number(normalized.slice(-2)) % 10 !== 0;
}

function parseValue(value: string): Parts {
  const normalized = normalizeLocalDateTime(value);
  if (!normalized) return EMPTY;
  const [date, time = ""] = normalized.split("T");
  const [hour = "", minute = ""] = time.split(":");
  // 10분 단위가 아닌 분은 select 옵션에 없으므로 비워 둔다 (아래 안내 문구로 다시 고르게 한다)
  return { date, hour, minute: MINUTES.includes(minute) ? minute : "" };
}

function composeValue({ date, hour, minute }: Parts): string {
  return date && hour && minute ? `${date}T${hour}:${minute}` : "";
}

type SessionDateTimePickerProps = {
  /** 그룹 이름 (fieldset legend) — 스크린리더가 날짜·시·분 컨트롤 앞에 읽는다 */
  legend: ReactNode;
  legendClassName?: string;
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
  /** 이 입력에 오류가 있음 — 비어 있는 칸(모두 찼으면 날짜)에만 aria-invalid를 단다 */
  invalid?: boolean;
  /** 오류·안내 문구 요소의 id — 컨트롤마다가 아니라 fieldset 그룹에 한 번만 연결한다 */
  describedBy?: string;
  /** 마운트 시 날짜 칸으로 포커스 (수정 폼이 열릴 때) */
  autoFocus?: boolean;
  /** 날짜 칸만 미리 채운다(시각은 사용자가 고른다). nonce 가 바뀔 때마다 한 번 적용 — 같은 날짜를 다시 눌러도 다시 채운다 */
  datePreset?: { date: string; nonce: number } | null;
};

export default function SessionDateTimePicker({
  legend,
  legendClassName = ui.label,
  value,
  onChange,
  disabled = false,
  invalid = false,
  describedBy,
  autoFocus = false,
  datePreset = null,
}: SessionDateTimePickerProps) {
  const [parts, setParts] = useState<Parts>(() => parseValue(value));
  const [prevValue, setPrevValue] = useState(value);
  /** 저장돼 있던 10분 단위가 아닌 값을 열었고 아직 분을 다시 고르지 않음 */
  const [needsMinute, setNeedsMinute] = useState(() => isOffStep(value));
  const dateRef = useRef<HTMLInputElement>(null);
  const noticeId = useId();

  // 부모가 값을 직접 바꿨을 때(추가 후 비우기, 수정 시작 등)만 내부 선택을 맞춘다.
  // 내가 방금 보낸 값이거나 부분 선택("")이면 내부 상태를 그대로 둔다.
  if (value !== prevValue) {
    setPrevValue(value);
    if (value !== composeValue(parts)) {
      setParts(parseValue(value));
      setNeedsMinute(isOffStep(value));
    }
  }

  // 날짜 미리 채우기: 부모가 값을 비운 직후에도 적용되도록 값 동기화 다음에 처리한다
  const [appliedNonce, setAppliedNonce] = useState<number | null>(null);
  if (datePreset && datePreset.nonce !== appliedNonce) {
    setAppliedNonce(datePreset.nonce);
    setParts((p) => ({ ...p, date: datePreset.date }));
  }

  useEffect(() => {
    if (autoFocus) dateRef.current?.focus();
  }, [autoFocus]);

  const update = (patch: Partial<Parts>) => {
    const next = { ...parts, ...patch };
    setParts(next);
    if (next.minute) setNeedsMinute(false);
    onChange(composeValue(next));
  };

  const offStep = needsMinute && !parts.minute;
  // 오류가 있으면 비어 있는 칸을 가리킨다. 모두 찼는데 오류(지난 시각·중복 등)면 날짜가 대표
  const hasError = invalid || offStep;
  const invalidDate = hasError && (!parts.date || (!!parts.hour && !!parts.minute));
  const invalidHour = hasError && !!parts.date && !parts.hour;
  const invalidMinute = hasError && !!parts.date && !!parts.hour && !parts.minute;
  const describedIds = [offStep ? noticeId : undefined, describedBy].filter(Boolean).join(" ") || undefined;

  return (
    <fieldset className="flex min-w-0 flex-1 flex-col gap-2" disabled={disabled} aria-describedby={describedIds}>
      <legend className={`${legendClassName} mb-2`}>{legend}</legend>
      {offStep && (
        <p id={noticeId} className={ui.warning}>
          이 회차는 10분 단위가 아닙니다. 분을 다시 선택해주세요.
        </p>
      )}
      <div className="flex gap-2">
        <input
          ref={dateRef}
          type="date"
          aria-label="날짜"
          className={invalidDate ? input.invalid : input.normal}
          value={parts.date}
          onChange={(e) => update({ date: e.target.value })}
          aria-invalid={invalidDate}
        />
        <select
          aria-label="시"
          className={invalidHour ? input.invalid : input.normal}
          value={parts.hour}
          onChange={(e) => update({ hour: e.target.value })}
          aria-invalid={invalidHour}
        >
          <option value="">시</option>
          {HOURS.map((h) => (
            <option key={h} value={h}>
              {h}시
            </option>
          ))}
        </select>
        <select
          aria-label="분"
          className={invalidMinute ? input.invalid : input.normal}
          value={parts.minute}
          onChange={(e) => update({ minute: e.target.value })}
          aria-invalid={invalidMinute}
        >
          <option value="">분</option>
          {MINUTES.map((m) => (
            <option key={m} value={m}>
              {m}분
            </option>
          ))}
        </select>
      </div>
    </fieldset>
  );
}
