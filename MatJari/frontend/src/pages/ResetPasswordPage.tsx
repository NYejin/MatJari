import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";
import { authApi } from "../api/auth";
import { getErrorCode, parseApiError, passwordResetRateLimitMessage } from "../api/errors";
import {
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  compactErrors,
  validatePasswordConfirm,
  validateSignupPassword,
} from "../api/authValidation";
import { useAuth } from "../hooks/useAuth";
import TextField from "../components/TextField";
import AuthShell from "../components/AuthShell";
import { authFormClasses as ui } from "../components/authFormClasses";

// 비밀번호 재설정 — 공개 라우트 /reset-password#token=… . 토큰은 읽자마자 주소에서 지우고 메모리에만 둔다.

type Field = "newPassword" | "confirm";

/** location.hash 에서 token 값을 읽는다 (없으면 null) */
function readHashToken(hash: string): string | null {
  const value = new URLSearchParams(hash.startsWith("#") ? hash.slice(1) : hash).get("token");
  return value && value.trim() ? value : null;
}

export default function ResetPasswordPage() {
  const { isAuthenticated, logout } = useAuth();
  const navigate = useNavigate();
  // 첫 렌더에서 한 번만 읽어 메모리에 보관한다 (StrictMode 이중 실행에도 같은 값)
  const tokenRef = useRef<string | null | undefined>(undefined);
  if (tokenRef.current === undefined) tokenRef.current = readHashToken(window.location.hash);

  const [newPassword, setNewPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);
  const [invalidToken, setInvalidToken] = useState(tokenRef.current === null);

  // 화면 공유·기록에 토큰이 남지 않게 주소의 해시를 즉시 지운다
  useEffect(() => {
    if (window.location.hash) {
      window.history.replaceState(window.history.state, "", window.location.pathname + window.location.search);
    }
  }, []);

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const token = tokenRef.current;
    if (submitting || !token) return;
    const errors = compactErrors<Field>({
      newPassword: validateSignupPassword(newPassword),
      confirm: validatePasswordConfirm(newPassword, confirm),
    });
    setFieldErrors(errors);
    setFormError(null);
    if (Object.keys(errors).length > 0) return;

    setSubmitting(true);
    try {
      await authApi.confirmPasswordReset(token, newPassword);
      tokenRef.current = null;
      setNewPassword("");
      setConfirm("");
      setDone(true);
      // 이 탭이 로그인 상태였다면 서버가 모든 기기를 로그아웃시켰으므로 로컬 세션도 정리한다 (다른 탭에도 신호)
      if (isAuthenticated) void logout();
    } catch (err) {
      if (getErrorCode(err) === "INVALID_RESET_TOKEN") {
        tokenRef.current = null;
        setInvalidToken(true);
      } else {
        const limited = passwordResetRateLimitMessage(err);
        if (limited) {
          setFormError(limited);
        } else {
          const parsed = parseApiError(err, "비밀번호를 바꾸지 못했어요. 잠시 후 다시 시도해 주세요.");
          if (parsed.fieldErrors.newPassword) setFieldErrors({ newPassword: parsed.fieldErrors.newPassword });
          else setFormError(parsed.message);
        }
      }
    } finally {
      setSubmitting(false);
    }
  };

  if (done) {
    return (
      <AuthShell>
          <h1 className={ui.title}>비밀번호 재설정</h1>
          <p className={ui.notice} role="status">
            비밀번호를 바꿨어요. 모든 기기에서 로그아웃됐으니 새 비밀번호로 로그인해 주세요.
          </p>
          <button type="button" className={`${ui.submit} w-full`} onClick={() => navigate("/login", { replace: true })}>
            로그인
          </button>
        </AuthShell>
    );
  }

  if (invalidToken) {
    return (
      <AuthShell>
          <h1 className={ui.title}>비밀번호 재설정</h1>
          <p className={ui.formError} role="alert">
            링크가 만료됐거나 이미 사용됐어요. 비밀번호 재설정을 다시 요청해 주세요.
          </p>
          <p className={ui.footer}>
            <Link to="/forgot-password" className={ui.link}>
              다시 요청하기
            </Link>
          </p>
        </AuthShell>
    );
  }

  return (
    <AuthShell>
        <h1 className={ui.title}>새 비밀번호 정하기</h1>
        <p className={ui.subtitle}>앞으로 사용할 새 비밀번호를 입력해 주세요.</p>

        <form className={ui.form} onSubmit={handleSubmit} noValidate>
          <TextField
            id="reset-new-password"
            label="새 비밀번호"
            type="password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            disabled={submitting}
            error={fieldErrors.newPassword}
            hint={`${PASSWORD_MIN_LENGTH}~${PASSWORD_MAX_LENGTH}자`}
          />
          <TextField
            id="reset-new-password-confirm"
            label="새 비밀번호 확인"
            type="password"
            autoComplete="new-password"
            value={confirm}
            onChange={(e) => setConfirm(e.target.value)}
            disabled={submitting}
            error={fieldErrors.confirm}
          />

          {formError && (
            <p className={ui.formError} role="alert">
              {formError}
            </p>
          )}

          <button type="submit" className={ui.submit} disabled={submitting} aria-busy={submitting}>
            {submitting ? "바꾸는 중..." : "비밀번호 바꾸기"}
          </button>
        </form>
      </AuthShell>
  );
}
