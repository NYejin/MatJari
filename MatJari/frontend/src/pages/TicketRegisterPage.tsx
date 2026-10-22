import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { compareLocalDateTime, formatKstDateTime, isSessionClosed, kstMinuteString } from "../api/dateTime";
import { ROW_COL_MAX, ZONE_MAX } from "../api/seat";
import { getErrorStatus, performancesApi } from "../api/performances";
import { ticketsApi } from "../api/tickets";
import TextField from "../components/TextField";
import ZonePickField from "../components/ZonePickField";
import { button, input, linkButton, liveRegionClass, ui } from "../components/ui";
import { useDebouncedValue } from "../hooks/useDebouncedValue";
import { usePagedList } from "../hooks/usePagedList";
import { useZones } from "../hooks/useZones";
import type { PerformanceDetail, PerformanceSummary } from "../types/performance";

// FR-03 티켓 등록 (보호 라우트 /tickets/new[?performanceId=&sessionId=]).
// 공연 선택(검색) -> 회차 선택 -> 구역(필수 텍스트)·열·번 입력 -> 등록. 좌석표는 쓰지 않는다.
// 공연 상세에서 들어오면 performanceId로 공연이 고정되고, sessionId가 있으면 그 회차가 미리 선택된다.

type SeatField = "zone" | "row" | "col";
const FIELD_ID: Record<SeatField, string> = { zone: "ticket-zone", row: "ticket-row", col: "ticket-col" };

const parseId = (value: string | null): number | null => {
  const n = Number(value);
  return value && Number.isInteger(n) && n > 0 ? n : null;
};

export default function TicketRegisterPage() {
  const [params] = useSearchParams();
  const fixedPerformanceId = parseId(params.get("performanceId"));
  const presetSessionId = parseId(params.get("sessionId"));
  const [pickedId, setPickedId] = useState<number | null>(null);
  const performanceId = fixedPerformanceId ?? pickedId;

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>티켓 등록</h1>
        {performanceId === null ? (
          <PerformancePicker onPick={setPickedId} />
        ) : (
          <TicketForm
            key={performanceId}
            performanceId={performanceId}
            presetSessionId={fixedPerformanceId !== null ? presetSessionId : null}
            canChangePerformance={fixedPerformanceId === null}
            onChangePerformance={() => setPickedId(null)}
          />
        )}
      </div>
    </div>
  );
}

// ---- ① 공연 선택 ----

function PerformancePicker({ onPick }: { onPick: (id: number) => void }) {
  const [query, setQuery] = useState("");
  const debounced = useDebouncedValue(query.trim(), 300);
  const asOf = useRef(kstMinuteString());
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<PerformanceSummary>(
    (page, signal) => performancesApi.list({ query: debounced, page, asOf: asOf.current }, signal),
    debounced,
    (p) => p.id
  );

  const statusText =
    state.status === "loading" ? "공연 목록을 불러오는 중..." : state.status === "success" ? `공연 ${state.totalElements}개` : "";

  return (
    <section className={`${ui.card} flex flex-col gap-4`} aria-labelledby="picker-title">
      <h2 id="picker-title" className={ui.sectionTitle}>
        1. 공연 선택
      </h2>
      <div className="flex flex-col gap-1.5">
        <label htmlFor="picker-search" className={ui.label}>
          공연 제목 검색
        </label>
        <input
          id="picker-search"
          type="search"
          className={input.normal}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="공연 제목을 입력하세요"
          autoComplete="off"
        />
      </div>

      <p className={liveRegionClass(statusText, ui.status)} role="status">
        {statusText}
      </p>

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

      {state.status === "success" && state.items.length === 0 && (
        <div className="flex flex-col items-start gap-3">
          <p className={ui.body}>
            {debounced
              ? "검색 결과가 없어요. 다른 제목으로 찾아보거나, 공연을 새로 등록하면서 내 티켓을 함께 등록해 주세요."
              : "아직 등록된 공연이 없어요. 공연을 새로 등록하면서 내 티켓을 함께 등록해 주세요."}
          </p>
          <Link to="/performances/new" className={linkButton.outline}>
            새 공연과 내 티켓 등록하기
          </Link>
        </div>
      )}

      {state.status === "success" && state.items.length > 0 && (
        <>
          <ul className="flex flex-col divide-y divide-gray-200" aria-label="공연 목록">
            {state.items.map((p) => (
              <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 py-3">
                <div className="flex min-w-0 flex-col">
                  <span className="text-[0.9375rem]/[normal] font-bold break-keep text-gray-900">{p.title}</span>
                  <span className={ui.muted}>
                    {p.venueName}
                    {p.nextSessionStartsAt && ` · 다음 회차 ${formatKstDateTime(p.nextSessionStartsAt)}`}
                  </span>
                </div>
                <button
                  type="button"
                  className={button.outline}
                  onClick={() => onPick(p.id)}
                  aria-label={`${p.title} 선택`}
                >
                  선택
                </button>
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
          <p className={ui.hint}>
            찾는 공연이 없나요?{" "}
            <Link to="/performances/new" className={ui.link}>
              새 공연과 내 티켓 등록하기
            </Link>
          </p>
        </>
      )}
    </section>
  );
}

// ---- ②③ 회차 선택 + 좌석 입력 ----

type DetailState =
  | { status: "loading" }
  | { status: "notFound" }
  | { status: "error"; message: string }
  | { status: "ready"; detail: PerformanceDetail };

interface TicketFormProps {
  performanceId: number;
  presetSessionId: number | null;
  canChangePerformance: boolean;
  onChangePerformance: () => void;
}

function TicketForm({ performanceId, presetSessionId, canChangePerformance, onChangePerformance }: TicketFormProps) {
  const navigate = useNavigate();
  const [detailState, setDetailState] = useState<DetailState>({ status: "loading" });
  const [retryKey, setRetryKey] = useState(0);
  /** 공연에 등록된 구역 목록(없거나 실패하면 null → 텍스트 입력) */
  const zoneData = useZones(performanceId);
  const [sessionId, setSessionId] = useState<number | null>(presetSessionId);
  const [zone, setZone] = useState("");
  const [row, setRow] = useState("");
  const [col, setCol] = useState("");
  const [errors, setErrors] = useState<Partial<Record<SeatField | "sessionId", string>>>({});
  const [submitError, setSubmitError] = useState<{ message: string; code: string | null } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  /** 더블 탭 방지용 (state는 다음 렌더 전까지 갱신되지 않는다) */
  const submittingRef = useRef(false);
  /** 서버 오류 뒤 포커스를 줄 요소 id — 입력칸이 disabled인 동안은 focus()가 안 먹으므로 렌더 뒤 effect가 처리 */
  const pendingFocus = useRef<string | null>(null);

  useEffect(() => {
    if (!pendingFocus.current) return;
    const id = pendingFocus.current;
    pendingFocus.current = null;
    document.getElementById(id)?.focus();
  });

  useEffect(() => {
    const controller = new AbortController();
    setDetailState({ status: "loading" });
    performancesApi
      .get(performanceId, controller.signal)
      .then((detail) => {
        // 미리 선택된 회차가 이 공연의 것이 아니거나 마감이면 선택하지 않은 상태로 되돌린다
        setSessionId((prev) => {
          const s = detail.sessions.find((x) => x.id === prev);
          return s && !isSessionClosed(s.startsAt) ? prev : null;
        });
        setDetailState({ status: "ready", detail });
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        if (getErrorStatus(err) === 404) setDetailState({ status: "notFound" });
        else setDetailState({ status: "error", message: exchangeErrorMessage(err, "공연 정보를 불러오지 못했습니다.").message });
      });
    return () => controller.abort();
  }, [performanceId, retryKey]);

  if (detailState.status === "loading") {
    return (
      <p className={ui.status} role="status">
        공연 정보를 불러오는 중...
      </p>
    );
  }
  if (detailState.status === "notFound") {
    return (
      <div className={`${ui.card} flex flex-col items-start gap-3`}>
        <p className={ui.body}>공연을 찾을 수 없어요. 존재하지 않거나 잘못된 주소예요.</p>
        <Link to="/" className={linkButton.outline}>
          공연 목록으로
        </Link>
      </div>
    );
  }
  if (detailState.status === "error") {
    return (
      <div className={`${ui.card} flex flex-col gap-3`}>
        <p className={ui.errorBox} role="alert">
          {detailState.message}
        </p>
        <button type="button" className={button.solid} onClick={() => setRetryKey((k) => k + 1)}>
          다시 시도
        </button>
      </div>
    );
  }

  const { detail } = detailState;
  const sessions = [...detail.sessions].sort((a, b) => compareLocalDateTime(a.startsAt, b.startsAt));

  /** 오류 칸의 요소 id. 선택 가능한 회차가 하나도 없으면(모두 마감) 회차 묶음 자체 */
  const fieldFocusId = (field: SeatField | "sessionId"): string => {
    if (field !== "sessionId") return FIELD_ID[field];
    const open = sessions.find((s) => !isSessionClosed(s.startsAt));
    return open ? `ticket-session-${open.id}` : "ticket-session-group";
  };

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (submittingRef.current) return;
    setSubmitError(null);

    const z = zone.trim();
    const r = row.trim();
    const c = col.trim();
    const next: Partial<Record<SeatField | "sessionId", string>> = {};
    if (sessionId === null) next.sessionId = "회차를 선택해주세요.";
    if (!z) next.zone = "구역을 입력해주세요.";
    else if (z.length > ZONE_MAX) next.zone = `구역은 ${ZONE_MAX}자 이하로 입력해주세요.`;
    if (!r) next.row = "열을 입력해주세요.";
    else if (r.length > ROW_COL_MAX) next.row = `열은 ${ROW_COL_MAX}자 이하로 입력해주세요.`;
    if (!c) next.col = "번을 입력해주세요.";
    else if (c.length > ROW_COL_MAX) next.col = `번은 ${ROW_COL_MAX}자 이하로 입력해주세요.`;
    setErrors(next);
    const firstKey = (["sessionId", "zone", "row", "col"] as const).find((k) => next[k]);
    if (firstKey || sessionId === null) {
      if (firstKey) document.getElementById(fieldFocusId(firstKey))?.focus();
      return;
    }

    submittingRef.current = true;
    setSubmitting(true);
    try {
      await ticketsApi.create({ sessionId, zone: z, row: r, col: c });
      navigate("/tickets", { replace: true, state: { notice: "티켓을 등록했어요. 교환 조건을 설정하면 바꿀 상대를 찾을 수 있어요." } });
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "티켓을 등록하지 못했습니다.");
      const fe = parsed.fieldErrors;
      const fieldMap: Partial<Record<SeatField | "sessionId", string>> = {};
      for (const key of ["sessionId", "zone", "row", "col"] as const) if (fe[key]) fieldMap[key] = fe[key];
      setErrors(fieldMap);
      const first = (["sessionId", "zone", "row", "col"] as const).find((k) => fieldMap[k]);
      if (first) {
        pendingFocus.current = fieldFocusId(first);
        // 필드 오류만 있는 400은 대표 메시지를 따로 띄우지 않는다 (각 칸에 표시)
        setSubmitError(null);
      } else {
        setSubmitError({ message: parsed.message, code: parsed.code });
      }
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  const clear = (field: SeatField | "sessionId") => {
    setErrors((prev) => (prev[field] ? { ...prev, [field]: undefined } : prev));
    setSubmitError(null);
  };

  let errorBlock: ReactNode = null;
  if (submitError) {
    errorBlock = (
      <div className={`${ui.errorBox} flex flex-col items-start gap-2`} role="alert">
        <span>{submitError.message}</span>
        {submitError.code === "SEAT_ALREADY_REGISTERED" && (
          <>
            <span>내 자리가 맞다면 티켓을 인증해 주세요.</span>
            <Link
              to={`/seat-claims/new?${new URLSearchParams({
                performanceId: String(performanceId),
                ...(sessionId !== null ? { sessionId: String(sessionId) } : {}),
                zone: zone.trim(),
                row: row.trim(),
                col: col.trim(),
              }).toString()}`}
              className={linkButton.outline}
            >
              내 티켓 인증
            </Link>
          </>
        )}
        {submitError.code === "MY_TICKET_ALREADY_REGISTERED" && (
          <Link to="/tickets" className={linkButton.outline}>
            내 티켓 보기
          </Link>
        )}
      </div>
    );
  }

  const sessionError = errors.sessionId;

  return (
    <form className="flex flex-col gap-4" onSubmit={submit} noValidate aria-label="티켓 등록">
      <section className={`${ui.card} flex flex-col gap-2`} aria-labelledby="ticket-perf-title">
        <h2 id="ticket-perf-title" className="text-sm/[normal] font-semibold text-gray-700">
          공연
        </h2>
        <p className="text-lg/[normal] font-bold break-keep text-gray-900">{detail.title}</p>
        <p className={ui.body}>{detail.venueName}</p>
        {canChangePerformance && (
          <div>
            <button type="button" className={button.outline} onClick={onChangePerformance} disabled={submitting}>
              다른 공연 선택
            </button>
          </div>
        )}
      </section>

      <fieldset
        id="ticket-session-group"
        tabIndex={-1}
        className={`${ui.card} flex flex-col gap-2 outline-none`}
        disabled={submitting}
        aria-labelledby="ticket-session-title"
        aria-describedby={sessionError ? "ticket-session-error" : undefined}
      >
        <h2 id="ticket-session-title" className={`${ui.sectionTitle} mb-1`}>
          회차 선택
        </h2>
        {sessions.length === 0 ? (
          <p className={ui.body}>이 공연에는 등록된 회차가 없어요.</p>
        ) : (
          <ul className="flex flex-col divide-y divide-gray-200">
            {sessions.map((s) => {
              const closed = isSessionClosed(s.startsAt);
              const id = `ticket-session-${s.id}`;
              return (
                <li key={s.id}>
                  <label htmlFor={id} className="flex min-h-11 cursor-pointer items-center gap-3 py-2">
                    <input
                      id={id}
                      type="radio"
                      name="ticket-session"
                      className="size-5 shrink-0 accent-primary-600"
                      checked={sessionId === s.id}
                      disabled={closed}
                      onChange={() => {
                        setSessionId(s.id);
                        clear("sessionId");
                      }}
                      aria-invalid={!!sessionError}
                    />
                    <span className="flex flex-wrap items-center gap-2 text-[0.9375rem]/[normal] text-gray-900">
                      {formatKstDateTime(s.startsAt)}
                      {closed && <span className={ui.badge}>마감</span>}
                    </span>
                  </label>
                </li>
              );
            })}
          </ul>
        )}
        {sessionError && (
          <p id="ticket-session-error" className={ui.fieldError}>
            {sessionError}
          </p>
        )}
      </fieldset>

      <fieldset
        className={`${ui.card} flex flex-col gap-4`}
        disabled={submitting}
        aria-labelledby="ticket-seat-title"
      >
        <h2 id="ticket-seat-title" className={`${ui.sectionTitle} mb-1`}>
          내 좌석
        </h2>
        <p className={ui.hint}>티켓에 적힌 그대로 입력해주세요. 열·번 끝의 ‘열’, ‘번’은 빼도 돼요.</p>
        <ZonePickField
          id={FIELD_ID.zone}
          label="구역"
          value={zone}
          zoneData={zoneData}
          onValueChange={(v) => {
            setZone(v);
            clear("zone");
          }}
          error={errors.zone}
          hint="예: A구역 → A | 33구역 → 33"
          maxLength={ZONE_MAX}
          autoComplete="off"
        />
        <div className="grid grid-cols-2 gap-3">
          <TextField
            id={FIELD_ID.row}
            label="열"
            value={row}
            onChange={(e) => {
              setRow(e.target.value);
              clear("row");
            }}
            error={errors.row}
            hint="숫자 또는 문자 (예: 3, A)"
            maxLength={ROW_COL_MAX}
            autoComplete="off"
            autoCapitalize="characters"
          />
          <TextField
            id={FIELD_ID.col}
            label="번"
            value={col}
            onChange={(e) => {
              setCol(e.target.value);
              clear("col");
            }}
            error={errors.col}
            hint="숫자 또는 문자 (예: 12)"
            maxLength={ROW_COL_MAX}
            autoComplete="off"
            autoCapitalize="characters"
          />
        </div>
      </fieldset>

      {errorBlock}

      <div className="flex flex-wrap justify-end gap-2">
        <Link to="/tickets" className={linkButton.outline}>
          취소
        </Link>
        <button type="submit" className={button.solid} disabled={submitting} aria-busy={submitting}>
          {submitting ? "등록 중..." : "티켓 등록"}
        </button>
      </div>
    </form>
  );
}
