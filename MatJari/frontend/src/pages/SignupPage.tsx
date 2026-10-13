import { useEffect, useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { useAuthRedirect, type AuthRouteState } from "../hooks/useAuthRedirect";
import { authApi } from "../api/auth";
import { parseApiError } from "../api/errors";
import {
  NICKNAME_MAX_LENGTH,
  NICKNAME_MIN_LENGTH,
  EMAIL_CODE_LENGTH,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  compactErrors,
  normalizeEmail,
  normalizeNickname,
  pickFieldErrors,
  validateEmail,
  validateEmailCode,
  validateNickname,
  validatePasswordConfirm,
  validateSignupPassword,
} from "../api/authValidation";
import TextField from "../components/TextField";
import { authFormClasses as ui } from "../components/authFormClasses";
import SocialLoginButtons from "../components/SocialLoginButtons";
import { button } from "../components/ui";

// FR-01 회원가입/로그인 — 회원가입 화면

/** 서버 @Valid 오류가 올 수 있는 필드 (passwordConfirm은 클라이언트 전용) */
const SERVER_FIELDS = ["email", "emailCode", "password", "nickname"] as const;
type Field = (typeof SERVER_FIELDS)[number] | "passwordConfirm";
type FieldErrors = Partial<Record<Field, string>>;

interface FormValues {
  email: string;
  emailCode: string;
  password: string;
  passwordConfirm: string;
  nickname: string;
}

function validate(values: FormValues, codeRequested: boolean): FieldErrors {
  return compactErrors<Field>({
    email: validateEmail(values.email),
    emailCode: codeRequested ? validateEmailCode(values.emailCode) : "먼저 '인증번호 받기'를 눌러 메일로 받은 번호를 입력해주세요.",
    password: validateSignupPassword(values.password),
    passwordConfirm: validatePasswordConfirm(values.password, values.passwordConfirm),
    nickname: validateNickname(values.nickname),
  });
}

const SIGNUP_DONE_NOTICE = "가입이 완료되었습니다. 로그인해 주세요.";

/** 서버가 429 로 알려 주는 대기 시간(Retry-After)을 초로 읽는다. 없으면 null */
function retryAfterSeconds(err: unknown): number | null {
  const data = (err as { response?: { data?: { retryAfterSeconds?: unknown } } })?.response?.data;
  return typeof data?.retryAfterSeconds === "number" ? data.retryAfterSeconds : null;
}

export default function SignupPage() {
  const { isAuthenticated, isInitializing, login } = useAuth();
  const { redirectTo, forwardState, from } = useAuthRedirect();
  const navigate = useNavigate();

  const [values, setValues] = useState<FormValues>({
    email: "",
    emailCode: "",
    password: "",
    passwordConfirm: "",
    nickname: "",
  });
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // 인증번호: 받은 적이 있는 이메일(정규화 값)과 다시 보낼 수 있는 시각(ms)
  const [codeEmail, setCodeEmail] = useState<string | null>(null);
  const [resendAt, setResendAt] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const [sending, setSending] = useState(false);
  const [codeNotice, setCodeNotice] = useState<string | null>(null);

  // 다시 받기 대기 시간 표시용 1초 타이머 (대기 중일 때만 돈다)
  useEffect(() => {
    if (resendAt <= Date.now()) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [resendAt]);

  // 이미 로그인 상태(또는 가입 직후 자동 로그인 성공)면 원래 경로로 이동
  if (isAuthenticated) return <Navigate to={redirectTo} replace />;
  if (isInitializing) return null;

  const setValue = (key: keyof FormValues) => (e: { target: { value: string } }) =>
    setValues((prev) => ({ ...prev, [key]: e.target.value }));

  // 인증번호는 받은 이메일과 같은 이메일일 때만 유효하다 — 이메일을 바꾸면 다시 받게 한다
  const normalizedEmail = normalizeEmail(values.email);
  const codeRequested = codeEmail !== null && codeEmail === normalizedEmail;
  const waitSeconds = Math.max(0, Math.ceil((resendAt - now) / 1000));

  const handleSendCode = async () => {
    if (sending || submitting || waitSeconds > 0) return;
    const emailError = validateEmail(values.email);
    setFormError(null);
    setCodeNotice(null);
    if (emailError) {
      setFieldErrors((prev) => ({ ...prev, email: emailError }));
      return;
    }
    setSending(true);
    try {
      const res = await authApi.sendEmailCode(normalizedEmail);
      setCodeEmail(normalizedEmail);
      setValues((prev) => ({ ...prev, emailCode: "" }));
      setFieldErrors((prev) => ({ ...prev, email: undefined, emailCode: undefined }));
      setNow(Date.now());
      setResendAt(Date.now() + res.resendAfterSeconds * 1000);
      setCodeNotice(`인증번호를 보냈어요. 메일함(스팸함 포함)을 확인하고 ${Math.floor(res.expiresInSeconds / 60)}분 안에 입력해주세요.`);
    } catch (err) {
      const parsed = parseApiError(err, "인증번호를 보내지 못했어요. 잠시 후 다시 시도해 주세요.");
      const wait = retryAfterSeconds(err);
      if (wait) {
        setNow(Date.now());
        setResendAt(Date.now() + wait * 1000);
      }
      // 이메일 오류(이미 가입된 이메일 등)는 입력칸 아래에, 나머지는 위쪽 메시지로
      if (parsed.fieldErrors.email) setFieldErrors((prev) => ({ ...prev, email: parsed.fieldErrors.email }));
      else setFormError(parsed.message);
    } finally {
      setSending(false);
    }
  };

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (submitting) return;

    const errors = validate(values, codeRequested);
    setFieldErrors(errors);
    setFormError(null);
    if (Object.keys(errors).length > 0) return;

    const email = normalizeEmail(values.email);
    const { password } = values;

    setSubmitting(true);
    try {
      await authApi.signup({
        email,
        password,
        nickname: normalizeNickname(values.nickname),
        emailCode: values.emailCode.trim(),
      });
    } catch (err) {
      const parsed = parseApiError(err, "회원가입에 실패했습니다. 잠시 후 다시 시도해 주세요.");
      const { fieldErrors: next, hasFieldError } = pickFieldErrors(parsed.fieldErrors, SERVER_FIELDS);
      setFieldErrors(next);
      // 필드 오류(이메일 중복 포함)가 입력칸 아래 표시되면 상단 메시지는 중복이므로 생략
      setFormError(hasFieldError ? null : parsed.message);
      setSubmitting(false);
      return;
    }

    // 가입 성공 → 같은 자격증명으로 자동 로그인. 성공 시 isAuthenticated가 true가 되어 위의 <Navigate>로 이동.
    try {
      await login({ email, password });
    } catch {
      // 계정은 이미 생성됨 — 로그인 화면에서 다시 시도하도록 안내 (원래 복귀 경로는 유지)
      const state: AuthRouteState = { from, notice: SIGNUP_DONE_NOTICE, email };
      navigate("/login", { replace: true, state });
    }
  };

  return (
    <div className={ui.page}>
      <div className={ui.card}>
        <h1 className={ui.title}>맞자리 회원가입</h1>
        <p className={ui.subtitle}>가입하고 같은 공연 티켓 보유자와 좌석을 교환하세요.</p>

        <form className={ui.form} onSubmit={handleSubmit} noValidate>
          <TextField
            id="signup-email"
            label="이메일"
            type="email"
            inputMode="email"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            value={values.email}
            onChange={setValue("email")}
            disabled={submitting}
            error={fieldErrors.email}
            placeholder="you@example.com"
          />

          <div className="flex flex-col gap-2">
            <button
              type="button"
              className={button.outline}
              onClick={handleSendCode}
              disabled={sending || submitting || waitSeconds > 0}
              aria-busy={sending}
            >
              {sending
                ? "보내는 중..."
                : waitSeconds > 0
                  ? `다시 받기 (${waitSeconds}초 후)`
                  : codeRequested
                    ? "인증번호 다시 받기"
                    : "인증번호 받기"}
            </button>
            <p className="text-[0.8125rem]/[normal] text-primary-800" role="status">
              {codeRequested ? codeNotice : null}
            </p>
          </div>

          {codeRequested && (
            <TextField
              id="signup-email-code"
              label="인증번호"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={EMAIL_CODE_LENGTH}
              value={values.emailCode}
              onChange={setValue("emailCode")}
              disabled={submitting}
              error={fieldErrors.emailCode}
              hint="메일로 받은 숫자 6자리"
            />
          )}
          {!codeRequested && fieldErrors.emailCode && (
            <p className="text-[0.8125rem]/[normal] text-red-600" role="alert">
              {fieldErrors.emailCode}
            </p>
          )}

          <TextField
            id="signup-password"
            label="비밀번호"
            type="password"
            autoComplete="new-password"
            value={values.password}
            onChange={setValue("password")}
            disabled={submitting}
            error={fieldErrors.password}
            hint={`${PASSWORD_MIN_LENGTH}~${PASSWORD_MAX_LENGTH}자`}
          />

          <TextField
            id="signup-password-confirm"
            label="비밀번호 확인"
            type="password"
            autoComplete="new-password"
            value={values.passwordConfirm}
            onChange={setValue("passwordConfirm")}
            disabled={submitting}
            error={fieldErrors.passwordConfirm}
          />

          <TextField
            id="signup-nickname"
            label="닉네임"
            type="text"
            autoComplete="nickname"
            value={values.nickname}
            onChange={setValue("nickname")}
            disabled={submitting}
            error={fieldErrors.nickname}
            hint={`${NICKNAME_MIN_LENGTH}~${NICKNAME_MAX_LENGTH}자`}
          />

          {formError && (
            <p className={ui.formError} role="alert">
              {formError}
            </p>
          )}

          <button type="submit" className={ui.submit} disabled={submitting} aria-busy={submitting}>
            {submitting ? "가입 중..." : "회원가입"}
          </button>

          <p className="text-[0.8125rem]/[normal] text-gray-700">
            가입하면{" "}
            <Link
              to="/privacy"
              target="_blank"
              rel="noopener"
              className="font-semibold text-primary-700 underline underline-offset-2"
            >
              개인정보처리방침
            </Link>
            에 동의한 것으로 봐요. 만 14세 미만은 가입할 수 없어요.
          </p>
        </form>

        <SocialLoginButtons />

        <p className={ui.footer}>
          이미 계정이 있나요?{" "}
          <Link to="/login" state={forwardState} className={ui.link}>
            로그인
          </Link>
        </p>
      </div>
    </div>
  );
}
