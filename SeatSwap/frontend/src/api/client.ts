import axios from "axios";
import { API_BASE_URL } from "./authClient";
import { createAuthRefreshHandler } from "./authInterceptor";
import { refreshSession } from "./session";
import { parseSuspension, saveSuspensionNotice } from "./suspension";
import { tokenStorage } from "./tokenStorage";

// JWT 인터셉터는 여기 한 곳에서만 처리한다 (react-conventions 스킬 참고)
export const apiClient = axios.create({ baseURL: API_BASE_URL });

/** 인터셉터 없는 인증 전용 클라이언트 (authClient.ts 참고) */
export { authClient } from "./authClient";

apiClient.interceptors.request.use((config) => {
  const token = tokenStorage.getAccessToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// 401 → single-flight refreshSession()으로 재발급 후 원 요청 1회 재시도 (정책은 authInterceptor.ts 주석 참고).
// AuthProvider의 만료 60초 전 선제 재발급 타이머가 1차, 이 인터셉터는 보조 안전망이다.
apiClient.interceptors.response.use(
  undefined,
  createAuthRefreshHandler({
    retry: (config) => apiClient.request(config),
    refreshSession,
    getAccessToken: tokenStorage.getAccessToken,
    clearTokens: tokenStorage.clear,
  })
);

// 이용 정지(403 ACCOUNT_SUSPENDED): 재발급·재시도 없이 토큰을 지워 로그아웃 상태로 돌리고(보호 라우트가 /login으로 보낸다),
// 안내 문구는 로그인 화면이 보여 준다. 401이 아니라서 위 재발급 핸들러는 건드리지 않는다.
apiClient.interceptors.response.use(undefined, (error: unknown) => {
  const suspension = parseSuspension(error);
  if (suspension) {
    saveSuspensionNotice(suspension);
    tokenStorage.clear();
  }
  return Promise.reject(error);
});
