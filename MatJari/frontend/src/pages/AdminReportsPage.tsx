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
import { FOCUS_RING, button, input, ui } from "../components/adminUi";
import { tbl, tone } from "../components/adminUi";
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

        <div className="flex flex-wrap items-end gap-2">
        <div role="group" aria-label="처리 상태" className="flex flex-wrap gap-1">
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
        <div className="flex flex-col gap-0.5">
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
        </div>

        {state.status === "loading" && (
          <p className={ui.status} role="status">
            불러오는 중...
          </p>
        )}
        {state.status === "error" && (
          <div className="flex flex-col gap-1.5">
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
            <div className={tbl.wrap}>
              <table className={tbl.table} aria-label="신고 목록">
                <thead>
                  <tr>
                    {["#", "사유", "상태", "신고자", "대상", "대상 누적", "대상 열린", "대상 정지", "접수"].map((c) => (
                      <th key={c} className={tbl.th}>
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {state.items.map((r) => (
                    <ReportRow key={r.id} report={r} />
                  ))}
                </tbody>
              </table>
            </div>
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

function ReportRow({ report: r }: { report: AdminReport }) {
  return (
    <tr className={tbl.tr}>
      <td className={tbl.num}>
        <Link to={`/admin/reports/${r.id}`} className={`${ui.link} ${FOCUS_RING}`}>
          {r.id}
        </Link>
      </td>
      <td className={`${tbl.td} font-semibold whitespace-nowrap`}>
        <Link to={`/admin/reports/${r.id}`} className="text-gray-900 no-underline hover:underline">
          {REPORT_REASON_LABEL[r.reason]}
        </Link>
      </td>
      <td className={tbl.td}>
        <span className={r.status === "OPEN" ? tone.warn : r.status === "RESOLVED" ? tone.danger : tone.mute}>{REPORT_STATUS_LABEL[r.status]}</span>
      </td>
      <td className={tbl.td}>{r.reporter.nickname}</td>
      <td className={`${tbl.td} font-semibold`}>{r.reported.nickname}</td>
      <td className={tbl.num}>{r.reported.totalReports}</td>
      <td className={tbl.num}>{r.reported.openReports}</td>
      <td className={`${tbl.td} whitespace-nowrap`}>{r.reported.activeSanction ? <span className={tone.danger}>{formatUntil(r.reported.activeSanction.until)}</span> : "-"}</td>
      <td className={`${tbl.td} whitespace-nowrap`}>{formatKstDateTime(r.createdAt)}</td>
    </tr>
  );
}
