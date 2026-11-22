import { useEffect, useId, useState } from "react";
import { usersApi } from "../api/users";
import { parseApiError } from "../api/errors";
import type { NotificationSettings } from "../types/user";
import { FOCUS_RING, button, ui } from "./ui";

// 마이페이지 '알림 설정' 카드 — 알림 메일 받기 스위치 (GET·PATCH /api/users/me/notification-settings).
// 저장 중에는 스위치를 잠그고, 실패하면 이전 값으로 되돌린다.

export default function NotificationSettingsCard() {
  const [settings, setSettings] = useState<NotificationSettings | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [retryKey, setRetryKey] = useState(0);
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
