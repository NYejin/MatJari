import { useEffect, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { getErrorCode, parseApiError } from "../api/errors";
import { getErrorStatus } from "../api/performances";
import { EDIT_TITLE_MAX, EDIT_VENUE_MAX } from "../api/editProposals";
import {
  ADMIN_PERFORMANCE_CODE_MESSAGES,
  ADMIN_REASON_MAX,
  adminPerformancesApi,
  type AdminExchangePage,
  type AdminExchangeSide,
  type AdminPerformanceDetail,
  type MatchStatus,
} from "../api/adminPerformances";
import { toPayloadZones, toZoneRows, validateZoneRows, type ZoneRow } from "../api/zones";
import SessionEditor, { checkSessionChanges, initialSessionRows, type SessionRow } from "../components/SessionEditor";
import TextField from "../components/TextField";
import ZoneEditor from "../components/ZoneEditor";
import SeatmapUpload from "../components/SeatmapUpload";
import type { AiExtractResult } from "../types/performance";
import { button, input, linkButton, liveRegionClass, tbl, tone, ui } from "../components/adminUi";

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
      <div className="flex flex-col gap-1.5">
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
        {/* 넓은 화면에서는 정보·숨김 / 회차 / 구역을 나란히 둔다 */}
        <div className="grid grid-cols-1 items-start gap-2 xl:grid-cols-3">
          <div className="flex min-w-0 flex-col gap-2">
            <InfoCard detail={d} onUpdated={updated} />
            <HideCard detail={d} onUpdated={updated} />
            {d.hidden && <DeleteCard detail={d} />}
          </div>
          <SessionsCard detail={d} onUpdated={updated} />
          <ZonesCard detail={d} onUpdated={updated} />
        </div>
        <ExchangesCard performanceId={d.id} />
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
    <section className={`${ui.card} flex flex-col gap-2`} aria-labelledby={titleId}>
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
      <form className="flex flex-col gap-2" onSubmit={(e) => void submit(e)} noValidate>
        <TextField dense id="admin-perf-title" label="제목" value={title} maxLength={EDIT_TITLE_MAX} disabled={busy} onChange={(e) => setTitle(e.target.value)} />
        <TextField dense id="admin-perf-venue" label="공연장" value={venue} maxLength={EDIT_VENUE_MAX} disabled={busy} onChange={(e) => setVenue(e.target.value)} />
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

/** 숨긴 공연 완전 삭제: 티켓·교환 희망 회차·좌석 인증·교환 이력이 하나도 없을 때만 서버가 허락한다. 확인 문구 입력 필요 */
function DeleteCard({ detail }: { detail: CardProps["detail"] }) {
  const navigate = useNavigate();
  const [reason, setReason] = useState("");
  const [confirmText, setConfirmText] = useState("");
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const ready = reason.trim().length > 0 && confirmText.trim() === "삭제";

  const remove = async () => {
    if (busyRef.current || !ready) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await adminPerformancesApi.remove(detail.id, reason.trim());
      navigate("/admin/performances", { replace: true });
    } catch (err) {
      setError(errorText(err, "삭제하지 못했습니다."));
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <SectionCard titleId="admin-perf-delete" title="공연 삭제">
      <p className={ui.warning}>
        완전히 지우고 되돌릴 수 없어요. 회차·구역·공연 수정 제안이 함께 지워지고, 티켓·교환 희망 조건·좌석 인증·교환 이력이 하나라도 있으면 삭제되지 않아요(그때는 숨김 상태로 두세요).
      </p>
      <ReasonField id="admin-perf-delete-reason" value={reason} onChange={setReason} disabled={busy} />
      <div className="flex flex-col gap-0.5">
        <label htmlFor="admin-perf-delete-confirm" className={ui.label}>
          확인 문구: "삭제"를 입력하세요
        </label>
        <input
          id="admin-perf-delete-confirm"
          className={input.normal}
          value={confirmText}
          disabled={busy}
          autoComplete="off"
          onChange={(e) => setConfirmText(e.target.value)}
        />
      </div>
      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}
      <button type="button" className={`${button.danger} self-start`} disabled={busy || !ready} aria-busy={busy} onClick={() => void remove()}>
        {busy ? "삭제 중..." : "공연 완전 삭제"}
      </button>
    </SectionCard>
  );
}

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
            <div className="flex flex-col gap-1.5">
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
      <form className="flex flex-col gap-2" onSubmit={(e) => void submit(e)} noValidate>
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
  const [aspect, setAspect] = useState<number | null>(detail.seatmapAspect);
  const [aiBusy, setAiBusy] = useState<"link" | "image" | null>(null);
  const [aiNote, setAiNote] = useState("");

  /** 다시 인식: 결과로 편집 목록을 통째로 바꾼다(저장은 아래 사유 + 구역 저장으로만). 월 횟수는 세지 않는다 */
  const recognize = async (kind: "link" | "image", run: () => Promise<AiExtractResult>) => {
    if (aiBusy || busyRef.current) return;
    setAiBusy(kind);
    setAiNote("");
    setError(null);
    setDone("");
    try {
      const r = await run();
      const zones = r.zones ?? [];
      if (zones.length === 0) {
        setAiNote("구역을 읽지 못했어요. 기존 목록은 그대로예요.");
        return;
      }
      setRows(toZoneRows(zones));
      setAspect(typeof r.seatmapAspect === "number" && r.seatmapAspect > 0 ? r.seatmapAspect : null);
      const shaped = zones.filter((z) => z.shapePolygons && z.shapePolygons.length > 0).length;
      setAiNote(`구역 ${zones.length}개(모양 ${shaped}개)를 읽어 아래 목록을 바꿨어요. 아직 저장 전이에요 — 지도를 확인하고 사유를 적어 저장하세요.`);
    } catch (err) {
      setError(errorText(err, "구역을 다시 인식하지 못했습니다."));
    } finally {
      setAiBusy(null);
    }
  };
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
        seatmapAspect: aspect,
        reason: reason.trim(),
      });
      setRows(toZoneRows(saved.zones));
      setAspect(saved.seatmapAspect);
      setAiNote("");
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
      <form className="flex flex-col gap-2" onSubmit={(e) => void submit(e)} noValidate>
        <p className={ui.hint}>
          저장하면 구역 목록 전체가 지금 화면의 목록으로 바뀌어요. 이름을 고쳐도 모양은 유지되고, 구역을 지우면 모양도 사라져요. 모두 지우면 구역이 없는 공연이 돼요.
        </p>
        <div className="flex flex-wrap items-start gap-2">
          <button
            type="button"
            className={button.outline}
            disabled={aiBusy !== null || busy}
            aria-busy={aiBusy === "link"}
            onClick={() => void recognize("link", () => adminPerformancesApi.reextractZones(detail.id))}
          >
            {aiBusy === "link" ? "링크에서 읽는 중..." : "링크에서 다시 인식"}
          </button>
          <SeatmapUpload
            buttonClass={button.outline}
            errorClass={ui.fieldError}
            label="좌석표 이미지로 인식"
            busy={aiBusy === "image"}
            disabled={(aiBusy !== null && aiBusy !== "image") || busy}
            onFile={(f) => void recognize("image", () => adminPerformancesApi.seatmapZones(detail.id, f))}
          />
          <p className={`${ui.hint} basis-full`}>
            AI로 다시 읽어 아래 목록을 바꿔요(보관된 결과를 쓰지 않고 새로 읽음, 월 횟수 없음, 하루 전체 상한은 적용). 저장하기 전에는 공연에 반영되지 않아요.
          </p>
          {aiNote && (
            <p className={`${ui.notice} basis-full`} role="status">
              {aiNote}
            </p>
          )}
        </div>
        <ZoneEditor rows={rows} aspect={aspect} onChange={setRows} disabled={busy || aiBusy !== null} />
        <ReasonField id="admin-perf-zones-reason" value={reason} onChange={setReason} disabled={busy} error={reasonError} />
        <Feedback done={done} error={error} />
        <button type="submit" className={`${button.solid} self-start`} disabled={busy} aria-busy={busy}>
          {busy ? "저장 중..." : "구역 저장"}
        </button>
      </form>
    </SectionCard>
  );
}

// ---- ⑤ 교환 목록 ----

const MATCH_STATUS_LABEL: Record<MatchStatus, string> = {
  CHATTING: "채팅 중",
  RESERVED: "예약 중",
  COMPLETED: "교환 완료",
  CANCELED: "종료",
};
const MATCH_STATUS_TONE: Record<MatchStatus, string> = {
  CHATTING: tone.info,
  RESERVED: tone.warn,
  COMPLETED: tone.ok,
  CANCELED: tone.mute,
};
const MATCH_FILTERS: (MatchStatus | "ALL")[] = ["ALL", "CHATTING", "RESERVED", "COMPLETED", "CANCELED"];

function SideCell({ side }: { side: AdminExchangeSide }) {
  return (
    <>
      <Link to={`/admin/users/${side.userId}`} className={ui.link}>
        {side.nickname}
      </Link>
      <div className="whitespace-nowrap">
        {side.zone} {side.row}열 {side.col}번
      </div>
      <div className={`${ui.muted} whitespace-nowrap`}>
        {formatKstDateTime(side.sessionStartsAt)}
        {side.completedAt && " · 수락함"}
      </div>
    </>
  );
}

function ExchangesCard({ performanceId }: { performanceId: number }) {
  const [status, setStatus] = useState<MatchStatus | "ALL">("ALL");
  const [page, setPage] = useState(0);
  const [data, setData] = useState<AdminExchangePage | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setError(null);
    adminPerformancesApi
      .exchanges(performanceId, { status, page }, controller.signal)
      .then(setData)
      .catch((err: unknown) => {
        if (!controller.signal.aborted) setError(parseApiError(err, "교환 목록을 불러오지 못했습니다.").message);
      });
    return () => controller.abort();
  }, [performanceId, status, page]);

  const counts = data?.statusCounts;
  const total = counts ? Object.values(counts).reduce((a, b) => a + b, 0) : 0;
  const p = data?.page;

  return (
    <SectionCard titleId="admin-perf-exchanges" title={`교환 ${total}건`}>
      <div className="flex flex-wrap items-center gap-1" role="group" aria-label="교환 상태 필터">
        {MATCH_FILTERS.map((f) => (
          <button
            key={f}
            type="button"
            className={status === f ? button.solid : button.outline}
            aria-pressed={status === f}
            onClick={() => {
              setStatus(f);
              setPage(0);
            }}
          >
            {f === "ALL" ? `전체 ${total}` : `${MATCH_STATUS_LABEL[f]} ${counts?.[f] ?? 0}`}
          </button>
        ))}
        <span className={`${ui.hint} ml-1`}>최근 변경순 · 좌석은 교환 전 자리 · A는 채팅을 연 쪽</span>
      </div>
      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}
      {!data && !error && (
        <p className={ui.status} role="status">
          불러오는 중...
        </p>
      )}
      {p && p.content.length === 0 && <p className={ui.muted}>교환 기록이 없어요.</p>}
      {p && p.content.length > 0 && (
        <div className={tbl.wrap}>
          <table className={tbl.table}>
            <thead>
              <tr>
                <th className={tbl.th}>#</th>
                <th className={tbl.th}>상태</th>
                <th className={tbl.th}>A</th>
                <th className={tbl.th}>B</th>
                <th className={tbl.th}>메시지</th>
                <th className={tbl.th}>시작</th>
                <th className={tbl.th}>예약</th>
                <th className={tbl.th}>종료</th>
                <th className={tbl.th}>최근 변경</th>
              </tr>
            </thead>
            <tbody>
              {p.content.map((m) => (
                <tr key={m.matchId} className={tbl.tr}>
                  <td className={tbl.num}>{m.matchId}</td>
                  <td className={tbl.td}>
                    <span className={MATCH_STATUS_TONE[m.status]}>{MATCH_STATUS_LABEL[m.status]}</span>
                  </td>
                  <td className={tbl.td}>
                    <SideCell side={m.a} />
                  </td>
                  <td className={tbl.td}>
                    <SideCell side={m.b} />
                  </td>
                  <td className={tbl.num}>{m.messageCount}</td>
                  <td className={`${tbl.td} whitespace-nowrap`}>{formatKstDateTime(m.createdAt)}</td>
                  <td className={`${tbl.td} whitespace-nowrap`}>{m.reservedAt ? formatKstDateTime(m.reservedAt) : "-"}</td>
                  <td className={`${tbl.td} whitespace-nowrap`}>{m.canceledAt ? formatKstDateTime(m.canceledAt) : "-"}</td>
                  <td className={`${tbl.td} whitespace-nowrap`}>{formatKstDateTime(m.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {p && p.totalPages > 1 && (
        <div className="flex items-center gap-2">
          <button type="button" className={button.outline} disabled={page <= 0} onClick={() => setPage((x) => x - 1)}>
            이전
          </button>
          <span className={ui.muted}>
            {page + 1} / {p.totalPages}
          </span>
          <button type="button" className={button.outline} disabled={page + 1 >= p.totalPages} onClick={() => setPage((x) => x + 1)}>
            다음
          </button>
        </div>
      )}
    </SectionCard>
  );
}
