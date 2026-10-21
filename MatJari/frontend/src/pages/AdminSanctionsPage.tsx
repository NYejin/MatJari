import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { ADMIN_REPORT_CODE_MESSAGES, adminReportsApi, formatUntil, type AdminSanction, type SanctionFilter } from "../api/adminReports";
import { button, ui } from "../components/adminUi";
import { tbl, tone } from "../components/adminUi";
import { usePagedList } from "../hooks/usePagedList";

// 관리자 이용 정지(제재) 목록·해제 (/admin/sanctions).
const TABS: { value: SanctionFilter; label: string }[] = [
  { value: "true", label: "활성" },
  { value: "all", label: "전체" },
];

export default function AdminSanctionsPage() {
  const [filter, setFilter] = useState<SanctionFilter>("true");
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<AdminSanction>(
    (page, signal) => adminReportsApi.sanctions(filter, page, signal),
    filter,
    (s) => s.id
  );

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>이용 정지 관리</h1>

        <div role="group" aria-label="제재 상태" className="flex gap-2">
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
          <div className="flex flex-col gap-1.5">
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "제재 목록을 불러오지 못했습니다.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}
        {state.status === "success" && state.items.length === 0 && <p className={`${ui.card} ${ui.body}`}>해당하는 제재가 없어요.</p>}
        {state.status === "success" && state.items.length > 0 && (
          <>
            <div className={tbl.wrap}>
              <table className={tbl.table} aria-label="제재 목록">
                <thead>
                  <tr>
                    {["#", "대상", "상태", "사유", "시작", "기한", "처리자", "신고", "조치"].map((c) => (
                      <th key={c} className={tbl.th}>
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {state.items.map((s) => (
                    <SanctionRow key={s.id} sanction={s} onDone={reload} />
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

function SanctionRow({ sanction: s, onDone }: { sanction: AdminSanction; onDone: () => void }) {
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);

  const revoke = async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await adminReportsApi.revokeSanction(s.id);
      onDone();
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "해제하지 못했습니다.");
      setError((parsed.code && ADMIN_REPORT_CODE_MESSAGES[parsed.code]) || parsed.message);
      setConfirming(false);
      if (parsed.code === "SANCTION_NOT_ACTIVE") onDone();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <tr className={tbl.tr}>
      <td className={tbl.num}>{s.id}</td>
      <td className={`${tbl.td} font-semibold`}>{s.nickname}</td>
      <td className={tbl.td}>
        {s.revokedAt ? (
          <span className={tone.mute} title={formatKstDateTime(s.revokedAt)}>
            해제됨
          </span>
        ) : s.active ? (
          <span className={tone.danger}>정지 중</span>
        ) : (
          <span className={tone.mute}>만료</span>
        )}
      </td>
      <td className={`${tbl.td} max-w-80`}>{s.note}</td>
      <td className={`${tbl.td} whitespace-nowrap`}>{formatKstDateTime(s.startsAt)}</td>
      <td className={`${tbl.td} whitespace-nowrap`}>{formatUntil(s.endsAt)}</td>
      <td className={tbl.td}>{s.createdByNickname}</td>
      <td className={tbl.td}>
        {s.reportId !== null ? (
          <Link to={`/admin/reports/${s.reportId}`} className={ui.link}>
            #{s.reportId}
          </Link>
        ) : (
          "직접"
        )}
      </td>
      <td className={`${tbl.td} whitespace-nowrap`}>
        {error && (
          <p className={ui.errorBox} role="alert">
            {error}
          </p>
        )}
        {s.active && !confirming && (
          <button type="button" className={button.dangerOutline} onClick={() => setConfirming(true)} disabled={busy}>
            정지 해제
          </button>
        )}
        {s.active && confirming && (
          <span className="flex items-center gap-1" role="group" aria-label={`${s.nickname} 정지 해제 확인`}>
            <span className="text-[11px] text-gray-700">해제할까요?</span>
            <button type="button" className={button.outline} onClick={() => setConfirming(false)} disabled={busy}>
              취소
            </button>
            <button type="button" className={button.solid} onClick={() => void revoke()} disabled={busy} aria-busy={busy}>
              {busy ? "처리 중" : "해제 확정"}
            </button>
          </span>
        )}
      </td>
    </tr>
  );
}
