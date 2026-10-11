import { Navigate, Outlet } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";

/**
 * 관리자 전용 라우트. 로그인 + 프로필 role이 ADMIN일 때만 통과한다.
 * UI 가드일 뿐이며 최종 권한 판정은 서버(/api/admin/** hasRole)가 한다.
 * 비로그인·비관리자는 홈으로, 프로필 확인 중에는 판단을 보류한다.
 */
export default function AdminRoute() {
  const { isAuthenticated, isInitializing, profile, profileStatus } = useAuth();
  if (isInitializing) return null;
  if (!isAuthenticated) return <Navigate to="/" replace />;
  if (!profile && (profileStatus === "loading" || profileStatus === "idle")) {
    return (
      <p className="p-4 text-sm text-gray-700" role="status">
        권한을 확인하는 중...
      </p>
    );
  }
  return profile?.role === "ADMIN" ? <Outlet /> : <Navigate to="/" replace />;
}
