import { useEffect, useId, useState } from "react";
import { usersApi } from "../api/users";
import { getErrorCode, getErrorNumber, parseApiError } from "../api/errors";
import { EMAIL_CODE_LENGTH, validateEmailCode } from "../api/authValidation";
import type { NotificationSettings } from "../types/user";
import TextField from "./TextField";
import { FOCUS_RING, button, ui } from "./ui";

// 마이페이지 '알림 설정' 카드 — 알림 메일 받기 스위치 (GET·PATCH /api/users/me/notification-settings).
// 저장 중에는 스위치를 잠그고, 실패하면 이전 값으로 되돌린다.

export default function NotificationSettingsCard() {
  const [settings, setSettings] = useState<NotificationSettings | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [retryKey, setRetryKey] = useState(0);
  // 이메일 확인: 다시 받기 가능 시각(ms), 인증번호 입력
  const [codeSent, setCodeSent] = useState(false);
  const [sending, setSending] = useState(false);
  const [resendAt, setResendAt] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const [code, setCode] = useState("");
  const [codeError, setCodeError] = useState<string | null>(null);
  const [verifyNotice, setVerifyNotice] = useState<string | null>(null);
  const [verifyError, setVerifyError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const titleId = useId();
  const descId = useId();

  useEffect(() => {
    let cancelled = false;
    setLoadError(null);
    usersApi
      .getNotificationSettings()
      .then((res) => {
        if (!cancelled) setSettings(res);
      })
      .catch((err: unknown) => {
        if (!cancelled) setLoadError(parseApiError(err, "알림 설정을 불러오지 못했어요.").message);
      });
    return () => {
      cancelled = true;
    };
  }, [retryKey]);

  useEffect(() => {
    if (resendAt <= Date.now()) return;
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [resendAt]);

  const waitSeconds = Math.max(0, Math.ceil((resendAt - now) / 1000));

  const sendCode = async () => {
    if (sending || waitSeconds > 0) return;
    setSending(true);
    setVerifyError(null);
    setVerifyNotice(null);
    try {
      await usersApi.sendEmailVerificationCode();
      setCodeSent(true);
      setCode("");
      setCodeError(null);
      setNow(Date.now());
      setResendAt(Date.now() + 60 * 1000);
      setVerifyNotice("인증번호를 보냈어요. 메일함(스팸함 포함)을 확인하고 10분 안에 입력해 주세요.");
    } catch (err) {
      const errCode = getErrorCode(err);
      if (errCode === "EMAIL_ALREADY_VERIFIED") {
        // 다른 탭·기기에서 이미 확인함 → 설정을 다시 읽는다
        setRetryKey((k) => k + 1);
        return;
      }
      if (errCode === "EMAIL_CODE_COOLDOWN" || errCode === "EMAIL_CODE_RATE_LIMITED") {
        const wait = getErrorNumber(err, "retryAfterSeconds");
        if (wait && wait > 0) {
          setNow(Date.now());
          setResendAt(Date.now() + wait * 1000);
        }
        setVerifyError(
          errCode === "EMAIL_CODE_COOLDOWN"
            ? "방금 인증번호를 보냈어요. 잠시 뒤 다시 받아 주세요."
            : "인증번호 요청이 많아요. 잠시 뒤 다시 시도해 주세요."
        );
      } else if (errCode === "EMAIL_SEND_FAILED" || errCode === "EMAIL_UNAVAILABLE") {
        setVerifyError("지금은 인증 메일을 보낼 수 없어요. 잠시 뒤 다시 시도해 주세요.");
      } else {
        setVerifyError(parseApiError(err, "인증번호를 보내지 못했어요. 잠시 뒤 다시 시도해 주세요.").message);
      }
    } finally {
      setSending(false);
    }
  };

  const confirmCode = async () => {
    if (confirming) return;
    const invalid = validateEmailCode(code);
    setCodeError(invalid ?? null);
    if (invalid) return;
    setConfirming(true);
    setVerifyError(null);
    try {
      const res = await usersApi.confirmEmailVerification(code.trim());
      setSettings(res);
      setCodeSent(false);
      setCode("");
      setVerifyNotice("이메일을 확인했어요");
    } catch (err) {
      const parsed = parseApiError(err, "이메일을 확인하지 못했어요. 잠시 뒤 다시 시도해 주세요.");
      if (parsed.fieldErrors.code) setCodeError(parsed.fieldErrors.code);
      else setVerifyError(parsed.message);
    } finally {
      setConfirming(false);
    }
  };

  const toggle = async () => {
    if (!settings || saving || !settings.emailAvailable) return;
    const previous = settings;
    const next = !settings.emailEnabled;
    setSettings({ ...settings, emailEnabled: next }); // 낙관적 표시, 실패하면 되돌린다
    setSaving(true);
    setSaveError(null);
    try {
      setSettings(await usersApi.updateNotificationSettings(next));
    } catch (err) {
      setSettings(previous);
      setSaveError(parseApiError(err, "알림 설정을 저장하지 못했어요. 잠시 후 다시 시도해 주세요.").message);
    } finally {
      setSaving(false);
    }
  };

  const disabled = !settings || saving || !settings.emailAvailable;

  return (
    <section className={`${ui.card} flex flex-col gap-3`} aria-labelledby={titleId}>
      <h2 id={titleId} className={ui.sectionTitle}>
        알림 설정
      </h2>
      {loadError ? (
        <>
          <p className={ui.errorBox} role="alert">
            {loadError}
          </p>
          <div>
            <button type="button" className={button.outline} onClick={() => setRetryKey((k) => k + 1)}>
              다시 시도
            </button>
          </div>
        </>
      ) : (
        <>
          <div className="flex min-h-11 items-center justify-between gap-3">
            <span className="text-[0.9375rem]/[normal] font-semibold text-gray-900">알림 메일 받기</span>
            <button
              type="button"
              role="switch"
              aria-checked={settings?.emailEnabled ?? false}
              aria-label="알림 메일 받기"
              aria-describedby={descId}
              aria-busy={saving}
              disabled={disabled}
              onClick={() => void toggle()}
              className={
                "relative inline-flex h-8 w-14 shrink-0 cursor-pointer touch-manipulation items-center rounded-full border-0 p-0 transition-colors " +
                "disabled:cursor-not-allowed disabled:opacity-60 " +
                `${settings?.emailEnabled ? "bg-primary-600" : "bg-gray-400"} ${FOCUS_RING}`
              }
            >
              <span
                aria-hidden="true"
                className={`inline-block size-6 rounded-full bg-white shadow transition-transform ${settings?.emailEnabled ? "translate-x-7" : "translate-x-1"}`}
              />
            </button>
          </div>
          <p id={descId} className={ui.hint}>
            사이트를 보고 있지 않을 때 새 제안·예약 제안·채팅·새 후보를 메일로 알려 드려요. 5분 안에 사이트에서 확인하면 메일은
            보내지 않고, 메일은 30분에 한 번까지만 보내요.
          </p>
          {settings && !settings.emailAvailable && (
            <p className={ui.warning}>소셜 계정으로 가입해 이메일이 없어 메일 알림을 받을 수 없어요.</p>
          )}
          {settings?.emailAvailable && !settings.emailVerified && (
            <div className="flex flex-col gap-2">
              <p className={ui.hint}>메일을 받으려면 먼저 이메일 주소를 확인해 주세요. 확인 전에는 알림 메일을 보내지 않아요.</p>
              <div>
                <button type="button" className={button.outline} onClick={() => void sendCode()} disabled={sending || waitSeconds > 0} aria-busy={sending}>
                  {sending ? "보내는 중..." : waitSeconds > 0 ? `다시 받기 (${waitSeconds}초 후)` : codeSent ? "인증번호 다시 받기" : "인증번호 받기"}
                </button>
              </div>
              {codeSent && (
                <div className="flex flex-col gap-2">
                  <TextField
                    id="notification-email-code"
                    label="인증번호"
                    type="text"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    maxLength={EMAIL_CODE_LENGTH}
                    value={code}
                    onChange={(e) => setCode(e.target.value)}
                    disabled={confirming}
                    error={codeError ?? undefined}
                    hint="메일로 받은 숫자 6자리"
                  />
                  <div>
                    <button type="button" className={button.solid} onClick={() => void confirmCode()} disabled={confirming} aria-busy={confirming}>
                      {confirming ? "확인 중..." : "확인"}
                    </button>
                  </div>
                </div>
              )}
            </div>
          )}
          {settings?.emailAvailable && settings.emailVerified && !verifyNotice && <p className={ui.hint}>확인된 이메일로 보내요.</p>}
          <p className="text-[0.8125rem]/[normal] text-primary-800" role="status">
            {verifyNotice}
          </p>
          {verifyError && (
            <p className={ui.errorBox} role="alert">
              {verifyError}
            </p>
          )}
          {saveError && (
            <p className={ui.errorBox} role="alert">
              {saveError}
            </p>
          )}
        </>
      )}
    </section>
  );
}
