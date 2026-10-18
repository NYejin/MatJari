import { useState } from "react";
import { Link } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { adminPerformancesApi, type AdminPerformanceItem, type HiddenFilter } from "../api/adminPerformances";
import { button, input, ui } from "../components/ui";
import { useDebouncedValue } from "../hooks/useDebouncedValue";
import { usePagedList } from "../hooks/usePagedList";

// 관리자 공연 관리 목록 (/admin/performances). 숨긴 공연도 보인다.
const TABS: { value: HiddenFilter; label: string }[] = [
  { value: "ALL", label: "전체" },
  { value: "VISIBLE", label: "노출" },
  { value: "HIDDEN", label: "숨김" },
];

export default function AdminPerformancesPage() {
  const [filter, setFilter] = useState<HiddenFilter>("ALL");
  const [query, setQuery] = useState("");
  const debounced = useDebouncedValue(query.trim(), 300);
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<AdminPerformanceItem>(
    (page, signal) => adminPerformancesApi.list({ query: debounced, hidden: filter, page }, signal),
    `${filter}|${debounced}`,
    (p) => p.id
  );

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>공연 관리</h1>

        <div className="flex flex-col gap-1.5">
          <label htmlFor="admin-perf-search" className={ui.label}>
            제목 검색
          </label>
          <input
            id="admin-perf-search"
            type="search"
            className={input.normal}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="공연 제목"
            autoComplete="off"
          />
        </div>

        <div role="group" aria-label="노출 상태" className="flex gap-2">
          {TABS.map((t) => (
            <button
              key={t.value}
              type="button"
              className={filter === t.value ? button.solid : button.outline}
              aria-pressed={filter === t.value}
              onClick={() => setFilter(t.value)}
            >
              {t.label}
            </button>
          ))}
        </div>

        {state.status === "loading" && (
          <p className={ui.status} role="status">
            불러오는 중...
          </p>
        )}
        {state.status === "error" && (
          <div className="flex flex-col gap-3">
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "공연 목록을 불러오지 못했습니다.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}
        {state.status === "success" && state.items.length === 0 && <p className={`${ui.card} ${ui.body}`}>해당하는 공연이 없어요.</p>}
        {state.status === "success" && state.items.length > 0 && (
          <>
            <p className={ui.muted}>총 {state.totalElements}개</p>
            <ul className="flex flex-col gap-3" aria-label="공연 목록">
              {state.items.map((p) => (
                <li key={p.id}>
                  <Link to={`/admin/performances/${p.id}`} className={`${ui.card} flex flex-col gap-1.5 no-underline active:bg-gray-50`}>
                    <span className="flex flex-wrap items-center gap-2">
                      <span className="min-w-0 text-base/[normal] font-bold break-words text-gray-900">{p.title}</span>
                      {p.hidden && <span className={ui.badge}>숨김</span>}
                    </span>
                    <span className={`${ui.body} break-words`}>{p.venueName}</span>
                    <span className={ui.muted}>
                      회차 {p.sessionCount}개 · 구역 {p.zoneCount}개 · 등록 {p.registrantNickname} · {formatKstDateTime(p.createdAt)}
                    </span>
                  </Link>
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
