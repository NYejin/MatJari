import { useEffect, useState, type FormEvent } from "react";
import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { useAuthRedirect } from "../hooks/useAuthRedirect";
import { parseApiError } from "../api/errors";
import { clearSuspensionNotice, parseSuspension, peekSuspensionNotice, suspensionMessage } from "../api/suspension";
import {
  compactErrors,
  normalizeEmail,
  pickFieldErrors,
  validateEmail,
  validateLoginPassword,
} from "../api/authValidation";
import TextField from "../components/TextField";
import { authFormClasses as ui } from "../components/authFormClasses";

// FR-01 회원가입/로그인 — 로그인 화면

const FIELDS = ["email", "password"] as const;
type Field = (typeof FIELDS)[number];
type FieldErrors = Partial<Record<Field, string>>;

function validate(email: string, password: string): FieldErrors {
  return compactErrors<Field>({ email: validateEmail(email), password: validateLoginPassword(password) });
}

export default function LoginPage() {
  const { isAuthenticated, isInitializing, login } = useAuth();
  const { redirectTo, forwardState, notice: stateNotice, email: prefillEmail } = useAuthRedirect();
  const location = useLocation();
  const navigate = useNavigate();

  // 일회성 값(안내 문구/이메일 프리필)은 최초 진입 시 컴포넌트 state로 옮겨 보존한다
  const [notice] = useState(stateNotice);
  // 이용 정지로 로그아웃된 경우의 안내(전역 인터셉터가 저장). 다음 로그인 시도 때 지운다
  const [suspendedNotice, setSuspendedNotice] = useState<string | null>(peekSuspensionNotice);
  const [email, setEmail] = useState(prefillEmail ?? "");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // 히스토리 state에서 notice/email을 제거해 새로고침·뒤로가기 시 다시 표시되지 않게 한다 (from은 유지)
  const hasOneTimeState = stateNotice !== undefined || prefillEmail !== undefined;
  useEffect(() => {
    // 로그인 상태면 아래 <Navigate>가 이동을 담당하므로 경합하지 않게 건너뜀
    if (!hasOneTimeState || isAuthenticated) return;
    navigate(
      { pathname: location.pathname, search: location.search, hash: location.hash },
      { replace: true, state: forwardState }
    );
    // forwardState는 렌더마다 새 객체이므로 의존성에서 제외 (일회성 값이 있을 때만 1회 실행)
  }, [hasOneTimeState, isAuthenticated, navigate, location.pathname, location.search, location.hash]);

  // 이미 로그인 상태(또는 방금 로그인 성공)면 원래 경로로 이동
  if (isAuthenticated) return <Navigate to={redirectTo} replace />;
  if (isInitializing) return null;

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (submitting) return;

    const errors = validate(email, password);
    setFieldErrors(errors);
    setFormError(null);
    if (Object.keys(errors).length > 0) return;
    clearSuspensionNotice();
    setSuspendedNotice(null);

    setSubmitting(true);
    try {
      // 서버와 동일하게 trim + 소문자 정규화
      await login({ email: normalizeEmail(email), password });
      // 성공 시 isAuthenticated가 true가 되어 위의 <Navigate>로 이동한다
    } catch (err) {
      const suspension = parseSuspension(err);
      if (suspension) {
        setFieldErrors({});
        setFormError(suspensionMessage(suspension));
        setSubmitting(false);
        return;
      }
      const parsed = parseApiError(err, "로그인에 실패했습니다. 잠시 후 다시 시도해 주세요.");
      const { fieldErrors: next, hasFieldError } = pickFieldErrors(parsed.fieldErrors, FIELDS);
      setFieldErrors(next);
      // @Valid 필드 오류가 입력칸 아래 표시되면 상단 메시지는 중복이므로 생략
      setFormError(hasFieldError ? null : parsed.message);
      setSubmitting(false);
    }
  };

  return (
    <div className={ui.page}>
      <div className={ui.card}>
        <h1 className={ui.title}>SeatSwap 로그인</h1>
        <p className={ui.subtitle}>같은 공연 티켓 보유자와 좌석을 교환하세요.</p>

        {notice && (
          <p className={ui.notice} role="status">
            {notice}
          </p>
        )}

        {suspendedNotice && (
          <p className={ui.formError} role="alert">
            {suspendedNotice}
          </p>
        )}

        <form className={ui.form} onSubmit={handleSubmit} noValidate>
          <TextField
            id="login-email"
            label="이메일"
            type="email"
            inputMode="email"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={submitting}
            error={fieldErrors.email}
            placeholder="you@example.com"
          />

          <TextField
            id="login-password"
            label="비밀번호"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            disabled={submitting}
            error={fieldErrors.password}
          />

          {formError && (
            <p className={ui.formError} role="alert">
              {formError}
            </p>
          )}

          <button type="submit" className={ui.submit} disabled={submitting} aria-busy={submitting}>
            {submitting ? "로그인 중..." : "로그인"}
          </button>
        </form>

        <p className={ui.footer}>
          아직 계정이 없나요?{" "}
          <Link to="/signup" state={forwardState} className={ui.link}>
            회원가입
          </Link>
        </p>
      </div>
    </div>
  );
}
