import { useId } from "react";
import {
  compareLocalDateTime,
  isPastKst,
  normalizeLocalDateTime,
  SESSION_TIME_STEP_ERROR,
  SESSION_TIME_STEP_SECONDS,
  validateSessionTimeStep,
} from "../api/dateTime";
import { SESSION_CHANGES_MAX, type SessionChangeRequest } from "../api/editProposals";
import type { PerformanceSession } from "../types/performance";
import { useTokens } from "./density";

// 공연 수정 제안의 '회차 수정' 입력. 현재 회차는 값 변경(CHANGE)·삭제 토글(REMOVE), 새 줄은 ADD.
// 사전 검사는 서버 규칙과 같다: 10분 단위, 지난 시각 불가, 같은 시각 중복 불가, 남는 회차 1개 이상, 빈 ADD 줄 무시.

export interface SessionRow {
  key: string;
  /** 기존 회차 id (새 줄은 null) */
  sessionId: number | null;
  /** 기존 시각 "YYYY-MM-DDTHH:mm" (새 줄은 null) */
  original: string | null;
  value: string;
  removed: boolean;
}

let rowSeq = 0;
export const newSessionRow = (): SessionRow => ({ key: `new-${rowSeq++}`, sessionId: null, original: null, value: "", removed: false });

export function initialSessionRows(sessions: PerformanceSession[]): SessionRow[] {
  return [...sessions]
    .sort((a, b) => compareLocalDateTime(a.startsAt, b.startsAt))
    .map((s) => {
      const t = normalizeLocalDateTime(s.startsAt) ?? s.startsAt;
      return { key: `s-${s.id}`, sessionId: s.id, original: t, value: t, removed: false };
    });
}

export interface SessionChangeCheck {
  changes: SessionChangeRequest[];
  rowErrors: Record<string, string>;
  /** 줄에 속하지 않는 오류 (남는 회차 없음 등) */
  formError: string | null;
}

export function checkSessionChanges(rows: SessionRow[]): SessionChangeCheck {
  const changes: SessionChangeRequest[] = [];
  const rowErrors: Record<string, string> = {};
  const remaining: { key: string; time: string }[] = [];

  for (const r of rows) {
    const v = r.value.trim();
    if (r.sessionId !== null) {
      if (r.removed) {
        changes.push({ type: "REMOVE", sessionId: r.sessionId });
        continue;
      }
      if (v === "") {
        rowErrors[r.key] = "날짜와 시간을 입력해주세요. 비우려면 '삭제'를 눌러 주세요.";
        continue;
      }
      const norm = normalizeLocalDateTime(v);
      if (norm !== null && norm === r.original) {
        remaining.push({ key: r.key, time: norm });
        continue;
      }
      const stepError = validateSessionTimeStep(v);
      if (stepError || !norm) rowErrors[r.key] = stepError ?? SESSION_TIME_STEP_ERROR;
      else if (isPastKst(norm)) rowErrors[r.key] = "지난 시각으로는 바꿀 수 없어요.";
      else {
        changes.push({ type: "CHANGE", sessionId: r.sessionId, startsAt: norm });
        remaining.push({ key: r.key, time: norm });
      }
    } else {
      if (v === "") continue; // 빈 ADD 줄은 무시
      const norm = normalizeLocalDateTime(v);
      const stepError = validateSessionTimeStep(v);
      if (stepError || !norm) rowErrors[r.key] = stepError ?? SESSION_TIME_STEP_ERROR;
      else if (isPastKst(norm)) rowErrors[r.key] = "지난 시각은 추가할 수 없어요.";
      else {
        changes.push({ type: "ADD", startsAt: norm });
        remaining.push({ key: r.key, time: norm });
      }
    }
  }

  // 변경 후 같은 시각 회차가 둘 이상이면 해당 줄에 표시
  const seen = new Map<string, string>();
  for (const { key, time } of remaining) {
    const first = seen.get(time);
    if (first === undefined) {
      seen.set(time, key);
    } else {
      rowErrors[key] = "같은 시각의 회차가 이미 있어요.";
      rowErrors[first] ??= "같은 시각의 회차가 이미 있어요.";
    }
  }

  let formError: string | null = null;
  if (changes.length > SESSION_CHANGES_MAX) formError = `회차 변경은 한 번에 ${SESSION_CHANGES_MAX}개까지 제안할 수 있어요.`;
  else if (changes.length > 0 && remaining.length === 0) formError = "회차를 모두 삭제할 수는 없어요. 최소 1개는 남겨 주세요.";

  return { changes, rowErrors, formError };
}

interface Props {
  rows: SessionRow[];
  onChange: (rows: SessionRow[]) => void;
  rowErrors: Record<string, string>;
  formError: string | null;
  disabled?: boolean;
  /** 삭제할 수 없는 기존 회차 id (사용 중인 회차). 삭제 버튼을 막고 사유를 보인다 */
  lockedIds?: ReadonlySet<number>;
  /** 관리자 직접 수정처럼 '제안'이 아닌 문맥의 제목·안내 */
  title?: string;
  hint?: string;
}

export default function SessionEditor({ rows, onChange, rowErrors, formError, disabled, lockedIds, title, hint }: Props) {
  const { button, input, ui } = useTokens();
  const baseId = useId();
  const update = (key: string, patch: Partial<SessionRow>) =>
    onChange(rows.map((r) => (r.key === key ? { ...r, ...patch } : r)));

  return (
    <fieldset className="flex min-w-0 flex-col gap-3 border-0 p-0">
      <legend className={`${ui.label} mb-1.5`}>{title ?? "회차 수정 (선택)"}</legend>
      <p className={ui.hint}>
        {hint ?? "시각을 바꾸면 변경, '삭제'를 누르면 삭제를 제안해요. 새 회차는 '회차 추가'로 넣어 주세요. 10분 단위로 입력해요."}
      </p>
      <ul className="flex flex-col gap-3">
        {rows.map((r, i) => {
          const id = `${baseId}-${r.key}`;
          const err = rowErrors[r.key];
          const isNew = r.sessionId === null;
          const label = isNew ? `추가할 회차 ${i + 1}` : `회차 ${i + 1}`;
          return (
            <li key={r.key} className="flex flex-col gap-1.5">
              <label htmlFor={id} className="sr-only">
                {label}
              </label>
              <div className="flex flex-wrap items-center gap-2">
                <input
                  id={id}
                  type="datetime-local"
                  step={SESSION_TIME_STEP_SECONDS}
                  className={`${err ? input.invalid : input.normal} min-w-0 flex-1 ${r.removed ? "text-gray-500 line-through" : ""}`}
                  value={r.value}
                  disabled={disabled || r.removed}
                  aria-invalid={err ? true : undefined}
                  aria-describedby={err ? `${id}-err` : undefined}
                  onChange={(e) => update(r.key, { value: e.target.value })}
                />
                {isNew ? (
                  <button
                    type="button"
                    className={button.outline}
                    disabled={disabled}
                    onClick={() => onChange(rows.filter((x) => x.key !== r.key))}
                  >
                    줄 삭제<span className="sr-only"> ({label})</span>
                  </button>
                ) : (
                  <button
                    type="button"
                    className={r.removed ? button.outline : button.dangerOutline}
                    aria-pressed={r.removed}
                    disabled={disabled || (r.sessionId !== null && !!lockedIds?.has(r.sessionId))}
                    onClick={() => update(r.key, { removed: !r.removed, value: r.original ?? r.value })}
                  >
                    {r.removed ? "삭제 취소" : "삭제"}
                    <span className="sr-only"> ({label})</span>
                  </button>
                )}
              </div>
              {r.removed && <p className={ui.hint}>{title ? "이 회차는 삭제돼요." : "이 회차는 삭제하도록 제안해요."}</p>}
              {r.sessionId !== null && lockedIds?.has(r.sessionId) && (
                <p className={ui.hint}>티켓이나 교환 조건이 걸려 있어 삭제할 수 없어요. 시각 변경은 가능해요.</p>
              )}
              {err && (
                <p id={`${id}-err`} className={ui.fieldError}>
                  {err}
                </p>
              )}
            </li>
          );
        })}
      </ul>
      <button
        type="button"
        className={`${button.outline} self-start`}
        disabled={disabled}
        onClick={() => onChange([...rows, newSessionRow()])}
      >
        회차 추가
      </button>
      {formError && <p className={ui.fieldError}>{formError}</p>}
    </fieldset>
  );
}
