import { useEffect, useRef, useState } from "react";
import { formatKstDateTime } from "../api/dateTime";
import { button, ui } from "./ui";

// 희망 회차 선택 + 우선순위 설정 (요청 단위). 체크한 회차가 목록 순서대로 1순위, 2순위...가 된다.
// 위/아래 버튼은 이웃한 '체크된' 회차와 자리를 바꾼다. 반드시 모듈 최상위 컴포넌트로 쓴다.

export interface WantSessionRow {
  sessionId: number;
  startsAt: string;
  checked: boolean;
  /** 마감(회차 당일이 지남) — 선택할 수 없다 */
  closed: boolean;
  /** 내 티켓의 회차 */
  mine: boolean;
}

interface WantSessionsEditorProps {
  rows: WantSessionRow[];
  onChange: (rows: WantSessionRow[]) => void;
  error?: string;
  disabled?: boolean;
}

export const WANT_SESSIONS_ERROR_ID = "want-sessions-error";
export const wantSessionCheckboxId = (sessionId: number) => `want-session-${sessionId}`;

export default function WantSessionsEditor({ rows, onChange, error, disabled = false }: WantSessionsEditorProps) {
  const [announce, setAnnounce] = useState("");
  /** 이동 뒤 포커스를 돌려줄 버튼 id (요소가 재배치되면 포커스를 잃을 수 있어서) */
  const pendingFocus = useRef<string | null>(null);

  useEffect(() => {
    if (!pendingFocus.current) return;
    document.getElementById(pendingFocus.current)?.focus();
    pendingFocus.current = null;
  });

  const toggle = (sessionId: number) =>
    onChange(rows.map((r) => (r.sessionId === sessionId && !r.closed ? { ...r, checked: !r.checked } : r)));

  const move = (index: number, dir: -1 | 1) => {
    let target = index + dir;
    while (target >= 0 && target < rows.length && !rows[target].checked) target += dir;
    if (target < 0 || target >= rows.length) return;
    const next = [...rows];
    [next[index], next[target]] = [next[target], next[index]];
    const checkedOrder = next.filter((r) => r.checked);
    const rank = checkedOrder.findIndex((r) => r.sessionId === rows[index].sessionId) + 1;
    // 요소가 재배치되면 포커스를 잃을 수 있어 같은 방향 버튼(더 못 옮기면 반대 버튼)으로 돌려준다
    const canUp = next.slice(0, target).some((r) => r.checked);
    const canDown = next.slice(target + 1).some((r) => r.checked);
    const dirName = dir === -1 ? (canUp ? "up" : "down") : canDown ? "down" : "up";
    pendingFocus.current = `want-session-${rows[index].sessionId}-${dirName}`;
    setAnnounce(`${formatKstDateTime(rows[index].startsAt)} 회차를 ${rank}순위로 옮겼어요.`);
    onChange(next);
  };

  const checkedCount = rows.filter((r) => r.checked).length;
  let rank = 0;

  return (
    <fieldset
      className="flex min-w-0 flex-col gap-3"
      disabled={disabled}
      aria-describedby={error ? WANT_SESSIONS_ERROR_ID : "want-sessions-hint"}
    >
      <legend className={`${ui.sectionTitle} mb-1`}>희망 회차와 우선순위</legend>
      <p id="want-sessions-hint" className={ui.hint}>
        교환하고 싶은 회차를 고르고, 위·아래 버튼으로 우선순위를 정해요. 위에 있을수록 먼저 보여드려요.
      </p>
      {rows.length === 0 ? (
        <p className={ui.body}>이 공연에는 회차가 없어요.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-gray-200">
          {rows.map((row, index) => {
            const label = formatKstDateTime(row.startsAt);
            const thisRank = row.checked ? ++rank : 0;
            const hasPrev = rows.slice(0, index).some((r) => r.checked);
            const hasNext = rows.slice(index + 1).some((r) => r.checked);
            const cbId = wantSessionCheckboxId(row.sessionId);
            return (
              <li key={row.sessionId} className="flex flex-wrap items-center justify-between gap-2 py-2">
                <div className="flex min-h-11 min-w-0 items-center gap-3">
                  <input
                    id={cbId}
                    type="checkbox"
                    className="size-5 shrink-0 accent-primary-600"
                    checked={row.checked}
                    disabled={row.closed || disabled}
                    onChange={() => toggle(row.sessionId)}
                  />
                  <label htmlFor={cbId} className="flex min-w-0 flex-wrap items-center gap-2 text-[0.9375rem]/[normal] text-gray-900">
                    <span>{label}</span>
                    {row.mine && <span className={ui.badge}>내 티켓 회차</span>}
                    {row.closed && <span className={ui.badge}>마감</span>}
                    {row.checked && <span className="text-sm/[normal] font-bold text-primary-700">{thisRank}순위</span>}
                  </label>
                </div>
                {row.checked && checkedCount > 1 && (
                  <div className="flex gap-2">
                    <button
                      id={`want-session-${row.sessionId}-up`}
                      type="button"
                      className={button.outline}
                      disabled={!hasPrev || disabled}
                      onClick={() => move(index, -1)}
                      aria-label={`${label} 우선순위 올리기`}
                    >
                      위로
                    </button>
                    <button
                      id={`want-session-${row.sessionId}-down`}
                      type="button"
                      className={button.outline}
                      disabled={!hasNext || disabled}
                      onClick={() => move(index, 1)}
                      aria-label={`${label} 우선순위 내리기`}
                    >
                      아래로
                    </button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
      <p className="sr-only" role="status">
        {announce}
      </p>
      {error && (
        <p id={WANT_SESSIONS_ERROR_ID} className={ui.fieldError}>
          {error}
        </p>
      )}
    </fieldset>
  );
}
