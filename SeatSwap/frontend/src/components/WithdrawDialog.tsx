import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { Link } from "react-router-dom";
import { usersApi } from "../api/users";
import { getErrorCode, parseApiError } from "../api/errors";
import axios from "axios";
import TextField from "./TextField";
import { button, ui } from "./ui";

// 회원탈퇴 확인 모달 (MyPage에서 연다). role="dialog" + 포커스 트랩 + Esc 닫기.
// 본인 확인: 비밀번호 계정은 비밀번호, 소셜 전용 계정은 "탈퇴합니다" 입력. 성공 후 처리(로그아웃·이동)는 호출자가 한다.

export const WITHDRAW_CONFIRM_TEXT = "탈퇴합니다";

interface Props {
  /** 비밀번호로 로그인할 수 있는 계정인가 (서버가 안 주면 true로 간주) */
  passwordLoginEnabled: boolean;
  /** 이용 정지 중 로그인으로 받은 탈퇴 전용 토큰. 있으면 로그인 상태 없이 이 토큰으로 탈퇴한다 */
  withdrawToken?: string;
  onClose: () => void;
  onWithdrawn: () => void;
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])';

function errorMessage(err: unknown, passwordMode: boolean, tokenMode: boolean): string {
  if (getErrorCode(err) === "ADMIN_CANNOT_WITHDRAW") {
    return "관리자 계정은 바로 탈퇴할 수 없어요. 운영자에게 관리자 권한 해제를 요청한 뒤 다시 시도해 주세요.";
  }
  if (axios.isAxiosError(err)) {
    const status = err.response?.status;
    if (tokenMode && status === 401) return "시간이 지나 인증이 만료됐어요. 로그인 화면에서 다시 시도해 주세요.";
    if (status === 429) return "시도가 너무 많아요. 잠시 후 다시 시도해 주세요.";
    if (status === 400) {
      return passwordMode
        ? "비밀번호가 맞지 않아요. 다시 확인해 주세요."
        : `확인 문구가 맞지 않아요. '${WITHDRAW_CONFIRM_TEXT}'를 정확히 입력해 주세요.`;
    }
  }
  return parseApiError(err, "탈퇴하지 못했어요. 잠시 후 다시 시도해 주세요.").message;
}

export default function WithdrawDialog({ passwordLoginEnabled, withdrawToken, onClose, onWithdrawn }: Props) {
  const [value, setValue] = useState("");
  const [error, setError] = useState<string | undefined>();
  const [submitting, setSubmitting] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  // 열기 전 포커스 위치 — 닫을 때 복원
  const openerRef = useRef<Element | null>(null);
  // 성공 후에는 컴포넌트가 사라지므로 닫기 동작과 겹치지 않게 한다
  const closedRef = useRef(false);

  useEffect(() => {
    openerRef.current = document.activeElement;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousOverflow;
      if (!closedRef.current && openerRef.current instanceof HTMLElement) openerRef.current.focus();
    };
  }, []);

  const confirmed = passwordLoginEnabled ? value.length > 0 : value === WITHDRAW_CONFIRM_TEXT;

  const handleKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === "Escape") {
      e.stopPropagation();
      if (!submitting) onClose();
      return;
    }
    if (e.key !== "Tab") return;
    const nodes = dialogRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE);
    if (!nodes || nodes.length === 0) return;
    const first = nodes[0];
    const last = nodes[nodes.length - 1];
    if (e.shiftKey && document.activeElement === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && document.activeElement === last) {
      e.preventDefault();
      first.focus();
    }
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (submitting || !confirmed) return;
    setSubmitting(true);
    setError(undefined);
    try {
      const body = passwordLoginEnabled ? { password: value } : { confirmText: value };
      if (withdrawToken) await usersApi.withdrawSuspended(withdrawToken, body);
      else await usersApi.withdraw(body);
      closedRef.current = true;
      onWithdrawn();
    } catch (err) {
      setError(errorMessage(err, passwordLoginEnabled, !!withdrawToken));
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-end justify-center bg-black/50 sm:items-center sm:p-4"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !submitting) onClose();
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="withdraw-title"
        aria-describedby="withdraw-desc"
        onKeyDown={handleKeyDown}
        className="max-h-[92dvh] w-full max-w-[480px] overflow-y-auto overscroll-contain rounded-t-2xl bg-white p-5 pb-[calc(20px+env(safe-area-inset-bottom))] shadow-[0_4px_24px_rgba(0,0,0,0.2)] sm:rounded-2xl sm:p-6"
      >
        <h2 id="withdraw-title" className={ui.sectionTitle}>
          회원탈퇴
        </h2>
        <div id="withdraw-desc" className={`mt-3 flex flex-col gap-2 ${ui.body}`}>
          <p>탈퇴하면 진행 중인 교환(예약 포함)이 모두 취소되고 등록한 티켓이 내려가요.</p>
          <p>이메일·닉네임·소셜 연결 정보는 삭제·익명화돼요.</p>
          <p>
            채팅 기록, 교환 이력, 신고·제재 기록은 익명화되어 남아요.{" "}
            <Link to="/privacy" target="_blank" rel="noopener" className={ui.link}>
              개인정보처리방침
            </Link>
          </p>
          <p className="font-semibold text-red-700">되돌릴 수 없어요.</p>
        </div>

        <form onSubmit={handleSubmit} noValidate className="mt-4 flex flex-col gap-3">
          {passwordLoginEnabled ? (
            <TextField
              id="withdraw-password"
              label="비밀번호"
              type="password"
              autoComplete="current-password"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              hint="본인 확인을 위해 현재 비밀번호를 입력해 주세요."
              disabled={submitting}
              autoFocus
            />
          ) : (
            <TextField
              id="withdraw-confirm"
              label={`확인 문구 입력 ('${WITHDRAW_CONFIRM_TEXT}')`}
              type="text"
              autoComplete="off"
              autoCapitalize="none"
              spellCheck={false}
              value={value}
              onChange={(e) => setValue(e.target.value)}
              hint={`소셜 계정은 비밀번호가 없어요. '${WITHDRAW_CONFIRM_TEXT}'를 그대로 입력해 주세요.`}
              disabled={submitting}
              autoFocus
            />
          )}

          <p className={error ? ui.errorBox : "sr-only"} role="alert">
            {error ?? ""}
          </p>

          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" className={`${button.outline} min-h-12`} onClick={onClose} disabled={submitting}>
              취소
            </button>
            <button
              type="submit"
              className={`${button.danger} min-h-12`}
              disabled={!confirmed || submitting}
              aria-busy={submitting}
            >
              {submitting ? "탈퇴 처리 중..." : "탈퇴하기"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
