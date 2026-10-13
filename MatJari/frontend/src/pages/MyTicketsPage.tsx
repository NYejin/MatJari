import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { exchangeApi } from "../api/exchange";
import { exchangeErrorMessage, summarizeRangeExtras } from "../api/exchangeMessages";
import { formatKstDateTime } from "../api/dateTime";
import { formatSeat } from "../api/seat";
import { ticketsApi } from "../api/tickets";
import { button, linkButton, liveRegionClass, ui } from "../components/ui";
import type { ExchangeRequest } from "../types/exchange";
import type { Ticket } from "../types/ticket";

// FR-03 내 티켓 (보호 라우트 /tickets). 티켓 카드마다 교환 조건 설정/수정·후보 보기·내리기.

type LoadState =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "success"; tickets: Ticket[]; requests: Map<number, ExchangeRequest> };

function readNotice(state: unknown): string | null {
  if (typeof state !== "object" || state === null || !("notice" in state)) return null;
  const notice: unknown = (state as { notice: unknown }).notice;
  return typeof notice === "string" && notice ? notice : null;
}

export default function MyTicketsPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [notice] = useState(() => readNotice(location.state));
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [retryKey, setRetryKey] = useState(0);
  const [status, setStatus] = useState("");

  // 일회성 안내는 히스토리에서 지워 새로고침 시 다시 보이지 않게
  useEffect(() => {
    if (notice) navigate(location.pathname, { replace: true, state: null });
    // 최초 1회
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: "loading" });
    Promise.all([ticketsApi.listMine(controller.signal), exchangeApi.listMyRequests(undefined, controller.signal)])
      .then(([tickets, requests]) => {
        setState({ status: "success", tickets, requests: new Map(requests.map((r) => [r.ticketId, r])) });
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        setState({ status: "error", message: exchangeErrorMessage(err, "내 티켓을 불러오지 못했습니다.").message });
      });
    return () => controller.abort();
  }, [retryKey]);

  const removeFromList = (ticket: Ticket) => {
    setState((prev) => (prev.status === "success" ? { ...prev, tickets: prev.tickets.filter((t) => t.id !== ticket.id) } : prev));
    setStatus(`${formatSeat(ticket)} 티켓을 내렸어요.`);
  };

  const statusText = state.status === "loading" ? "내 티켓을 불러오는 중..." : status;

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 className={ui.pageTitle}>내 티켓</h1>
          <Link to="/tickets/new" className={linkButton.solid}>
            티켓 등록
          </Link>
        </div>

        <p className={liveRegionClass(notice, ui.notice)} role="status">
          {notice ?? ""}
        </p>
        <p className={liveRegionClass(statusText, ui.status)} role="status">
          {statusText}
        </p>

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {state.message}
            </p>
            <button type="button" className={button.solid} onClick={() => setRetryKey((k) => k + 1)}>
              다시 시도
            </button>
          </div>
        )}

        {state.status === "success" && state.tickets.length === 0 && (
          <div className={`${ui.card} flex flex-col items-start gap-3`}>
            <p className={ui.body}>아직 등록한 티켓이 없어요. 가지고 있는 티켓을 등록하면 바꿀 상대를 찾을 수 있어요.</p>
            <Link to="/tickets/new" className={linkButton.outline}>
              티켓 등록하기
            </Link>
          </div>
        )}

        {state.status === "success" && state.tickets.length > 0 && (
          <ul className="flex flex-col gap-3" aria-label="내 티켓 목록">
            {state.tickets.map((ticket) => (
              <li key={ticket.id}>
                <TicketCard ticket={ticket} request={state.requests.get(ticket.id) ?? null} onRemoved={removeFromList} />
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function TicketCard({
  ticket,
  request,
  onRemoved,
}: {
  ticket: Ticket;
  request: ExchangeRequest | null;
  onRemoved: (ticket: Ticket) => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const removingRef = useRef(false);
  const confirmRef = useRef<HTMLDivElement>(null);

  // 확인 영역이 열리면 포커스를 그리로 옮긴다 (누른 버튼이 사라지므로)
  useEffect(() => {
    if (confirming) confirmRef.current?.focus();
  }, [confirming]);

  const remove = async () => {
    if (removingRef.current) return;
    removingRef.current = true;
    setRemoving(true);
    setError(null);
    try {
      await ticketsApi.deactivate(ticket.id);
      onRemoved(ticket);
    } catch (err) {
      setError(exchangeErrorMessage(err, "티켓을 내리지 못했습니다.").message);
      removingRef.current = false;
      setRemoving(false);
    }
  };

  const open = request !== null && request.status === "OPEN";
  const titleId = `ticket-${ticket.id}-title`;

  return (
    <article className={`${ui.card} flex flex-col gap-3`} aria-labelledby={titleId}>
      <div className="flex flex-col gap-1">
        <h2 id={titleId} className="text-lg/[normal] font-bold break-keep text-gray-900">
          {ticket.performanceTitle}
        </h2>
        <p className={ui.body}>
          {formatKstDateTime(ticket.startsAt)} · {ticket.venueName}
        </p>
        <p className="text-[0.9375rem]/[normal] font-semibold text-primary-700">{formatSeat(ticket)}</p>
      </div>

      <div className="flex flex-col gap-1">
        {request ? (
          <>
            <span className={ui.badge + " self-start"}>{open ? "교환 조건 설정됨" : "교환 조건 닫힘"}</span>
            <p className={ui.muted}>
              희망 {request.wantSeatCount.toLocaleString("ko-KR")}석 · 희망 회차 {request.wantSessions.length}개 ·{" "}
              {summarizeRangeExtras(request.ranges)}
            </p>
          </>
        ) : (
          <p className={ui.muted}>아직 교환 조건이 없어요.</p>
        )}
      </div>

      <div className="flex flex-wrap gap-2">
        <Link to={`/tickets/${ticket.id}/exchange`} className={request ? linkButton.outline : linkButton.solid}>
          {request ? "교환 조건 수정" : "교환 조건 설정"}
        </Link>
        {request && open && (
          <Link to={`/exchange/requests/${request.id}/candidates`} className={linkButton.solid}>
            후보 보기
          </Link>
        )}
        {!confirming && (
          <button type="button" className={button.dangerOutline} onClick={() => setConfirming(true)} disabled={removing}>
            내리기
          </button>
        )}
      </div>

      {confirming && (
        <div ref={confirmRef} tabIndex={-1} className="flex flex-col gap-3 outline-none">
          <p className={ui.warning}>
            티켓을 내리면 교환 조건이 닫히고 진행 중인 대화는 취소돼요. 예약된 티켓은 내릴 수 없어요. 내릴까요?
          </p>
          <div className="flex flex-wrap gap-2">
            <button type="button" className={button.danger} onClick={remove} disabled={removing} aria-busy={removing}>
              {removing ? "내리는 중..." : "내릴게요"}
            </button>
            <button
              type="button"
              className={button.outline}
              onClick={() => {
                setConfirming(false);
                setError(null);
              }}
              disabled={removing}
            >
              취소
            </button>
          </div>
        </div>
      )}

      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}
    </article>
  );
}
