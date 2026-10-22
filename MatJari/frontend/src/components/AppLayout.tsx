import { useEffect } from "react";
import { Outlet, useLocation, useMatch, useNavigate } from "react-router-dom";
import { useAuth } from "../hooks/useAuth";
import Header from "./Header";
import Footer from "./Footer";

/**
 * 모든 라우트 공통 레이아웃: 상단 Header + 페이지 영역(<main>, 페이지 쪽에서는 <main>을 다시 쓰지 않는다) + 하단 Footer.
 * 페이지 영역은 헤더를 뺀 남은 높이를 채우는 flex 컨테이너다. 페이지는 100dvh 대신 flex: 1로
 * 높이를 채워야 헤더 높이만큼 넘치지 않는다 (authFormClasses.ts page 참고).
 * 푸터는 main 아래라 페이지가 남은 높이를 채우면 항상 맨 아래에 붙는다.
 * 채팅 화면은 입력창이 화면 하단에 붙는(sticky bottom) 고정 높이형이라 푸터를 숨긴다.
 */
const ADMIN_LANDED_KEY = "matjari.adminLanded";

/**
 * 관리자 계정은 로그인(또는 로그인된 채로 처음 열었을 때) 홈에 오면 운영 대시보드로 보낸다(탭 세션마다 한 번).
 * 그 뒤에는 헤더의 '공연 목록'으로 홈을 볼 수 있다. 로그아웃하면 표시를 지워 다음 로그인 때 다시 보낸다.
 */
function useAdminLanding() {
  const { isAuthenticated, profile } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  useEffect(() => {
    try {
      if (!isAuthenticated) {
        sessionStorage.removeItem(ADMIN_LANDED_KEY);
        return;
      }
      if (profile?.role !== "ADMIN" || location.pathname !== "/" || sessionStorage.getItem(ADMIN_LANDED_KEY)) return;
      sessionStorage.setItem(ADMIN_LANDED_KEY, "1");
    } catch {
      if (!isAuthenticated || profile?.role !== "ADMIN" || location.pathname !== "/") return; // 저장소를 못 쓰면 매번 보낸다
    }
    navigate("/admin", { replace: true });
  }, [isAuthenticated, profile?.role, location.pathname, navigate]);
}

export default function AppLayout() {
  useAdminLanding();
  const isChat = useMatch("/exchange/matches/:matchId/chat") !== null;
  const isAdminPage = useMatch("/admin/*") !== null;
  return (
    // 채팅 화면은 화면 높이에 고정하고(메시지 영역만 스크롤) 나머지 화면은 기존처럼 min-height
    <div className={isChat ? "flex h-[100vh] flex-col supports-[height:100dvh]:h-dvh" : "flex min-h-[100vh] flex-col supports-[min-height:100dvh]:min-h-dvh"}>
      <Header />
      <main className={isChat ? "flex min-h-0 flex-1 flex-col" : "flex flex-[1_0_auto] flex-col"}>
        <Outlet />
      </main>
      {!isChat && <Footer wide={isAdminPage} />}
    </div>
  );
}
