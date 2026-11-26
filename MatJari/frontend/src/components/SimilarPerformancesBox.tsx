import { useRef, useState } from "react";
import { getErrorCode, parseApiError } from "../api/errors";
import { formatKstDateTime } from "../api/dateTime";
import { getConflictPerformanceId, performancesApi } from "../api/performances";
import type { SimilarCandidate } from "../types/performance";
import { button, ui } from "./ui";

// 등록 1단계·409 SIMILAR_PERFORMANCE_EXISTS 공용: 다른 예매처 링크로 이미 등록된 같은 공연 후보.
// 한 공연을 여러 예매처가 나눠 파는 경우를 한 공연으로 묶는다.

const SITE_NAMES: Record<string, string> = { melon: "멜론티켓", interpark: "인터파크(NOL)", ticketlink: "티켓링크", yes24: "YES24" };

interface Props {
  sourceUrl: string;
  candidates: SimilarCandidate[];
  /** 링크 추가(또는 이미 같은 공연) 성공 뒤 그 공연으로 이동 */
  onLinked: (performanceId: number) => void;
  /** [다른 공연이에요, 새로 등록할게요] */
  onNew: () => void;
  /** 관리자(티켓 없이 등록)면 버튼 문구가 '링크 추가' */
  isAdmin?: boolean;
  disabled?: boolean;
}

function periodText(c: SimilarCandidate): string {
  if (!c.firstSessionAt) return "회차 없음";
  const first = formatKstDateTime(c.firstSessionAt);
  const date = (s: string) => s.slice(0, 10);
  if (!c.lastSessionAt || date(c.lastSessionAt) === date(c.firstSessionAt)) return first;
  return `${date(first)} ~ ${date(formatKstDateTime(c.lastSessionAt))}`;
}

export default function SimilarPerformancesBox({ sourceUrl, candidates, onLinked, onNew, isAdmin, disabled }: Props) {
  const [busyId, setBusyId] = useState<number | null>(null);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);

  const link = async (c: SimilarCandidate) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusyId(c.id);
    setError(null);
    try {
      await performancesApi.addSource(c.id, sourceUrl);
      onLinked(c.id);
    } catch (err) {
      const code = getErrorCode(err);
      // 이미 이 공연의 링크면 멱등으로 보고 이동한다
      if (code === "SOURCE_ALREADY_REGISTERED" && getConflictPerformanceId(err) === c.id) {
        onLinked(c.id);
        return;
      }
      setError(
        code === "SOURCE_NOT_SAME_PERFORMANCE"
          ? "같은 공연으로 확인되지 않았어요. 다른 공연이라면 아래에서 새로 등록할 수 있어요."
          : code === "SOURCE_LIMIT_REACHED"
            ? "이 공연에는 예매처 링크를 더 붙일 수 없어요."
            : parseApiError(err, "링크를 추가하지 못했어요.").message,
      );
    } finally {
      busyRef.current = false;
      setBusyId(null);
    }
  };

  return (
    <div className="flex flex-col gap-3" data-testid="similar-box">
      <p className={ui.notice} role="status">
        다른 예매처 링크로 이미 등록된 같은 공연일 수 있어요. 한 공연을 여러 예매처가 나눠 파는 경우가 있어요.
      </p>
      <ul className="flex flex-col gap-2">
        {candidates.map((c) => (
          <li key={c.id} className={`${ui.well} flex flex-col gap-2`}>
            <div className="flex flex-col gap-0.5">
              <span className="font-semibold break-words">{c.title}</span>
              <span className={ui.hint}>
                {c.venueName} · {periodText(c)} · 회차 {c.sessionCount}개
              </span>
              <span className="flex flex-wrap gap-1">
                {c.sites.map((s) => (
                  <span key={s} className={ui.badge}>
                    {SITE_NAMES[s] ?? s}
                  </span>
                ))}
              </span>
            </div>
            <div>
              <button
                type="button"
                className={button.solid}
                disabled={disabled || busyId !== null}
                aria-busy={busyId === c.id}
                onClick={() => void link(c)}
              >
                {busyId === c.id ? "연결 중..." : isAdmin ? "이 공연에 링크 추가" : "이 공연에 티켓 등록하기"}
              </button>
            </div>
          </li>
        ))}
      </ul>
      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}
      <div>
        <button type="button" className={button.outline} disabled={disabled || busyId !== null} onClick={onNew}>
          다른 공연이에요, 새로 등록할게요
        </button>
      </div>
    </div>
  );
}
