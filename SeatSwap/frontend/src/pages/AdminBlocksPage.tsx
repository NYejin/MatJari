import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { adminReportsApi, type AdminBlock } from "../api/adminReports";
import { button, ui } from "../components/ui";
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
          <div className="flex flex-col gap-3">
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
            <ul className="flex flex-col gap-3" aria-label="차단 목록">
              {state.items.map((b) => (
                <li key={b.id} className={`${ui.card} flex flex-col gap-1`}>
                  <p className={ui.body}>
                    <span className="font-semibold">{b.blocker.nickname}</span> 님이{" "}
                    <span className="font-semibold">{b.blocked.nickname}</span> 님을 차단
                  </p>
                  <p className={ui.muted}>{formatKstDateTime(b.createdAt)}</p>
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
