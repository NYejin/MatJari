import { Link } from "react-router-dom";
import { FOCUS_RING, ui } from "../components/ui";

// 관리자 대시보드 (/admin, AdminRoute). 처리 메뉴 카드만 둔다.
const MENUS = [
  { to: "/admin/performance-edit-proposals", title: "공연 수정 제안", desc: "제목·공연장 수정 제안을 검토해요." },
  { to: "/admin/seat-claims", title: "좌석 인증 요청", desc: "예매내역 사진으로 좌석 소유를 확인해요." },
] as const;

export default function AdminDashboardPage() {
  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>관리자</h1>
        <ul className="flex flex-col gap-3">
          {MENUS.map((m) => (
            <li key={m.to}>
              <Link
                to={m.to}
                className={`${ui.card} flex min-h-11 flex-col gap-1 no-underline active:bg-gray-50 ${FOCUS_RING}`}
              >
                <span className={ui.sectionTitle}>{m.title}</span>
                <span className={ui.body}>{m.desc}</span>
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
