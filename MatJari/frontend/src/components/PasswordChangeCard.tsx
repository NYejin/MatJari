import { useState, type FormEvent } from "react";
import { authApi } from "../api/auth";
import { authRateLimitMessage, getErrorCode, parseApiError } from "../api/errors";
import {
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  compactErrors,
  pickFieldErrors,
  validateLoginPassword,
  validatePasswordConfirm,
  validateSignupPassword,
} from "../api/authValidation";
import TextField from "./TextField";
import { button, ui } from "./ui";

// 마이페이지 '비밀번호 변경' 카드 — 비밀번호 로그인 계정에만 MyPage가 렌더한다.
// PATCH /api/auth/password: 현재 기기는 로그인 유지, 다른 기기는 로그아웃된다.

type Field = "currentPassword" | "newPassword" | "confirm";
const SERVER_FIELDS = ["currentPassword", "newPassword"] as const;

export default function PasswordChangeCard() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (saving) return;
    const errors = compactErrors<Field>({
      currentPassword: validateLoginPassword(current),
      newPassword: validateSignupPassword(next),
      confirm: validatePasswordConfirm(next, confirm),
    });
    setFieldErrors(errors);
    setFormError(null);
    setNotice("");
    if (Object.keys(errors).length > 0) return;

    setSaving(true);
    try {
      await authApi.changePassword(current, next);
      setCurrent("");
      setNext("");
      setConfirm("");
      setNotice("비밀번호를 바꿨어요. 다른 기기에서는 로그아웃됐어요.");
    } catch (err) {
      const limited = authRateLimitMessage(err);
      if (limited) {
        setFormError(limited);
      } else if (getErrorCode(err) === "PASSWORD_LOGIN_DISABLED") {
        setFormError("이 계정은 비밀번호로 로그인하지 않아 비밀번호를 바꿀 수 없어요.");
      } else {
        const parsed = parseApiError(err, "비밀번호를 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.");
        const { fieldErrors: serverErrors, hasFieldError } = pickFieldErrors(parsed.fieldErrors, SERVER_FIELDS);
        setFieldErrors(serverErrors);
        setFormError(hasFieldError ? null : parsed.message);
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <section
      className={ui.card}
      aria-labelledby="password-change-title"
    >
      <h2 id="password-change-title" className={ui.sectionTitle}>
        비밀번호 변경
      </h2>
      <form onSubmit={handleSubmit} noValidate className="mt-4 flex flex-col gap-4">
        <TextField
          id="pw-current"
          label="현재 비밀번호"
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          disabled={saving}
          error={fieldErrors.currentPassword}
        />
        <TextField
          id="pw-new"
          label="새 비밀번호"
          type="password"
          autoComplete="new-password"
          value={next}
          onChange={(e) => setNext(e.target.value)}
          disabled={saving}
          error={fieldErrors.newPassword}
          hint={`${PASSWORD_MIN_LENGTH}~${PASSWORD_MAX_LENGTH}자, 현재 비밀번호와 달라야 해요.`}
        />
        <TextField
          id="pw-new-confirm"
          label="새 비밀번호 확인"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          disabled={saving}
          error={fieldErrors.confirm}
        />
        {formError && (
          <p className="rounded-[10px] bg-red-50 px-3.5 py-3 text-sm/[normal] text-red-700" role="alert">
            {formError}
          </p>
        )}
        <p
          className="rounded-[10px] bg-primary-50 px-3.5 py-3 text-sm/[normal] text-primary-800 empty:hidden"
          role="status"
        >
          {notice}
        </p>
        <div className="flex justify-end">
          <button type="submit" className={button.solid} disabled={saving} aria-busy={saving}>
            {saving ? "바꾸는 중..." : "비밀번호 바꾸기"}
          </button>
        </div>
      </form>
    </section>
  );
}
