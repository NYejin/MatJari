import type { ReactNode } from "react";
import { authFormClasses as ui } from "./authFormClasses";

// 로그인·가입·비밀번호 화면 공통 틀: 배경 그라데이션 원(장식) + 넓은 화면의 서비스 소개 패널 + 폼 카드.
// 소개 패널은 장식·안내용이다(모바일은 숨김).

const POINTS = [
  { tone: "bg-sky-100 text-sky-700", title: "조건이 맞는 상대를 찾아 드려요", body: "내 좌석과 원하는 좌석을 입력하면 서로 맞는 후보를 보여 줘요.", icon: "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zm9 15-3.5-3.5" },
  { tone: "bg-mint-100 text-mint-700", title: "채팅으로 안전하게 확인해요", body: "예매내역을 서로 확인하고 예약·교환 수락까지 한 곳에서 진행해요.", icon: "M5 12.5l4.5 4.5L19 7.5" },
  { tone: "bg-coral-100 text-coral-700", title: "추가금은 직접, 부담 없이", body: "결제는 중개하지 않아요. 받을 금액·낼 수 있는 금액만 미리 맞춰요.", icon: "M12 5v14M7 9.5c0-1.4 2-2.5 5-2.5s5 1.1 5 2.5-2 2.5-5 2.5-5 1.1-5 2.5 2 2.5 5 2.5 5-1.1 5-2.5" },
];

export default function AuthShell({ children }: { children: ReactNode }) {
  return (
    <div className={ui.page}>
      <div aria-hidden="true" className="pointer-events-none absolute -top-24 -left-24 h-72 w-72 rounded-full bg-primary-200/50 blur-3xl" />
      <div aria-hidden="true" className="pointer-events-none absolute -right-20 bottom-0 h-80 w-80 rounded-full bg-coral-100/70 blur-3xl" />
      <div className={ui.layout}>
        <aside className={ui.intro}>
          <p className="text-sm/[normal] font-semibold text-white/90">MatJari · 맞자리</p>
          <h2 className="mt-3 text-[1.75rem]/[1.35] font-bold break-keep text-white">
            원하는 자리로,
            <br />
            맞게 바꿔요
          </h2>
          <ul className="mt-8 flex flex-col gap-5">
            {POINTS.map((p) => (
              <li key={p.title} className="flex gap-3.5">
                <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full ${p.tone}`}>
                  <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d={p.icon} />
                  </svg>
                </span>
                <span className="min-w-0">
                  <span className="block text-[0.9375rem]/[1.5] font-semibold break-keep text-white">{p.title}</span>
                  <span className="mt-0.5 block text-sm/[1.55] break-keep text-white/90">{p.body}</span>
                </span>
              </li>
            ))}
          </ul>
        </aside>
        <div className={ui.card}>{children}</div>
      </div>
    </div>
  );
}
