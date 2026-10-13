import { Link } from "react-router-dom";
import { FOCUS_RING } from "./ui";

// 모든 화면 하단 공통 푸터 (AppLayout). 개인정보처리방침 링크 + 서비스명·저작권 한 줄.
// 흰 배경 + gray-700 글자 10.31:1, 링크는 터치 영역 44px.
export default function Footer() {
  return (
    <footer className="border-t border-gray-200 bg-white px-4 pt-3 pb-[calc(12px+env(safe-area-inset-bottom))] text-[0.8125rem]/[normal] text-gray-700">
      <div className="mx-auto flex w-full max-w-[720px] flex-wrap items-center justify-between gap-x-4 gap-y-1">
        <Link
          to="/privacy"
          className={`inline-flex min-h-11 items-center font-semibold text-gray-900 underline underline-offset-2 active:opacity-70 ${FOCUS_RING}`}
        >
          개인정보처리방침
        </Link>
        <p>© {new Date().getFullYear()} 맞자리</p>
      </div>
    </footer>
  );
}
