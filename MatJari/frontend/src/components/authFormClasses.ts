// 로그인/회원가입 화면 전용 Tailwind 클래스 조합 (FR-01). 다른 화면 공용 조합은 ui.ts.
// 2026-11-25 디자인 개편: 가운데 카드 + 배경 그라데이션 원, 넓은 화면에서는 왼쪽 소개 패널(AuthShell)과 2단.

import { FOCUS_RING } from "./ui";

export const authFormClasses = {
  /** AppLayout 콘텐츠 영역(헤더 아래 남은 높이)을 채운다. 장식 원이 넘치지 않게 overflow-hidden */
  page:
    "relative flex w-full flex-[1_0_auto] items-start justify-center overflow-hidden " +
    "bg-linear-to-br from-primary-50 via-surface to-coral-50 px-4 pt-8 pb-[calc(40px+env(safe-area-inset-bottom))] sm:items-center sm:px-6 sm:py-12",
  /** 소개 패널 + 카드 2단 (lg 이상) */
  layout: "relative flex w-full max-w-[460px] items-stretch justify-center lg:max-w-[860px]",
  /** 소개 패널: 모바일에서는 숨김. 흰 글자 대비를 위해 어두운 쪽 보라→진한 코랄 */
  intro:
    "hidden w-[380px] shrink-0 flex-col justify-center rounded-l-[24px] bg-linear-to-br from-primary-700 via-primary-800 to-coral-700 p-10 lg:flex",
  card:
    "w-full min-w-0 rounded-[24px] border border-gray-200/70 bg-white p-6 shadow-[0_12px_40px_-12px_rgba(138,43,226,0.25)] " +
    "sm:p-9 lg:flex-1 lg:rounded-l-none lg:rounded-r-[24px]",
  title: "mb-2 text-[1.625rem]/[1.3] font-bold tracking-[-0.01em] break-keep text-gray-900",
  subtitle: "mb-7 text-[0.9375rem]/[1.6] break-keep text-gray-600",
  form: "flex flex-col gap-5",
  /** 가입 완료 등 비오류 안내 */
  notice: "mb-4 rounded-2xl border-l-4 border-primary-400 bg-primary-50 px-4 py-3 text-sm/[1.6] text-primary-800",
  formError: "rounded-2xl border-l-4 border-red-600 bg-red-50 px-4 py-3 text-sm/[1.6] text-red-700",
  /** 터치 영역 48px (react-conventions 최소 44px) */
  submit:
    "mt-2 min-h-12 cursor-pointer touch-manipulation rounded-xl bg-primary-600 text-base/[normal] font-semibold " +
    "text-white shadow-[0_6px_16px_-6px_rgba(138,43,226,0.55)] enabled:hover:bg-primary-700 enabled:active:bg-primary-800 disabled:cursor-default disabled:opacity-60 " +
    FOCUS_RING,
  footer: "mt-7 text-center text-[0.9375rem]/[normal] text-gray-600",
  link: "inline-flex min-h-11 items-center px-1 font-semibold text-primary-700 no-underline hover:underline active:opacity-70 " + FOCUS_RING,
} as const;
