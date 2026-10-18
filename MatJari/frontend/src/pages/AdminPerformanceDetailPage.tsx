import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { getErrorCode, parseApiError } from "../api/errors";
import { getErrorStatus } from "../api/performances";
import { EDIT_TITLE_MAX, EDIT_VENUE_MAX } from "../api/editProposals";
import {
  ADMIN_PERFORMANCE_CODE_MESSAGES,
  ADMIN_REASON_MAX,
  adminPerformancesApi,
  type AdminPerformanceDetail,
} from "../api/adminPerformances";
import { toPayloadZones, toZoneRows, validateZoneRows, type ZoneRow } from "../api/zones";
import SessionEditor, { checkSessionChanges, initialSessionRows, type SessionRow } from "../components/SessionEditor";
import TextField from "../components/TextField";
import ZoneEditor from "../components/ZoneEditor";
import { button, input, linkButton, liveRegionClass, ui } from "../components/ui";

// 관리자 공연 관리 상세 (/admin/performances/:id): 정보 수정·숨김·회차·구역. 모든 변경은 사유가 필요하다.

type LoadState =
  | { status: "loading" }
  | { status: "notFound" }
  | { status: "error"; message: string }
  | { status: "success"; detail: AdminPerformanceDetail };

function errorText(err: unknown, fallback: string): string {
  const code = getErrorCode(err);
  const parsed = parseApiError(err, fallback);
  return (code && ADMIN_PERFORMANCE_CODE_MESSAGES[code]) || parsed.fieldErrors.reason || parsed.message;
}

export default function AdminPerformanceDetailPage() {
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const validId = Number.isInteger(id) && id > 0;
  const [state, setState] = useState<LoadState>(validId ? { status: "loading" } : { status: "notFound" });
  const [retryKey, setRetryKey] = useState(0);

  useEffect(() => {
    if (!validId) return;
    const controller = new AbortController();
    adminPerformancesApi
      .get(id, controller.signal)
      .then((detail) => setState({ status: "success", detail }))
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        if (getErrorStatus(err) === 404) setState({ status: "notFound" });
        else setState({ status: "error", message: parseApiError(err, "공연 정보를 불러오지 못했습니다.").message });
      });
    return () => controller.abort();
  }, [id, validId, retryKey]);

  const updated = (detail: AdminPerformanceDetail) => setState({ status: "success", detail });

  let content: ReactNode = null;
  if (state.status === "loading") {
    content = (
      <p className={ui.status} role="status">
        불러오는 중...
      </p>
    );
  } else if (state.status === "notFound") {
    content = (
      <div className={`${ui.card} flex flex-col items-start gap-3`}>
        <p className={ui.body}>공연을 찾을 수 없어요.</p>
        <Link to="/admin/performances" className={linkButton.outline}>
          공연 관리 목록
        </Link>
      </div>
    );
  } else if (state.status === "error") {
    content = (
      <div className="flex flex-col gap-3">
        <p className={ui.errorBox} role="alert">
          {state.message}
        </p>
        <div>
          <button
            type="button"
            className={button.solid}
            onClick={() => {
              setState({ status: "loading" });
              setRetryKey((k) => k + 1);
            }}
          >
            다시 시도
          </button>
        </div>
      </div>
    );
  } else {
    const d = state.detail;
    content = (
      <>
        <div className="flex flex-wrap items-center gap-2">
          <h1 className={`${ui.pageTitle} min-w-0 break-words`}>{d.title}</h1>
          {d.hidden && <span className={ui.badge}>숨김</span>}
        </div>
        <p className={ui.muted}>
          등록 {d.registrant.nickname} · {formatKstDateTime(d.createdAt)} ·{" "}
          <Link to={`/performances/${d.id}`} className={ui.link}>
            사용자 화면에서 보기
          </Link>
        </p>
        <InfoCard detail={d} onUpdated={updated} />
        <HideCard detail={d} onUpdated={updated} />
        <SessionsCard detail={d} onUpdated={updated} />
        <ZonesCard detail={d} onUpdated={updated} />
      </>
    );
  }

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <Link to="/admin/performances" className={ui.link}>
          ← 공연 관리 목록
        </Link>
        {content}
      </div>
    </div>
  );
}

// ---- 공용 조각 (모듈 최상위 컴포넌트) ----

function ReasonField({
  id,
  value,
  onChange,
  required = true,
  disabled,
  error,
}: {
  id: string;
  value: string;
  onChange: (v: string) => void;
  required?: boolean;
  disabled?: boolean;
  error?: string;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className={ui.label}>
        사유 {required ? "(필수)" : "(선택)"}
      </label>
      <textarea
        id={id}
        className={`${error ? input.invalid : input.normal} py-2`}
        rows={2}
        maxLength={ADMIN_REASON_MAX}
        value={value}
        disabled={disabled}
        aria-invalid={!!error}
        onChange={(e) => onChange(e.target.value)}
      />
      {error ? (
        <p className={ui.fieldError} role="alert">
          {error}
        </p>
      ) : (
        <p className={ui.hint}>
          조치 기록에 남아요. {value.length}/{ADMIN_REASON_MAX}
        </p>
      )}
    </div>
  );
}

function SectionCard({ titleId, title, children }: { titleId: string; title: string; children: ReactNode }) {
  return (
    <section className={`${ui.card} flex flex-col gap-4`} aria-labelledby={titleId}>
      <h2 id={titleId} className={ui.sectionTitle}>
        {title}
      </h2>
      {children}
    </section>
  );
}

function Feedback({ done, error }: { done: string; error: string | null }) {
  return (
    <>
      <p className={liveRegionClass(done, ui.notice)} role="status">
        {done}
      </p>
      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}
    </>
  );
}

interface CardProps {
  detail: AdminPerformanceDetail;
  onUpdated: (detail: AdminPerformanceDetail) => void;
}

// ---- ① 공연 정보 ----

function InfoCard({ detail, onUpdated }: CardProps) {
  const [title, setTitle] = useState(detail.title);
  const [venue, setVenue] = useState(detail.venueName);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [reasonError, setReasonError] = useState<string>();
  const [done, setDone] = useState("");

  const newTitle = title.trim();
  const newVenue = venue.trim();
  const titleChanged = newTitle !== detail.title;
  const venueChanged = newVenue !== detail.venueName;

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busyRef.current) return;
    setDone("");
    setError(null);
    if (!newTitle || !newVenue) {
      setError("제목과 공연장은 비울 수 없어요.");
      return;
    }
    if (!titleChanged && !venueChanged) {
      setError("바뀐 내용이 없어요.");
      return;
    }
    if (!reason.trim()) {
      setReasonError("사유를 입력해 주세요.");
      return;
    }
    setReasonError(undefined);
    busyRef.current = true;
    setBusy(true);
    try {
      const saved = await adminPerformancesApi.edit(detail.id, {
        ...(titleChanged ? { title: newTitle } : {}),
        ...(venueChanged ? { venueName: newVenue } : {}),
        reason: reason.trim(),
      });
      setTitle(saved.title);
      setVenue(saved.venueName);
      setReason("");
      setDone("공연 정보를 저장했어요.");
      onUpdated(saved);
    } catch (err) {
      setError(errorText(err, "저장하지 못했습니다."));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <SectionCard titleId="admin-perf-info" title="공연 정보">
      <form className="flex flex-col gap-4" onSubmit={(e) => void submit(e)} noValidate>
        <TextField id="admin-perf-title" label="제목" value={title} maxLength={EDIT_TITLE_MAX} disabled={busy} onChange={(e) => setTitle(e.target.value)} />
        <TextField id="admin-perf-venue" label="공연장" value={venue} maxLength={EDIT_VENUE_MAX} disabled={busy} onChange={(e) => setVenue(e.target.value)} />
        <ReasonField id="admin-perf-info-reason" value={reason} onChange={setReason} disabled={busy} error={reasonError} />
        <Feedback done={done} error={error} />
        <button type="submit" className={`${button.solid} self-start`} disabled={busy} aria-busy={busy}>
          {busy ? "저장 중..." : "정보 저장"}
        </button>
      </form>
    </SectionCard>
  );
}

// ---- ② 숨김 ----

function HideCard({ detail, onUpdated }: CardProps) {
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string>();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState("");

  const run = async (action: () => Promise<void>, doneText: string, fallback: string) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    setDone("");
    try {
      await action();
      const fresh = await adminPerformancesApi.get(detail.id);
      setReason("");
      setConfirming(false);
      setDone(doneText);
      onUpdated(fresh);
    } catch (err) {
      setError(errorText(err, fallback));
      setConfirming(false);
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  const askHide = () => {
    if (!reason.trim()) {
      setReasonError("숨기는 사유를 입력해 주세요.");
      return;
    }
    setReasonError(undefined);
    setConfirming(true);
  };

  return (
    <SectionCard titleId="admin-perf-hide" title="노출 상태">
      {detail.hidden ? (
        <>
          <p className={ui.warning}>
            숨겨진 공연이에요. 목록·검색에서 보이지 않고, 새 티켓 등록·새 교환 요청·새 제안이 막혀 있어요. 진행 중인 매칭과 채팅은 그대로예요.
          </p>
          <p className={`${ui.body} break-words`}>
            {detail.hiddenAt && <>{formatKstDateTime(detail.hiddenAt)} · </>}
            {detail.hiddenByNickname ?? "관리자"}
            {detail.hiddenReason && <> · 사유: {detail.hiddenReason}</>}
          </p>
          <ReasonField id="admin-perf-unhide-reason" value={reason} onChange={setReason} required={false} disabled={busy} />
          <Feedback done={done} error={error} />
          <button
            type="button"
            className={`${button.solid} self-start`}
            disabled={busy}
            aria-busy={busy}
            onClick={() => void run(() => adminPerformancesApi.unhide(detail.id, reason.trim()), "공연을 다시 올렸어요.", "다시 올리지 못했습니다.")}
          >
            {busy ? "처리 중..." : "다시 올리기"}
          </button>
        </>
      ) : (
        <>
          <p className={ui.body}>
            공연을 숨기면 목록·검색에서 빠지고 새 티켓 등록·새 교환 요청·새 제안이 막혀요. 진행 중인 매칭과 채팅은 유지되고, 나중에 다시 올릴 수 있어요.
          </p>
          <ReasonField id="admin-perf-hide-reason" value={reason} onChange={setReason} disabled={busy || confirming} error={reasonError} />
          <Feedback done={done} error={error} />
          {!confirming ? (
            <button type="button" className={`${button.dangerOutline} self-start`} disabled={busy} onClick={askHide}>
              공연 숨기기
            </button>
          ) : (
            <div className="flex flex-col gap-3">
              <p className={ui.warning} role="alert">
                「{detail.title}」을(를) 숨길까요? 사용자는 새로 티켓을 등록하거나 교환을 요청할 수 없게 돼요.
              </p>
              <div className="flex flex-wrap justify-end gap-2">
                <button type="button" className={button.outline} disabled={busy} onClick={() => setConfirming(false)}>
                  돌아가기
                </button>
                <button
                  type="button"
                  className={button.danger}
                  disabled={busy}
                  aria-busy={busy}
                  onClick={() => void run(() => adminPerformancesApi.hide(detail.id, reason.trim()), "공연을 숨겼어요.", "숨기지 못했습니다.")}
                >
                  {busy ? "처리 중..." : "숨기기 확정"}
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </SectionCard>
  );
}

// ---- ③ 회차 ----

function SessionsCard({ detail, onUpdated }: CardProps) {
  const [rows, setRows] = useState<SessionRow[]>(() => initialSessionRows(detail.sessions));
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState("");

  const lockedIds = new Set(detail.sessions.filter((s) => s.inUse).map((s) => s.id));
  const check = checkSessionChanges(rows);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busyRef.current) return;
    setDone("");
    setError(null);
    if (Object.keys(check.rowErrors).length > 0 || check.formError) {
      setError("회차 입력을 확인해 주세요.");
      return;
    }
    if (check.changes.length === 0) {
      setError("바뀐 회차가 없어요.");
      return;
    }
    if (!reason.trim()) {
      setReasonError("사유를 입력해 주세요.");
      return;
    }
    setReasonError(undefined);
    busyRef.current = true;
    setBusy(true);
    try {
      const saved = await adminPerformancesApi.changeSessions(detail.id, check.changes, reason.trim());
      setRows(initialSessionRows(saved.sessions));
      setReason("");
      setDone("회차를 저장했어요.");
      onUpdated(saved);
    } catch (err) {
      setError(errorText(err, "회차를 저장하지 못했습니다."));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <SectionCard titleId="admin-perf-sessions" title={`회차 ${detail.sessions.length}개`}>
      <form className="flex flex-col gap-4" onSubmit={(e) => void submit(e)} noValidate>
        <SessionEditor
          rows={rows}
          onChange={setRows}
          rowErrors={check.rowErrors}
          formError={check.formError}
          disabled={busy}
          lockedIds={lockedIds}
          title="회차 수정"
          hint="시각을 바꾸면 변경, '삭제'를 누르면 삭제, 새 회차는 '회차 추가'로 넣어요. 10분 단위이고 지난 시각은 쓸 수 없어요. 삭제 뒤에도 회차는 1개 이상 남아야 해요."
        />
        <ReasonField id="admin-perf-sessions-reason" value={reason} onChange={setReason} disabled={busy} error={reasonError} />
        <Feedback done={done} error={error} />
        <button type="submit" className={`${button.solid} self-start`} disabled={busy} aria-busy={busy}>
          {busy ? "저장 중..." : "회차 저장"}
        </button>
      </form>
    </SectionCard>
  );
}

// ---- ④ 구역 ----

function ZonesCard({ detail, onUpdated }: CardProps) {
  const [rows, setRows] = useState<ZoneRow[]>(() => toZoneRows(detail.zones));
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState("");

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busyRef.current) return;
    setDone("");
    setError(null);
    if (Object.keys(validateZoneRows(rows)).length > 0) {
      setError("구역 이름을 확인해 주세요.");
      return;
    }
    if (!reason.trim()) {
      setReasonError("사유를 입력해 주세요.");
      return;
    }
    setReasonError(undefined);
    busyRef.current = true;
    setBusy(true);
    try {
      const saved = await adminPerformancesApi.replaceZones(detail.id, {
        zones: toPayloadZones(rows),
        seatmapAspect: detail.seatmapAspect,
        reason: reason.trim(),
      });
      setRows(toZoneRows(saved.zones));
      setReason("");
      setDone("구역을 저장했어요.");
      onUpdated(saved);
    } catch (err) {
      setError(errorText(err, "구역을 저장하지 못했습니다."));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <SectionCard titleId="admin-perf-zones" title={`구역 ${detail.zones.length}개`}>
      <form className="flex flex-col gap-4" onSubmit={(e) => void submit(e)} noValidate>
        <p className={ui.hint}>
          저장하면 구역 목록 전체가 지금 화면의 목록으로 바뀌어요. 이름을 고쳐도 모양은 유지되고, 구역을 지우면 모양도 사라져요. 모두 지우면 구역이 없는 공연이 돼요.
        </p>
        <ZoneEditor rows={rows} aspect={detail.seatmapAspect} onChange={setRows} disabled={busy} />
        <ReasonField id="admin-perf-zones-reason" value={reason} onChange={setReason} disabled={busy} error={reasonError} />
        <Feedback done={done} error={error} />
        <button type="submit" className={`${button.solid} self-start`} disabled={busy} aria-busy={busy}>
          {busy ? "저장 중..." : "구역 저장"}
        </button>
      </form>
    </SectionCard>
  );
}
