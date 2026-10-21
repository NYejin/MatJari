import type { InputHTMLAttributes } from "react";
import { input, ui } from "./ui";
import { input as denseInput, ui as denseUi } from "./adminUi";

// 공용 입력칸: 라벨 + 입력 + (안내) + 필드 오류를 접근성 속성과 함께 묶는다.
// (로그인/회원가입에서 시작해 공연 등록 등 다른 폼에서도 쓰도록 일반화 — 구 AuthTextField)

interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "id" | "className"> {
  id: string;
  label: string;
  /** 필드 오류 메시지 — 있으면 입력칸 아래 표시하고 aria-invalid 처리 */
  error?: string;
  /** 입력 규칙 안내 (오류가 없을 때만 표시) */
  hint?: string;
  /** true면 오류 문구에 role="alert"를 달아 나타나는 즉시 읽히게 한다 (제출 후 오류를 알려야 하는 폼용) */
  liveError?: boolean;
  /** 관리자 화면용 촘촘한 크기 */
  dense?: boolean;
}

export default function TextField({ id, label, error, hint, liveError = false, dense = false, ...inputProps }: TextFieldProps) {
  const t = dense ? denseUi : ui;
  const inp = dense ? denseInput : input;
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const showHint = !!hint && !error;
  const describedBy = error ? errorId : showHint ? hintId : undefined;

  return (
    <div className={dense ? "flex flex-col gap-0.5" : "flex flex-col gap-1.5"}>
      <label htmlFor={id} className={t.label}>
        {label}
      </label>
      <input
        {...inputProps}
        id={id}
        className={error ? inp.invalid : inp.normal}
        aria-invalid={!!error}
        aria-describedby={describedBy}
      />
      {showHint && (
        <p id={hintId} className={t.hint}>
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className={t.fieldError} role={liveError ? "alert" : undefined}>
          {error}
        </p>
      )}
    </div>
  );
}
