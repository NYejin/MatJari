import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { getErrorStatus, performancesApi } from "../api/performances";
import { parseApiError } from "../api/errors";
import { compareLocalDateTime, formatKstDateTime, isPastKst } from "../api/dateTime";
import type { PerformanceDetail } from "../types/performance";
import { button, input, linkButton, liveRegionClass, ui } from "../components/ui";
import TextField from "../components/TextField";
import PosterImage from "../components/PosterImage";
import {
  EDIT_ERROR_MESSAGES,
  EDIT_REASON_MAX,
  EDIT_TITLE_MAX,
  EDIT_VENUE_MAX,
  editProposalsApi,
} from "../api/editProposals";
import { getErrorCode } from "../api/errors";
import SessionEditor, { checkSessionChanges, initialSessionRows, type SessionRow } from "../components/SessionEditor";
// FR-02 공연 상세 (보호 라우트 /performances/:id) — 읽기 전용.
// 공연은 등록 후 아무도 수정·삭제할 수 없다 (수정은 아래 "정보가 틀렸어요" 수정 제안을 관리자가 승인할 때만).
// 티켓 영역: 이 공연 티켓 등록(/tickets/new?performanceId=) 진입.

/** 백엔드 TicketingSite와 같은 알려진 예매처 호스트 (호스트가 이것이거나 그 하위 도메인) */
const KNOWN_TICKETING_HOSTS = ["interpark.com", "ticket.melon.com", "ticket.yes24.com", "ticketlink.co.kr"];

const cls = {
  infoList: "grid grid-cols-[auto_1fr] items-baseline gap-x-4 gap-y-2",
  term: "text-sm/[normal] font-semibold text-gray-700",
  desc: "min-w-0 text-[0.9375rem]/[normal] break-words text-gray-900",
  descSub: "block text-sm/[normal] text-gray-700",
  title: "text-2xl/[normal] font-bold break-keep text-gray-900",
  sessionList: "flex flex-col divide-y divide-gray-200",
  sessionRow: "flex flex-col gap-2 py-3",
  sessionMain: "flex flex-wrap items-center justify-between gap-2",
  /** 회차 시각 — 지난 회차는 gray-500(흰 배경 4.83:1)으로 흐리게 + "지난 회차" 배지 (색만으로 구분하지 않음) */
  sessionTimeUpcoming: "text-[0.9375rem]/[normal] font-semibold text-gray-900",
  sessionTimePast: "text-[0.9375rem]/[normal] text-gray-500",
} as const;

/** http(s) 링크만 링크로 렌더링 (javascript: 등 차단). 표시용 호스트와 알려진 예매처 여부 */
function parseTicketingLink(url: string): { href: string; host: string; known: boolean } | null {
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return null;
    const host = parsed.hostname.toLowerCase();
    const known = KNOWN_TICKETING_HOSTS.some((h) => host === h || host.endsWith(`.${h}`));
    return { href: parsed.href, host, known };
  } catch {
    return null;
  }
}

type LoadState =
  | { status: "loading" }
  | { status: "notFound" }
  | { status: "error"; message: string }
  | { status: "success"; detail: PerformanceDetail };

export default function PerformanceDetailPage() {
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const validId = Number.isInteger(id) && id > 0;
  const [state, setState] = useState<LoadState>(validId ? { status: "loading" } : { status: "notFound" });
  const [retryKey, setRetryKey] = useState(0);

  useEffect(() => {
    if (!validId) {
      setState({ status: "notFound" });
      return;
    }
    const controller = new AbortController();
    setState({ status: "loading" });
    performancesApi
      .get(id, controller.signal)
      .then((detail) => setState({ status: "success", detail }))
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        if (getErrorStatus(err) === 404) setState({ status: "notFound" });
        else setState({ status: "error", message: parseApiError(err, "공연 정보를 불러오지 못했습니다.").message });
      });
    return () => controller.abort();
  }, [id, validId, retryKey]);

  // 로딩 중 안내는 아래 상태 라이브 영역이 담당
  let content: ReactNode = null;
  if (state.status === "notFound") {
    content = (
      <div className={`${ui.card} flex flex-col items-start gap-3`}>
        <h1 className={ui.pageTitle}>공연을 찾을 수 없어요</h1>
        <p className={ui.body}>존재하지 않거나 잘못된 주소예요.</p>
        <Link to="/" className={linkButton.outline}>
          공연 목록으로
        </Link>
      </div>
    );
  } else if (state.status === "error") {
    content = (
      <div className={`${ui.card} flex flex-col gap-3`}>
        <p className={ui.errorBox} role="alert">
          {state.message}
        </p>
        <button type="button" className={button.solid} onClick={() => setRetryKey((k) => k + 1)}>
          다시 시도
        </button>
      </div>
    );
  } else if (state.status === "success") {
    const { detail } = state;
    content = (
      <>
        <InfoSection detail={detail} />
        <SessionsSection detail={detail} />
        <EditProposalSection detail={detail} />
        <section className={`${ui.card} flex flex-col gap-4`} aria-labelledby="perf-coming-title">
          <h2 id="perf-coming-title" className={ui.sectionTitle}>
            티켓
          </h2>
          <p className={ui.body}>이 공연의 티켓을 가지고 있다면 등록하고, 바꾸고 싶은 자리를 정해 상대를 찾아보세요.</p>
          <div className="flex flex-wrap gap-2">
            <Link to={`/tickets/new?performanceId=${detail.id}`} className={linkButton.solid}>
              이 공연 티켓 등록
            </Link>
            <Link to="/tickets" className={linkButton.outline}>
              내 티켓
            </Link>
          </div>
        </section>
      </>
    );
  }

  const loadingText = state.status === "loading" ? "공연 정보를 불러오는 중..." : "";
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

// ---- 공연 정보 (읽기 전용) ----

function InfoSection({ detail }: { detail: PerformanceDetail }) {
  const link = parseTicketingLink(detail.sourceUrl);
  // 포스터는 부가 정보 — 못 가져오면 조용히 없는 것으로 둔다(오류 표시·대체 링크 없음)
  const [posterUrl, setPosterUrl] = useState<string | null>(null);
  useEffect(() => {
    const controller = new AbortController();
    setPosterUrl(null);
    performancesApi
      .poster(detail.id, controller.signal)
      .then((url) => setPosterUrl(url))
      .catch(() => setPosterUrl(null));
    return () => controller.abort();
  }, [detail.id]);

  return (
    <section className={`${ui.card} flex flex-col gap-4 md:flex-row md:items-start md:justify-between`} aria-labelledby="perf-title">
      <div className="flex min-w-0 flex-1 flex-col gap-4">
      <h1 id="perf-title" className={cls.title}>
        {detail.title}
      </h1>

      <dl className={cls.infoList}>
        <dt className={cls.term}>공연장</dt>
        <dd className={cls.desc}>{detail.venueName}</dd>
        <dt className={cls.term}>티켓팅</dt>
        <dd className={cls.desc}>
          {link ? (
            <>
              <a href={link.href} target="_blank" rel="noopener noreferrer" className={ui.link}>
                {link.host}
                <span aria-hidden="true"> ↗</span>
                <span className="sr-only"> 티켓팅 페이지 (새 창에서 열림)</span>
              </a>
              {!link.known && <span className={cls.descSub}>공식 예매처가 아닐 수 있어요. 주소를 확인하고 열어 주세요.</span>}
            </>
          ) : (
            <span className="break-all">{detail.sourceUrl}</span>
          )}
        </dd>
      </dl>
      </div>
      <PosterImage key={posterUrl ?? "none"} url={posterUrl} title={detail.title} className="self-start" />
    </section>
  );
}

// ---- 회차 (읽기 전용) ----

function SessionsSection({ detail }: { detail: PerformanceDetail }) {
  const sessions = [...detail.sessions].sort((a, b) => compareLocalDateTime(a.startsAt, b.startsAt));

  return (
    <section className={`${ui.card} flex flex-col gap-4`} aria-labelledby="perf-sessions-title">
      <h2 id="perf-sessions-title" className={ui.sectionTitle}>
        회차 {sessions.length}개
      </h2>

      {sessions.length === 0 ? (
        <p className={ui.body}>등록된 회차가 없어요.</p>
      ) : (
        <ul className={cls.sessionList}>
          {sessions.map((session) => {
            const past = isPastKst(session.startsAt);
            return (
              <li key={session.id} className={cls.sessionRow}>
                <div className={cls.sessionMain}>
                  <span className="flex flex-wrap items-center gap-2">
                    <span className={past ? cls.sessionTimePast : cls.sessionTimeUpcoming}>
                      {formatKstDateTime(session.startsAt)}
                    </span>
                    {past && <span className={ui.badge}>지난 회차</span>}
                  </span>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

// ---- 수정 제안 ("정보가 틀렸어요", 접이식) ----
// 공연은 직접 수정할 수 없다. 제목·공연장·회차 중 바뀐 것만 적어 제안하면 관리자가 검토한다 (링크는 대상 아님).

function EditProposalSection({ detail }: { detail: PerformanceDetail }) {
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [venue, setVenue] = useState("");
  const [reason, setReason] = useState("");
  const [rows, setRows] = useState<SessionRow[]>(() => initialSessionRows(detail.sessions));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [done, setDone] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    const newTitle = title.trim();
    const newVenueName = venue.trim();
    const check = checkSessionChanges(rows);
    if (Object.keys(check.rowErrors).length > 0 || check.formError) {
      setError("회차 입력을 확인해 주세요.");
      return;
    }
    if (!newTitle && !newVenueName && check.changes.length === 0) {
      setError("바뀐 제목, 공연장, 회차 중 하나는 적어 주세요.");
      return;
    }
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      await editProposalsApi.create(detail.id, {
        newTitle: newTitle || null,
        newVenueName: newVenueName || null,
        reason: reason.trim() || null,
        ...(check.changes.length > 0 ? { sessionChanges: check.changes } : {}),
      });
      setDone(true);
      setOpen(false);
      setTitle("");
      setVenue("");
      setReason("");
      setRows(initialSessionRows(detail.sessions));
    } catch (err) {
      const code = getErrorCode(err);
      const parsed = parseApiError(err, "제안을 보내지 못했습니다.");
      setFieldErrors(parsed.fieldErrors);
      setError((code && EDIT_ERROR_MESSAGES[code]) || parsed.fieldErrors.sessionChanges || parsed.message);
    } finally {
      setBusy(false);
    }
  };

  const sessionCheck = checkSessionChanges(rows);
  const panelId = "edit-proposal-panel";
  return (
    <section className={`${ui.card} flex flex-col gap-3`} aria-labelledby="edit-proposal-title">
      <h2 id="edit-proposal-title" className={ui.sectionTitle}>
        정보가 틀렸어요
      </h2>
      <p className={liveRegionClass(done ? "done" : "", ui.notice)} role="status">
        {done && (
          <>
            제안을 보냈어요. 관리자가 확인한 뒤 반영해요.{" "}
            <Link to="/my/edit-proposals" className={ui.link}>
              내 수정 제안 보기
            </Link>
          </>
        )}
      </p>
      <button
        type="button"
        className={`${button.outline} self-start`}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => {
          setOpen((o) => !o);
          setDone(false);
        }}
      >
        {open ? "닫기" : "수정 제안하기"}
      </button>

      {open && (
        <form id={panelId} onSubmit={(e) => void submit(e)} className="flex flex-col gap-4" noValidate>
          <p className={ui.hint}>공연 정보는 직접 고칠 수 없어요. 제목·공연장·회차 중 바뀐 것만 적어 제안해 주세요.</p>
          <p className={`${ui.body} break-words`}>
            현재 제목: <strong>{detail.title}</strong>
            <br />
            현재 공연장: <strong>{detail.venueName}</strong>
          </p>
          <TextField
            id="edit-new-title"
            label="새 제목 (선택)"
            value={title}
            maxLength={EDIT_TITLE_MAX}
            error={fieldErrors.newTitle}
            onChange={(e) => setTitle(e.target.value)}
          />
          <TextField
            id="edit-new-venue"
            label="새 공연장 (선택)"
            value={venue}
            maxLength={EDIT_VENUE_MAX}
            error={fieldErrors.newVenueName}
            onChange={(e) => setVenue(e.target.value)}
          />
          <SessionEditor
            rows={rows}
            onChange={setRows}
            rowErrors={sessionCheck.rowErrors}
            formError={sessionCheck.formError}
            disabled={busy}
          />
          <div className="flex flex-col gap-1.5">
            <label htmlFor="edit-reason" className={ui.label}>
              사유 (선택)
            </label>
            <textarea
              id="edit-reason"
              className={`${input.normal} py-2`}
              rows={3}
              maxLength={EDIT_REASON_MAX}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
            <p className={ui.hint}>
              {reason.length}/{EDIT_REASON_MAX}
            </p>
          </div>
          {error && (
            <p className={ui.errorBox} role="alert">
              {error}
            </p>
          )}
          <button type="submit" className={`${button.solid} self-start`} disabled={busy}>
            {busy ? "보내는 중..." : "제안 보내기"}
          </button>
        </form>
      )}
    </section>
  );
}
