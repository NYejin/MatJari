import { BrowserRouter, Routes, Route } from "react-router-dom";
import AppLayout from "./components/AppLayout";
import ProtectedRoute from "./components/ProtectedRoute";
import LoginPage from "./pages/LoginPage";
import SignupPage from "./pages/SignupPage";
import SocialCallbackPage from "./pages/SocialCallbackPage";
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
import AdminLayout from "./components/AdminLayout";
import SeatClaimNewPage from "./pages/SeatClaimNewPage";
import ExchangeHistoryPage from "./pages/ExchangeHistoryPage";
import MySeatClaimsPage from "./pages/MySeatClaimsPage";
import AdminSeatClaimsPage from "./pages/AdminSeatClaimsPage";
import AdminDashboardPage from "./pages/AdminDashboardPage";
import AdminEditProposalsPage from "./pages/AdminEditProposalsPage";
import MyEditProposalsPage from "./pages/MyEditProposalsPage";
import MyBlocksPage from "./pages/MyBlocksPage";
import MyReportsPage from "./pages/MyReportsPage";
import MyChatsPage from "./pages/MyChatsPage";
import AdminReportsPage from "./pages/AdminReportsPage";
import AdminReportDetailPage from "./pages/AdminReportDetailPage";
import AdminSanctionsPage from "./pages/AdminSanctionsPage";
import AdminBlocksPage from "./pages/AdminBlocksPage";
import AdminPerformancesPage from "./pages/AdminPerformancesPage";
import AdminPerformanceDetailPage from "./pages/AdminPerformanceDetailPage";
import AdminUsersPage from "./pages/AdminUsersPage";
import AdminUserDetailPage from "./pages/AdminUserDetailPage";
import AdminLogsPage from "./pages/AdminLogsPage";
import PrivacyPolicyPage from "./pages/PrivacyPolicyPage";
import TermsOfServicePage from "./pages/TermsOfServicePage";

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* 공통 레이아웃: 모든 화면 상단에 Header. 홈(/)은 공개 — 비로그인이면 안내, 로그인이면 공연 목록 */}
        <Route element={<AppLayout />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/login" element={<LoginPage />} />
          <Route path="/signup" element={<SignupPage />} />
          <Route path="/auth/callback" element={<SocialCallbackPage />} />
          {/* 개인정보처리방침: 공개 (로그인 불필요, 리다이렉트 없음) */}
          <Route path="/privacy" element={<PrivacyPolicyPage />} />
          <Route path="/terms" element={<TermsOfServicePage />} />

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
            <Route path="/my/chats" element={<MyChatsPage />} />
            <Route path="/my/blocks"element={<MyBlocksPage />} />
            <Route path="/my/reports" element={<MyReportsPage />} />

            {/* 관리자 전용 (UI 가드 — 최종 권한은 서버) */}
            <Route element={<AdminRoute />}>
             <Route element={<AdminLayout />}>
              <Route path="/admin" element={<AdminDashboardPage />} />
              <Route path="/admin/performance-edit-proposals" element={<AdminEditProposalsPage />} />
              <Route path="/admin/seat-claims" element={<AdminSeatClaimsPage />} />
              <Route path="/admin/reports" element={<AdminReportsPage />} />
              <Route path="/admin/reports/:id" element={<AdminReportDetailPage />} />
              <Route path="/admin/sanctions" element={<AdminSanctionsPage />} />
              <Route path="/admin/blocks" element={<AdminBlocksPage />} />
              <Route path="/admin/performances" element={<AdminPerformancesPage />} />
              <Route path="/admin/performances/:id" element={<AdminPerformanceDetailPage />} />
              <Route path="/admin/users" element={<AdminUsersPage />} />
              <Route path="/admin/users/:id" element={<AdminUserDetailPage />} />
              <Route path="/admin/logs" element={<AdminLogsPage />} />
             </Route>
            </Route>
          </Route>
        </Route>
      </Routes>
    </BrowserRouter>
  );
}
