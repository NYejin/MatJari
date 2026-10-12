import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { ADMIN_REPORT_CODE_MESSAGES, adminReportsApi, formatUntil, type AdminSanction, type SanctionFilter } from "../api/adminReports";
import { button, ui } from "../components/ui";
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
          <div className="flex flex-col gap-3">
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
            <ul className="flex flex-col gap-3" aria-label="제재 목록">
              {state.items.map((s) => (
                <li key={s.id}>
                  <SanctionCard sanction={s} onDone={reload} />
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

function SanctionCard({ sanction: s, onDone }: { sanction: AdminSanction; onDone: () => void }) {
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

  const status = s.revokedAt ? `해제됨 (${formatKstDateTime(s.revokedAt)})` : s.active ? "정지 중" : "기간 만료";

  return (
    <article className={`${ui.card} flex flex-col gap-2`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-base/[normal] font-bold text-gray-900">{s.nickname}</h2>
        <span className={ui.badge}>{status}</span>
      </div>
      <p className={ui.body}>사유: {s.note}</p>
      <p className={ui.muted}>
        {formatKstDateTime(s.startsAt)} 시작 · {formatUntil(s.endsAt)} · 처리 {s.createdByNickname}
      </p>
      {s.reportId !== null && (
        <p>
          <Link to={`/admin/reports/${s.reportId}`} className={ui.link}>
            관련 신고 #{s.reportId}
          </Link>
        </p>
      )}
      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}
      {s.active && !confirming && (
        <div className="flex justify-end">
          <button type="button" className={button.dangerOutline} onClick={() => setConfirming(true)} disabled={busy}>
            정지 해제
          </button>
        </div>
      )}
      {s.active && confirming && (
        <div className="flex flex-col gap-3">
          <p className={ui.warning} role="alert">
            {s.nickname}님의 이용 정지를 해제할까요? 이미 종료된 교환 채팅은 되살아나지 않아요.
          </p>
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={() => setConfirming(false)} disabled={busy}>
              돌아가기
            </button>
            <button type="button" className={button.solid} onClick={() => void revoke()} disabled={busy} aria-busy={busy}>
              {busy ? "처리 중..." : "해제 확정"}
            </button>
          </div>
        </div>
      )}
    </article>
  );
}
