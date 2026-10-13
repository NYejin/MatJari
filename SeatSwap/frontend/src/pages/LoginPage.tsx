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
import SocialLoginButtons from "../components/SocialLoginButtons";
import WithdrawDialog from "../components/WithdrawDialog";
import { socialErrorMessage } from "../api/socialAuth";

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
  // 소셜 로그인 실패 안내(?socialError=&until=). 최초 진입 시 state로 옮기고 쿼리는 지운다
  const [socialNotice] = useState<string | null>(() => {
    const params = new URLSearchParams(location.search);
    const code = params.get("socialError");
    return code ? socialErrorMessage(code, params.get("until")) : null;
  });
  const hasSocialQuery = new URLSearchParams(location.search).has("socialError");
  const [email, setEmail] = useState(prefillEmail ?? "");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // 이용 정지 중 로그인(비밀번호 확인됨)이 준 탈퇴 전용 토큰 — 저장하지 않고 이 화면 메모리에만 둔다
  const [withdrawToken, setWithdrawToken] = useState<string | null>(null);
  const [withdrawOpen, setWithdrawOpen] = useState(false);
  const [withdrawnNotice, setWithdrawnNotice] = useState<string | null>(null);

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

  useEffect(() => {
    if (!hasSocialQuery || isAuthenticated) return;
    const params = new URLSearchParams(location.search);
    params.delete("socialError");
    params.delete("until");
    const search = params.toString();
    navigate(
      { pathname: location.pathname, search: search ? `?${search}` : "", hash: location.hash },
      { replace: true, state: location.state }
    );
  }, [hasSocialQuery, isAuthenticated, navigate, location.pathname, location.search, location.hash, location.state]);

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
    setWithdrawToken(null);
    setWithdrawnNotice(null);

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
        setWithdrawToken(suspension.withdrawToken ?? null);
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

        {withdrawnNotice && (
          <p className={ui.notice} role="status">
            {withdrawnNotice}
          </p>
        )}

        {socialNotice && (
          <p className={`${ui.formError} mb-4`} role="alert">
            {socialNotice}
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

          {withdrawToken && (
            <div className="flex flex-col gap-2">
              <p className={ui.notice}>정지 중에도 계정을 탈퇴할 수 있어요. 탈퇴하면 되돌릴 수 없어요.</p>
              <button type="button" className={ui.link} onClick={() => setWithdrawOpen(true)}>
                이 계정 탈퇴하기
              </button>
            </div>
          )}

          <button type="submit" className={ui.submit} disabled={submitting} aria-busy={submitting}>
            {submitting ? "로그인 중..." : "로그인"}
          </button>
        </form>

        <SocialLoginButtons />

        {withdrawOpen && withdrawToken && (
          <WithdrawDialog
            passwordLoginEnabled
            withdrawToken={withdrawToken}
            onClose={() => setWithdrawOpen(false)}
            onWithdrawn={() => {
              setWithdrawOpen(false);
              setWithdrawToken(null);
              setFormError(null);
              setPassword("");
              setWithdrawnNotice("탈퇴가 완료됐어요. 그동안 이용해 주셔서 고마워요.");
            }}
          />
        )}

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
