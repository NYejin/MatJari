import { useState } from "react";
import { Link } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import {
  REPORT_REASON_LABEL,
  REPORT_STATUS_LABEL,
  adminReportsApi,
  formatUntil,
  type AdminReport,
  type ReportReason,
  type ReportStatus,
} from "../api/adminReports";
import { FOCUS_RING, button, input, ui } from "../components/ui";
import { usePagedList } from "../hooks/usePagedList";

// 관리자 신고 목록 (/admin/reports, ADMIN 전용 — 가드는 라우트에서).
const STATUS_TABS: { value: ReportStatus | ""; label: string }[] = [
  { value: "OPEN", label: "처리 대기" },
  { value: "RESOLVED", label: "조치함" },
  { value: "DISMISSED", label: "기각" },
  { value: "", label: "전체" },
];

export default function AdminReportsPage() {
  const [status, setStatus] = useState<ReportStatus | "">("OPEN");
  const [reason, setReason] = useState<ReportReason | "">("");
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<AdminReport>(
    (page, signal) => adminReportsApi.list({ status: status || undefined, reason: reason || undefined }, page, signal),
    `${status}|${reason}`,
    (r) => r.id
  );

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>신고 처리</h1>

        <div role="group" aria-label="처리 상태" className="flex flex-wrap gap-2">
          {STATUS_TABS.map((t) => (
            <button
              key={t.value || "all"}
              type="button"
              className={status === t.value ? button.solid : button.outline}
              aria-pressed={status === t.value}
              onClick={() => setStatus(t.value)}
            >
              {t.label}
            </button>
          ))}
        </div>
        <div className="flex flex-col gap-1.5">
          <label htmlFor="report-reason-filter" className={ui.label}>
            신고 사유
          </label>
          <select
            id="report-reason-filter"
            className={input.normal}
            value={reason}
            onChange={(e) => setReason(e.target.value as ReportReason | "")}
          >
            <option value="">전체</option>
            {(Object.keys(REPORT_REASON_LABEL) as ReportReason[]).map((r) => (
              <option key={r} value={r}>
                {REPORT_REASON_LABEL[r]}
              </option>
            ))}
          </select>
        </div>

        {state.status === "loading" && (
          <p className={ui.status} role="status">
            불러오는 중...
          </p>
        )}
        {state.status === "error" && (
          <div className="flex flex-col gap-3">
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "신고 목록을 불러오지 못했습니다.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}
        {state.status === "success" && state.items.length === 0 && <p className={`${ui.card} ${ui.body}`}>해당하는 신고가 없어요.</p>}
        {state.status === "success" && state.items.length > 0 && (
          <>
            <ul className="flex flex-col gap-3" aria-label="신고 목록">
              {state.items.map((r) => (
                <li key={r.id}>
                  <ReportCard report={r} />
                </li>
              ))}
            </ul>
            {moreError && (
              <p className={ui.errorBox} role="alert">
                {moreError}
              </p>
            )}
            {state.page + 1 < state.totalPages && (
              <button type="button" className={button.outline} onClick={loadMore} disabled={loadingMore} aria-busy={loadingMore}>
                {loadingMore ? "불러오는 중..." : "더 보기"}
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function ReportCard({ report: r }: { report: AdminReport }) {
  return (
    <Link
      to={`/admin/reports/${r.id}`}
      className={`${ui.card} flex min-h-11 flex-col gap-2 no-underline active:bg-gray-50 ${FOCUS_RING}`}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-base/[normal] font-bold text-gray-900">{REPORT_REASON_LABEL[r.reason]}</span>
        <span className={ui.badge}>{REPORT_STATUS_LABEL[r.status]}</span>
      </div>
      <p className={ui.body}>
        {r.reporter.nickname} → <span className="font-semibold">{r.reported.nickname}</span>
      </p>
      <p className={ui.muted}>
        대상의 누적 신고 {r.reported.totalReports}건 · 열린 신고 {r.reported.openReports}건 · {formatKstDateTime(r.createdAt)}
      </p>
      {r.reported.activeSanction && <p className={ui.errorBox}>이용 정지 중 ({formatUntil(r.reported.activeSanction.until)})</p>}
    </Link>
  );
}
