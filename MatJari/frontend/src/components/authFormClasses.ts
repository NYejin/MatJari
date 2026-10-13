// 로그인/회원가입 화면 전용 Tailwind 클래스 조합 (FR-01). 다른 화면 공용 조합은 ui.ts.
// 두 화면이 같은 레이아웃·폼 스타일을 쓰므로 반복되는 조합을 여기 모은다 (react-conventions "CSS 전략").
// 모바일 우선: 기본은 전체 폭, sm(640px) 이상에서만 카드 형태로 세로·가로 중앙 정렬.

import { FOCUS_RING } from "./ui";

export const authFormClasses = {
  /** AppLayout 콘텐츠 영역(헤더 아래 남은 높이)을 채운다. 100dvh를 쓰면 헤더 높이만큼 넘쳐 스크롤이 생긴다 */
  page:
    "flex w-full flex-[1_0_auto] items-start justify-center bg-surface px-4 pt-8 " +
    "pb-[calc(24px+env(safe-area-inset-bottom))] sm:items-center",
  /** sm 이상 카드 상단 장식선은 보조색 (장식 용도 — 글자에는 쓰지 않음) */
  card:
    "w-full max-w-[400px] sm:rounded-2xl sm:border-t-4 sm:border-secondary-500 sm:bg-white sm:p-8 " +
    "sm:shadow-[0_4px_24px_rgba(0,0,0,0.06)]",
  title: "mb-2 text-2xl/[normal] font-bold text-gray-900",
  subtitle: "mb-6 text-[0.9375rem]/[normal] text-gray-500",
  form: "flex flex-col gap-4",
  /** 가입 완료 등 비오류 안내 */
  notice: "mb-4 rounded-[10px] bg-primary-50 px-3.5 py-3 text-sm/[normal] text-primary-800",
  formError: "rounded-[10px] bg-red-50 px-3.5 py-3 text-sm/[normal] text-red-700",
  /** 터치 영역 48px (react-conventions 최소 44px) */
  submit:
    "mt-2 min-h-12 cursor-pointer touch-manipulation rounded-[10px] bg-primary-600 text-base/[normal] font-semibold " +
    "text-white enabled:active:bg-primary-700 disabled:cursor-default disabled:opacity-60 " +
    FOCUS_RING,
  footer: "mt-6 text-center text-[0.9375rem]/[normal] text-gray-500",
  link: "inline-flex min-h-11 items-center px-1 font-semibold text-primary-600 no-underline active:opacity-70 " + FOCUS_RING,
} as const;
