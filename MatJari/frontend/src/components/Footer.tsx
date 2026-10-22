import { Link } from "react-router-dom";
import { FOCUS_RING } from "./ui";

// 모든 화면 하단 공통 푸터 (AppLayout, 관리자 화면은 wide 로 폭 제한 없음). 서비스 이용약관·개인정보처리방침 링크 + 서비스명·저작권 한 줄.
// 흰 배경 + gray-700 글자 10.31:1, 링크는 터치 영역 44px, 좁은 화면(360px)에서는 줄바꿈.
const LINK_CLASS = `inline-flex min-h-11 items-center font-semibold text-gray-900 underline underline-offset-2 active:opacity-70 ${FOCUS_RING}`;

export default function Footer({ wide = false }: { wide?: boolean }) {
  return (
    <footer
      className={
        wide // 관리자 화면: 본문과 같은 작은 글자·좁은 여백
          ? "border-t border-gray-200 bg-white px-4 pt-1 pb-[calc(4px+env(safe-area-inset-bottom))] text-[11px]/[normal] text-gray-700 [&_a]:min-h-7!"
          : "border-t border-gray-200 bg-white px-4 pt-3 pb-[calc(12px+env(safe-area-inset-bottom))] text-[0.8125rem]/[normal] text-gray-700"
      }
    >
      <div className={`mx-auto flex w-full ${wide ? "" : "max-w-[720px]"} flex-wrap items-center justify-between gap-x-4 gap-y-1`}>
        <nav aria-label="약관 및 방침" className="flex flex-wrap items-center gap-x-4 gap-y-1">
          <Link to="/terms" className={LINK_CLASS}>
            서비스 이용약관
          </Link>
          <Link to="/privacy" className={LINK_CLASS}>
            개인정보처리방침
          </Link>
        </nav>
        <p>© {new Date().getFullYear()} 맞자리</p>
      </div>
    </footer>
  );
}
