import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { exchangeApi } from "../api/exchange";
import { exchangeErrorMessage, formatExtra, formatWon } from "../api/exchangeMessages";
import { getErrorStatus } from "../api/performances";
import { formatKstDateTime } from "../api/dateTime";
import { formatSeat } from "../api/seat";
import { button, linkButton, liveRegionClass, ui } from "../components/ui";
import { useCursorList } from "../hooks/useCursorList";
import type { ExchangeCandidate, ExchangeRequest } from "../types/exchange";

// FR-04 매칭 후보 (보호 라우트 /exchange/requests/:requestId/candidates).
// 시스템이 조건이 서로 맞는 상대를 보여주면 사용자가 골라 '제안하기'(= 채팅 단계 매칭 생성)를 누른다.
// 점수·랭킹·신뢰도는 없다. 정렬은 서버가 정한 내 희망 회차 우선순위 -> 최신 요청 순이다.

function readNotice(state: unknown): string | null {
  if (typeof state !== "object" || state === null || !("notice" in state)) return null;
  const notice: unknown = (state as { notice: unknown }).notice;
  return typeof notice === "string" && notice ? notice : null;
}

export default function CandidatesPage() {
  const { requestId: idParam } = useParams();
  const requestId = Number(idParam);
  const validId = Number.isInteger(requestId) && requestId > 0;
  // 다른 요청으로 바뀌면 제안 중·카드 오류 같은 상태를 모두 버리도록 key로 다시 마운트한다
  return <CandidatesView key={idParam} requestId={requestId} validId={validId} />;
}

function CandidatesView({ requestId, validId }: { requestId: number; validId: boolean }) {
  const location = useLocation();
  const navigate = useNavigate();
  const [notice] = useState(() => readNotice(location.state));
  /** 이 요청의 티켓 id — '교환 조건 수정' 링크용. 못 찾아도 화면은 쓸 수 있다 */
  const [myRequest, setMyRequest] = useState<ExchangeRequest | null>(null);
  const [proposingId, setProposingId] = useState<number | null>(null);
  const [cardErrors, setCardErrors] = useState<Record<number, { message: string; code: string | null }>>({});

  useEffect(() => {
    if (notice) navigate(location.pathname, { replace: true, state: null });
    // 최초 1회
  }, []);

  useEffect(() => {
    if (!validId) return;
    const controller = new AbortController();
    exchangeApi
      .listMyRequests(undefined, controller.signal)
      .then((list) => setMyRequest(list.find((r) => r.id === requestId) ?? null))
      // 수정 링크용 보조 조회라 실패해도 후보 목록은 쓸 수 있다 — 링크만 숨기고 오류는 띄우지 않는다
      .catch(() => undefined);
    return () => controller.abort();
  }, [requestId, validId]);

  // 후보가 많아도 뒤쪽이 느려지지 않도록 커서(keyset) 방식으로 읽는다 — 전체 개수는 알 수 없다
  const { state, loadingMore, moreError, loadMore, reload } = useCursorList<ExchangeCandidate>(
    (cursor, signal) => exchangeApi.candidateScroll(requestId, cursor, 20, signal),
    String(requestId),
    (c) => c.requestId
  );

  const propose = async (candidate: ExchangeCandidate) => {
    if (proposingId !== null) return;
    setProposingId(candidate.requestId);
    setCardErrors((prev) => {
      const { [candidate.requestId]: _removed, ...rest } = prev;
      void _removed;
      return rest;
    });
    try {
      await exchangeApi.propose(requestId, candidate.requestId);
      navigate("/exchange/matches", {
        state: { notice: `${candidate.nickname}님과 매칭을 시작했어요. 내 매칭 화면에서 채팅하고, 한 명이 예약을 누르면 돼요.` },
      });
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "제안하지 못했습니다.");
      setCardErrors((prev) => ({
        ...prev,
        [candidate.requestId]: { message: parsed.message, code: parsed.code },
      }));
      setProposingId(null);
    }
  };

  if (!validId) {
    return (
      <div className={ui.page}>
        <div className={ui.container}>
          <div className={`${ui.card} flex flex-col items-start gap-3`}>
            <h1 className={ui.pageTitle}>잘못된 주소예요</h1>
            <Link to="/tickets" className={linkButton.outline}>
              내 티켓으로
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const errorStatus = state.status === "error" ? getErrorStatus(state.error) : null;
  const notFound = errorStatus === 403 || errorStatus === 404;

  const statusText =
    state.status === "loading"
      ? "후보를 찾는 중..."
      : state.status === "success"
        ? `조건이 맞는 상대 ${state.items.length}명${state.hasNext ? " 이상" : ""}`
        : "";

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className={ui.pageTitle}>매칭 후보</h1>
          <div className="flex flex-wrap gap-2">
            {myRequest && (
              <Link to={`/tickets/${myRequest.ticketId}/exchange`} className={linkButton.outline}>
                교환 조건 수정
              </Link>
            )}
            <Link to="/exchange/matches" className={linkButton.outline}>
              내 매칭
            </Link>
          </div>
        </div>

        <p className={liveRegionClass(notice, ui.notice)} role="status">
          {notice ?? ""}
        </p>
        <p className={liveRegionClass(statusText, ui.status)} role="status">
          {statusText}
        </p>

        {state.status === "error" && notFound && (
          <div className={`${ui.card} flex flex-col items-start gap-3`}>
            <p className={ui.body}>교환 요청을 찾을 수 없어요. 없거나 내 요청이 아닐 수 있어요.</p>
            <Link to="/tickets" className={linkButton.outline}>
              내 티켓으로
            </Link>
          </div>
        )}

        {state.status === "error" && !notFound && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "후보를 불러오지 못했습니다.").message}
            </p>
            <div className="flex flex-wrap gap-2">
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
              <Link to="/tickets" className={linkButton.outline}>
                내 티켓으로
              </Link>
            </div>
          </div>
        )}

        {state.status === "success" && state.items.length === 0 && (
          <div className={`${ui.card} flex flex-col items-start gap-3`}>
            <p className={ui.body}>조건이 맞는 상대가 아직 없어요.</p>
            <p className={ui.muted}>새 상대가 등록하면 다시 확인할 수 있어요. 희망 범위나 회차를 넓히면 후보가 늘어날 수 있어요.</p>
            <div className="flex flex-wrap gap-2">
              {myRequest && (
                <Link to={`/tickets/${myRequest.ticketId}/exchange`} className={linkButton.solid}>
                  교환 조건 수정
                </Link>
              )}
              <button type="button" className={button.outline} onClick={reload}>
                새로고침
              </button>
            </div>
          </div>
        )}

        {state.status === "success" && state.items.length > 0 && (
          <>
            <p className={ui.hint}>
              ‘내 조건’은 내가 이 자리를 원할 때 정한 추가금, ‘상대 조건’은 상대가 내 자리를 원할 때 정한 추가금이에요. 금액은 매칭 판정에 쓰이지 않고 참고로만 보여요. ‘제안하기’를 누르면 바로 매칭(채팅 단계)이 시작되고, 예약은 채팅 화면에서 한 명이 ‘예약 제안하기’를 누르고 상대가 수락하면 두 티켓이 예약돼요.
            </p>
            <ul className="flex flex-col gap-3" aria-label="매칭 후보 목록">
              {state.items.map((c) => (
                <li key={c.requestId}>
                  <CandidateCard
                    candidate={c}
                    proposing={proposingId === c.requestId}
                    disabled={proposingId !== null}
                    error={cardErrors[c.requestId]}
                    onPropose={() => propose(c)}
                  />
                </li>
              ))}
            </ul>
            {moreError && (
              <p className={ui.errorBox} role="alert">
                {moreError}
              </p>
            )}
            {state.hasNext && (
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

function CandidateCard({
  candidate: c,
  proposing,
  disabled,
  error,
  onPropose,
}: {
  candidate: ExchangeCandidate;
  proposing: boolean;
  disabled: boolean;
  error?: { message: string; code: string | null };
  onPropose: () => void;
}) {
  const titleId = `cand-${c.requestId}-title`;
  const errorId = `cand-${c.requestId}-error`;
  const locked = error?.code === "NOT_A_CANDIDATE" || error?.code === "MATCH_ALREADY_OPEN";

  return (
    <article className={`${ui.card} flex flex-col gap-3`} aria-labelledby={titleId}>
      <div className="flex flex-col gap-1">
        <h2 id={titleId} className="text-lg/[normal] font-bold text-gray-900">
          {c.nickname}
        </h2>
        <p className="text-[0.9375rem]/[normal] font-semibold text-primary-700">{formatSeat(c)}</p>
        <p className={ui.body}>{formatKstDateTime(c.startsAt)}</p>
      </div>

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5">
        <dt className={ui.muted}>내 희망 회차</dt>
        <dd className="text-sm/[normal] text-gray-900">{c.wantPriority}순위</dd>
        <dt className={ui.muted}>내 조건</dt>
        <dd className="text-sm/[normal] text-gray-900">{formatExtra(c.myExtraType, c.myExtraAmount)}</dd>
        <dt className={ui.muted}>상대 조건</dt>
        <dd className="text-sm/[normal] text-gray-900">{formatExtra(c.extraType, c.extraAmount)}</dd>
        {c.settlementHint && (
          <>
            <dt className={ui.muted}>참고 금액</dt>
            <dd className="text-sm/[normal] text-gray-900">
              {formatWon(c.settlementHint.min)}~{formatWon(c.settlementHint.max)}
            </dd>
          </>
        )}
      </dl>

      {error && (
        <div id={errorId} className={`${ui.errorBox} flex flex-col items-start gap-2`} role="alert">
          <span>{error.message}</span>
          {(error.code === "MATCH_ALREADY_OPEN" || error.code === "TICKET_LOCKED" || error.code === "TICKET_ALREADY_RESERVED") && (
            <Link to="/exchange/matches" className={linkButton.outline}>
              내 매칭 보기
            </Link>
          )}
        </div>
      )}

      <div>
        <button
          type="button"
          className={button.solid}
          onClick={onPropose}
          disabled={disabled || locked}
          aria-busy={proposing}
          aria-describedby={error ? errorId : undefined}
          aria-label={`${c.nickname}님에게 제안하기`}
        >
          {proposing ? "제안하는 중..." : "제안하기"}
        </button>
      </div>
    </article>
  );
}
