import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import type { PerformancePreview } from "../types/performance";
import { getConflictPerformanceId, performancesApi } from "../api/performances";
import { parseApiError } from "../api/errors";
import {
  // TODO: 지우기
  // SESSION_TIME_STEP_HINT,
  SESSION_TIME_STEP_SECONDS,
  compareLocalDateTime,
  formatKstDateTime,
  isPastKst,
  normalizeLocalDateTime,
  validateSessionTimeStep,
} from "../api/dateTime";
import TextField from "../components/TextField";
import SessionDateTimePicker from "../components/SessionDateTimePicker";
import PosterImage from "../components/PosterImage";
import { button, linkButton, liveRegionClass, ui } from "../components/ui";

// FR-02 공연 등록 (보호 라우트 /performances/new) — 단계형:
// ① 티켓팅 링크(이미 등록됐는지 확인) ② 공연 정보(제목·공연장 이름·회차) ③ 확인 후 등록.
// 공연장은 별도 엔티티 없이 텍스트 한 칸(venueName). 링크 확인 뒤 읽은 제목·공연장을 ②단계에 미리 채운다(회차는 읽을 수 없어 직접 입력).

const STEPS = ["티켓팅 링크", "공연 정보", "확인"] as const;
type Step = 0 | 1 | 2;

const TITLE_MAX = 200;
const VENUE_MAX = 100;
const URL_MAX = 2048;
const SESSIONS_MAX = 100;
/** 링크 정보 읽기 전체 제한 시간 — 넘으면 중단하고 그냥 다음 단계로 */
const PREVIEW_TIMEOUT_MS = 15_000;

/** 2단계 상단 안내 — 링크에서 읽은 결과 */
type AutoFillNote = { kind: "filled" | "none" | "unsupported" | "failed"; periodStart: string | null; periodEnd: string | null };

/** yyyy-MM-dd → yyyy.MM.dd */
const dotDate = (d: string) => d.replace(/-/g, ".");
function periodText(start: string, end: string | null): string {
  return end && end !== start ? `${dotDate(start)} ~ ${dotDate(end)}` : dotDate(start);
}

const cls = {
  steps: "flex flex-wrap gap-x-3 gap-y-1",
  /** 단계 표시 — 현재/그 외 중 완성된 문자열 하나. surface 배경 위라 gray-500(4.47:1) 대신 gray-700.
      완료 단계는 색이 아닌 체크 표시로 구분한다 */
  stepCurrent: "text-sm/[normal] font-bold text-primary-700",
  stepOther: "text-sm/[normal] text-gray-700",
  stepBody: "flex flex-col gap-4",
  actions: "flex flex-wrap justify-between gap-2",
  sessionList: "flex flex-col divide-y divide-gray-200",
  sessionRow: "flex min-h-11 items-center justify-between gap-3 py-1.5",
  summary: "grid grid-cols-[auto_1fr] gap-x-4 gap-y-2",
  summaryTerm: "text-sm/[normal] font-semibold text-gray-700",
  summaryDesc: "min-w-0 text-[0.9375rem]/[normal] break-all text-gray-900",
} as const;

const stepClass = (index: number, current: number) => (index === current ? cls.stepCurrent : cls.stepOther);

/** http(s) 링크만 허용 */
function validateSourceUrl(value: string): string | undefined {
  const v = value.trim();
  if (!v) return "티켓팅 링크를 입력해주세요.";
  if (v.length > URL_MAX) return `티켓팅 링크는 ${URL_MAX}자 이하로 입력해주세요.`;
  try {
    const url = new URL(v);
    if (url.protocol !== "http:" && url.protocol !== "https:") return "http 또는 https 링크를 입력해주세요.";
  } catch {
    return "올바른 링크 형식이 아닙니다.";
  }
  return undefined;
}

/** 서버 필드명 → 그 필드를 입력하는 단계 */
const FIELD_STEP: Record<string, Step> = { sourceUrl: 0, title: 1, venueName: 1, sessions: 1 };

/** 서버 필드명 → 포커스를 줄 요소 id (회차는 그룹 래퍼 안의 첫 컨트롤) */
const FIELD_FOCUS_ID: Record<string, string> = {
  sourceUrl: "perf-source-url",
  title: "perf-title",
  venueName: "perf-venue",
  sessions: "perf-sessions",
};
/** 여러 필드가 한꺼번에 틀렸을 때 먼저 보여줄 순서 */
const FIELD_ORDER = ["sourceUrl", "title", "venueName", "sessions"] as const;

/** id 요소로 포커스. 래퍼(div)면 그 안의 첫 입력 컨트롤로 */
function focusById(id: string) {
  const el = document.getElementById(id);
  if (!el) return;
  const target = el.matches("input,select,textarea,button") ? el : el.querySelector<HTMLElement>("input,select,textarea");
  target?.focus();
}

export default function PerformanceRegisterPage() {
  const navigate = useNavigate();
  const [step, setStep] = useState<Step>(0);
  const headingRef = useRef<HTMLHeadingElement>(null);
  /** 사용자가 단계를 옮겼을 때만 제목으로 포커스 (최초 진입·StrictMode 재실행에서는 하지 않음) */
  const focusHeading = useRef(false);
  /** 단계 이동 뒤 제목 대신 포커스를 줄 요소 id (오류가 있는 입력) */
  const focusTargetId = useRef<string | null>(null);
  /** 중복 조회에서 "등록되지 않음"으로 확인된 링크 — 같은 링크면 다시 조회하지 않는다 */
  const checkedUrl = useRef<string | null>(null);
  /** 링크 정보를 이미 읽어 본 링크 — 같은 링크면 다시 호출하지 않는다 */
  const previewedUrl = useRef<string | null>(null);
  const lastPrefill = useRef<{ title: string; venue: string } | null>(null);
  const previewAbort = useRef<AbortController | null>(null);
  const unmounted = useRef(false);
  useEffect(() => {
    unmounted.current = false;
    return () => {
      unmounted.current = true;
      previewAbort.current?.abort();
    };
  }, []);

  // ① 링크
  const [sourceUrl, setSourceUrl] = useState("");
  const [urlError, setUrlError] = useState<string>();
  const [checking, setChecking] = useState(false);
  const [existingId, setExistingId] = useState<number | null>(null);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const [reading, setReading] = useState(false);
  const [autoFill, setAutoFill] = useState<AutoFillNote | null>(null);
  /** 링크에서 읽은 포스터 이미지 주소(표시만, 저장하지 않는다) */
  const [posterUrl, setPosterUrl] = useState<string | null>(null);
  /** 링크 기간에서 얻은 회차 날짜 후보(yyyy-MM-dd) — 눌러서 날짜 칸만 채운다(시각은 직접 입력) */
  const [candidateDates, setCandidateDates] = useState<string[]>([]);
  const [datePreset, setDatePreset] = useState<{ date: string; nonce: number } | null>(null);
  const presetNonce = useRef(0);
  const presetDate = (date: string) => {
    presetNonce.current += 1;
    setDatePreset({ date, nonce: presetNonce.current });
  };
  // ② 공연 정보: 제목·공연장 이름·회차
  const [title, setTitle] = useState("");
  const [titleError, setTitleError] = useState<string>();
  const [venueName, setVenueName] = useState("");
  const [venueError, setVenueError] = useState<string>();
  const [sessionInput, setSessionInput] = useState("");
  const [sessions, setSessions] = useState<string[]>([]);
  const [sessionError, setSessionError] = useState<string>();
  // ③ 등록
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<{ message: string; step?: Step; focusId?: string } | null>(null);
  const [conflictId, setConflictId] = useState<number | null>(null);

  // 단계가 바뀌면 단계 제목으로 포커스 (스크린리더가 새 단계를 읽도록)
  useEffect(() => {
    if (!focusHeading.current) return;
    focusHeading.current = false;
    const targetId = focusTargetId.current;
    focusTargetId.current = null;
    if (targetId) focusById(targetId);
    else headingRef.current?.focus();
  }, [step]);

  /** 단계 이동. 이전 등록 시도의 오류 박스·"등록된 공연 보기" 링크는 지운다. focusId가 있으면 제목 대신 그 입력으로 포커스 */
  const goTo = (next: Step, focusId?: string) => {
    focusHeading.current = true;
    focusTargetId.current = focusId ?? null;
    setSubmitError(null);
    setConflictId(null);
    setStep(next);
  };

  /** 링크 정보 읽기. 실패해도 오류로 막지 않고(15초 제한) 호출한 쪽이 다음 단계로 간다 */
  const readPreview = async (url: string) => {
    const controller = new AbortController();
    previewAbort.current = controller;
    const timer = window.setTimeout(() => controller.abort(), PREVIEW_TIMEOUT_MS);
    setReading(true);
    let result: PerformancePreview | null = null;
    try {
      result = await performancesApi.preview(url, controller.signal);
    } catch {
      result = null; // 시간 초과·400·429·네트워크 오류 모두 직접 입력으로 안내
    } finally {
      window.clearTimeout(timer);
      setReading(false);
    }
    if (unmounted.current) return; // 화면을 떠난 뒤의 응답은 버린다
    setPosterUrl(result?.status === "OK" ? (result.posterUrl ?? null) : null);
    const dates = result?.status === "OK" ? (result.sessionCandidates ?? []).map((c) => c.date) : [];
    setCandidateDates(dates);
    if (dates.length > 0 && sessions.length === 0) presetDate(dates[0]);
    else if (dates.length === 0) setDatePreset(null);
    // 오류·시간 초과·읽기 실패는 기록하지 않아 같은 링크로 다시 시도할 수 있다
    if (result && result.status !== "FETCH_FAILED") previewedUrl.current = url;
    // 사용자가 쓴 값은 덮어쓰지 않는다 — 제목·공연장이 모두 비어 있거나, 이전 링크에서 미리 채운 값 그대로일 때만 바꾼다
    const prev = lastPrefill.current;
    const untouched = (!title.trim() && !venueName.trim()) || (prev !== null && title === prev.title && venueName === prev.venue);
    if (!result || result.status === "FETCH_FAILED" || result.status === "NO_INFO" || result.status === "UNSUPPORTED_SITE") {
      if (untouched && prev !== null) {
        // 다른 링크에서 미리 채운 값이 남아 있으면 지운다(이 링크의 정보가 아니다)
        setTitle("");
        setVenueName("");
        lastPrefill.current = null;
      }
      setAutoFill({
        kind: result?.status === "UNSUPPORTED_SITE" ? "unsupported" : "failed",
        periodStart: null,
        periodEnd: null,
      });
      return;
    }
    const nextTitle = untouched ? (result.title ?? "") : title;
    const nextVenue = untouched ? (result.venueName ?? "") : venueName;
    if (untouched) {
      setTitle(nextTitle);
      setVenueName(nextVenue);
      setTitleError(undefined);
      setVenueError(undefined);
      lastPrefill.current = { title: nextTitle, venue: nextVenue };
    }
    setAutoFill({
      kind: untouched && (result.title || result.venueName) ? "filled" : "none",
      periodStart: result.periodStart,
      periodEnd: result.periodEnd,
    });
  };

  const submitUrl = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (checking || reading) return;
    const error = validateSourceUrl(sourceUrl);
    setUrlError(error);
    setExistingId(null);
    setLookupError(null);
    if (error) return;
    // 이미 "등록되지 않음"으로 확인한 같은 링크면 조회를 건너뛴다
    const url = sourceUrl.trim();
    if (url === checkedUrl.current) {
      if (url !== previewedUrl.current) await readPreview(url);
      goTo(1);
      return;
    }
    setChecking(true);
    try {
      const result = await performancesApi.lookup(url);
      if (result.exists && typeof result.performanceId === "number") {
        setExistingId(result.performanceId);
        return;
      }
      checkedUrl.current = url;
      // 중복이 없을 때만 링크에서 공연 정보를 읽는다 (실패해도 2단계로 간다)
      if (url !== previewedUrl.current) await readPreview(url);
      goTo(1);
    } catch (err) {
      setLookupError(parseApiError(err, "링크를 확인하지 못했습니다.").message);
    } finally {
      setChecking(false);
    }
  };

  const addSession = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    // 10분 단위가 아니면(직접 입력·붙여넣기) 거부
    const stepError = validateSessionTimeStep(sessionInput);
    if (stepError) {
      setSessionError(stepError);
      return;
    }
    const normalized = normalizeLocalDateTime(sessionInput)!;
    if (isPastKst(normalized)) {
      // 백엔드도 지난 일시는 거부한다 — 미리 막는다
      setSessionError("이미 지난 시각이에요. 앞으로 있을 회차만 등록할 수 있어요.");
      return;
    }
    if (sessions.includes(normalized)) {
      setSessionError("이미 추가한 회차예요.");
      return;
    }
    if (sessions.length >= SESSIONS_MAX) {
      setSessionError(`회차는 한 번에 ${SESSIONS_MAX}개까지 등록할 수 있어요.`);
      return;
    }
    setSessions((prev) => [...prev, normalized].sort(compareLocalDateTime));
    setSessionInput("");
    setSessionError(undefined);
    // 아직 회차가 없는 다음 후보 날짜를 날짜 칸에 미리 채운다
    const covered = new Set([...sessions, normalized].map((s) => s.slice(0, 10)));
    const next = candidateDates.find((d) => !covered.has(d));
    if (next) presetDate(next);
  };

  const removeSession = (value: string) => setSessions((prev) => prev.filter((s) => s !== value));

  /** 제목·공연장·회차를 모두 검사. 처음 오류가 있는 칸으로 포커스를 옮긴다 */
  const confirmInfo = () => {
    const t = title.trim();
    const v = venueName.trim();
    const tErr = !t ? "공연 제목을 입력해주세요." : t.length > TITLE_MAX ? `공연 제목은 ${TITLE_MAX}자 이하로 입력해주세요.` : undefined;
    const vErr = !v ? "공연장 이름을 입력해주세요." : v.length > VENUE_MAX ? `공연장 이름은 ${VENUE_MAX}자 이하로 입력해주세요.` : undefined;
    let sErr: string | undefined;
    if (sessions.length === 0) sErr = "회차를 1개 이상 추가해주세요.";
    // 단계를 오가는 사이 지난 회차가 생겼으면 알려준다
    else if (sessions.some((s) => isPastKst(s))) sErr = "지난 회차가 있어요. 삭제한 뒤 진행해주세요.";
    setTitleError(tErr);
    setVenueError(vErr);
    setSessionError(sErr);
    if (tErr) focusById("perf-title");
    else if (vErr) focusById("perf-venue");
    else if (sErr) focusById("perf-sessions");
    if (tErr || vErr || sErr) return;
    goTo(2);
  };

  const submit = async () => {
    if (submitting) return;
    setSubmitting(true);
    setSubmitError(null);
    setConflictId(null);
    try {
      const created = await performancesApi.create({
        sourceUrl: sourceUrl.trim(),
        title: title.trim(),
        venueName: venueName.trim(),
        sessions,
      });
      navigate(`/performances/${created.id}`, { replace: true });
    } catch (err) {
      const existing = getConflictPerformanceId(err);
      const parsed = parseApiError(err, "공연을 등록하지 못했습니다.");
      if (existing !== null) {
        setConflictId(existing);
        checkedUrl.current = null;
      }
      // 서버 필드 오류 메시지를 해당 입력의 오류 상태에 넣어 이동한 단계에서 사유가 보이게 한다
      const fe = parsed.fieldErrors;
      if (fe.sourceUrl) setUrlError(fe.sourceUrl);
      if (fe.title) setTitleError(fe.title);
      if (fe.venueName) setVenueError(fe.venueName);
      const sessionsKey = Object.keys(fe).find((k) => k === "sessions" || k.startsWith("sessions"));
      if (sessionsKey) setSessionError(fe[sessionsKey]);
      const first = FIELD_ORDER.find((k) => (k === "sessions" ? !!sessionsKey : !!fe[k]));
      setSubmitError({ message: parsed.message, step: first ? FIELD_STEP[first] : undefined, focusId: first ? FIELD_FOCUS_ID[first] : undefined });
      setSubmitting(false);
    }
  };

  let body: ReactNode;
  if (step === 0) {
    body = (
      <form className={cls.stepBody} onSubmit={submitUrl} noValidate>
        <TextField
          id="perf-source-url"
          label="티켓팅 페이지 링크"
          type="url"
          inputMode="url"
          autoComplete="url"
          autoCapitalize="none"
          spellCheck={false}
          value={sourceUrl}
          onChange={(e) => {
            setSourceUrl(e.target.value);
            setExistingId(null);
            setLookupError(null);
            setUrlError(undefined);
          }}
          disabled={checking || reading}
          error={urlError}
          hint="공연을 예매한 티켓팅 사이트의 공연 페이지 주소를 붙여 넣어 주세요."
          placeholder="https://"
        />
        {/* 안내 문구는 항상 있는 라이브 영역, 이동 버튼은 별도로 조건부 렌더링 */}
        <p className={liveRegionClass(existingId !== null ? "exists" : "", ui.notice)} role="status">
          {existingId !== null
            ? "이미 등록된 공연이에요. 새로 등록하지 않고 기존 공연에서 티켓을 등록하고 교환할 수 있어요."
            : ""}
        </p>
        <p className={ui.status} role="status">
          {reading ? "링크에서 공연 정보를 읽는 중..." : ""}
        </p>
        {existingId !== null && (
          <div>
            <Link to={`/performances/${existingId}`} className={linkButton.solid}>
              등록된 공연 보기
            </Link>
          </div>
        )}
        {lookupError && (
          <p className={ui.errorBox} role="alert">
            {lookupError}
          </p>
        )}
        <div className="flex justify-end">
          <button type="submit" className={button.solid} disabled={checking || reading} aria-busy={checking || reading}>
            {checking || reading ? "확인 중..." : "다음"}
          </button>
        </div>
      </form>
    );
  } else if (step === 1) {
    body = (
      <div className={cls.stepBody}>
        <div className="flex flex-col gap-4 md:flex-row md:items-start md:justify-between">
        <div className="flex min-w-0 flex-1 flex-col gap-4">
        <p className={ui.notice} role="status">
          {autoFill?.kind === "filled"
            ? "링크에서 읽은 정보예요. 맞는지 확인하고, 다르면 고쳐 주세요."
            : autoFill?.kind === "unsupported"
              ? "이 링크는 자동 입력을 지원하지 않아요. YES24·티켓링크·멜론티켓의 공연 상세 주소만 읽을 수 있어요. 직접 입력해 주세요."
              : autoFill?.kind === "failed"
                ? "링크에서 정보를 읽지 못했어요. 직접 입력해 주세요."
                : "정보가 맞는지 확인하고 틀리면 고쳐 주세요."}
        </p>
        <TextField
          id="perf-title"
          label="공연 제목"
          value={title}
          onChange={(e) => {
            setTitle(e.target.value);
            setTitleError(undefined);
          }}
          error={titleError}
          hint={`티켓팅 사이트에 표시된 제목 그대로 (${TITLE_MAX}자 이하)`}
          maxLength={TITLE_MAX}
        />
        <TextField
          id="perf-venue"
          label="공연장 이름"
          value={venueName}
          onChange={(e) => {
            setVenueName(e.target.value);
            setVenueError(undefined);
          }}
          error={venueError}
          hint={`티켓팅 사이트에 표시된 공연장 이름 (${VENUE_MAX}자 이하). 등록 후에는 수정할 수 없어요.`}
          maxLength={VENUE_MAX}
        />
        </div>
        <PosterImage key={posterUrl ?? "none"} url={posterUrl} title={title || "공연"} className="self-start" />
        </div>
        <form id="perf-sessions" className="flex flex-col gap-2" onSubmit={addSession} noValidate>
          <div className="flex flex-wrap items-end gap-2">
            <SessionDateTimePicker
              legend="회차 날짜·시간 (한국 시간)"
              value={sessionInput}
              onChange={setSessionInput}
              datePreset={datePreset}
              invalid={!!sessionError}
              describedBy={`perf-session-hint${sessionError ? " perf-session-error" : ""}`}
            />
            <button type="submit" className={button.outline}>
              회차 추가
            </button>
          </div>
          {sessionError && (
            <p id="perf-session-error" className={ui.fieldError} role="alert">
              {sessionError}
            </p>
          )}
          {/* 안내는 오류가 있어도 계속 보이고 aria-describedby로 연결된다 */}
          {autoFill?.periodStart && (
            <p className={ui.hint}>
              링크에 적힌 공연 기간: {periodText(autoFill.periodStart, autoFill.periodEnd)} ·{" "}
              {candidateDates.length > 0
                ? "회차 시각은 링크에서 읽을 수 없어 직접 골라 주세요. 날짜는 공연 기간에 맞춰 미리 채워 뒀으니 맞는지 확인하고 다르면 고쳐 주세요."
                : "회차 일시는 링크에서 읽을 수 없어 직접 입력해 주세요."}
            </p>
          )}
          {candidateDates.length > 0 && (
            <div className="flex flex-wrap items-center gap-2" role="group" aria-label="공연 기간 날짜 선택">
              <span className={ui.hint}>날짜 바로 채우기:</span>
              {candidateDates.map((d) => (
                <button key={d} type="button" className={button.outline} onClick={() => presetDate(d)}>
                  {dotDate(d)}
                </button>
              ))}
            </div>
          )}
          <p id="perf-session-hint" className={ui.hint}>
            {/* TODO: Hint 지우기 */}
            {/* {SESSION_TIME_STEP_HINT}  */}같은 공연의 여러 회차를 모두 추가해 주세요.
          </p>
        </form>

        <p className={ui.status} role="status">
          {sessions.length > 0 ? `추가한 회차 ${sessions.length}개` : "아직 추가한 회차가 없어요."}
        </p>
        {sessions.length > 0 && (
          <ul className={cls.sessionList} aria-label="추가한 회차">
            {sessions.map((s) => (
              <li key={s} className={cls.sessionRow}>
                <span className={ui.body}>{formatKstDateTime(s)}</span>
                <button
                  type="button"
                  className={button.outline}
                  onClick={() => removeSession(s)}
                  aria-label={`${formatKstDateTime(s)} 회차 삭제`}
                >
                  삭제
                </button>
              </li>
            ))}
          </ul>
        )}

        <div className={cls.actions}>
          <button type="button" className={button.outline} onClick={() => goTo(0)}>
            이전
          </button>
          <button type="button" className={button.solid} onClick={confirmInfo}>
            다음
          </button>
        </div>
      </div>
    );
  } else {
    body = (
      <div className={cls.stepBody}>
        <dl className={cls.summary}>
          <dt className={cls.summaryTerm}>링크</dt>
          <dd className={cls.summaryDesc}>{sourceUrl.trim()}</dd>
          <dt className={cls.summaryTerm}>제목</dt>
          <dd className={cls.summaryDesc}>{title.trim()}</dd>
          <dt className={cls.summaryTerm}>공연장</dt>
          <dd className={cls.summaryDesc}>
            {venueName.trim()}
          </dd>
          <dt className={cls.summaryTerm}>회차</dt>
          <dd className={cls.summaryDesc}>
            <ul>
              {sessions.map((s) => (
                <li key={s}>{formatKstDateTime(s)}</li>
              ))}
            </ul>
          </dd>
        </dl>

        {submitError && (
          <div className={`${ui.errorBox} flex flex-col items-start gap-2`} role="alert">
            <span>{submitError.message}</span>
            {conflictId !== null && (
              <Link to={`/performances/${conflictId}`} className={linkButton.outline}>
                등록된 공연 보기
              </Link>
            )}
            {submitError.step !== undefined && (
              <button type="button" className={button.outline} onClick={() => goTo(submitError.step!, submitError.focusId)}>
                {STEPS[submitError.step]} 단계로 이동
              </button>
            )}
          </div>
        )}

        <div className={cls.actions}>
          <button type="button" className={button.outline} onClick={() => goTo(1)} disabled={submitting}>
            이전
          </button>
          <button type="button" className={button.solid} onClick={submit} disabled={submitting} aria-busy={submitting}>
            {submitting ? "등록 중..." : "공연 등록"}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>공연 등록</h1>

        <ol className={cls.steps} aria-label="등록 단계">
          {STEPS.map((name, index) => (
            <li key={name} className={stepClass(index, step)} aria-current={index === step ? "step" : undefined}>
              {index < step && (
                <>
                  <span aria-hidden="true">✓ </span>
                  <span className="sr-only">(완료) </span>
                </>
              )}
              {index + 1}. {name}
            </li>
          ))}
        </ol>

        <section className={`${ui.card} flex flex-col gap-4`} aria-labelledby="perf-step-title">
          <h2 id="perf-step-title" ref={headingRef} tabIndex={-1} className={`${ui.sectionTitle} outline-none`}>
            {step + 1}단계 · {STEPS[step]}
          </h2>
          {body}
        </section>
      </div>
    </div>
  );
}
