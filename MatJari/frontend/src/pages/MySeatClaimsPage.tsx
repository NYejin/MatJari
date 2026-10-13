import { useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { CLAIM_BADGE, CLAIM_STATUS_LABEL, seatClaimsApi, type SeatClaim } from "../api/seatClaims";
import { formatSeat } from "../api/seat";
import { button, linkButton, liveRegionClass, ui } from "../components/ui";
import { usePagedList } from "../hooks/usePagedList";

// 내 좌석 인증 요청 (보호 라우트 /my/seat-claims). 처리 결과(승인·거절 사유)를 본다.

function readNotice(state: unknown): string | null {
  if (typeof state !== "object" || state === null || !("notice" in state)) return null;
  const notice: unknown = (state as { notice: unknown }).notice;
  return typeof notice === "string" && notice ? notice : null;
}

export default function MySeatClaimsPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [notice] = useState(() => {
    const n = readNotice(location.state);
    if (n) navigate(location.pathname, { replace: true, state: null });
    return n;
  });
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<SeatClaim>(
    (page, signal) => seatClaimsApi.listMine(page, signal),
    "mine",
    (c) => c.id
  );

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>내 티켓 인증 요청</h1>
        <p className={liveRegionClass(notice, ui.notice)} role="status">
          {notice ?? ""}
        </p>

        {state.status === "loading" && (
          <p className={ui.status} role="status">
            불러오는 중...
          </p>
        )}
        {state.status === "error" && (
          <div className="flex flex-col gap-3">
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "인증 요청 목록을 불러오지 못했습니다.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}
        {state.status === "success" && state.items.length === 0 && (
          <div className={`${ui.card} flex flex-col items-start gap-3`}>
            <p className={ui.body}>보낸 인증 요청이 없어요.</p>
            <Link to="/tickets/new" className={linkButton.outline}>
              티켓 등록하기
            </Link>
          </div>
        )}
        {state.status === "success" && state.items.length > 0 && (
          <>
            <ul className="flex flex-col gap-3" aria-label="인증 요청 목록">
              {state.items.map((c) => (
                <li key={c.id} className={`${ui.card} flex flex-col gap-2`}>
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="text-base/[normal] font-bold break-keep text-gray-900">{c.performanceTitle}</span>
                    <span className={CLAIM_BADGE[c.status]}>{CLAIM_STATUS_LABEL[c.status]}</span>
                  </div>
                  <p className={ui.body}>
                    {formatKstDateTime(c.sessionStartsAt)} · {formatSeat(c)}
                  </p>
                  {c.memo && <p className={ui.muted}>메모: {c.memo}</p>}
                  <p className={ui.muted}>
                    요청 {formatKstDateTime(c.createdAt)}
                    {c.reviewedAt && ` · 처리 ${formatKstDateTime(c.reviewedAt)}`}
                  </p>
                  {c.status === "REJECTED" && (
                    <p className={ui.errorBox}>거절 사유: {c.reviewNote || "사유가 적히지 않았어요."}</p>
                  )}
                  {c.status === "APPROVED" && (
                    <div className="flex flex-col items-start gap-2">
                      <p className={ui.notice}>승인됐어요. 기존 등록이 내려갔으니 내 티켓으로 직접 등록해 주세요.</p>
                      <Link to={`/tickets/new?performanceId=${c.performanceId}&sessionId=${c.sessionId}`} className={linkButton.solid}>
                        티켓 등록하기
                      </Link>
                    </div>
                  )}
                  {c.status === "PENDING" && <p className={ui.muted}>관리자가 확인하고 있어요.</p>}
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
