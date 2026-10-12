import { Link } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { parseApiError } from "../api/errors";
import { REASON_LABEL, REPORT_STATUS_LABEL, reportsApi, type MyReport, type ReportStatus } from "../api/reports";
import { usePagedList } from "../hooks/usePagedList";
import { button, liveRegionClass, ui } from "../components/ui";

// 내 신고 내역 (/my/reports, 보호 라우트). 신고자에게는 처리 상태만 보인다(처리 메모·제재 내용은 숨김). 읽기 전용.

const PAGE_SIZE = 20;

/** 상태 뱃지: 처리 전은 강조, 처리 후는 기본 */
const STATUS_CLASS: Record<ReportStatus, string> = {
  OPEN: "inline-flex shrink-0 items-center rounded-full bg-accent-100 px-2.5 py-0.5 text-[0.8125rem]/[normal] text-gray-900",
  RESOLVED: ui.badge,
  DISMISSED: ui.badge,
};

export default function MyReportsPage() {
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<MyReport>(
    (page, signal) => reportsApi.listMine(page, PAGE_SIZE, signal),
    "mine",
    (r) => r.id
  );

  const statusText =
    state.status === "loading" ? "불러오는 중..." : loadingMore ? "더 불러오는 중..." : state.status === "success" ? `내 신고 ${state.totalElements}건` : "";

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>내 신고 내역</h1>
        <p className={ui.hint}>처리 상태만 확인할 수 있어요. 신고는 상대에게 알리지 않아요.</p>
        <p className={liveRegionClass(statusText, ui.status)} role="status">
          {statusText}
        </p>

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {parseApiError(state.error, "신고 내역을 불러오지 못했습니다.").message}
            </p>
            <button type="button" className={button.solid} onClick={reload}>
              다시 시도
            </button>
          </div>
        )}

        {state.status === "success" && state.items.length === 0 && (
          <div className={ui.card}>
            <p className={ui.body}>접수한 신고가 없어요. 신고는 채팅 화면의 '신고' 버튼으로 할 수 있어요.</p>
          </div>
        )}

        {state.status === "success" && state.items.length > 0 && (
          <ul className="flex flex-col gap-3">
            {state.items.map((r) => (
              <li key={r.id} className={`${ui.card} flex flex-col gap-2`}>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className="min-w-0 truncate text-base/[normal] font-semibold text-gray-900">{r.reportedNickname}님</p>
                  <span className={STATUS_CLASS[r.status]}>{REPORT_STATUS_LABEL[r.status]}</span>
                </div>
                <p className={ui.body}>사유: {REASON_LABEL[r.reason]}</p>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <p className={ui.muted}>
                    {formatKstDateTime(r.createdAt)} 접수{r.handledAt ? ` · ${formatKstDateTime(r.handledAt)} 처리` : ""}
                  </p>
                  <Link to={`/exchange/matches/${r.matchId}/chat`} className={ui.link}>
                    채팅 보기
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
