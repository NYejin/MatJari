import { FOCUS_RING } from "./ui";

export { FOCUS_RING, liveRegionClass } from "./ui";

// 관리자 화면 전용 촘촘한 토큰 (2026-10-21 사용자 지시: 관리자 화면은 보기 편함보다 한 화면에 최대한 많은 정보).
// 이용자 화면 토큰(ui.ts)과 키 이름을 맞춰, 관리자 페이지는 import 경로만 바꾸면 같은 구조가 촘촘해진다.

const BUTTON_LAYOUT =
  "inline-flex min-h-7 cursor-pointer touch-manipulation items-center justify-center gap-1 rounded-md " +
  "px-2 text-xs/[normal] font-semibold whitespace-nowrap no-underline " +
  "disabled:cursor-not-allowed disabled:opacity-50 " +
  FOCUS_RING;

export const button = {
  solid: `${BUTTON_LAYOUT} border border-primary-600 bg-primary-600 text-white enabled:active:bg-primary-700`,
  outline: `${BUTTON_LAYOUT} border border-gray-300 bg-white text-gray-900 enabled:hover:bg-gray-50 enabled:active:bg-gray-100`,
  danger: `${BUTTON_LAYOUT} border border-red-600 bg-red-600 text-white enabled:active:bg-red-700`,
  dangerOutline: `${BUTTON_LAYOUT} border border-red-600 bg-white text-red-700 enabled:active:bg-red-50`,
} as const;

export const linkButton = {
  solid: `${BUTTON_LAYOUT} border border-primary-600 bg-primary-600 text-white active:bg-primary-700`,
  outline: `${BUTTON_LAYOUT} border border-gray-300 bg-white text-gray-900 hover:bg-gray-50 active:bg-gray-100`,
} as const;

const INPUT_BASE =
  "min-h-7 w-full rounded-md border bg-white px-2 text-xs/[normal] text-gray-900 outline-hidden " +
  "placeholder:text-gray-500 focus-visible:ring-2 disabled:bg-gray-100";

export const input = {
  normal: `${INPUT_BASE} border-gray-300 focus-visible:border-primary-600 focus-visible:ring-primary-600/20`,
  invalid: `${INPUT_BASE} border-red-600 focus-visible:ring-red-600/20`,
} as const;

export const ui = {
  /** AdminLayout 이 바깥 여백·배경을 맡으므로 페이지는 여백 없이 채운다 */
  page: "flex w-full min-w-0 flex-col",
  container: "flex w-full min-w-0 flex-col gap-2",
  pageTitle: "text-base/[normal] font-bold text-gray-900",
  card: "min-w-0 rounded-md border border-gray-200 bg-white p-2",
  sectionTitle: "text-[13px]/[normal] font-bold text-gray-900",
  label: "text-[11px]/[normal] font-semibold text-gray-600",
  body: "text-xs/[normal] text-gray-800",
  muted: "text-[11px]/[normal] text-gray-500",
  fieldError: "text-[11px]/[normal] text-red-600",
  hint: "text-[11px]/[normal] text-gray-500",
  notice: "rounded-md bg-primary-50 px-2 py-1 text-xs/[normal] text-primary-800",
  warning: "rounded-md bg-accent-100 px-2 py-1 text-xs/[normal] text-gray-900",
  errorBox: "rounded-md bg-red-50 px-2 py-1 text-xs/[normal] text-red-700",
  badge: "inline-flex shrink-0 items-center rounded px-1.5 py-px text-[11px]/[normal] bg-gray-100 text-gray-700",
  link: "font-semibold text-primary-700 underline underline-offset-2 active:opacity-70 text-xs/[normal] " + FOCUS_RING,
  status: "text-xs/[normal] text-gray-700",
} as const;

/** 촘촘한 표 */
export const tbl = {
  wrap: "min-w-0 overflow-x-auto rounded-md border border-gray-200 bg-white",
  table: "w-full border-collapse text-xs/[normal]",
  th: "sticky top-0 z-[1] border-b border-gray-200 bg-gray-50 px-2 py-1 text-left text-[11px]/[normal] font-semibold whitespace-nowrap text-gray-600",
  td: "border-b border-gray-100 px-2 py-1 align-top text-gray-800",
  num: "border-b border-gray-100 px-2 py-1 text-right align-top tabular-nums text-gray-800",
  tr: "hover:bg-primary-50/50",
} as const;

/** 상태별 작은 뱃지 색 */
export const tone = {
  ok: "inline-flex shrink-0 items-center rounded px-1.5 py-px text-[11px]/[normal] bg-emerald-50 text-emerald-700",
  warn: "inline-flex shrink-0 items-center rounded px-1.5 py-px text-[11px]/[normal] bg-amber-50 text-amber-800",
  danger: "inline-flex shrink-0 items-center rounded px-1.5 py-px text-[11px]/[normal] bg-red-50 text-red-700",
  info: "inline-flex shrink-0 items-center rounded px-1.5 py-px text-[11px]/[normal] bg-primary-50 text-primary-800",
  mute: "inline-flex shrink-0 items-center rounded px-1.5 py-px text-[11px]/[normal] bg-gray-100 text-gray-600",
} as const;
