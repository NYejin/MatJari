import { useCallback, useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { getErrorCode, parseApiError } from "../api/errors";
import { parseSuspension, suspensionMessage } from "../api/suspension";
import {
  PROVIDER_LABELS,
  confirmSocialLink,
  fetchSocialLinks,
  isSocialProvider,
  requestSocialLink,
  socialLinkErrorMessage,
  unlinkSocial,
  type SocialLinkStatus,
} from "../api/socialLink";
import type { SocialProvider } from "../api/socialAuth";
import { button, ui } from "./ui";

// 마이페이지 '소셜 계정 연결' 카드. 활성 제공자가 없으면 숨긴다.
// 연결: link API → authorizeUrl로 이동 → 성공하면 /me#linkCode= 를 confirm API로 확정, 실패는 /me?socialLinkError= 를 읽어 보여 주고 지운다.

const UNLINK_BLOCKED_HINT = "로그인 수단이 하나뿐이라 해제할 수 없어요.";

interface ConfirmResult {
  kind: "ok" | "error";
  text: string;
  /** 오류여도 목록을 다시 불러올지(이미 연결됨 등) */
  reload?: boolean;
}

// 일회용 코드는 한 번만 확정해야 하므로 확정 요청은 모듈 수준 Promise 하나를 공유한다(StrictMode 이중 effect 대비).
let confirmInflight: Promise<ConfirmResult> | null = null;

/** 현재 주소의 `#linkCode=`를 읽고 즉시 주소에서 지운다 */
function takeLinkCodeFromHash(): string | null {
  const hash = window.location.hash;
  if (!hash) return null;
  const code = new URLSearchParams(hash.replace(/^#/, "")).get("linkCode");
  if (code === null) return null;
  window.history.replaceState(window.history.state, "", window.location.pathname + window.location.search);
  return code.trim() || "";
}

async function runConfirm(code: string): Promise<ConfirmResult> {
  if (!code) return { kind: "error", text: socialLinkErrorMessage("INVALID_LINK_CODE") };
  try {
    const provider = await confirmSocialLink(code);
    return { kind: "ok", text: provider ? `${PROVIDER_LABELS[provider]} 계정을 연결했어요.` : "소셜 계정을 연결했어요." };
  } catch (err) {
    const suspension = parseSuspension(err);
    if (suspension) return { kind: "error", text: suspensionMessage(suspension) };
    const errCode = getErrorCode(err);
    if (errCode && Object.prototype.hasOwnProperty.call(CONFIRM_CODES, errCode)) {
      return { kind: "error", text: socialLinkErrorMessage(errCode), reload: errCode !== "INVALID_LINK_CODE" };
    }
    return { kind: "error", text: parseApiError(err, socialLinkErrorMessage("")).message };
  }
}

const CONFIRM_CODES = { INVALID_LINK_CODE: 1, SOCIAL_ALREADY_LINKED: 1, SOCIAL_LINKED_OTHER: 1 };

/** 코드가 있으면 확정을 시작하고(이미 진행 중이면 같은 Promise 공유), 없으면 null */
function startConfirm(): Promise<ConfirmResult> | null {
  if (confirmInflight) return confirmInflight;
  const code = takeLinkCodeFromHash();
  if (code === null) return null;
  const p = runConfirm(code).finally(() => {
    if (confirmInflight === p) confirmInflight = null;
  });
  confirmInflight = p;
  return p;
}

function linkedDate(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  return m ? `${m[1]}.${m[2]}.${m[3]}` : "";
}

export default function SocialLinkCard() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [status, setStatus] = useState<SocialLinkStatus | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busy, setBusy] = useState<SocialProvider | null>(null);
  const [confirming, setConfirming] = useState<SocialProvider | null>(null);
  const [message, setMessage] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  const load = useCallback(async () => {
    try {
      setStatus(await fetchSocialLinks());
      setLoadFailed(false);
    } catch {
      setLoadFailed(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // 성공 콜백: /me#linkCode= 를 읽어 바로 지우고 confirm으로 확정한다. 요청은 모듈 수준 Promise로 1번만 나간다.
  useEffect(() => {
    let active = true;
    const p = startConfirm();
    if (!p) return;
    void p.then((result) => {
      if (!active) return;
      setMessage(result);
      if (result.kind === "ok" || result.reload) void load();
    });
    return () => {
      active = false;
    };
  }, [load]);

  // 실패 콜백 쿼리를 한 번 읽고 지운다 (replace — 뒤로 가기에 남기지 않음). socialLinked·ALREADY_LINKED(_OTHER)는 호환용으로 무시 가능하게 둔다.
  const linkedParam = params.get("socialLinked");
  const errorParam = params.get("socialLinkError");
  const untilParam = params.get("until");
  useEffect(() => {
    if (linkedParam === null && errorParam === null) return;
    if (errorParam !== null) {
      setMessage({ kind: "error", text: socialLinkErrorMessage(errorParam, untilParam) });
    } else if (isSocialProvider(linkedParam)) {
      setMessage({ kind: "ok", text: `${PROVIDER_LABELS[linkedParam]} 계정을 연결했어요.` });
    }
    const next = new URLSearchParams(params);
    next.delete("socialLinked");
    next.delete("socialLinkError");
    next.delete("until");
    const search = next.toString();
    navigate({ search: search ? `?${search}` : "" }, { replace: true });
  }, [linkedParam, errorParam, untilParam, params, navigate]);

  const handleLink = async (provider: SocialProvider) => {
    if (busy) return;
    setBusy(provider);
    setMessage(null);
    try {
      const url = await requestSocialLink(provider);
      window.location.assign(url);
      // 이동 중에는 버튼을 잠근 채 둔다
    } catch (err) {
      setBusy(null);
      const code = getErrorCode(err);
      setMessage({
        kind: "error",
        text: code === "SOCIAL_ALREADY_LINKED" ? socialLinkErrorMessage("ALREADY_LINKED") : parseApiError(err, socialLinkErrorMessage("")).message,
      });
      if (code === "SOCIAL_ALREADY_LINKED") void load();
    }
  };

  const handleUnlink = async (provider: SocialProvider) => {
    if (busy) return;
    setBusy(provider);
    setMessage(null);
    try {
      await unlinkSocial(provider);
      setMessage({ kind: "ok", text: `${PROVIDER_LABELS[provider]} 연결을 해제했어요.` });
      setConfirming(null);
    } catch (err) {
      const code = getErrorCode(err);
      setMessage({
        kind: "error",
        text: code === "LAST_LOGIN_METHOD" ? UNLINK_BLOCKED_HINT : parseApiError(err, "연결을 해제하지 못했어요.").message,
      });
      setConfirming(null);
    } finally {
      setBusy(null);
      await load();
    }
  };

  const hasProviders = !!status && status.providers.length > 0;
  // 활성 제공자가 없고 알릴 결과도 없으면 카드를 숨긴다
  if (!hasProviders && !message && !loadFailed) return null;

  const linkedMap = new Map(status?.accounts.map((a) => [a.provider, a]));
  const canUnlink = status?.canUnlink === true;

  return (
    <section className={ui.card} aria-labelledby="mypage-social-link">
      <h2 id="mypage-social-link" className="text-base/[normal] font-semibold text-gray-900">
        소셜 계정 연결
      </h2>
      <p className={`${ui.status} not-empty:mt-2`} role="status">
        {message?.kind === "ok" ? message.text : ""}
      </p>
      {message?.kind === "error" && (
        <p className={`${ui.errorBox} mt-2`} role="alert">
          {message.text}
        </p>
      )}
      {loadFailed && !status && (
        <div className="mt-2">
          <p className={ui.muted}>연결 정보를 불러오지 못했어요.</p>
          <button type="button" className={`${button.outline} mt-2`} onClick={() => void load()}>
            다시 시도
          </button>
        </div>
      )}
      {hasProviders && (
        <>
          <ul className="mt-2 divide-y divide-gray-200">
            {status.providers.map((provider) => {
              const account = linkedMap.get(provider);
              const label = PROVIDER_LABELS[provider];
              const rowBusy = busy === provider;
              return (
                <li key={provider} className="flex min-h-14 flex-wrap items-center justify-between gap-2 py-2">
                  <div className="min-w-0">
                    <p className="text-[0.9375rem]/[normal] font-semibold text-gray-900">{label}</p>
                    <p className={ui.muted}>
                      {account ? `연결됨${linkedDate(account.linkedAt) ? ` · ${linkedDate(account.linkedAt)}` : ""}` : "연결 안 됨"}
                    </p>
                  </div>
                  {!account && (
                    <button
                      type="button"
                      className={button.outline}
                      disabled={busy !== null}
                      onClick={() => void handleLink(provider)}
                      aria-label={`${label} 계정 연결`}
                    >
                      {rowBusy ? "이동 중..." : "연결"}
                    </button>
                  )}
                  {account && confirming !== provider && (
                    <button
                      type="button"
                      className={`${button.outline} aria-disabled:cursor-not-allowed aria-disabled:opacity-60`}
                      // 해제 불가면 숨기지 않고 비활성 + 사유 안내(aria-disabled라 포커스·낭독은 유지)
                      aria-disabled={!canUnlink || busy !== null}
                      aria-describedby={!canUnlink ? "social-unlink-hint" : undefined}
                      onClick={() => {
                        if (canUnlink && busy === null) setConfirming(provider);
                      }}
                      aria-label={`${label} 연결 해제`}
                    >
                      연결 해제
                    </button>
                  )}
                  {account && confirming === provider && (
                    <div className="flex w-full flex-col gap-2 sm:w-auto sm:items-end" role="group" aria-label={`${label} 연결 해제 확인`}>
                      <p className={ui.warning}>{label} 로그인을 더 이상 쓸 수 없게 돼요. 해제할까요?</p>
                      <div className="flex justify-end gap-2">
                        <button type="button" className={button.outline} disabled={rowBusy} onClick={() => setConfirming(null)}>
                          취소
                        </button>
                        <button type="button" className={button.danger} disabled={rowBusy} onClick={() => void handleUnlink(provider)}>
                          {rowBusy ? "해제 중..." : "해제"}
                        </button>
                      </div>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
          {!canUnlink && status.accounts.length > 0 && (
            <p id="social-unlink-hint" className={`${ui.hint} mt-2`}>
              {UNLINK_BLOCKED_HINT}
            </p>
          )}
        </>
      )}
    </section>
  );
}
