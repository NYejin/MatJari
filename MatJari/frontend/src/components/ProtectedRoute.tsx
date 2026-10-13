import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";

export default function ProtectedRoute() {
  const { isAuthenticated, isInitializing } = useAuth();
  const location = useLocation();

  // 저장된 토큰 재발급 확인 중에는 판단 보류 (새로고침 시 로그인 페이지로 튀는 것 방지)
  if (isInitializing) return null;

  // 로그인 후 원래 가려던 경로로 돌아올 수 있도록 from을 넘긴다 (LoginPage에서 사용)
  return isAuthenticated ? <Outlet /> : <Navigate to="/login" replace state={{ from: location }} />;
}
