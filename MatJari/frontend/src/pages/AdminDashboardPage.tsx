import { useCallback, useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { adminDashboardApi, type AdminDashboard, type Row } from "../api/adminDashboard";
import { REPORT_REASON_LABEL, type ReportReason } from "../api/adminReports";
import { ACTION_LABEL, TARGET_TYPE_LABEL } from "../api/adminUsers";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { BarChart, HBars, Legend, LineChart, Meter, RemainGauge, StackBar } from "../components/AdminCharts";
import { button, tbl, tone, ui } from "../components/adminUi";

// 관리자 운영 대시보드 (/admin). 한 화면에 수치·추이 그래프·구성 비율·처리 대기열·최근 목록·알림을 촘촘하게 모은다(1분마다 갱신).

const C = { blue: "#2563eb", green: "#059669", amber: "#d97706", red: "#dc2626", violet: "#7c3aed", gray: "#9ca3af", teal: "#0d9488" };

const n = (v: unknown) => (typeof v === "number" ? v : Number(v ?? 0) || 0);
const fmt = (v: unknown) => n(v).toLocaleString("ko-KR");
/** "2026-10-21T13:05:00" → "10/21 13:05" */
const short = (v: unknown) => {
  const s = typeof v === "string" ? v : "";
  return s.length >= 16 ? `${Number(s.slice(5, 7))}/${Number(s.slice(8, 10))} ${s.slice(11, 16)}` : s ? s.slice(5, 10) : "-";
};
/** 달러 표시: 1달러 미만은 소수 넷째 자리까지 */
const usd = (v: unknown) => {
  const x = n(v);
  return `$${x < 1 ? x.toFixed(4) : x.toFixed(2)}`;
};
const STAGE_LABEL: Record<string, string> = {
  first: "1차 읽기",
  locate: "좌석표 띠 찾기",
  label: "도형 이름 판독",
  second: "좌표 대체",
  tile: "타일 재판독",
  legend: "범례 읽기",
};
const md = (d: string) => `${Number(d.slice(5, 7))}/${Number(d.slice(8, 10))}`;
/** 지금부터 몇 시간/일 전 */
const ago = (v: unknown, now: string) => {
  if (typeof v !== "string") return "-";
  const h = (Date.parse(now) - Date.parse(v)) / 3_600_000;
  if (!Number.isFinite(h)) return "-";
  return h < 1 ? `${Math.max(0, Math.round(h * 60))}분` : h < 48 ? `${Math.round(h)}시간` : `${Math.round(h / 24)}일`;
};

export default function AdminDashboardPage() {
  const [data, setData] = useState<AdminDashboard | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback((signal?: AbortSignal) => {
    setLoading(true);
    adminDashboardApi
      .get(signal)
      .then((d) => {
        setData(d);
        setError(null);
      })
      .catch((err: unknown) => {
        if (!signal?.aborted) setError(exchangeErrorMessage(err, "대시보드를 불러오지 못했습니다.").message);
      })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal);
    const timer = window.setInterval(() => load(controller.signal), 60_000);
    return () => {
      controller.abort();
      window.clearInterval(timer);
    };
  }, [load]);

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <div className="flex flex-wrap items-center gap-2">
          <h1 className={ui.pageTitle}>운영 대시보드</h1>
          {data && <span className={ui.muted}>기준 {short(data.generatedAt)} · 1분마다 갱신</span>}
          <button type="button" className={`${button.outline} ml-auto`} onClick={() => load()} disabled={loading} aria-busy={loading}>
            {loading ? "불러오는 중" : "새로고침"}
          </button>
        </div>
        {error && (
          <p className={ui.errorBox} role="alert">
            {error}
          </p>
        )}
        {!data && !error && (
          <p className={ui.status} role="status">
            불러오는 중...
          </p>
        )}
        {data && <Dashboard d={data} />}
      </div>
    </div>
  );
}

function Panel({ title, right, children, className = "" }: { title: string; right?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`${ui.card} flex min-w-0 flex-col gap-1.5 ${className}`} aria-label={title}>
      <div className="flex items-baseline justify-between gap-2">
        <h2 className={ui.sectionTitle}>{title}</h2>
        {right}
      </div>
      {children}
    </section>
  );
}

type KpiItem = { label: string; value: unknown; sub?: string; to?: string; warn?: boolean };

// 분류별 묶음 표: 상자 하나에 '이름 · 값 · 보조' 행을 나열한다(보조가 잘리면 마우스를 올려 본다)
function KpiGroup({ title, items, to }: { title: string; items: KpiItem[]; to?: string }) {
  return (
    <section className="flex min-w-0 flex-col rounded-md border border-gray-200 bg-white" aria-label={title}>
      <h2 className="flex items-center justify-between border-b border-gray-100 bg-gray-50 px-2 py-0.5 text-[11px] font-semibold text-gray-600">
        {title}
        {to && (
          <Link to={to} className="font-normal text-primary-600 no-underline hover:underline">
            보기
          </Link>
        )}
      </h2>
      <dl className="grid grid-cols-[auto_auto_minmax(0,1fr)] items-baseline gap-x-2 px-2 py-1">
        {items.map((it) => {
          const label = it.to ? (
            <Link to={it.to} className="text-gray-600 no-underline hover:text-primary-600 hover:underline">
              {it.label}
            </Link>
          ) : (
            it.label
          );
          return (
            <div key={it.label} className="contents">
              <dt className="truncate text-xs/5 text-gray-600">{label}</dt>
              <dd className={`text-right text-sm/5 font-bold tabular-nums ${it.warn ? "text-red-600" : "text-gray-900"}`}>{typeof it.value === "string" ? it.value : fmt(it.value)}</dd>
              <dd className="truncate text-[11px]/5 text-gray-400" title={it.sub}>
                {it.sub}
              </dd>
            </div>
          );
        })}
      </dl>
    </section>
  );
}

function MiniTable({ cols, rows, empty, render }: { cols: string[]; rows: Row[]; empty: string; render: (r: Row) => ReactNode[] }) {
  if (rows.length === 0) return <p className={ui.muted}>{empty}</p>;
  return (
    <div className={tbl.wrap}>
      <table className={tbl.table}>
        <thead>
          <tr>
            {cols.map((c) => (
              <th key={c} className={tbl.th}>
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={String(r.id ?? r.sessionId ?? i)} className={tbl.tr}>
              {render(r).map((cell, j) => (
                <td key={j} className={tbl.td}>
                  {cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Dashboard({ d }: { d: AdminDashboard }) {
  const { users, performances, tickets, requests, matches, chat, queues, ai } = d.kpis;
  const labels = d.daily.map((x) => md(x.date));
  const sum = (k: keyof AdminDashboard["daily"][number]) => d.daily.reduce((a, x) => a + n(x[k]), 0);
  const alertTone = { danger: tone.danger, warn: tone.warn, info: tone.info, ok: tone.ok } as const;

  return (
    <>
      {/* 알림 */}
      <section aria-label="알림" className="flex flex-wrap gap-1">
        {d.alerts.map((a) =>
          a.link ? (
            <Link key={a.text} to={a.link} className={`${alertTone[a.level]} px-2 py-0.5 text-xs no-underline hover:underline`}>
              {a.text} →
            </Link>
          ) : (
            <span key={a.text} className={`${alertTone[a.level]} px-2 py-0.5 text-xs`}>
              {a.text}
            </span>
          ),
        )}
      </section>

      {/* 핵심 수치 */}
      <div aria-label="핵심 수치" className="grid grid-cols-1 gap-1.5 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-6">
        <KpiGroup
          title="처리 대기"
          items={[
            { label: "신고", value: queues.openReports, sub: queues.oldestOpenReportAt ? `가장 오래 ${ago(queues.oldestOpenReportAt, d.generatedAt)}` : "없음", to: "/admin/reports", warn: n(queues.openReports) > 0 },
            { label: "좌석 인증", value: queues.pendingClaims, sub: queues.oldestPendingClaimAt ? `가장 오래 ${ago(queues.oldestPendingClaimAt, d.generatedAt)}` : "없음", to: "/admin/seat-claims", warn: n(queues.pendingClaims) > 0 },
            { label: "수정 제안", value: queues.pendingProposals, sub: queues.oldestPendingProposalAt ? `가장 오래 ${ago(queues.oldestPendingProposalAt, d.generatedAt)}` : "없음", to: "/admin/performance-edit-proposals", warn: n(queues.pendingProposals) > 0 },
          ]}
        />
        <KpiGroup
          title="회원"
          to="/admin/users"
          items={[
            { label: "활성", value: users.active, sub: `전체 ${fmt(users.total)} · 관리자 ${fmt(users.admins)}` },
            { label: "오늘 가입", value: users.newToday, sub: `7일 ${fmt(users.new7d)}` },
            { label: "이용 정지", value: users.suspended, sub: `탈퇴 ${fmt(users.withdrawn)}`, to: "/admin/sanctions" },
          ]}
        />
        <KpiGroup
          title="공연·티켓"
          to="/admin/performances"
          items={[
            { label: "공연", value: performances.total, sub: `다가옴 ${fmt(performances.upcoming)} · 숨김 ${fmt(performances.hidden)}` },
            { label: "구역 지도", value: performances.withZones, sub: `7일 신규 공연 ${fmt(performances.new7d)}` },
            { label: "활성 티켓", value: tickets.active, sub: `오늘 ${fmt(tickets.newToday)} · 7일 ${fmt(tickets.new7d)}` },
          ]}
        />
        <KpiGroup
          title="교환"
          items={[
            { label: "열린 요청", value: requests.open, sub: `닫힘 ${fmt(requests.closed)} · 삭제 ${fmt(requests.deleted)}` },
            { label: "채팅·예약 중", value: n(matches.chatting) + n(matches.reserved), sub: `채팅 ${fmt(matches.chatting)} · 예약 ${fmt(matches.reserved)}` },
            { label: "오늘 매칭", value: matches.createdToday, sub: `7일 ${fmt(matches.created7d)} · 7일 취소 ${fmt(matches.canceled7d)}` },
            { label: "교환 완료", value: matches.completed, sub: `7일 ${fmt(matches.completed7d)} · 교환된 티켓 ${fmt(tickets.exchanged)}` },
          ]}
        />
        <KpiGroup
          title="AI 불러오기"
          items={[
            { label: "이번 달", value: ai.usedMonth, sub: `반환 ${fmt(ai.refundedMonth)} · 사용자 ${fmt(ai.usersMonth)}` },
            { label: "오늘 전체", value: ai.globalToday, sub: `상한 ${fmt(ai.dailyGlobalLimit)} · 보관 ${fmt(ai.cacheEntries)}`, warn: n(ai.globalToday) >= n(ai.dailyGlobalLimit) * 0.8 },
            { label: "이번 달 비용", value: usd(ai.costMonthUsd), sub: `오늘 ${usd(ai.costTodayUsd)} · 1회 ${usd(ai.avgCostPerLoadUsd)}` },
          ]}
        />
        <KpiGroup
          title="채팅·운영"
          items={[
            { label: "오늘 메시지", value: chat.today, sub: `7일 ${fmt(chat.last7d)} · 대화방 ${fmt(chat.activeMatches7d)}` },
            { label: "7일 신고", value: queues.reports7d, sub: `차단 7일 ${fmt(queues.blocks7d)} · 누적 ${fmt(queues.blocksTotal)}` },
            { label: "7일 조치", value: queues.actions7d, to: "/admin/logs" },
          ]}
        />
      </div>

      {/* 추이 그래프 */}
      <div className="grid grid-cols-1 gap-2 lg:grid-cols-3">
        <Panel title="최근 14일 활동" right={<span className={ui.muted}>합계 가입 {sum("signups")} · 티켓 {sum("tickets")} · 매칭 {sum("matches")} · 완료 {sum("completed")}</span>} className="lg:col-span-2">
          <LineChart
            title="최근 14일 가입·티켓·매칭·교환 완료"
            height={170}
            labels={labels}
            series={[
              { label: "가입", color: C.blue, values: d.daily.map((x) => x.signups) },
              { label: "티켓 등록", color: C.green, values: d.daily.map((x) => x.tickets) },
              { label: "매칭 생성", color: C.amber, values: d.daily.map((x) => x.matches) },
              { label: "교환 완료", color: C.violet, values: d.daily.map((x) => x.completed) },
            ]}
          />
          <Legend
            items={[
              { label: "가입", color: C.blue },
              { label: "티켓 등록", color: C.green },
              { label: "매칭 생성", color: C.amber },
              { label: "교환 완료", color: C.violet },
            ]}
          />
        </Panel>
        <Panel title="AI 공연정보 불러오기">
          {ai.creditRemainingRatio !== undefined && (
            <>
              <RemainGauge label="남은 크레딧(추정)" ratio={n(ai.creditRemainingRatio)} value={`${usd(ai.creditRemainingUsd)} / 전체 ${usd(ai.creditTotalUsd ?? ai.creditBaselineUsd)}`} />
              <p className="text-[10px]/[normal] text-gray-500">
                {String(ai.creditBaselineAt).replace("T", " ")} Console 잔액 {usd(ai.creditBaselineUsd)}에서 이 서버가 기록한 호출 비용({usd(ai.spentSinceBaselineUsd)})을 뺀 추정치이고, 비율은 전체 예산 대비예요. 평가·다른 앱 사용은 빠져 실제 잔액은 Console에서 확인하세요.
              </p>
            </>
          )}
          <Meter label="오늘 전체 호출(메모리)" value={n(ai.globalToday)} max={n(ai.dailyGlobalLimit)} />
          <BarChart title="최근 14일 AI 불러오기 횟수" labels={labels} values={d.daily.map((x) => x.aiCalls)} color={C.teal} height={80} />
          <span className={ui.label}>최근 14일 예상 비용(센트)</span>
          <BarChart title="최근 14일 AI 예상 비용(센트)" labels={labels} values={d.daily.map((x) => Math.round(n(x.aiCostUsd) * 1000) / 10)} color={C.violet} height={80} />
          <span className={ui.label}>이번 달 단계별 비용</span>
          <HBars
            color={C.violet}
            empty="이번 달 기록된 호출이 없어요"
            items={Object.entries(d.aiCostByStage).map(([k, v]) => ({ label: STAGE_LABEL[k] ?? k, value: Math.round(v * 10000) / 10000 }))}
          />
          <div className="grid grid-cols-2 gap-x-2 text-[11px]/[normal] text-gray-600">
            <span>
              이번 달 사용 <b className="text-gray-900">{fmt(ai.usedMonth)}</b> · 반환 <b className="text-gray-900">{fmt(ai.refundedMonth)}</b>
            </span>
            <span>
              월 한도({fmt(ai.monthlyPerUser)}회) 소진 <b className="text-gray-900">{fmt(ai.usersAtLimit)}명</b>
            </span>
            <span>
              오늘 사용 <b className="text-gray-900">{fmt(ai.usedToday)}</b>
            </span>
            <span>
              보관 결과 <b className="text-gray-900">{fmt(ai.cacheEntries)}개</b>
            </span>
            <span>
              이번 달 토큰 <b className="text-gray-900">{fmt(ai.tokensMonth)}</b> · 호출 <b className="text-gray-900">{fmt(ai.callsMonth)}</b>
            </span>
          </div>
        </Panel>
        <Panel title="채팅 메시지 (14일)">
          <BarChart title="최근 14일 채팅 메시지" labels={labels} values={d.daily.map((x) => x.messages)} color={C.blue} height={100} />
        </Panel>
        <Panel title="신고 (14일 · 대기 사유)">
          <BarChart title="최근 14일 신고" labels={labels} values={d.daily.map((x) => x.reports)} color={C.red} height={80} />
          <HBars
            color={C.red}
            empty="처리 대기 신고 없음"
            items={Object.entries(d.openReportReasons).map(([k, v]) => ({ label: REPORT_REASON_LABEL[k as ReportReason] ?? k, value: v }))}
          />
        </Panel>
        <Panel title="구성 비율">
          <span className={ui.label}>매칭 상태(누적)</span>
          <StackBar
            parts={[
              { label: "채팅 중", value: n(d.matchStatus.CHATTING), color: C.blue },
              { label: "예약 중", value: n(d.matchStatus.RESERVED), color: C.amber },
              { label: "완료", value: n(d.matchStatus.COMPLETED), color: C.green },
              { label: "종료", value: n(d.matchStatus.CANCELED), color: C.gray },
            ]}
          />
          <span className={ui.label}>티켓 상태</span>
          <StackBar
            parts={[
              { label: "활성", value: n(tickets.active), color: C.green },
              { label: "교환됨", value: n(tickets.exchanged), color: C.violet },
              { label: "내림", value: n(tickets.inactive), color: C.gray },
            ]}
          />
          <span className={ui.label}>회원</span>
          <StackBar
            parts={[
              { label: "활성", value: Math.max(0, n(users.active) - n(users.suspended)), color: C.blue },
              { label: "정지 중", value: n(users.suspended), color: C.red },
              { label: "탈퇴", value: n(users.withdrawn), color: C.gray },
            ]}
          />
        </Panel>
      </div>

      {/* 처리 대기열·목록 */}
      <div className="grid grid-cols-1 gap-2 lg:grid-cols-2 2xl:grid-cols-3">
        <Panel title={`처리 대기 신고 (${fmt(queues.openReports)})`} right={<Link to="/admin/reports" className={`${ui.link} text-[11px]`}>전체</Link>}>
          <MiniTable
            cols={["#", "사유", "신고자 → 대상", "대상 누적", "경과"]}
            rows={d.openReports}
            empty="대기 중인 신고가 없어요."
            render={(r) => [
              <Link to={`/admin/reports/${r.id}`} className={ui.link}>{String(r.id)}</Link>,
              REPORT_REASON_LABEL[r.reason as ReportReason] ?? String(r.reason),
              `${r.reporter} → ${r.reported}`,
              fmt(r.reportedTotal),
              ago(r.createdAt, d.generatedAt),
            ]}
          />
        </Panel>
        <Panel title={`좌석 인증 대기 (${fmt(queues.pendingClaims)})`} right={<Link to="/admin/seat-claims" className={`${ui.link} text-[11px]`}>전체</Link>}>
          <MiniTable
            cols={["#", "요청자", "공연 · 회차", "좌석", "경과"]}
            rows={d.pendingClaims}
            empty="대기 중인 인증 요청이 없어요."
            render={(r) => [String(r.id), String(r.claimant), `${r.title} · ${short(r.startsAt)}`, `${r.zone} ${r.rowLabel}열 ${r.colLabel}번`, ago(r.createdAt, d.generatedAt)]}
          />
        </Panel>
        <Panel title={`수정 제안 대기 (${fmt(queues.pendingProposals)})`} right={<Link to="/admin/performance-edit-proposals" className={`${ui.link} text-[11px]`}>전체</Link>}>
          <MiniTable
            cols={["#", "공연", "제안", "제안자", "경과"]}
            rows={d.pendingProposals}
            empty="대기 중인 수정 제안이 없어요."
            render={(r) => [
              String(r.id),
              <Link to={`/admin/performances/${r.performanceId}`} className={ui.link}>{String(r.title)}</Link>,
              [r.newTitle && "제목", r.newVenueName && "공연장", n(r.hasSessionChanges) ? "회차" : null].filter(Boolean).join("·") || "-",
              String(r.proposer),
              ago(r.createdAt, d.generatedAt),
            ]}
          />
        </Panel>
        <Panel title="활성 티켓 많은 공연">
          <MiniTable
            cols={["공연", "티켓", "열린 요청", "다음 회차", "구역"]}
            rows={d.topPerformances}
            empty="활성 티켓이 있는 공연이 없어요."
            render={(r) => [
              <Link to={`/admin/performances/${r.id}`} className={ui.link}>
                {String(r.title)}
                {n(r.hidden) ? " (숨김)" : ""}
              </Link>,
              fmt(r.activeTickets),
              fmt(r.openRequests),
              short(r.nextSession),
              fmt(r.zones),
            ]}
          />
        </Panel>
        <Panel title="다가오는 회차 (7일)">
          <MiniTable
            cols={["일시", "공연", "활성 티켓"]}
            rows={d.upcomingSessions}
            empty="7일 안에 회차가 없어요."
            render={(r) => [short(r.startsAt), <Link to={`/admin/performances/${r.performanceId}`} className={ui.link}>{String(r.title)}</Link>, fmt(r.activeTickets)]}
          />
        </Panel>
        <Panel title="최근 가입" right={<Link to="/admin/users" className={`${ui.link} text-[11px]`}>전체</Link>}>
          <MiniTable
            cols={["#", "닉네임", "가입", "방식", "활성 티켓"]}
            rows={d.recentUsers}
            empty="회원이 없어요."
            render={(r) => [
              <Link to={`/admin/users/${r.id}`} className={ui.link}>{String(r.id)}</Link>,
              String(r.nickname),
              short(r.createdAt),
              n(r.social) ? <span className={tone.info}>소셜</span> : <span className={tone.mute}>이메일</span>,
              fmt(r.activeTickets),
            ]}
          />
        </Panel>
        <Panel title="최근 관리자 조치" right={<Link to="/admin/logs" className={`${ui.link} text-[11px]`}>전체</Link>} className="2xl:col-span-3 lg:col-span-2">
          <MiniTable
            cols={["시각", "관리자", "조치", "대상", "사유"]}
            rows={d.recentActions}
            empty="조치 기록이 없어요."
            render={(r) => [
              short(r.createdAt),
              String(r.admin),
              ACTION_LABEL[String(r.action)] ?? String(r.action),
              `${TARGET_TYPE_LABEL[String(r.targetType)] ?? r.targetType} #${r.targetId}`,
              <span className="line-clamp-1">{String(r.reason ?? "")}</span>,
            ]}
          />
        </Panel>
      </div>
    </>
  );
}
