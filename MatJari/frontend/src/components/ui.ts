// 화면 공용 Tailwind 클래스 조합 (react-conventions "CSS 전략").
// 클래스는 항상 완성된 문자열로 두고, 상태별 변형은 완성된 문자열 중 하나를 고르는 방식으로 쓴다.
// 대비(WCAG AA)는 각 조합 옆 주석 참고. 보조색(secondary)은 글자에 쓰지 않는다.

/**
 * 키보드 포커스 표시 (헤더·폼 공통 링). 비텍스트 대비 3:1 이상이 되도록 불투명 primary-600 외곽선 + 2px 간격.
 * 대비: 흰 배경 5.96:1, surface 배경 5.51:1, primary-50 배경 5.42:1 (이전 primary-600/40은 흰 배경 1.96:1로 미달)
 */
export const FOCUS_RING =
  "focus-visible:outline-2 focus-visible:outline-solid focus-visible:outline-offset-2 focus-visible:outline-primary-600";

// ---- 버튼 ----
// <button>은 enabled:active, 링크(<a>)는 :enabled가 없어 active만 쓴다 — 요소별로 완성된 문자열을 따로 둔다.
const BUTTON_LAYOUT =
  "inline-flex min-h-11 cursor-pointer touch-manipulation items-center justify-center gap-1.5 rounded-[10px] " +
  "px-4 text-[0.9375rem]/[normal] font-semibold whitespace-nowrap no-underline " +
  "disabled:cursor-not-allowed disabled:opacity-60 " +
  FOCUS_RING;

export const button = {
  /** 흰 글자 / primary-600 5.96:1 */
  solid: `${BUTTON_LAYOUT} border border-primary-600 bg-primary-600 text-white enabled:active:bg-primary-700`,
  /** gray-900 / 흰 배경 */
  outline: `${BUTTON_LAYOUT} border border-gray-300 bg-white text-gray-900 enabled:active:bg-gray-100`,
  /** 흰 글자 / red-600 4.83:1 */
  danger: `${BUTTON_LAYOUT} border border-red-600 bg-red-600 text-white enabled:active:bg-red-700`,
  /** red-700 / 흰 배경 6.47:1 */
  dangerOutline: `${BUTTON_LAYOUT} border border-red-600 bg-white text-red-700 enabled:active:bg-red-50`,
} as const;

/** 버튼 모양의 링크 (<Link>, <a>) */
export const linkButton = {
  solid: `${BUTTON_LAYOUT} border border-primary-600 bg-primary-600 text-white active:bg-primary-700`,
  outline: `${BUTTON_LAYOUT} border border-gray-300 bg-white text-gray-900 active:bg-gray-100`,
} as const;

// ---- 입력칸 ----
// text-base(16px): iOS Safari 입력 시 자동 확대 방지. 높이 48px 터치 영역.
// outline-hidden: 기본 외곽선은 숨기되 강제 색상 모드(Windows 고대비)에서는 유지된다.
const INPUT_BASE =
  "min-h-12 w-full rounded-[10px] border bg-white px-3.5 text-base/[normal] text-gray-900 outline-hidden " +
  "placeholder:text-gray-500 focus-visible:ring-3 disabled:bg-gray-100";

/** 입력칸 클래스 — 오류 여부에 따라 테두리·포커스 링 색을 한쪽만 붙인다 */
export const input = {
  normal: `${INPUT_BASE} border-gray-300 focus-visible:border-primary-600 focus-visible:ring-primary-600/20`,
  invalid: `${INPUT_BASE} border-red-600 focus-visible:ring-red-600/20`,
} as const;

// ---- 레이아웃·텍스트 ----
export const ui = {
  /** AppLayout 콘텐츠 영역을 채우는 배경 (100dvh 대신 flex — 헤더 높이만큼 넘치지 않게) */
  page: "flex w-full flex-[1_0_auto] justify-center bg-surface px-4 pt-6 pb-[calc(24px+env(safe-area-inset-bottom))]",
  container: "flex w-full max-w-[720px] min-w-0 flex-col gap-4",
  pageTitle: "text-2xl/[normal] font-bold text-gray-900",
  card: "min-w-0 rounded-2xl bg-white p-5 shadow-[0_4px_24px_rgba(0,0,0,0.06)] sm:p-6",
  sectionTitle: "text-lg/[normal] font-bold text-gray-900",
  label: "text-sm/[normal] font-semibold text-gray-700",
  /** gray-700 / 흰 배경 10.31:1 */
  body: "text-[0.9375rem]/[normal] text-gray-700",
  /** gray-500 / 흰 배경 4.83:1 (surface 배경 위에서는 4.47:1이라 카드 안에서만 쓴다) */
  muted: "text-sm/[normal] text-gray-500",
  fieldError: "text-[0.8125rem]/[normal] text-red-600",
  hint: "text-[0.8125rem]/[normal] text-gray-500",
  /** primary-800 / primary-50 9.02:1 */
  notice: "rounded-[10px] bg-primary-50 px-3.5 py-3 text-sm/[normal] text-primary-800",
  /** 경고(되돌릴 수 없는 동작 확인 등): gray-900 / accent-100 */
  warning: "rounded-[10px] bg-accent-100 px-3.5 py-3 text-sm/[normal] text-gray-900",
  /** red-700 / red-50 */
  errorBox: "rounded-[10px] bg-red-50 px-3.5 py-3 text-sm/[normal] text-red-700",
  /** gray-700 / gray-100 9.37:1 */
  badge: "inline-flex shrink-0 items-center rounded-full bg-gray-100 px-2.5 py-0.5 text-[0.8125rem]/[normal] text-gray-700",
  /** 본문 속 링크: primary-700 / 흰 배경 7.67:1 */
  link: "font-semibold text-primary-700 underline underline-offset-2 active:opacity-70 " + FOCUS_RING,
  /** 상태 라이브 영역 — 항상 렌더링하고 텍스트만 바꾼다 */
  status: "text-sm/[normal] text-gray-700",
} as const;

/**
 * 라이브 영역(role="status")은 내용이 없을 때도 DOM에 남겨야 이후 변경이 읽힌다.
 * 텍스트가 없으면 sr-only(절대 위치 — flex gap·여백을 차지하지 않음), 있으면 보이는 스타일 중 하나를 고른다.
 */
export const liveRegionClass = (text: string | null | undefined, visibleClass: string): string =>
  text ? visibleClass : "sr-only";
