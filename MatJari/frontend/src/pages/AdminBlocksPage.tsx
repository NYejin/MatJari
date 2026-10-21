import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { adminReportsApi, type AdminBlock } from "../api/adminReports";
import { button, ui } from "../components/adminUi";
import { tbl } from "../components/adminUi";
import { usePagedList } from "../hooks/usePagedList";

// 관리자 차단 현황 (/admin/blocks) — 읽기 전용.
export default function AdminBlocksPage() {
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<AdminBlock>(
    (page, signal) => adminReportsApi.blocks(page, signal),
    "blocks",
    (b) => b.id
  );

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>차단 현황</h1>
        <p className={ui.notice}>사용자끼리의 차단 현황을 보기만 해요. 관리자가 해제하거나 바꿀 수 없어요.</p>

        {state.status === "loading" && (
          <p className={ui.status} role="status">
            불러오는 중...
          </p>
        )}
        {state.status === "error" && (
          <div className="flex flex-col gap-1.5">
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "차단 현황을 불러오지 못했습니다.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}
        {state.status === "success" && state.items.length === 0 && <p className={`${ui.card} ${ui.body}`}>차단 내역이 없어요.</p>}
        {state.status === "success" && state.items.length > 0 && (
          <>
            <p className={ui.muted}>총 {state.totalElements}건</p>
            <div className={tbl.wrap}>
              <table className={tbl.table} aria-label="차단 목록">
                <thead>
                  <tr>
                    {["#", "차단한 사람", "차단된 사람", "일시"].map((c) => (
                      <th key={c} className={tbl.th}>
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {state.items.map((b) => (
                    <tr key={b.id} className={tbl.tr}>
                      <td className={tbl.num}>{b.id}</td>
                      <td className={`${tbl.td} font-semibold`}>{b.blocker.nickname}</td>
                      <td className={`${tbl.td} font-semibold`}>{b.blocked.nickname}</td>
                      <td className={`${tbl.td} whitespace-nowrap`}>{formatKstDateTime(b.createdAt)}</td>
                    </tr>
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
