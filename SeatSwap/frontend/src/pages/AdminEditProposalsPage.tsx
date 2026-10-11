import { useState } from "react";
import { Link } from "react-router-dom";
import {
  editProposalsApi,
  REVIEW_NOTE_MAX,
  STATUS_LABEL,
  EDIT_ERROR_MESSAGES,
  type AdminEditProposal,
  type EditProposalStatus,
} from "../api/editProposals";
import { formatKstDateTime } from "../api/dateTime";
import { getErrorCode, parseApiError } from "../api/errors";
import { usePagedList } from "../hooks/usePagedList";
import { button, FOCUS_RING, input, liveRegionClass, ui } from "../components/ui";

// 관리자 — 공연 수정 제안 처리 (/admin/performance-edit-proposals, AdminRoute).
// 상태 탭 → 현재 값→제안 값 비교 → 승인 / 거절(사유 + 2단계 확인). 처리 뒤 목록을 새로 불러온다.

const PAGE_SIZE = 20;
const TABS: EditProposalStatus[] = ["PENDING", "APPROVED", "REJECTED"];

const tabClass = (active: boolean) =>
  `inline-flex min-h-11 flex-1 items-center justify-center rounded-[10px] border px-3 text-[0.9375rem]/[normal] font-semibold ${FOCUS_RING} ` +
  (active
    ? "border-primary-600 bg-primary-600 text-white"
    : "border-gray-300 bg-white text-gray-900 active:bg-gray-100");

export default function AdminEditProposalsPage() {
  const [status, setStatus] = useState<EditProposalStatus>("PENDING");
  const [notice, setNotice] = useState<string | null>(null);
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<AdminEditProposal>(
    (page, signal) => editProposalsApi.adminList(status, page, PAGE_SIZE, signal),
    status,
    (p) => p.id
  );

  const statusText = state.status === "loading" ? "불러오는 중..." : loadingMore ? "더 불러오는 중..." : "";

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>공연 수정 제안</h1>
        <Link to="/admin" className={ui.link}>
          관리자 홈
        </Link>

        <div role="group" aria-label="상태" className="flex gap-2">
          {TABS.map((t) => (
            <button
              key={t}
              type="button"
              aria-pressed={t === status}
              className={tabClass(t === status)}
              onClick={() => {
                setStatus(t);
                setNotice(null);
              }}
            >
              {STATUS_LABEL[t]}
            </button>
          ))}
        </div>

        <p className={liveRegionClass(statusText || notice, ui.status)} role="status">
          {statusText || notice}
        </p>

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {parseApiError(state.error, "제안 목록을 불러오지 못했습니다.").message}
            </p>
            <button type="button" className={button.solid} onClick={reload}>
              다시 시도
            </button>
          </div>
        )}

        {state.status === "success" && state.items.length === 0 && (
          <div className={ui.card}>
            <p className={ui.body}>{STATUS_LABEL[status]} 상태의 제안이 없어요.</p>
          </div>
        )}

        {state.status === "success" && state.items.length > 0 && (
          <ul className="flex flex-col gap-3">
            {state.items.map((p) => (
              <li key={p.id}>
                <ProposalCard
                  proposal={p}
                  onDone={(msg) => {
                    setNotice(msg);
                    reload();
                  }}
                />
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

function ProposalCard({ proposal: p, onDone }: { proposal: AdminEditProposal; onDone: (message: string) => void }) {
  const [busy, setBusy] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [note, setNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const noteId = `reject-note-${p.id}`;
  const pending = p.status === "PENDING";

  const run = async (action: () => Promise<unknown>, doneMessage: string) => {
    setBusy(true);
    setError(null);
    try {
      await action();
      onDone(doneMessage);
    } catch (err) {
      const code = getErrorCode(err);
      setError((code && EDIT_ERROR_MESSAGES[code]) || parseApiError(err, "처리하지 못했습니다.").message);
      if (code === "PROPOSAL_ALREADY_REVIEWED") onDone(EDIT_ERROR_MESSAGES.PROPOSAL_ALREADY_REVIEWED);
    } finally {
      setBusy(false);
    }
  };

  return (
    <article className={`${ui.card} flex flex-col gap-3`} aria-label={`${p.performanceTitle} 수정 제안`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Link to={`/performances/${p.performanceId}`} className={`${ui.link} break-words`}>
          {p.performanceTitle}
        </Link>
        <span className={ui.badge}>{STATUS_LABEL[p.status]}</span>
      </div>

      {p.newTitle && (
        <Compare label="제목" from={p.status === "APPROVED" ? (p.oldTitle ?? p.currentTitle) : p.currentTitle} to={p.newTitle} />
      )}
      {p.newVenueName && (
        <Compare
          label="공연장"
          from={p.status === "APPROVED" ? (p.oldVenueName ?? p.currentVenueName) : p.currentVenueName}
          to={p.newVenueName}
        />
      )}
      {p.reason && <p className={`${ui.body} break-words`}>사유: {p.reason}</p>}
      <p className={ui.muted}>
        {p.proposerNickname} · {formatKstDateTime(p.createdAt)} 제안
        {p.reviewedAt && ` · ${p.reviewerNickname ?? "관리자"} ${formatKstDateTime(p.reviewedAt)} 처리`}
      </p>
      {p.reviewNote && <p className={ui.notice}>거절 사유: {p.reviewNote}</p>}

      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}

      {pending && !rejecting && (
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            className={button.solid}
            disabled={busy}
            onClick={() => void run(() => editProposalsApi.approve(p.id), "승인했어요.")}
          >
            {busy ? "처리 중..." : "승인"}
          </button>
          <button type="button" className={button.dangerOutline} disabled={busy} onClick={() => setRejecting(true)}>
            거절
          </button>
        </div>
      )}

      {pending && rejecting && (
        <div className="flex flex-col gap-2">
          <label htmlFor={noteId} className={ui.label}>
            거절 사유 (선택, {REVIEW_NOTE_MAX}자 이내)
          </label>
          <textarea
            id={noteId}
            className={`${input.normal} py-2`}
            rows={3}
            maxLength={REVIEW_NOTE_MAX}
            value={note}
            disabled={confirming || busy}
            onChange={(e) => setNote(e.target.value)}
          />
          {confirming && (
            <p className={ui.warning} role="alert">
              이 제안을 거절할까요? 되돌릴 수 없어요.
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            {confirming ? (
              <button
                type="button"
                className={button.danger}
                disabled={busy}
                onClick={() => void run(() => editProposalsApi.reject(p.id, note.trim() || null), "거절했어요.")}
              >
                {busy ? "처리 중..." : "거절 확정"}
              </button>
            ) : (
              <button type="button" className={button.danger} onClick={() => setConfirming(true)}>
                거절하기
              </button>
            )}
            <button
              type="button"
              className={button.outline}
              disabled={busy}
              onClick={() => {
                setRejecting(false);
                setConfirming(false);
              }}
            >
              취소
            </button>
          </div>
        </div>
      )}
    </article>
  );
}

function Compare({ label, from, to }: { label: string; from: string; to: string }) {
  return (
    <div className="flex flex-col gap-1">
      <span className={ui.label}>{label}</span>
      <p className={`${ui.body} break-words`}>
        <span className="text-gray-500 line-through">{from}</span>
        <span className="sr-only"> 에서 </span>
        <span aria-hidden="true"> → </span>
        <strong className="text-gray-900">{to}</strong>
      </p>
    </div>
  );
}
