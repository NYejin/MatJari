import { useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { authApi } from "../api/auth";
import { parseApiError, passwordResetRateLimitMessage } from "../api/errors";
import { normalizeEmail, validateEmail } from "../api/authValidation";
import TextField from "../components/TextField";
import { authFormClasses as ui } from "../components/authFormClasses";

// 비밀번호 찾기 — 공개 라우트 /forgot-password. 가입 여부를 알려 주지 않도록 성공·실패와 무관하게 같은 안내를 보인다.

const SENT_NOTICE =
  "입력한 이메일로 가입된 계정이 있으면 비밀번호 재설정 링크를 보냈어요. 메일이 보이지 않으면 스팸함도 확인해 주세요. 링크는 30분 동안 쓸 수 있어요.";

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState("");
  const [emailError, setEmailError] = useState<string | undefined>();
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [sent, setSent] = useState(false);

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    if (submitting) return;
    const error = validateEmail(email);
    setEmailError(error);
    setFormError(null);
    if (error) return;

    setSubmitting(true);
    try {
      await authApi.requestPasswordReset(normalizeEmail(email));
      setSent(true);
    } catch (err) {
      const limited = passwordResetRateLimitMessage(err);
      if (limited) {
        setFormError(limited);
      } else {
        const parsed = parseApiError(err, "요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.");
        if (parsed.fieldErrors.email) setEmailError(parsed.fieldErrors.email);
        else setFormError(parsed.message);
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className={ui.page}>
      <div className={ui.card}>
        <h1 className={ui.title}>비밀번호 찾기</h1>
        <p className={ui.subtitle}>가입한 이메일을 입력하면 비밀번호를 다시 정할 수 있는 링크를 보내 드려요.</p>

        {sent && (
          <p className={ui.notice} role="status">
            {SENT_NOTICE}
          </p>
        )}

        <form className={ui.form} onSubmit={handleSubmit} noValidate>
          <TextField
            id="forgot-email"
            label="이메일"
            type="email"
            inputMode="email"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            disabled={submitting}
            error={emailError}
            placeholder="you@example.com"
            hint="소셜 로그인으로 가입한 계정은 비밀번호가 없어 메일이 가지 않아요."
          />

          {formError && (
            <p className={ui.formError} role="alert">
              {formError}
            </p>
          )}

          <button type="submit" className={ui.submit} disabled={submitting} aria-busy={submitting}>
            {submitting ? "보내는 중..." : sent ? "링크 다시 보내기" : "재설정 링크 보내기"}
          </button>
        </form>

        <p className={ui.footer}>
          <Link to="/login" className={ui.link}>
            로그인으로 돌아가기
          </Link>
        </p>
      </div>
    </div>
  );
}
