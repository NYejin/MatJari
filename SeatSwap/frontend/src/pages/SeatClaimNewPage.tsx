import { useEffect, useRef, useState, type ChangeEvent, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { compareLocalDateTime, formatKstDateTime, isSessionClosed } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { performancesApi } from "../api/performances";
import { ROW_COL_MAX, ZONE_MAX } from "../api/seat";
import { SEAT_CLAIM_MEMO_MAX, httpStatus, seatClaimsApi, validateClaimImage } from "../api/seatClaims";
import TextField from "../components/TextField";
import { button, input, linkButton, ui } from "../components/ui";
import type { PerformanceDetail } from "../types/performance";

// 좌석 소유 인증 요청 (보호 라우트 /seat-claims/new?sessionId&zone&row&col[&performanceId]).
// 티켓 등록에서 409 SEAT_ALREADY_REGISTERED가 났을 때 넘어온다. 예매내역 사진 1장을 올리면 관리자가 확인한다.

const parseId = (value: string | null): number | null => {
  const n = Number(value);
  return value && Number.isInteger(n) && n > 0 ? n : null;
};

type FieldKey = "sessionId" | "zone" | "row" | "col" | "memo" | "image";
const FIELD_ID: Record<FieldKey, string> = {
  sessionId: "claim-session",
  zone: "claim-zone",
  row: "claim-row",
  col: "claim-col",
  memo: "claim-memo",
  image: "claim-image",
};
const FIELD_ORDER: FieldKey[] = ["sessionId", "zone", "row", "col", "image", "memo"];

const CODE_TEXT: Record<string, string> = {
  SESSION_CLOSED: "회차 당일이 지나 마감돼서 인증을 요청할 수 없어요.",
  SEAT_CLAIM_ALREADY_PENDING: "이 좌석은 이미 인증 요청을 보냈어요. 처리될 때까지 기다려 주세요.",
  SEAT_CLAIM_LIMIT_REACHED: "처리 대기 중인 인증 요청이 너무 많아요. 처리가 끝난 뒤 다시 요청해 주세요.",
  MY_TICKET_ALREADY_REGISTERED: "이미 내가 등록한 좌석이에요.",
  IMAGE_TOO_LARGE: "사진이 너무 커요. 5MB 이하 사진을 올려 주세요.",
};

export default function SeatClaimNewPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const performanceId = parseId(params.get("performanceId"));
  const [sessionId, setSessionId] = useState<number | null>(parseId(params.get("sessionId")));
  const [zone, setZone] = useState(params.get("zone") ?? "");
  const [row, setRow] = useState(params.get("row") ?? "");
  const [col, setCol] = useState(params.get("col") ?? "");
  const [memo, setMemo] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [detail, setDetail] = useState<PerformanceDetail | null>(null);
  const [errors, setErrors] = useState<Partial<Record<FieldKey, string>>>({});
  const [submitError, setSubmitError] = useState<{ message: string; code: string | null } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // 공연 정보(제목·회차 목록)는 있으면 보여주는 보조 정보다 — 실패해도 요청은 보낼 수 있다
  useEffect(() => {
    if (performanceId === null) return;
    const controller = new AbortController();
    performancesApi
      .get(performanceId, controller.signal)
      .then(setDetail)
      .catch(() => undefined);
    return () => controller.abort();
  }, [performanceId]);

  // 미리보기 object URL은 파일이 바뀌거나 화면이 사라질 때 해제한다
  useEffect(() => {
    if (!file) {
      setPreviewUrl(null);
      return;
    }
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  const clear = (key: FieldKey) => {
    setErrors((p) => (p[key] ? { ...p, [key]: undefined } : p));
    setSubmitError(null);
  };

  const onPickFile = (e: ChangeEvent<HTMLInputElement>) => {
    const picked = e.target.files?.[0] ?? null;
    if (!picked) return;
    const problem = validateClaimImage(picked);
    if (problem) {
      setFile(null);
      setErrors((p) => ({ ...p, image: problem }));
      e.target.value = "";
      return;
    }
    setFile(picked);
    clear("image");
  };

  const removeFile = () => {
    setFile(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
    fileInputRef.current?.focus();
  };

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (submittingRef.current) return;
    setSubmitError(null);
    const z = zone.trim();
    const r = row.trim();
    const c = col.trim();
    const next: Partial<Record<FieldKey, string>> = {};
    if (sessionId === null) next.sessionId = "회차를 선택해 주세요.";
    if (!z) next.zone = "구역을 입력해 주세요.";
    else if (z.length > ZONE_MAX) next.zone = `구역은 ${ZONE_MAX}자 이하로 입력해 주세요.`;
    if (!r) next.row = "열을 입력해 주세요.";
    else if (r.length > ROW_COL_MAX) next.row = `열은 ${ROW_COL_MAX}자 이하로 입력해 주세요.`;
    if (!c) next.col = "번을 입력해 주세요.";
    else if (c.length > ROW_COL_MAX) next.col = `번은 ${ROW_COL_MAX}자 이하로 입력해 주세요.`;
    if (!file) next.image = "예매내역 사진을 선택해 주세요.";
    if (memo.length > SEAT_CLAIM_MEMO_MAX) next.memo = `메모는 ${SEAT_CLAIM_MEMO_MAX}자 이하로 입력해 주세요.`;
    setErrors(next);
    const first = FIELD_ORDER.find((k) => next[k]);
    if (first || sessionId === null || !file) {
      if (first) document.getElementById(FIELD_ID[first])?.focus();
      return;
    }

    submittingRef.current = true;
    setSubmitting(true);
    try {
      await seatClaimsApi.create({ sessionId, zone: z, row: r, col: c, memo: memo.trim() || undefined, image: file });
      navigate("/my/seat-claims", {
        replace: true,
        state: { notice: "인증 요청을 보냈어요. 관리자가 확인하면 결과를 여기서 볼 수 있어요. 처리가 끝나면 사진은 삭제돼요." },
      });
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "인증 요청을 보내지 못했습니다.");
      const fe = parsed.fieldErrors;
      const fieldMap: Partial<Record<FieldKey, string>> = {};
      for (const k of FIELD_ORDER) if (fe[k]) fieldMap[k] = fe[k];
      const firstErr = FIELD_ORDER.find((k) => fieldMap[k]);
      if (firstErr) {
        setErrors(fieldMap);
        setSubmitError(null);
      } else {
        const tooLarge = httpStatus(err) === 413;
        const code = tooLarge ? "IMAGE_TOO_LARGE" : parsed.code;
        setSubmitError({ message: (code && CODE_TEXT[code]) || parsed.message, code });
      }
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  };

  if (sessionId === null && performanceId === null && !params.get("sessionId")) {
    return (
      <div className={ui.page}>
        <div className={ui.container}>
          <h1 className={ui.pageTitle}>내 티켓 인증</h1>
          <div className={`${ui.card} flex flex-col items-start gap-3`}>
            <p className={ui.body}>인증할 좌석 정보가 없어요. 티켓 등록 화면에서 이미 등록된 좌석 안내를 통해 들어와 주세요.</p>
            <Link to="/tickets/new" className={linkButton.outline}>
              티켓 등록으로
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const sessions = detail ? [...detail.sessions].sort((a, b) => compareLocalDateTime(a.startsAt, b.startsAt)) : [];
  const currentSession = sessions.find((s) => s.id === sessionId);
  let errorBlock: ReactNode = null;
  if (submitError) {
    errorBlock = (
      <div className={`${ui.errorBox} flex flex-col items-start gap-2`} role="alert">
        <span>{submitError.message}</span>
        {submitError.code === "SEAT_NOT_REGISTERED" && (
          <>
            <span>지금은 비어 있어요. 직접 등록해 주세요.</span>
            <Link
              to={performanceId ? `/tickets/new?performanceId=${performanceId}${sessionId ? `&sessionId=${sessionId}` : ""}` : "/tickets/new"}
              className={linkButton.outline}
            >
              티켓 직접 등록
            </Link>
          </>
        )}
        {submitError.code === "MY_TICKET_ALREADY_REGISTERED" && (
          <Link to="/tickets" className={linkButton.outline}>
            내 티켓 보기
          </Link>
        )}
        {(submitError.code === "SEAT_CLAIM_ALREADY_PENDING" || submitError.code === "SEAT_CLAIM_LIMIT_REACHED") && (
          <Link to="/my/seat-claims" className={linkButton.outline}>
            내 인증 요청 보기
          </Link>
        )}
      </div>
    );
  }

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>내 티켓 인증</h1>
        <p className={ui.body}>
          이미 다른 사람이 등록해 둔 좌석이 내 자리라면, 예매내역 사진으로 인증을 요청할 수 있어요. 관리자가 확인한 뒤 승인하면 기존
          등록이 내려가고, 그 뒤에 내 티켓으로 직접 등록할 수 있어요.
        </p>

        <form className="flex flex-col gap-4" onSubmit={submit} noValidate aria-label="내 티켓 인증 요청">
          <fieldset className={`${ui.card} flex flex-col gap-4`} disabled={submitting} aria-labelledby="claim-seat-title">
            <h2 id="claim-seat-title" className={ui.sectionTitle}>
              인증할 좌석
            </h2>
            {detail && <p className="text-base/[normal] font-bold break-keep text-gray-900">{detail.title}</p>}
            {detail && sessions.length > 0 ? (
              <div className="flex flex-col gap-1.5">
                <label htmlFor={FIELD_ID.sessionId} className={ui.label}>
                  회차
                </label>
                <select
                  id={FIELD_ID.sessionId}
                  className={errors.sessionId ? input.invalid : input.normal}
                  value={sessionId ?? ""}
                  onChange={(e) => {
                    setSessionId(e.target.value ? Number(e.target.value) : null);
                    clear("sessionId");
                  }}
                  aria-invalid={!!errors.sessionId}
                  aria-describedby={errors.sessionId ? "claim-session-error" : undefined}
                >
                  <option value="">회차를 선택하세요</option>
                  {sessions.map((s) => (
                    <option key={s.id} value={s.id} disabled={isSessionClosed(s.startsAt)}>
                      {formatKstDateTime(s.startsAt)}
                      {isSessionClosed(s.startsAt) ? " (마감)" : ""}
                    </option>
                  ))}
                </select>
                {errors.sessionId && (
                  <p id="claim-session-error" className={ui.fieldError}>
                    {errors.sessionId}
                  </p>
                )}
              </div>
            ) : (
              <div className="flex flex-col gap-1">
                <span className={ui.label}>회차</span>
                <span id={FIELD_ID.sessionId} tabIndex={-1} className={ui.body}>
                  {currentSession ? formatKstDateTime(currentSession.startsAt) : `회차 번호 ${sessionId}`}
                </span>
                {errors.sessionId && <p className={ui.fieldError}>{errors.sessionId}</p>}
              </div>
            )}
            <TextField
              id={FIELD_ID.zone}
              label="구역"
              value={zone}
              onChange={(e) => {
                setZone(e.target.value);
                clear("zone");
              }}
              error={errors.zone}
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
                maxLength={ROW_COL_MAX}
                autoComplete="off"
                autoCapitalize="characters"
              />
            </div>
          </fieldset>

          <fieldset className={`${ui.card} flex flex-col gap-3`} disabled={submitting} aria-labelledby="claim-photo-title">
            <h2 id="claim-photo-title" className={ui.sectionTitle}>
              예매내역 사진
            </h2>
            <p className={ui.warning}>예매번호·이름 등은 가려서 올려 주세요. 처리가 끝나면 사진은 삭제돼요.</p>
            <div className="flex flex-col gap-1.5">
              <label htmlFor={FIELD_ID.image} className={ui.label}>
                사진 선택 (JPG·PNG·WEBP, 5MB 이하)
              </label>
              <input
                ref={fileInputRef}
                id={FIELD_ID.image}
                type="file"
                accept="image/jpeg,image/png,image/webp"
                onChange={onPickFile}
                aria-invalid={!!errors.image}
                aria-describedby={errors.image ? "claim-image-error" : undefined}
                className="min-h-11 text-base text-gray-900 file:mr-3 file:min-h-11 file:cursor-pointer file:rounded-[10px] file:border file:border-gray-300 file:bg-white file:px-4 file:font-semibold"
              />
              {errors.image && (
                <p id="claim-image-error" className={ui.fieldError} role="alert">
                  {errors.image}
                </p>
              )}
            </div>
            {file && previewUrl && (
              <div className="flex flex-col items-start gap-2">
                <img
                  src={previewUrl}
                  alt="선택한 예매내역 사진 미리보기"
                  className="max-h-[50dvh] max-w-full rounded-[10px] border border-gray-200 object-contain"
                />
                <button type="button" className={button.outline} onClick={removeFile}>
                  사진 지우기
                </button>
              </div>
            )}
            <div className="flex flex-col gap-1.5">
              <label htmlFor={FIELD_ID.memo} className={ui.label}>
                메모 (선택)
              </label>
              <textarea
                id={FIELD_ID.memo}
                className={`${errors.memo ? input.invalid : input.normal} min-h-24 py-3`}
                value={memo}
                onChange={(e) => {
                  setMemo(e.target.value);
                  clear("memo");
                }}
                maxLength={SEAT_CLAIM_MEMO_MAX}
                aria-invalid={!!errors.memo}
                aria-describedby={errors.memo ? "claim-memo-error" : "claim-memo-hint"}
              />
              {errors.memo ? (
                <p id="claim-memo-error" className={ui.fieldError}>
                  {errors.memo}
                </p>
              ) : (
                <p id="claim-memo-hint" className={ui.hint}>
                  {memo.length}/{SEAT_CLAIM_MEMO_MAX}자
                </p>
              )}
            </div>
          </fieldset>

          {errorBlock}

          <div className="flex flex-wrap justify-end gap-2">
            <Link to="/tickets/new" className={linkButton.outline}>
              취소
            </Link>
            <button type="submit" className={button.solid} disabled={submitting} aria-busy={submitting}>
              {submitting ? "보내는 중..." : "인증 요청 보내기"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
