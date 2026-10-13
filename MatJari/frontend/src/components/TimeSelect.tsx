import { useState } from "react";
import { input } from "./ui";

// 시각 선택 (오전/오후 + 시 + 분). 오전/오후 기본값은 '오후', 분은 10분 단위(기본 '00분').
// 값은 24시간 "HH:mm", 시를 고르기 전에는 "". 오전/오후·분만 바꿔서는 값이 생기지 않는다(시를 골라야 시각이 정해진다).
// 내부에 부분 선택 상태를 들고 있어서 부모가 리렌더링돼도 선택 중인 값이 사라지지 않는다.
// 반드시 모듈 최상위 컴포넌트로 쓴다 (페이지 함수 안에 정의하면 렌더마다 재마운트된다).

const HOURS12 = Array.from({ length: 12 }, (_, i) => String(i + 1));
const MINUTES = ["00", "10", "20", "30", "40", "50"];

type Meridiem = "AM" | "PM";
type Parts = { meridiem: Meridiem; hour: string; minute: string };

const DEFAULT_PARTS: Parts = { meridiem: "PM", hour: "", minute: "00" };

function compose({ meridiem, hour, minute }: Parts): string {
  if (!hour) return "";
  const h24 = (Number(hour) % 12) + (meridiem === "PM" ? 12 : 0);
  return `${String(h24).padStart(2, "0")}:${minute}`;
}

/** "HH:mm"을 선택 상태로. 비어 있으면 시만 비우고 지금 고른 오전/오후·분은 유지한다 */
function parse(value: string, current: Parts): Parts {
  const m = /^(\d{2}):(\d{2})$/.exec(value);
  if (!m) return { ...current, hour: "" };
  const h24 = Number(m[1]);
  return {
    meridiem: h24 >= 12 ? "PM" : "AM",
    hour: String(h24 % 12 === 0 ? 12 : h24 % 12),
    minute: MINUTES.includes(m[2]) ? m[2] : "00",
  };
}

type TimeSelectProps = {
  /** "HH:mm" 또는 "" */
  value: string;
  onChange: (value: string) => void;
  /** 스크린리더용 이름 앞부분 (예: "2026.11.01 회차 시각") — 각 select 뒤에 오전/오후·시·분이 붙는다 */
  label: string;
  disabled?: boolean;
  invalid?: boolean;
  className?: string;
};

export default function TimeSelect({ value, onChange, label, disabled = false, invalid = false, className = "" }: TimeSelectProps) {
  const [parts, setParts] = useState<Parts>(() => parse(value, DEFAULT_PARTS));
  const [prevValue, setPrevValue] = useState(value);

  // 부모가 값을 직접 바꿨을 때(줄 시각 일괄 적용 등)만 내부 선택을 맞춘다.
  // 내가 방금 보낸 값이거나 시를 아직 안 골라 값이 ""이면 내부 상태를 그대로 둔다.
  if (value !== prevValue) {
    setPrevValue(value);
    if (value !== compose(parts)) setParts(parse(value, parts));
  }

  const update = (patch: Partial<Parts>) => {
    const next = { ...parts, ...patch };
    setParts(next);
    const composed = compose(next);
    // 값이 그대로면(시를 아직 안 고른 채 오전/오후·분만 바꿈) 부모에 알리지 않는다
    if (composed !== compose(parts) || (composed === "" && value !== "")) onChange(composed);
  };

  const cls = invalid ? input.invalid : input.normal;
  return (
    <span className={`flex shrink-0 gap-1.5 ${className}`} role="group" aria-label={label}>
      <select
        aria-label={`${label} 오전/오후`}
        className={`${cls} !w-auto`}
        value={parts.meridiem}
        disabled={disabled}
        onChange={(e) => update({ meridiem: e.target.value as Meridiem })}
      >
        <option value="AM">오전</option>
        <option value="PM">오후</option>
      </select>
      <select
        aria-label={`${label} 시`}
        className={`${cls} !w-auto`}
        value={parts.hour}
        disabled={disabled}
        onChange={(e) => update({ hour: e.target.value })}
      >
        <option value="">시</option>
        {HOURS12.map((h) => (
          <option key={h} value={h}>
            {h}시
          </option>
        ))}
      </select>
      <select
        aria-label={`${label} 분`}
        className={`${cls} !w-auto`}
        value={parts.minute}
        disabled={disabled}
        onChange={(e) => update({ minute: e.target.value })}
      >
        {MINUTES.map((m) => (
          <option key={m} value={m}>
            {m}분
          </option>
        ))}
      </select>
    </span>
  );
}
