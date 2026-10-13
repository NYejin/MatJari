import type { SessionChange } from "../api/editProposals";
import { formatKstDateTime } from "../api/dateTime";
import { ui } from "./ui";

// 수정 제안의 '회차 변경' 블록 (관리자 화면·내 수정 제안 공용). 변경이 없으면 아무것도 그리지 않는다.
// 추가: 새 시각 / 변경: 기존 시각 → 새 시각 / 삭제: 기존 시각

const when = (value: string | null) => (value ? formatKstDateTime(value) : "(알 수 없음)");

export default function SessionChangesView({ changes }: { changes: SessionChange[] }) {
  if (changes.length === 0) return null;
  return (
    <div className="flex flex-col gap-1">
      <span className={ui.label}>회차 변경</span>
      <ul className="flex flex-col gap-1">
        {changes.map((c, i) => (
          <li key={`${c.type}-${c.sessionId ?? "new"}-${i}`} className={`${ui.body} break-words`}>
            {c.type === "ADD" && (
              <>
                <span className="font-semibold">추가:</span> <strong className="text-gray-900">{when(c.startsAt)}</strong>
              </>
            )}
            {c.type === "CHANGE" && (
              <>
                <span className="font-semibold">변경:</span>{" "}
                <span className="text-gray-500 line-through">{when(c.currentStartsAt)}</span>
                <span className="sr-only"> 에서 </span>
                <span aria-hidden="true"> → </span>
                <strong className="text-gray-900">{when(c.startsAt)}</strong>
              </>
            )}
            {c.type === "REMOVE" && (
              <>
                <span className="font-semibold">삭제:</span>{" "}
                <span className="text-gray-500 line-through">{when(c.currentStartsAt)}</span>
              </>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
