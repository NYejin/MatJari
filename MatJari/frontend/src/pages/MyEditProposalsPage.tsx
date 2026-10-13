import { Link } from "react-router-dom";
import { editProposalsApi, STATUS_LABEL, type EditProposal } from "../api/editProposals";
import { formatKstDateTime } from "../api/dateTime";
import { parseApiError } from "../api/errors";
import { usePagedList } from "../hooks/usePagedList";
import SessionChangesView from "../components/SessionChangesView";
import { button, liveRegionClass, ui } from "../components/ui";

// 내 공연 수정 제안 목록 (/my/edit-proposals, 보호 라우트). 처리 결과(승인·거절 사유)를 여기서 본다.

const PAGE_SIZE = 20;

export default function MyEditProposalsPage() {
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<EditProposal>(
    (page, signal) => editProposalsApi.listMine(page, PAGE_SIZE, signal),
    "mine",
    (p) => p.id
  );

  const statusText = state.status === "loading" ? "불러오는 중..." : loadingMore ? "더 불러오는 중..." : "";

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>내 수정 제안</h1>
        <p className={liveRegionClass(statusText, ui.status)} role="status">
          {statusText}
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
          <div className={`${ui.card} flex flex-col gap-2`}>
            <p className={ui.body}>보낸 수정 제안이 없어요. 공연 상세에서 "정보가 틀렸어요"로 제안할 수 있어요.</p>
          </div>
        )}

        {state.status === "success" && state.items.length > 0 && (
          <ul className="flex flex-col gap-3">
            {state.items.map((p) => (
              <li key={p.id} className={`${ui.card} flex flex-col gap-2`}>
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <Link to={`/performances/${p.performanceId}`} className={`${ui.link} break-words`}>
                    {p.performanceTitle}
                  </Link>
                  <span className={ui.badge}>{STATUS_LABEL[p.status]}</span>
                </div>
                {p.newTitle && <Change label="제목" from={p.status === "APPROVED" ? null : p.currentTitle} to={p.newTitle} />}
                {p.newVenueName && <Change label="공연장" from={p.status === "APPROVED" ? null : p.currentVenueName} to={p.newVenueName} />}
                <SessionChangesView changes={p.sessionChanges ?? []} />
                {p.reason && <p className={ui.body}>사유: {p.reason}</p>}
                {p.reviewNote && <p className={ui.notice}>검토 의견: {p.reviewNote}</p>}
                <p className={ui.muted}>
                  {formatKstDateTime(p.createdAt)} 제안
                  {p.reviewedAt && ` · ${formatKstDateTime(p.reviewedAt)} 처리`}
                </p>
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

function Change({ label, from, to }: { label: string; from: string | null; to: string }) {
  return (
    <p className={`${ui.body} break-words`}>
      <span className="font-semibold">{label}</span> {from !== null && (<>{from} <span aria-label="에서">→</span> </>)}<strong>{to}</strong>
    </p>
  );
}
