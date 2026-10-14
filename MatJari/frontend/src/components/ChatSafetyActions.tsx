import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { Link } from "react-router-dom";
import { blocksApi } from "../api/blocks";
import { countCodePoints, truncateCodePoints } from "../api/chat";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { REPORT_DETAIL_MAX, REPORT_REASONS, reportsApi, type ReportReason } from "../api/reports";
import { button, FOCUS_RING, input, linkButton, ui } from "./ui";

// 채팅 화면 상단의 '차단'·'신고' (UC: 차단·신고는 매칭 화면에서만 시작). 눈에 띄는 작은 버튼 2개와 아래로 펼쳐지는 확인/폼 패널.
// 패널이 열리면 제목으로 포커스를 옮기고, 닫으면 눌렀던 버튼으로 되돌린다. 제출 중에는 중복 제출·닫기를 막는다.

type Mode = "block" | "report" | "blocked" | "reported" | null;

interface Props {
  matchId: number;
  counterpartName: string;
  /** true면 ⋯(더보기) 버튼 + 팝오버 메뉴로 보여주고 패널은 모달로 띄운다 (채팅 헤더용) */
  menu?: boolean;
}

export default function ChatSafetyActions({ matchId, counterpartName, menu = false }: Props) {
  const [mode, setMode] = useState<Mode>(null);
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const moreBtnRef = useRef<HTMLButtonElement>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ reason?: string; detail?: string }>({});
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [detail, setDetail] = useState("");
  const [canceledMatches, setCanceledMatches] = useState(0);

  const blockBtnRef = useRef<HTMLButtonElement>(null);
  const reportBtnRef = useRef<HTMLButtonElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const originRef = useRef<HTMLButtonElement | null>(null);
  const busyRef = useRef(false);

  // 패널이 열리거나 결과 단계로 바뀔 때 제목으로 포커스
  useEffect(() => {
    if (mode !== null) headingRef.current?.focus();
  }, [mode]);

  const open = (next: "block" | "report", origin: HTMLButtonElement | null) => {
    originRef.current = origin;
    setError(null);
    setFieldErrors({});
    setMode(next);
  };

  // 메뉴: 바깥 누르기·Esc로 닫는다 (Esc는 ⋯ 버튼으로 포커스를 돌려준다)
  useEffect(() => {
    if (!menuOpen) return;
    const onPointer = (e: PointerEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    const onKey = (e: globalThis.KeyboardEvent) => {
      if (e.key === "Escape") {
        setMenuOpen(false);
        moreBtnRef.current?.focus();
      }
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [menuOpen]);

  // 메뉴가 열리면 첫 항목으로 포커스
  useEffect(() => {
    if (menuOpen) reportBtnRef.current?.focus();
  }, [menuOpen]);

  // 시트의 배경·닫기로 닫을 때도 ⋯ 버튼으로 포커스를 돌려준다
  const closeMenu = () => {
    setMenuOpen(false);
    moreBtnRef.current?.focus();
  };

  const openFromMenu = (next: "block" | "report") => {
    setMenuOpen(false);
    open(next, moreBtnRef.current);
  };

  const close = () => {
    if (busyRef.current) return;
    setMode(null);
    setError(null);
    setFieldErrors({});
    // 렌더 뒤 버튼이 다시 보이면 포커스를 돌려준다
    window.setTimeout(() => originRef.current?.focus(), 0);
  };

  const onPanelKeyDown = (e: KeyboardEvent<HTMLElement>) => {
    if (e.key === "Escape" && (mode === "block" || mode === "report")) {
      e.stopPropagation();
      close();
    }
  };

  const confirmBlock = async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setSubmitting(true);
    setError(null);
    try {
      const res = await blocksApi.blockCounterpart(matchId);
      setCanceledMatches(res.canceledMatches);
      setMode("blocked");
    } catch (err) {
      setError(exchangeErrorMessage(err, "차단하지 못했어요. 잠시 후 다시 시도해 주세요.").message);
    } finally {
      busyRef.current = false;
      setSubmitting(false);
    }
  };

  const submitReport = async (e: FormEvent) => {
    e.preventDefault();
    if (busyRef.current) return;
    const trimmed = detail.trim();
    const errs: { reason?: string; detail?: string } = {};
    if (reason === null) errs.reason = "신고 사유를 선택해 주세요.";
    if (reason === "OTHER" && trimmed.length === 0) errs.detail = "기타를 선택한 경우 내용을 적어 주세요.";
    setFieldErrors(errs);
    if (reason === null || Object.keys(errs).length > 0) {
      setError("입력을 확인해 주세요.");
      return;
    }
    busyRef.current = true;
    setSubmitting(true);
    setError(null);
    try {
      await reportsApi.create(matchId, trimmed ? { reason, detail: trimmed } : { reason });
      setMode("reported");
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "신고하지 못했어요. 잠시 후 다시 시도해 주세요.");
      setFieldErrors({ reason: parsed.fieldErrors.reason, detail: parsed.fieldErrors.detail });
      setError(parsed.message);
    } finally {
      busyRef.current = false;
      setSubmitting(false);
    }
  };

  const panelBase = `${ui.card} flex flex-col gap-3 border border-gray-200`;
  const detailCount = countCodePoints(detail);
  const detailErrorId = "report-detail-error";
  const reasonErrorId = "report-reason-error";

  // 메뉴 모드: ⋯ 버튼의 팝오버에 차단·신고를 넣고, 확인/폼 패널은 화면 가운데 모달로 띄운다
  const panelOverlay = menu ? "fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/40 p-4 [&>section]:w-full [&>section]:max-w-md [&>section]:mt-16" : "contents";
  // 행 스타일: 모바일은 가운데 정렬의 전체 폭 행(높이 52px), 데스크톱은 왼쪽 정렬의 작은 행
  const itemCls = `flex min-h-13 w-full cursor-pointer items-center justify-center px-3 text-center text-base/[normal] text-gray-900 active:bg-gray-100 md:min-h-11 md:justify-start md:text-left md:text-[0.9375rem]/[normal] ${FOCUS_RING}`;

  return (
    <div className={menu ? "relative shrink-0" : "flex flex-col gap-2"}>
      {menu ? (
        <div ref={menuRef}>
          <button
            ref={moreBtnRef}
            type="button"
            aria-label="더보기"
            aria-haspopup="true"
            aria-expanded={menuOpen}
            aria-controls="chat-more-menu"
            onClick={() => setMenuOpen((v) => !v)}
            disabled={submitting}
            className={`inline-flex size-11 cursor-pointer items-center justify-center rounded-full text-gray-900 active:bg-gray-100 ${FOCUS_RING}`}
          >
            <svg viewBox="0 0 24 24" className="size-6" fill="currentColor" aria-hidden="true">
              <circle cx="5" cy="12" r="1.8" />
              <circle cx="12" cy="12" r="1.8" />
              <circle cx="19" cy="12" r="1.8" />
            </svg>
          </button>
          {menuOpen && (
            // 모바일(md 미만): 아래에서 올라오는 바텀 시트 + 반투명 배경 / 데스크톱: ⋯ 아래 작은 드롭다운
            <div id="chat-more-menu" className="fixed inset-0 z-50 flex flex-col justify-end md:absolute md:inset-auto md:top-full md:right-0 md:z-40 md:mt-1 md:block">
              <div className="absolute inset-0 bg-black/40 md:hidden" onClick={closeMenu} aria-hidden="true" />
              <div className="relative flex flex-col px-3 pb-[calc(12px+env(safe-area-inset-bottom))] md:w-40 md:p-0">
                <div className="flex flex-col divide-y divide-gray-200 overflow-hidden rounded-2xl bg-white md:rounded-xl md:border md:border-gray-200 md:shadow-lg">
                  <button ref={reportBtnRef} type="button" className={itemCls} onClick={() => openFromMenu("report")}>
                    신고하기
                  </button>
                  <button ref={blockBtnRef} type="button" className={itemCls} onClick={() => openFromMenu("block")}>
                    차단하기
                  </button>
                </div>
                <button type="button" className={`${itemCls} mt-2 rounded-2xl bg-white font-semibold md:hidden`} onClick={closeMenu}>
                  닫기
                </button>
              </div>
            </div>
          )}
        </div>
      ) : (
      <div className="flex gap-2">
        <button ref={blockBtnRef} type="button" className={button.outline} aria-expanded={mode === "block"} onClick={() => open("block", blockBtnRef.current)} disabled={submitting}>
          차단
        </button>
        <button ref={reportBtnRef} type="button" className={button.outline} aria-expanded={mode === "report"} onClick={() => open("report", reportBtnRef.current)} disabled={submitting}>
          신고
        </button>
      </div>
      )}
      <div className={mode === null ? "hidden" : panelOverlay}>

      {mode === "block" && (
        <section className={panelBase} aria-labelledby="block-title" onKeyDown={onPanelKeyDown}>
          <h2 id="block-title" ref={headingRef} tabIndex={-1} className={`${ui.sectionTitle} outline-none`}>
            {counterpartName}님을 차단할까요?
          </h2>
          <p className={ui.body}>
            차단하면 이 분과의 진행 중인 교환이 모두 종료되고 서로 후보에 나오지 않아요. 상대에게 알리지 않아요. 차단 목록에서 해제할 수 있어요.
          </p>
          {error && (
            <p className={ui.errorBox} role="alert">
              {error}
            </p>
          )}
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={close} disabled={submitting}>
              취소
            </button>
            <button type="button" className={button.danger} onClick={() => void confirmBlock()} disabled={submitting} aria-busy={submitting}>
              {submitting ? "차단하는 중..." : "차단하기"}
            </button>
          </div>
        </section>
      )}

      {mode === "blocked" && (
        <section className={panelBase} aria-labelledby="blocked-title">
          <h2 id="blocked-title" ref={headingRef} tabIndex={-1} className={`${ui.sectionTitle} outline-none`}>
            차단했어요
          </h2>
          <p className={ui.body} role="status">
            {counterpartName}님을 차단했어요.
            {canceledMatches > 0 ? ` 진행 중이던 교환 ${canceledMatches}건을 종료했어요.` : ""} 차단은 '마이페이지 &gt; 차단 목록'에서 해제할 수 있어요.
          </p>
          <div className="flex justify-end">
            <Link to="/exchange/matches" className={linkButton.solid}>
              내 매칭으로
            </Link>
          </div>
        </section>
      )}

      {mode === "report" && (
        <section className={panelBase} aria-labelledby="report-title" onKeyDown={onPanelKeyDown}>
          <h2 id="report-title" ref={headingRef} tabIndex={-1} className={`${ui.sectionTitle} outline-none`}>
            {counterpartName}님 신고
          </h2>
          <p className={ui.hint}>신고는 상대에게 알리지 않아요. 관리자가 이 채팅 내용을 확인할 수 있어요.</p>
          <form onSubmit={submitReport} className="flex flex-col gap-3" noValidate>
            <fieldset className="flex flex-col gap-2" disabled={submitting} aria-describedby={fieldErrors.reason ? reasonErrorId : undefined}>
              <legend className={ui.label}>신고 사유</legend>
              {REPORT_REASONS.map((r) => {
                const id = `report-reason-${r.value}`;
                return (
                  <label key={r.value} htmlFor={id} className={`flex min-h-11 cursor-pointer items-start gap-2.5 rounded-[10px] border border-gray-200 px-3 py-2.5 has-[:checked]:border-primary-600 has-[:checked]:bg-primary-50 has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-primary-600`}>
                    <input
                      id={id}
                      type="radio"
                      name="report-reason"
                      value={r.value}
                      checked={reason === r.value}
                      onChange={() => {
                        setReason(r.value);
                        setFieldErrors((f) => ({ ...f, reason: undefined }));
                      }}
                      className={`mt-1 size-5 shrink-0 accent-primary-600 ${FOCUS_RING}`}
                    />
                    <span className="flex flex-col">
                      <span className="text-[0.9375rem]/[normal] font-semibold text-gray-900">{r.label}</span>
                      <span className={ui.hint}>{r.help}</span>
                    </span>
                  </label>
                );
              })}
              {fieldErrors.reason && (
                <p id={reasonErrorId} className={ui.fieldError} role="alert">
                  {fieldErrors.reason}
                </p>
              )}
            </fieldset>

            <div className="flex flex-col gap-1.5">
              <label htmlFor="report-detail" className={ui.label}>
                상세 내용{reason === "OTHER" ? " (필수)" : " (선택)"}
              </label>
              <textarea
                id="report-detail"
                rows={4}
                value={detail}
                onChange={(e) => {
                  setDetail(truncateCodePoints(e.target.value, REPORT_DETAIL_MAX));
                  setFieldErrors((f) => ({ ...f, detail: undefined }));
                }}
                disabled={submitting}
                required={reason === "OTHER"}
                aria-invalid={fieldErrors.detail ? true : undefined}
                aria-describedby={`report-detail-count${fieldErrors.detail ? ` ${detailErrorId}` : ""}`}
                placeholder="어떤 일이 있었는지 적어 주세요. 이름·연락처 같은 개인정보는 적지 마세요."
                className={`${fieldErrors.detail ? input.invalid : input.normal} resize-none py-2.5`}
              />
              <div className="flex items-start justify-between gap-2">
                <p id={detailErrorId} className={fieldErrors.detail ? ui.fieldError : "sr-only"} role="alert">
                  {fieldErrors.detail ?? ""}
                </p>
                <span id="report-detail-count" className={`${ui.hint} ml-auto shrink-0`}>
                  {detailCount}/{REPORT_DETAIL_MAX}자
                </span>
              </div>
            </div>

            {error && (
              <p className={ui.errorBox} role="alert">
                {error}
              </p>
            )}
            <div className="flex flex-wrap justify-end gap-2">
              <button type="button" className={button.outline} onClick={close} disabled={submitting}>
                취소
              </button>
              <button type="submit" className={button.solid} disabled={submitting} aria-busy={submitting}>
                {submitting ? "접수하는 중..." : "신고 접수"}
              </button>
            </div>
          </form>
        </section>
      )}

      {mode === "reported" && (
        <section className={panelBase} aria-labelledby="reported-title">
          <h2 id="reported-title" ref={headingRef} tabIndex={-1} className={`${ui.sectionTitle} outline-none`}>
            신고를 접수했어요
          </h2>
          <p className={ui.body} role="status">
            처리 결과는 '내 신고 내역'에서 볼 수 있어요. 상대에게는 알리지 않아요.
          </p>
          <div className="flex flex-wrap justify-end gap-2">
            <button
              type="button"
              className={button.outline}
              onClick={() => {
                setReason(null);
                setDetail("");
                close();
              }}
            >
              닫기
            </button>
            <Link to="/my/reports" className={linkButton.solid}>
              내 신고 내역
            </Link>
          </div>
        </section>
      )}
      </div>
    </div>
  );
}
