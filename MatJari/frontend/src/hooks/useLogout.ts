import { useCallback } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "./useAuth";

/**
 * 로그아웃 공용 동작 (Header, MyPage).
 * 보호 화면을 먼저 벗어난 뒤 세션을 지운다. 순서가 반대면 ProtectedRoute가 현재 화면을 from으로
 * /login 리다이렉트를 걸 수 있어 "/" 이동과 경합한다.
 */
export function useLogout(): () => void {
  const { logout } = useAuth();
  const navigate = useNavigate();
  return useCallback(() => {
    navigate("/", { replace: true });
    logout();
  }, [navigate, logout]);
}
