import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import {
  ADMIN_REPORT_CODE_MESSAGES,
  REPORT_NOTE_MAX,
  REPORT_REASON_LABEL,
  REPORT_STATUS_LABEL,
  SANCTION_NOTE_MAX,
  adminReportsApi,
  formatUntil,
  type AdminReport,
  type ReportMessage,
} from "../api/adminReports";
import { button, input, ui } from "../components/adminUi";

// 관리자 신고 상세·처리 (/admin/reports/:id). 채팅 근거 메시지는 최신부터 불러오고 '이전 메시지 더 보기'로 위로 확장한다.

type DaysChoice = "7" | "30" | "forever";
type Mode = "idle" | "confirmResolve" | "confirmDismiss";

const DAYS_OPTIONS: { value: DaysChoice; label: string }[] = [
  { value: "7", label: "7일" },
  { value: "30", label: "30일" },
  { value: "forever", label: "무기한" },
];

export default function AdminReportDetailPage() {
  const { id } = useParams();
  const reportId = Number(id);
  const [report, setReport] = useState<AdminReport | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const load = useCallback(
    async (signal?: AbortSignal) => {
      try {
        const r = await adminReportsApi.get(reportId, signal);
        if (signal?.aborted) return;
        setReport(r);
        setLoadError(null);
      } catch (err) {
        if (signal?.aborted) return;
        setLoadError(exchangeErrorMessage(err, "신고를 불러오지 못했습니다.").message);
      }
    },
    [reportId]
  );

  useEffect(() => {
    if (!Number.isInteger(reportId) || reportId <= 0) return;
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load, reportId]);

  if (!Number.isInteger(reportId) || reportId <= 0) {
    return (
      <div className={ui.page}>
        <div className={ui.container}>
          <p className={ui.errorBox} role="alert">
            잘못된 신고 주소예요.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <div>
          <Link to="/admin/reports" className={ui.link}>
            ← 신고 목록
          </Link>
        </div>
        <h1 className={ui.pageTitle}>신고 상세</h1>

        {loadError && (
          <div className="flex flex-col gap-1.5">
            <p className={ui.errorBox} role="alert">
              {loadError}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={() => void load()}>
                다시 시도
              </button>
            </div>
          </div>
        )}
        {!report && !loadError && (
          <p className={ui.status} role="status">
            불러오는 중...
          </p>
        )}
        {report && (
          <div className="grid grid-cols-1 items-start gap-2 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
           <div className="flex min-w-0 flex-col gap-2">
            <ReportSummary report={report} />
            {report.status === "OPEN" ? (
              <ProcessForm report={report} onDone={setReport} onStale={() => void load()} />
            ) : (
              <section className={`${ui.card} flex flex-col gap-1.5`} aria-label="처리 결과">
                <h2 className={ui.sectionTitle}>처리 결과</h2>
                <p className={ui.body}>
                  {REPORT_STATUS_LABEL[report.status]} · {report.handlerNickname ?? "관리자"}
                  {report.handledAt && ` · ${formatKstDateTime(report.handledAt)}`}
                </p>
                <p className={ui.body}>메모: {report.handlerNote || "없음"}</p>
              </section>
            )}
           </div>
            <Evidence reportId={report.id} />
          </div>
        )}
      </div>
    </div>
  );
}

function ReportSummary({ report: r }: { report: AdminReport }) {
  return (
    <section className={`${ui.card} flex flex-col gap-2`} aria-label="신고 내용">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className={ui.sectionTitle}>{REPORT_REASON_LABEL[r.reason]}</h2>
        <span className={ui.badge}>{REPORT_STATUS_LABEL[r.status]}</span>
      </div>
      <p className={ui.body}>
        신고자 {r.reporter.nickname} → 대상 <span className="font-semibold">{r.reported.nickname}</span>
      </p>
      <p className={ui.muted}>
        {formatKstDateTime(r.createdAt)} · 매칭 #{r.matchId}
      </p>
      <p className={ui.body}>상세: {r.detail || "없음"}</p>
      <p className={ui.muted}>
        대상의 누적 신고 {r.reported.totalReports}건 · 열린 신고 {r.reported.openReports}건
      </p>
      {r.reported.activeSanction && <p className={ui.errorBox}>이용 정지 중 ({formatUntil(r.reported.activeSanction.until)})</p>}
    </section>
  );
}

function Evidence({ reportId }: { reportId: number }) {
  // 오래된 것부터 위에서 아래로 표시한다 (서버는 최신부터 내려주므로 id 오름차순으로 정렬해 합친다)
  const [messages, setMessages] = useState<ReportMessage[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const loadingRef = useRef(false);

  const fetchMore = useCallback(
    async (before: number | undefined, signal?: AbortSignal) => {
      if (loadingRef.current) return;
      loadingRef.current = true;
      setLoading(true);
      setError(null);
      try {
        const res = await adminReportsApi.messages(reportId, before, signal);
        if (signal?.aborted) return;
        setMessages((prev) => {
          const seen = new Set(prev.map((m) => m.id));
          return [...res.messages.filter((m) => !seen.has(m.id)), ...prev].sort((a, b) => a.id - b.id);
        });
        setHasMore(res.hasMore);
      } catch (err) {
        if (signal?.aborted) return;
        setError(exchangeErrorMessage(err, "채팅 근거를 불러오지 못했습니다.").message);
      } finally {
        // 중단된 요청(StrictMode 재마운트 등)은 가드·로딩 상태를 건드리지 않는다(다음 요청이 이어받는다)
        if (!signal?.aborted) {
          loadingRef.current = false;
          setLoading(false);
        }
      }
    },
    [reportId]
  );

  useEffect(() => {
    const controller = new AbortController();
    void fetchMore(undefined, controller.signal);
    return () => {
      controller.abort();
      loadingRef.current = false;
    };
  }, [fetchMore]);

  return (
    <section className={`${ui.card} flex flex-col gap-1.5`} aria-label="채팅 근거">
      <h2 className={ui.sectionTitle}>채팅 근거</h2>
      <p className={ui.warning}>
        관리자 전용 정보예요. 채팅에는 개인정보(예매번호 등)가 섞여 있을 수 있어요. 신고 처리 목적 외에는 열람·복사·공유하지 마세요.
      </p>
      {hasMore && (
        <button
          type="button"
          className={button.outline}
          onClick={() => void fetchMore(messages[0]?.id)}
          disabled={loading}
          aria-busy={loading}
        >
          {loading ? "불러오는 중..." : "이전 메시지 더 보기"}
        </button>
      )}
      {error && (
        <div className="flex flex-col gap-2">
          <p className={ui.errorBox} role="alert">
            {error}
          </p>
          <div>
            <button type="button" className={button.outline} onClick={() => void fetchMore(messages[0]?.id)}>
              다시 시도
            </button>
          </div>
        </div>
      )}
      {loading && messages.length === 0 && (
        <p className={ui.status} role="status">
          불러오는 중...
        </p>
      )}
      {!loading && !error && messages.length === 0 && <p className={ui.body}>채팅 메시지가 없어요.</p>}
      {messages.length > 0 && (
        <ul className="flex flex-col gap-2" aria-label="채팅 메시지">
          {messages.map((m) => (
            <li key={m.id} className="rounded-md bg-gray-100 px-3.5 py-2.5">
              <p className={ui.muted}>
                {m.senderNickname} · {formatKstDateTime(m.createdAt)}
              </p>
              <p className="text-[0.9375rem]/[1.5] break-words whitespace-pre-wrap text-gray-900">{m.content}</p>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ProcessForm({
  report,
  onDone,
  onStale,
}: {
  report: AdminReport;
  onDone: (r: AdminReport) => void;
  onStale: () => void;
}) {
  const [mode, setMode] = useState<Mode>("idle");
  const [note, setNote] = useState("");
  const [withSanction, setWithSanction] = useState(false);
  const [days, setDays] = useState<DaysChoice>("7");
  const [sanctionNote, setSanctionNote] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);

  const daysLabel = DAYS_OPTIONS.find((d) => d.value === days)?.label ?? "";

  const toConfirmResolve = () => {
    if (withSanction && !sanctionNote.trim()) {
      setFieldError("이용 정지 사유를 입력해 주세요.");
      return;
    }
    setFieldError(null);
    setError(null);
    setMode("confirmResolve");
  };

  const submit = async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      const trimmed = note.trim();
      const updated =
        mode === "confirmDismiss"
          ? await adminReportsApi.dismiss(report.id, trimmed || undefined)
          : await adminReportsApi.resolve(report.id, {
              note: trimmed || undefined,
              sanction: withSanction
                ? { days: days === "forever" ? null : (Number(days) as 7 | 30), note: sanctionNote.trim() }
                : undefined,
            });
      onDone(updated);
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "처리하지 못했습니다.");
      setError((parsed.code && ADMIN_REPORT_CODE_MESSAGES[parsed.code]) || parsed.message);
      if (parsed.code === "REPORT_ALREADY_HANDLED") onStale();
      setMode("idle");
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <section className={`${ui.card} flex flex-col gap-2`} aria-label="신고 처리">
      <h2 className={ui.sectionTitle}>신고 처리</h2>

      {mode === "idle" && (
        <>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="report-note" className={ui.label}>
              처리 메모 (선택)
            </label>
            <textarea
              id="report-note"
              className={`${input.normal} min-h-20 py-3`}
              value={note}
              maxLength={REPORT_NOTE_MAX}
              onChange={(e) => setNote(e.target.value)}
              disabled={busy}
            />
            <p className={ui.hint}>
              {note.length}/{REPORT_NOTE_MAX}자 · 신고자에게는 처리 상태만 보여요.
            </p>
          </div>

          <label className="flex min-h-11 items-center gap-2 text-[0.9375rem] text-gray-900">
            <input
              type="checkbox"
              className="size-5"
              checked={withSanction}
              onChange={(e) => setWithSanction(e.target.checked)}
              disabled={busy}
            />
            이용 정지 함께 걸기
          </label>

          {withSanction && (
            <div className="flex flex-col gap-1.5 rounded-md bg-gray-100 p-3.5">
              <fieldset className="flex flex-col gap-1">
                <legend className={ui.label}>정지 기간</legend>
                <div className="flex flex-wrap gap-4">
                  {DAYS_OPTIONS.map((d) => (
                    <label key={d.value} className="flex min-h-11 items-center gap-2 text-[0.9375rem] text-gray-900">
                      <input
                        type="radio"
                        name="sanction-days"
                        className="size-5"
                        checked={days === d.value}
                        onChange={() => setDays(d.value)}
                      />
                      {d.label}
                    </label>
                  ))}
                </div>
              </fieldset>
              <div className="flex flex-col gap-1.5">
                <label htmlFor="sanction-note" className={ui.label}>
                  정지 사유 (필수)
                </label>
                <textarea
                  id="sanction-note"
                  className={`${fieldError ? input.invalid : input.normal} min-h-20 py-3`}
                  value={sanctionNote}
                  maxLength={SANCTION_NOTE_MAX}
                  onChange={(e) => setSanctionNote(e.target.value)}
                  aria-invalid={fieldError ? true : undefined}
                  aria-describedby={fieldError ? "sanction-note-error" : undefined}
                />
                <p className={ui.hint}>
                  {sanctionNote.length}/{SANCTION_NOTE_MAX}자
                </p>
                {fieldError && (
                  <p id="sanction-note-error" className={ui.fieldError} role="alert">
                    {fieldError}
                  </p>
                )}
              </div>
            </div>
          )}

          {error && (
            <p className={ui.errorBox} role="alert">
              {error}
            </p>
          )}

          <div className="flex flex-wrap justify-end gap-2">
            <button
              type="button"
              className={button.outline}
              onClick={() => {
                setError(null);
                setMode("confirmDismiss");
              }}
            >
              기각
            </button>
            <button type="button" className={withSanction ? button.danger : button.solid} onClick={toConfirmResolve}>
              {withSanction ? "처리하고 이용 정지" : "조치함으로 처리"}
            </button>
          </div>
        </>
      )}

      {mode === "confirmResolve" && (
        <div className="flex flex-col gap-1.5">
          <p className={ui.warning} role="alert">
            {withSanction
              ? `${report.reported.nickname}님에게 이용 정지(${daysLabel})를 걸고 신고를 처리할까요? 정지하면 로그인이 막히고, 진행 중인 교환 채팅이 모두 종료돼요.`
              : "이용 정지 없이 신고를 '조치함'으로 처리할까요? 처리한 신고는 다시 처리할 수 없어요."}
          </p>
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={() => setMode("idle")} disabled={busy}>
              돌아가기
            </button>
            <button
              type="button"
              className={withSanction ? button.danger : button.solid}
              onClick={() => void submit()}
              disabled={busy}
              aria-busy={busy}
            >
              {busy ? "처리 중..." : "처리 확정"}
            </button>
          </div>
        </div>
      )}

      {mode === "confirmDismiss" && (
        <div className="flex flex-col gap-1.5">
          <p className={ui.warning} role="alert">
            이 신고를 기각할까요? 기각한 신고는 다시 처리할 수 없어요.
          </p>
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={() => setMode("idle")} disabled={busy}>
              돌아가기
            </button>
            <button type="button" className={button.solid} onClick={() => void submit()} disabled={busy} aria-busy={busy}>
              {busy ? "처리 중..." : "기각 확정"}
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
