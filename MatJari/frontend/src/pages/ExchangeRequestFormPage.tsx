import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { exchangeApi } from "../api/exchange";
import { exchangeErrorMessage, getConflictIndexes } from "../api/exchangeMessages";
import { getErrorNumber } from "../api/errors";
import { compareLocalDateTime, formatKstDateTime, isSessionClosed } from "../api/dateTime";
import { getErrorStatus, performancesApi } from "../api/performances";
import {
  WANT_MAX_RANGES,
  WANT_MAX_SEATS,
  countWantSeats,
  findExtraConflicts,
  formatSeat,
  toPayloadRange,
  validateRange,
  type RangeField,
  type RangeInput,
} from "../api/seat";
import { ticketsApi } from "../api/tickets";
import { extraAmountId, extraTypeId } from "../components/ExtraFields";
import SeatRangeCard, { rangeFieldId } from "../components/SeatRangeCard";
import WantSessionsEditor, {
  wantSessionCheckboxId,
  type WantSessionRow,
} from "../components/WantSessionsEditor";
import { button, linkButton, liveRegionClass, ui } from "../components/ui";
import type { ExchangeRequest, ExchangeRequestPayload, ExtraType, WantRangePayload } from "../types/exchange";
import type { PerformanceDetail } from "../types/performance";
import type { Ticket } from "../types/ticket";

// FR-04 교환 희망 조건 설정/수정 (보호 라우트 /tickets/:ticketId/exchange).
// 티켓 하나에 요청 1개: 없으면 등록(POST), 있으면 불러와 수정(PATCH), 삭제(DELETE).
// 구성: ①희망 좌석 범위 카드(구역·열·번 텍스트 + 범위별 추가금, 최대 50개) ②희망 회차와 우선순위(요청 단위).
// 좌석표·구역 자동완성은 없다 (좌석표 기능과 함께 후속).

interface CardState extends RangeInput {
  uid: number;
  extraType: ExtraType;
  /** 사용자가 입력하는 양수 문자열 (NEG도 양수로 입력, 전송 때 음수로 바꾼다) */
  extraAmount: string;
}

type Loaded = {
  ticket: Ticket;
  performance: PerformanceDetail;
  existing: ExchangeRequest | null;
};

type LoadState =
  | { status: "loading" }
  | { status: "notFound" }
  | { status: "error"; message: string }
  | { status: "ready"; data: Loaded };

const emptyCard = (uid: number, extraType: ExtraType = "ANY", extraAmount = ""): CardState => ({
  uid,
  zone: "",
  rowFrom: "",
  rowTo: "",
  colFrom: "",
  colTo: "",
  extraType,
  extraAmount,
});

const cardAmountText = (type: ExtraType, amount: number | null): string =>
  (type === "POS" || type === "NEG") && amount !== null ? String(Math.abs(amount)) : "";

function buildSessionRows(performance: PerformanceDetail, ticket: Ticket, existing: ExchangeRequest | null): WantSessionRow[] {
  const byTime = [...performance.sessions].sort((a, b) => compareLocalDateTime(a.startsAt, b.startsAt));
  const make = (s: { id: number; startsAt: string }, checked: boolean): WantSessionRow => {
    const closed = isSessionClosed(s.startsAt);
    return { sessionId: s.id, startsAt: s.startsAt, checked: checked && !closed, closed, mine: s.id === ticket.sessionId };
  };
  if (existing) {
    const wanted = [...existing.wantSessions].sort(
      (a, b) => a.priority - b.priority || compareLocalDateTime(a.startsAt, b.startsAt)
    );
    const wantedIds = new Set(wanted.map((w) => w.sessionId));
    const rows: WantSessionRow[] = [];
    for (const w of wanted) {
      const s = byTime.find((x) => x.id === w.sessionId);
      if (s) rows.push(make(s, true));
    }
    for (const s of byTime) if (!wantedIds.has(s.id)) rows.push(make(s, false));
    return rows;
  }
  // 새 요청: 내 회차를 1순위로, 나머지는 시간순. 기본 체크는 내 티켓 회차만(다른 회차는 사용자가 직접 선택)
  const mine = byTime.find((s) => s.id === ticket.sessionId);
  const rest = byTime.filter((s) => s.id !== ticket.sessionId);
  return [...(mine ? [mine] : []), ...rest].map((s) => make(s, s.id === ticket.sessionId));
}

/** 서버 오류 키(ranges[0].rowFrom 등) -> 포커스할 요소 id */
function focusIdFor(key: string, cards: CardState[]): string | null {
  const m = /^ranges\[(\d+)\]\.(\w+)$/.exec(key);
  if (m) {
    const card = cards[Number(m[1])];
    if (!card) return null;
    if (m[2] === "extraAmount") return extraAmountId(card.uid);
    if (m[2] === "extraType") return extraTypeId(card.uid, card.extraType);
    if (m[2] === "conflict") return rangeFieldId(card.uid, "zone");
    return rangeFieldId(card.uid, m[2] as RangeField);
  }
  if (key === "ranges") return cards[0] ? rangeFieldId(cards[0].uid, "zone") : null;
  if (key.startsWith("wantSessions")) return "want-sessions-anchor";
  return null;
}

export default function ExchangeRequestFormPage() {
  const { ticketId: ticketIdParam } = useParams();
  const ticketId = Number(ticketIdParam);
  const validId = Number.isInteger(ticketId) && ticketId > 0;
  const [load, setLoad] = useState<LoadState>(validId ? { status: "loading" } : { status: "notFound" });
  const [retryKey, setRetryKey] = useState(0);

  useEffect(() => {
    if (!validId) {
      setLoad({ status: "notFound" });
      return;
    }
    const controller = new AbortController();
    setLoad({ status: "loading" });
    (async () => {
      const [tickets, requests] = await Promise.all([
        ticketsApi.listMine(controller.signal),
        exchangeApi.listMyRequests(ticketId, controller.signal),
      ]);
      const ticket = tickets.find((t) => t.id === ticketId);
      if (!ticket) {
        setLoad({ status: "notFound" });
        return;
      }
      const performance = await performancesApi.get(ticket.performanceId, controller.signal);
      setLoad({ status: "ready", data: { ticket, performance, existing: requests[0] ?? null } });
    })().catch((err: unknown) => {
      if (controller.signal.aborted) return;
      if (getErrorStatus(err) === 404) setLoad({ status: "notFound" });
      else setLoad({ status: "error", message: exchangeErrorMessage(err, "교환 조건을 불러오지 못했습니다.").message });
    });
    return () => controller.abort();
  }, [ticketId, validId, retryKey]);

  if (load.status === "ready") return <FormBody data={load.data} />;

  let content: ReactNode = null;
  if (load.status === "notFound") {
    content = (
      <div className={`${ui.card} flex flex-col items-start gap-3`}>
        <h1 className={ui.pageTitle}>티켓을 찾을 수 없어요</h1>
        <p className={ui.body}>내 활성 티켓이 아니거나 이미 내린 티켓이에요.</p>
        <Link to="/tickets" className={linkButton.outline}>
          내 티켓으로
        </Link>
      </div>
    );
  } else if (load.status === "error") {
    content = (
      <div className={`${ui.card} flex flex-col gap-3`}>
        <p className={ui.errorBox} role="alert">
          {load.message}
        </p>
        <button type="button" className={button.solid} onClick={() => setRetryKey((k) => k + 1)}>
          다시 시도
        </button>
      </div>
    );
  }
  const loadingText = load.status === "loading" ? "교환 조건을 불러오는 중..." : "";
  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <p className={liveRegionClass(loadingText, ui.status)} role="status">
          {loadingText}
        </p>
        {content}
      </div>
    </div>
  );
}

function FormBody({ data }: { data: Loaded }) {
  const { ticket, performance, existing } = data;
  const navigate = useNavigate();
  const uidCounter = useRef(0);
  const nextUid = () => ++uidCounter.current;

  const [cards, setCards] = useState<CardState[]>(() =>
    existing && existing.ranges.length > 0
      ? existing.ranges.map((r) => ({
          uid: nextUid(),
          zone: r.zone,
          rowFrom: r.rowFrom,
          rowTo: r.rowTo,
          colFrom: r.colFrom,
          colTo: r.colTo,
          extraType: r.extraType,
          extraAmount: cardAmountText(r.extraType, r.extraAmount),
        }))
      : [emptyCard(nextUid())]
  );
  const [sessionRows, setSessionRows] = useState<WantSessionRow[]>(() => buildSessionRows(performance, ticket, existing));
  /** 서버 오류 키 형식으로 통일한 필드 오류 (클라이언트 검증 결과도 같은 키) */
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  /** 서버가 이미 삭제된 요청이라고 답한 경우 — 내 티켓으로 가는 링크를 보여준다 */
  const [requestDeleted, setRequestDeleted] = useState(false);
  /** 더블 탭 방지: state는 다음 렌더 전까지 갱신되지 않으므로 ref를 함께 쓴다 */
  const submittingRef = useRef(false);
  const deletingRef = useRef(false);
  const deleteConfirmRef = useRef<HTMLDivElement>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  /** 카드 추가·삭제 뒤 포커스를 줄 요소 id */
  const pendingFocus = useRef<string | null>(null);
  const [status, setStatus] = useState("");

  useEffect(() => {
    if (!pendingFocus.current) return;
    const id = pendingFocus.current;
    pendingFocus.current = null;
    document.getElementById(id)?.focus();
  });

  // 삭제 확인 영역이 열리면 포커스를 그리로 옮긴다 (누른 버튼이 사라지므로)
  useEffect(() => {
    if (confirmDelete) deleteConfirmRef.current?.focus();
  }, [confirmDelete]);

  // 저장돼 있던 희망 회차가 그사이 마감돼 자동으로 해제된 경우 알려준다
  const droppedClosed = useMemo(() => {
    if (!existing) return 0;
    const closedIds = new Set(sessionRows.filter((r) => r.closed).map((r) => r.sessionId));
    return existing.wantSessions.filter((w) => closedIds.has(w.sessionId)).length;
  }, [existing, sessionRows]);

  const { count, invalid } = useMemo(() => countWantSeats(cards), [cards]);
  const overLimit = count > WANT_MAX_SEATS;
  /** 겹치는데 추가금 유형이 다른 범위 쌍 (미리 경고용, 서버가 최종 판정) */
  const conflictPairs = useMemo(() => findExtraConflicts(cards), [cards]);
  const conflictWarnings = useMemo(() => {
    const out: Record<number, string> = {};
    for (const [i, j] of conflictPairs) {
      out[i] = `${out[i] ? out[i] + " " : ""}범위 ${j + 1}과 겹치는데 추가금 조건이 달라요.`;
      out[j] = `${out[j] ? out[j] + " " : ""}범위 ${i + 1}과 겹치는데 추가금 조건이 달라요.`;
    }
    return out;
  }, [conflictPairs]);

  /** 범위 오류 키는 인덱스 기반이라 카드 추가·삭제 시 모두 지운다 */
  const clearRangeErrors = (e: Record<string, string>) =>
    Object.fromEntries(Object.entries(e).filter(([k]) => !k.startsWith("ranges")));

  const clearError = (key: string) =>
    setErrors((prev) => {
      if (!(key in prev)) return prev;
      const { [key]: _removed, ...rest } = prev;
      void _removed;
      return rest;
    });

  const changeCard = (index: number, field: RangeField, value: string) => {
    setCards((prev) => prev.map((c, i) => (i === index ? { ...c, [field]: value } : c)));
    clearError(`ranges[${index}].${field}`);
    clearError(`ranges[${index}].conflict`);
    clearError("ranges");
    setFormError(null);
  };

  const changeCardExtra = (index: number, field: "extraType" | "extraAmount", value: string) => {
    setCards((prev) =>
      prev.map((c, i) => {
        if (i !== index) return c;
        return field === "extraType" ? { ...c, extraType: value as ExtraType, extraAmount: "" } : { ...c, extraAmount: value };
      })
    );
    clearError(`ranges[${index}].extraType`);
    clearError(`ranges[${index}].extraAmount`);
    clearError(`ranges[${index}].conflict`);
    setFormError(null);
  };

  /** 새 카드의 추가금은 직전 카드 값을 이어받는다 (같은 조건의 범위를 연달아 넣기 쉽게) */
  const addCard = () => {
    if (cards.length >= WANT_MAX_RANGES) return;
    const uid = nextUid();
    const last = cards[cards.length - 1];
    pendingFocus.current = rangeFieldId(uid, "zone");
    setCards((prev) => [...prev, emptyCard(uid, last?.extraType, last?.extraAmount)]);
    setErrors(clearRangeErrors);
    setFormError(null);
    setStatus(`희망 범위 ${cards.length + 1}을 추가했어요.`);
  };

  /** 카드를 통째로 복제해 바로 아래에 넣는다 */
  const duplicateCard = (index: number) => {
    if (cards.length >= WANT_MAX_RANGES) return;
    const uid = nextUid();
    pendingFocus.current = rangeFieldId(uid, "zone");
    setCards((prev) => [...prev.slice(0, index + 1), { ...prev[index], uid }, ...prev.slice(index + 1)]);
    setErrors(clearRangeErrors);
    setFormError(null);
    setStatus(`희망 범위 ${index + 1}을 복제해 범위 ${index + 2}로 추가했어요.`);
  };

  const removeCard = (index: number) => {
    if (cards.length <= 1) return;
    const focusIndex = Math.max(0, index - 1);
    const remaining = cards.filter((_, i) => i !== index);
    pendingFocus.current = rangeFieldId(remaining[focusIndex].uid, "zone");
    setCards(remaining);
    setErrors(clearRangeErrors);
    setFormError(null);
    setStatus(`희망 범위 ${index + 1}을 삭제했어요.`);
  };

  /** 오류가 있는 첫 칸의 요소 id (화면 순서: 범위 -> 회차 -> 추가금) */
  const firstErrorId = (all: Record<string, string>, currentCards: CardState[]): string | null => {
    const keys = Object.keys(all);
    // 화면 순서: 범위 -> 회차 -> 추가금
    const rank = (k: string) => (k.startsWith("ranges") ? 0 : k.startsWith("wantSessions") ? 1 : 2);
    const sortedKeys = keys.sort((a, b) => {
      const ra = rank(a) - rank(b);
      if (ra !== 0) return ra;
      const ia = Number(/\[(\d+)\]/.exec(a)?.[1] ?? 0);
      const ib = Number(/\[(\d+)\]/.exec(b)?.[1] ?? 0);
      return ia - ib;
    });
    for (const k of sortedKeys) {
      const id = focusIdFor(k, currentCards);
      if (!id) continue;
      if (id === "want-sessions-anchor") {
        const first = sessionRows.find((r) => !r.closed);
        return first ? wantSessionCheckboxId(first.sessionId) : null;
      }
      return id;
    }
    return null;
  };

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (submittingRef.current) return;
    setFormError(null);

    // ---- 클라이언트 검증 ----
    const next: Record<string, string> = {};
    cards.forEach((c, i) => {
      const errs = validateRange(c);
      for (const [field, msg] of Object.entries(errs)) next[`ranges[${i}].${field}`] = msg;
    });
    const picked = sessionRows.filter((r) => r.checked);
    if (picked.length === 0) next.wantSessions = "희망 회차를 1개 이상 선택해주세요.";
    // 범위마다 추가금 검증. NEG는 사용자가 양수로 입력하고 전송할 때 음수로 바꾼다. X/ANY는 금액을 보내지 않는다.
    const payloadRanges: WantRangePayload[] = [];
    cards.forEach((c, i) => {
      const base = toPayloadRange(c);
      if (c.extraType === "POS" || c.extraType === "NEG") {
        const trimmed = c.extraAmount.replace(/[,\s]/g, "");
        if (!/^\d+$/.test(trimmed) || Number(trimmed) < 1) next[`ranges[${i}].extraAmount`] = "금액을 1원 이상의 숫자로 입력해주세요.";
        else if (Number(trimmed) > 2_000_000_000) next[`ranges[${i}].extraAmount`] = "금액이 너무 커요.";
        else {
          const n = Number(trimmed);
          payloadRanges.push({ ...base, extraType: c.extraType, extraAmount: c.extraType === "NEG" ? -n : n });
          return;
        }
      }
      payloadRanges.push({ ...base, extraType: c.extraType });
    });
    if (overLimit) {
      next.ranges = `희망 좌석이 너무 많아요 (${count.toLocaleString("ko-KR")}석). ${WANT_MAX_SEATS.toLocaleString("ko-KR")}석 이하로 줄여주세요.`;
    }
    setErrors(next);
    if (Object.keys(next).length > 0) {
      setFormError(`입력을 확인해주세요. (${Object.keys(next).length}곳)`);
      pendingFocus.current = firstErrorId(next, cards);
      setStatus("");
      return;
    }

    // ---- 전송 ----
    const payload: ExchangeRequestPayload = {
      wantSessions: picked.map((r, i) => ({ sessionId: r.sessionId, priority: i + 1 })),
      ranges: payloadRanges,
    };
    submittingRef.current = true;
    setSubmitting(true);
    try {
      const saved = existing
        ? await exchangeApi.updateRequest(existing.id, payload)
        : await exchangeApi.createRequest({ ticketId: ticket.id, ...payload });
      navigate(`/exchange/requests/${saved.id}/candidates`, {
        replace: true,
        state: { notice: existing ? "교환 조건을 수정했어요." : "교환 조건을 저장했어요. 조건이 맞는 상대를 찾아봤어요." },
      });
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "교환 조건을 저장하지 못했습니다.");
      let message = parsed.message;
      if (parsed.code === "WANT_SEAT_LIMIT_EXCEEDED" || parsed.code === "WANT_RANGE_LIMIT_EXCEEDED") {
        const c = getErrorNumber(err, "count");
        const l = getErrorNumber(err, "limit");
        const unit = parsed.code === "WANT_SEAT_LIMIT_EXCEEDED" ? "석" : "개 범위";
        message =
          c !== null && l !== null
            ? `희망 ${unit === "석" ? "좌석" : "범위"}이 너무 많아요 (${c.toLocaleString("ko-KR")}${unit}, 최대 ${l.toLocaleString("ko-KR")}${unit}). 범위를 줄여주세요.`
            : message;
        setErrors({ ranges: message });
        setFormError(message);
        pendingFocus.current = rangeFieldId(cards[0].uid, "zone");
      } else if (parsed.code === "REQUEST_DELETED") {
        setErrors({});
        setFormError(message);
        setRequestDeleted(true);
      } else if (parsed.code === "WANT_EXTRA_CONFLICT") {
        // 충돌한 범위 인덱스가 오면 해당 카드에, 없으면 요약에만 보인다
        const indexes = getConflictIndexes(err).filter((i) => i < cards.length);
        const conflictErrors = Object.fromEntries(indexes.map((i) => [`ranges[${i}].conflict`, message]));
        setErrors(conflictErrors);
        setFormError(indexes.length > 0 ? `${message} (범위 ${indexes.map((i) => i + 1).join(", ")})` : message);
        pendingFocus.current = firstErrorId(conflictErrors, cards) ?? rangeFieldId(cards[0].uid, "zone");
      } else {
        // 400 필드 오류(ranges[0].rowFrom, ranges[0].extraType, wantSessions[0].sessionId ...)는 해당 칸에 매핑
        setErrors(parsed.fieldErrors);
        setFormError(message);
        // 입력칸이 아직 disabled라 지금 focus()하면 실패한다 — 렌더 뒤 effect가 포커스한다
        if (Object.keys(parsed.fieldErrors).length > 0) pendingFocus.current = firstErrorId(parsed.fieldErrors, cards);
      }
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  const remove = async () => {
    if (!existing || deletingRef.current) return;
    deletingRef.current = true;
    setDeleting(true);
    setDeleteError(null);
    try {
      await exchangeApi.deleteRequest(existing.id);
      navigate("/tickets", {
        replace: true,
        state: { notice: "교환 조건을 삭제했어요. 같은 티켓에서 새로 만들 수 있어요." },
      });
    } catch (err) {
      setDeleteError(exchangeErrorMessage(err, "교환 조건을 삭제하지 못했습니다.").message);
      deletingRef.current = false;
      setDeleting(false);
    }
  };

  const busy = submitting || deleting;
  const rangeSectionError = errors.ranges;
  const sessionError = Object.entries(errors).find(([k]) => k.startsWith("wantSessions"))?.[1];

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>{existing ? "교환 조건 수정" : "교환 조건 설정"}</h1>

        <section className={`${ui.card} flex flex-col gap-1`} aria-labelledby="ticket-summary-title">
          <h2 id="ticket-summary-title" className="text-sm/[normal] font-semibold text-gray-700">
            내 티켓
          </h2>
          <p className="text-lg/[normal] font-bold break-keep text-gray-900">{performance.title}</p>
          <p className={ui.body}>
            {formatKstDateTime(ticket.startsAt)} · {performance.venueName}
          </p>
          <p className="text-[0.9375rem]/[normal] font-semibold text-primary-700">{formatSeat(ticket)}</p>
        </section>

        <p className={ui.status} role="status">
          {status}
        </p>

        <form className="flex flex-col gap-4" onSubmit={submit} noValidate aria-label="교환 조건">
          <section className={`${ui.card} flex flex-col gap-4`} aria-labelledby="ranges-title">
            <div className="flex flex-col gap-1">
              <h2 id="ranges-title" className={ui.sectionTitle}>
                바꾸고 싶은 자리 범위
              </h2>
              <p className={ui.hint}>
                구역·열·번을 티켓에 적힌 그대로 입력해주세요. 숫자 열·번은 시작~끝 범위로 입력할 수 있고, 문자 열(A)은 하나씩 따로 추가해주세요.
              </p>
              <p className={ui.hint}>
                범위마다 추가금 조건을 따로 정해요. 금액은 매칭 판정에 쓰이지 않고 상대에게 참고로 보여져요.
              </p>
            </div>

            <div className="flex flex-col gap-3">
              {cards.map((card, i) => {
                const prefix = `ranges[${i}].`;
                const cardErrors = Object.fromEntries(
                  Object.entries(errors)
                    .filter(([k]) => k.startsWith(prefix))
                    .map(([k, v]) => [k.slice(prefix.length), v])
                );
                return (
                  <SeatRangeCard
                    key={card.uid}
                    uid={card.uid}
                    number={i + 1}
                    value={card}
                    errors={cardErrors}
                    disabled={busy}
                    warning={conflictWarnings[i]}
                    onChange={(field, value) => changeCard(i, field, value)}
                    onExtraChange={(field, value) => changeCardExtra(i, field, value)}
                    onRemove={() => removeCard(i)}
                    onDuplicate={() => duplicateCard(i)}
                    canDuplicate={cards.length < WANT_MAX_RANGES}
                    removable={cards.length > 1}
                  />
                );
              })}
            </div>

            <div className="flex flex-wrap items-center justify-between gap-2">
              <button
                type="button"
                className={button.outline}
                onClick={addCard}
                disabled={busy || cards.length >= WANT_MAX_RANGES}
              >
                범위 추가
              </button>
              <span className={ui.muted}>
                {cards.length}/{WANT_MAX_RANGES}개
              </span>
            </div>

            <div className="flex flex-col gap-1">
              <p className="text-[0.9375rem]/[normal] font-semibold text-gray-900">
                총 {count.toLocaleString("ko-KR")}석
                {invalid > 0 && <span className="font-normal text-gray-700"> (입력을 확인해야 하는 범위 {invalid}개는 제외)</span>}
              </p>
              <p className={ui.hint}>겹치는 자리는 한 번만 세요. 서버 안전 상한은 {WANT_MAX_SEATS.toLocaleString("ko-KR")}석이에요.</p>
              {overLimit && (
                <p className={ui.errorBox} role="status">
                  희망 좌석이 {WANT_MAX_SEATS.toLocaleString("ko-KR")}석을 넘었어요. 범위를 줄여야 저장할 수 있어요.
                </p>
              )}
              {conflictPairs.length > 0 && (
                <p className={ui.warning} role="status">
                  겹치는 범위의 추가금 조건이 서로 달라요 ({conflictPairs.map(([a, b]) => `범위 ${a + 1}·${b + 1}`).join(", ")}). 겹치는 범위는 같은 조건으로 맞춰야 저장돼요.
                </p>
              )}
              {rangeSectionError && !overLimit && (
                <p className={ui.fieldError}>
                  {rangeSectionError}
                </p>
              )}
            </div>
          </section>

          <section className={`${ui.card} flex flex-col gap-3`}>
            {droppedClosed > 0 && (
              <p className={ui.notice}>
                저장돼 있던 희망 회차 {droppedClosed}개가 마감되어 선택이 해제됐어요. 저장하면 반영돼요.
              </p>
            )}
            {/* 오류 시 포커스가 갈 곳: 첫 선택 가능한 체크박스 (id는 WantSessionsEditor가 부여) */}
            <WantSessionsEditor rows={sessionRows} onChange={(r) => { setSessionRows(r); clearError("wantSessions"); setFormError(null); }} error={sessionError} disabled={busy} />
          </section>

          {formError && (
            <p className={ui.errorBox} role="alert">
              {formError}
            </p>
          )}

          {requestDeleted && (
            <Link to="/tickets" className={linkButton.outline}>
              내 티켓으로
            </Link>
          )}

          {existing && (
            <p className={ui.hint}>저장하면 이 요청의 진행 중인(예약 전) 채팅 매칭이 모두 자동 취소돼요. 예약된 매칭이 있으면 수정할 수 없어요.</p>
          )}

          <div className="flex flex-wrap justify-end gap-2">
            {existing && (
              <Link to={`/exchange/requests/${existing.id}/candidates`} className={linkButton.outline}>
                후보 보기
              </Link>
            )}
            <button type="submit" className={button.solid} disabled={busy || overLimit} aria-busy={submitting}>
              {submitting ? "저장 중..." : existing ? "수정 저장" : "저장하고 후보 보기"}
            </button>
          </div>
        </form>

        {existing && (
          <section className={`${ui.card} flex flex-col gap-3`} aria-labelledby="delete-title">
            <h2 id="delete-title" className={ui.sectionTitle}>
              교환 조건 삭제
            </h2>
            {!confirmDelete ? (
              <div>
                <button type="button" className={button.dangerOutline} onClick={() => setConfirmDelete(true)} disabled={busy}>
                  교환 조건 삭제
                </button>
              </div>
            ) : (
              <div ref={deleteConfirmRef} tabIndex={-1} className="flex flex-col gap-3 outline-none">
                <p className={ui.warning}>삭제하면 진행 중인 채팅 단계 매칭은 자동으로 취소돼요. 예약된 매칭이 있으면 삭제할 수 없어요. 티켓은 그대로 남아요. 삭제할까요?</p>
                <div className="flex flex-wrap gap-2">
                  <button type="button" className={button.danger} onClick={remove} disabled={deleting} aria-busy={deleting}>
                    {deleting ? "삭제 중..." : "삭제할게요"}
                  </button>
                  <button
                    type="button"
                    className={button.outline}
                    onClick={() => {
                      setConfirmDelete(false);
                      setDeleteError(null);
                    }}
                    disabled={deleting}
                  >
                    취소
                  </button>
                </div>
              </div>
            )}
            {deleteError && (
              <p className={ui.errorBox} role="alert">
                {deleteError}
              </p>
            )}
          </section>
        )}
      </div>
    </div>
  );
}
