import { useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import { useAuthRedirect, type AuthRouteState } from "../hooks/useAuthRedirect";
import { authApi } from "../api/auth";
import { parseApiError } from "../api/errors";
import {
  NICKNAME_MAX_LENGTH,
  NICKNAME_MIN_LENGTH,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  compactErrors,
  normalizeEmail,
  normalizeNickname,
  pickFieldErrors,
  validateEmail,
  validateNickname,
  validatePasswordConfirm,
  validateSignupPassword,
} from "../api/authValidation";
import TextField from "../components/TextField";
import { authFormClasses as ui } from "../components/authFormClasses";
import SocialLoginButtons from "../components/SocialLoginButtons";

// FR-01 회원가입/로그인 — 회원가입 화면

/** 서버 @Valid 오류가 올 수 있는 필드 (passwordConfirm은 클라이언트 전용) */
const SERVER_FIELDS = ["email", "password", "nickname"] as const;
type Field = (typeof SERVER_FIELDS)[number] | "passwordConfirm";
type FieldErrors = Partial<Record<Field, string>>;

interface FormValues {
  email: string;
  password: string;
  passwordConfirm: string;
  nickname: string;
}

function validate(values: FormValues): FieldErrors {
  return compactErrors<Field>({
    email: validateEmail(values.email),
    password: validateSignupPassword(values.password),
    passwordConfirm: validatePasswordConfirm(values.password, values.passwordConfirm),
    nickname: validateNickname(values.nickname),
  });
}

const SIGNUP_DONE_NOTICE = "가입이 완료되었습니다. 로그인해 주세요.";

export default function SignupPage() {
  const { isAuthenticated, isInitializing, login } = useAuth();
  const { redirectTo, forwardState, from } = useAuthRedirect();
  const navigate = useNavigate();

  const [values, setValues] = useState<FormValues>({ email: "", password: "", passwordConfirm: "", nickname: "" });
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // 이미 로그인 상태(또는 가입 직후 자동 로그인 성공)면 원래 경로로 이동
  if (isAuthenticated) return <Navigate to={redirectTo} replace />;
  if (isInitializing) return null;

  const setValue = (key: keyof FormValues) => (e: { target: { value: string } }) =>
    setValues((prev) => ({ ...prev, [key]: e.target.value }));

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (submitting) return;

    const errors = validate(values);
    setFieldErrors(errors);
    setFormError(null);
    if (Object.keys(errors).length > 0) return;

    const email = normalizeEmail(values.email);
    const { password } = values;

    setSubmitting(true);
    try {
      await authApi.signup({ email, password, nickname: normalizeNickname(values.nickname) });
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
        <h1 className={ui.title}>SeatSwap 회원가입</h1>
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
