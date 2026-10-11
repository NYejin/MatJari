import { BrowserRouter, Routes, Route } from "react-router-dom";
import AppLayout from "./components/AppLayout";
import ProtectedRoute from "./components/ProtectedRoute";
import LoginPage from "./pages/LoginPage";
import SignupPage from "./pages/SignupPage";
import HomePage from "./pages/HomePage";
import PerformanceDetailPage from "./pages/PerformanceDetailPage";
import PerformanceRegisterPage from "./pages/PerformanceRegisterPage";
import MyPage from "./pages/MyPage";
import MyTicketsPage from "./pages/MyTicketsPage";
import TicketRegisterPage from "./pages/TicketRegisterPage";
import ExchangeRequestFormPage from "./pages/ExchangeRequestFormPage";
import CandidatesPage from "./pages/CandidatesPage";
import MatchesPage from "./pages/MatchesPage";
import NotificationsPage from "./pages/NotificationsPage";
import ChatPage from "./pages/ChatPage";
import AdminRoute from "./components/AdminRoute";
import SeatClaimNewPage from "./pages/SeatClaimNewPage";
import ExchangeHistoryPage from "./pages/ExchangeHistoryPage";
import MySeatClaimsPage from "./pages/MySeatClaimsPage";
import AdminSeatClaimsPage from "./pages/AdminSeatClaimsPage";
import AdminDashboardPage from "./pages/AdminDashboardPage";
import AdminEditProposalsPage from "./pages/AdminEditProposalsPage";
import MyEditProposalsPage from "./pages/MyEditProposalsPage";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* 공통 레이아웃: 모든 화면 상단에 Header. 홈(/)은 공개 — 비로그인이면 안내, 로그인이면 공연 목록 */}
        <Route element={<AppLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />

          <Route element={<ProtectedRoute />}>
            {/* 공연 API는 모두 로그인 필요 → 상세도 보호 라우트 (비로그인 진입 시 로그인 후 복귀) */}
            <Route path="/performances/new" element={<PerformanceRegisterPage />} />
            <Route path="/performances/:id" element={<PerformanceDetailPage />} />
            <Route path="/me" element={<MyPage />} />
            {/* 티켓·교환 (FR-03, FR-04) */}
            <Route path="/tickets" element={<MyTicketsPage />} />
            <Route path="/tickets/new" element={<TicketRegisterPage />} />
            <Route path="/tickets/:ticketId/exchange" element={<ExchangeRequestFormPage />} />
            <Route path="/exchange/requests/:requestId/candidates" element={<CandidatesPage />} />
            <Route path="/exchange/matches" element={<MatchesPage />} />
            <Route path="/exchange/history" element={<ExchangeHistoryPage />} />
            <Route path="/exchange/matches/:matchId/chat" element={<ChatPage />} />
            <Route path="/notifications" element={<NotificationsPage />} />
            <Route path="/seat-claims/new" element={<SeatClaimNewPage />} />
            <Route path="/my/seat-claims" element={<MySeatClaimsPage />} />
            <Route path="/my/edit-proposals" element={<MyEditProposalsPage />} />

            {/* 관리자 전용 (UI 가드 — 최종 권한은 서버) */}
            <Route element={<AdminRoute />}>
              <Route path="/admin" element={<AdminDashboardPage />} />
              <Route path="/admin/performance-edit-proposals" element={<AdminEditProposalsPage />} />
              <Route path="/admin/seat-claims" element={<AdminSeatClaimsPage />} />
            </Route>
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
