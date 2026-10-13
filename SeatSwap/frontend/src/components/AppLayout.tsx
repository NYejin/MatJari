import { Outlet, useMatch } from "react-router-dom";
import Header from "./Header";
import Footer from "./Footer";

/**
 * 모든 라우트 공통 레이아웃: 상단 Header + 페이지 영역(<main>, 페이지 쪽에서는 <main>을 다시 쓰지 않는다) + 하단 Footer.
 * 페이지 영역은 헤더를 뺀 남은 높이를 채우는 flex 컨테이너다. 페이지는 100dvh 대신 flex: 1로
 * 높이를 채워야 헤더 높이만큼 넘치지 않는다 (authFormClasses.ts page 참고).
 * 푸터는 main 아래라 페이지가 남은 높이를 채우면 항상 맨 아래에 붙는다.
 * 채팅 화면은 입력창이 화면 하단에 붙는(sticky bottom) 고정 높이형이라 푸터를 숨긴다.
 */
export default function AppLayout() {
  const isChat = useMatch("/exchange/matches/:matchId/chat") !== null;
  return (
    <div className="flex min-h-[100vh] flex-col supports-[min-height:100dvh]:min-h-dvh">
      <Header />
      <main className="flex flex-[1_0_auto] flex-col">
        <Outlet />
      </main>
      {!isChat && <Footer />}
    </div>
  );
}
