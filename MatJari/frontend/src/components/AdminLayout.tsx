import { useEffect, useState } from "react";
import { NavLink, Outlet } from "react-router-dom";
import { adminDashboardApi } from "../api/adminDashboard";
import { FOCUS_RING } from "./adminUi";
import { DenseContext } from "./density";

// 관리자 공통 틀: 왼쪽 메뉴(넓은 화면) / 위쪽 가로 메뉴(좁은 화면) + 넓은 본문. 처리 대기 수를 메뉴 옆에 보인다(1분마다 갱신).
const MENUS: { to: string; label: string; badge?: "openReports" | "pendingClaims" | "pendingProposals" }[] = [
  { to: "/admin", label: "대시보드" },
  { to: "/admin/reports", label: "신고 처리", badge: "openReports" },
  { to: "/admin/seat-claims", label: "좌석 인증", badge: "pendingClaims" },
  { to: "/admin/performance-edit-proposals", label: "수정 제안", badge: "pendingProposals" },
  { to: "/admin/performances", label: "공연 관리" },
  { to: "/admin/users", label: "이용자 관리" },
  { to: "/admin/sanctions", label: "이용 정지" },
  { to: "/admin/blocks", label: "차단 현황" },
  { to: "/admin/logs", label: "조치 기록" },
];

type AiUsage = Record<string, number | string>;

const num = (v: number | string | undefined) => (typeof v === "number" ? v : Number(v ?? NaN));
const usd = (v: number | string | undefined) => {
  const n = num(v);
  return Number.isFinite(n) ? `$${n < 1 ? n.toFixed(3) : n.toFixed(2)}` : "-";
};

/** 사이드바 아래 API 사용량(대시보드와 같은 값, 1분마다 갱신). 남은 크레딧은 이 서버가 기록한 비용으로 낸 추정치 */
function ApiUsage({ ai }: { ai: AiUsage | null }) {
  if (!ai) return null;
  const ratio = num(ai.creditRemainingRatio);
  const pct = Number.isFinite(ratio) ? Math.max(0, Math.min(100, Math.round(ratio * 100))) : null;
  const bar = pct === null ? "" : pct < 10 ? "bg-red-600" : pct < 20 ? "bg-amber-500" : "bg-emerald-600";
  return (
    <section aria-label="API 사용량" className="hidden flex-col gap-1 border-t border-gray-200 px-2 pt-2 text-[11px]/[normal] text-gray-600 md:flex">
      <span className="font-bold tracking-wide text-gray-500">API 사용량</span>
      {pct !== null && (
        <>
          <div className="flex items-baseline justify-between gap-1">
            <span>남은 크레딧</span>
            <b className="tabular-nums text-gray-900">{pct}%</b>
          </div>
          <div className="h-1.5 w-full overflow-hidden rounded bg-gray-200" role="img" aria-label={`남은 크레딧 ${pct}%`}>
            <div className={`h-full ${bar}`} style={{ width: `${pct}%` }} />
          </div>
          <span className="tabular-nums">
            {usd(ai.creditRemainingUsd)} / {usd(ai.creditTotalUsd)}
          </span>
        </>
      )}
      <div className="flex justify-between gap-1">
        <span>오늘</span>
        <span className="tabular-nums text-gray-900">
          {usd(ai.costTodayUsd)} · {num(ai.globalToday)}/{num(ai.dailyGlobalLimit)}회
        </span>
      </div>
      <div className="flex justify-between gap-1">
        <span>이번 달</span>
        <span className="tabular-nums text-gray-900">
          {usd(ai.costMonthUsd)} · 호출 {num(ai.callsMonth)}
        </span>
      </div>
      <span className="text-gray-500">추정치(이 서버 기록 기준)</span>
    </section>
  );
}

export default function AdminLayout() {
  const [badges, setBadges] = useState<Record<string, number>>({});
  const [ai, setAi] = useState<AiUsage | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const load = () =>
      adminDashboardApi
        .get(controller.signal)
        .then((d) => {
          const q = d.kpis.queues;
          setBadges({
            openReports: Number(q.openReports ?? 0),
            pendingClaims: Number(q.pendingClaims ?? 0),
            pendingProposals: Number(q.pendingProposals ?? 0),
          });
          setAi(d.kpis.ai ?? null);
        })
        .catch(() => {});
    void load();
    const timer = window.setInterval(load, 60_000);
    return () => {
      controller.abort();
      window.clearInterval(timer);
    };
  }, []);

  const link = ({ isActive }: { isActive: boolean }) =>
    `flex items-center justify-between gap-2 rounded-md px-2 py-1 text-xs/[normal] whitespace-nowrap no-underline ${FOCUS_RING} ${
      isActive ? "bg-primary-600 font-semibold text-white" : "text-gray-700 hover:bg-gray-200"
    }`;

  return (
    <div className="flex w-full min-w-0 flex-[1_0_auto] flex-col bg-gray-100 md:flex-row">
      <nav
        aria-label="관리자 메뉴"
        className="flex shrink-0 gap-1 overflow-x-auto border-b border-gray-200 bg-white px-2 py-1.5 md:w-40 md:flex-col md:overflow-visible md:border-r md:border-b-0 md:py-2"
      >
        {/* <span className="hidden px-2 pb-1 text-[11px] font-bold tracking-wide text-gray-500 md:block">관리자</span> */}
        {MENUS.map((m) => {
          const n = m.badge ? badges[m.badge] ?? 0 : 0;
          return (
            <NavLink key={m.to} to={m.to} end={m.to === "/admin"} className={link}>
              <span>{m.label}</span>
              {n > 0 && (
                <span className="rounded-full bg-red-600 px-1.5 text-[10px]/[16px] font-bold text-white" aria-label={`대기 ${n}건`}>
                  {n}
                </span>
              )}
            </NavLink>
          );
        })}
        <ApiUsage ai={ai} />
      </nav>
      <main className="min-w-0 flex-1 p-2 pb-[calc(8px+env(safe-area-inset-bottom))]">
        {/* 관리자 화면 안의 공용 컴포넌트(입력칸·구역/회차 편집기 등)도 촘촘한 토큰을 쓰게 한다 */}
        <DenseContext.Provider value={true}>
          <Outlet />
        </DenseContext.Provider>
      </main>
    </div>
  );
}
