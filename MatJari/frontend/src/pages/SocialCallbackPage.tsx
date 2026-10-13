import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { parseSuspension, saveSuspensionNotice } from "../api/suspension";
import { exchangeSocialCode } from "../api/socialAuth";
import { getUserFromAccessToken, tokenStorage } from "../api/tokenStorage";
import { authFormClasses as ui } from "../components/authFormClasses";

// 소셜 로그인 콜백: 백엔드가 `/auth/callback#code=...`로 보낸다 (프래그먼트라 서버 로그·Referer에 남지 않음).
// 일회용 코드는 한 번만 교환해야 하므로, 교환은 모듈 수준 Promise 하나를 공유한다.
// React StrictMode가 effect를 두 번 돌려도(첫 번째 cleanup 후 재실행) 같은 Promise를 받아 요청은 1번만 나가고,
// 결과는 항상 "현재 살아 있는" effect만 처리한다. ref 가드 플래그를 쓰지 않으므로 중단된 요청 때문에 로딩에 갇히지 않는다.

const FAIL_PATH = "/login?socialError=INVALID_LOGIN_CODE";

interface CallbackResult {
  to: string;
}

let inflight: Promise<CallbackResult> | null = null;

/** 현재 주소의 `#code=`를 읽고 즉시 주소에서 지운다 */
function takeCodeFromHash(): string | null {
  const params = new URLSearchParams(window.location.hash.replace(/^#/, ""));
  const code = params.get("code");
  window.history.replaceState(window.history.state, "", window.location.pathname + window.location.search);
  return code && code.trim() ? code.trim() : null;
}

async function runExchange(code: string | null): Promise<CallbackResult> {
  if (!code) return { to: FAIL_PATH };
  try {
    const tokens = await exchangeSocialCode(code);
    if (!getUserFromAccessToken(tokens.accessToken)) return { to: FAIL_PATH };
    // 일반 로그인과 같은 저장소 — AuthProvider가 구독해 세션을 동기화한다
    tokenStorage.save(tokens);
    return { to: "/" };
  } catch (err) {
    const suspension = parseSuspension(err);
    if (suspension) {
      // 로그인 화면이 저장된 정지 안내를 보여 준다
      saveSuspensionNotice(suspension);
      return { to: "/login" };
    }
    return { to: FAIL_PATH };
  }
}

function startExchange(): Promise<CallbackResult> {
  if (inflight) return inflight;
  const p = runExchange(takeCodeFromHash()).finally(() => {
    if (inflight === p) inflight = null;
  });
  inflight = p;
  return p;
}

export default function SocialCallbackPage() {
  const navigate = useNavigate();

  useEffect(() => {
    let active = true;
    void startExchange().then((result) => {
      if (active) navigate(result.to, { replace: true });
    });
    return () => {
      active = false;
    };
  }, [navigate]);

  return (
    <div className={ui.page}>
      <div className={ui.card}>
        <p className={ui.subtitle} role="status">
          로그인 처리 중이에요...
        </p>
      </div>
    </div>
  );
}
