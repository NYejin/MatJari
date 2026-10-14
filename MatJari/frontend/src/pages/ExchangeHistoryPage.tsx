import { Link } from "react-router-dom";
import { exchangeHistoryApi, type ExchangeHistory } from "../api/exchangeHistory";
import { formatKstDateTime } from "../api/dateTime";
import { parseApiError } from "../api/errors";
import { formatSeat } from "../api/seat";
import { usePagedList } from "../hooks/usePagedList";
import { button, liveRegionClass, ui } from "../components/ui";

// 내 교환 이력 (/exchange/history, 보호 라우트). 교환이 완료될 때 남긴 '(기존 자리) -> (바꾼 자리)' 스냅샷을 최신순으로 보여 준다.
// 공연 정보는 교환 당시 값이라 이후 공연이 바뀌어도 변하지 않는다. 읽기 전용.

const PAGE_SIZE = 20;

export default function ExchangeHistoryPage() {
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<ExchangeHistory>(
    (page, signal) => exchangeHistoryApi.list(page, PAGE_SIZE, signal),
    "mine",
    (h) => h.id
  );

  const statusText =
    state.status === "loading"
      ? "불러오는 중..."
      : loadingMore
        ? "더 불러오는 중..."
        : state.status === "success"
          ? `교환 이력 ${state.totalElements}건`
          : "";

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>교환 이력</h1>
        <p className={liveRegionClass(statusText, ui.status)} role="status">
          {statusText}
        </p>

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {parseApiError(state.error, "교환 이력을 불러오지 못했습니다.").message}
            </p>
            <button type="button" className={button.solid} onClick={reload}>
              다시 시도
            </button>
          </div>
        )}

        {state.status === "success" && state.items.length === 0 && (
          <div className={`${ui.card} flex flex-col gap-2`}>
            <p className={ui.body}>아직 완료된 교환이 없어요. 두 사람이 모두 '교환 수락'을 누르면 여기에 남아요.</p>
            <Link to="/exchange/matches" className={ui.link}>
              내 매칭 보기
            </Link>
          </div>
        )}

        {state.status === "success" && state.items.length > 0 && (
          <ul className="flex flex-col gap-3">
            {state.items.map((h) => (
              <li key={h.id} className={`${ui.card} flex flex-col gap-2`}>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <Link to={`/performances/${h.performanceId}`} className={`${ui.link} break-words`}>
                    {h.performanceTitle}
                  </Link>
                  <span className={ui.badge}>교환 완료</span>
                </div>
                <p className={ui.muted}>{h.venueName}</p>
                <dl className="flex flex-col gap-1">
                  <div>
                    <dt className="text-sm/[normal] font-semibold text-gray-700">기존 자리</dt>
                    <dd className="text-sm/[normal] break-words text-gray-600">
                      {formatKstDateTime(h.oldStartsAt)} · {formatSeat(h.oldSeat)}
                    </dd>
                  </div>
                  <div aria-hidden="true" className="text-gray-500">
                    ↓
                  </div>
                  <div>
                    <dt className="text-sm/[normal] font-semibold text-gray-700">바꾼 자리</dt>
                    <dd className="text-base/[normal] font-extrabold break-words text-primary-800">
                      {formatKstDateTime(h.newStartsAt)} · {formatSeat(h.newSeat)}
                    </dd>
                  </div>
                </dl>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className={ui.muted}>{formatKstDateTime(h.completedAt)} 교환 완료</p>
                  <Link to={`/exchange/matches/${h.matchId}/chat`} className={ui.link}>
                    채팅 기록 보기
                  </Link>
                </div>
              </li>
            ))}
          </ul>
        )}

        {state.status === "success" && state.page + 1 < state.totalPages && (
          <div className="flex flex-col items-center gap-2">
            {moreError && (
              <p className={ui.errorBox} role="alert">
                {moreError}
              </p>
            )}
            <button type="button" className={button.outline} onClick={loadMore} disabled={loadingMore}>
              더 보기
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
