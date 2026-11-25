// 화면 공용 Tailwind 클래스 조합 (react-conventions "CSS 전략").
// 클래스는 항상 완성된 문자열로 두고, 상태별 변형은 완성된 문자열 중 하나를 고르는 방식으로 쓴다.
// 대비(WCAG AA)는 각 조합 옆 주석 참고. 보조색(secondary)은 글자에 쓰지 않는다.
//
// 2026-11-25 디자인 개편(사용자 지시 "예쁘고 널널하게, 색 여러 개 써도 됨"):
// - 보라(primary)는 주요 동작·현재 위치에, sky·mint·coral·amber(accent)는 상태·분류 구분에 쓴다(index.css 주석).
// - 카드는 큰 그림자 대신 얇은 테두리 + 옅은 그림자, 모서리 20px, 여백을 넓혔다.
// - 버튼은 모서리 12px, 주요 버튼에 보라 그림자, 보조 버튼은 옅은 테두리, soft·ghost 추가.
// - 상태 뱃지는 tone(색)별 완성 문자열(badgeTone)을 쓴다.

/**
 * 키보드 포커스 표시 (헤더·폼 공통 링). 비텍스트 대비 3:1 이상이 되도록 불투명 primary-600 외곽선 + 2px 간격.
 * 대비: 흰 배경 5.96:1, surface 배경 5.51:1, primary-50 배경 5.42:1
 */
export const FOCUS_RING =
  "focus-visible:outline-2 focus-visible:outline-solid focus-visible:outline-offset-2 focus-visible:outline-primary-600";

// ---- 버튼 ----
// <button>은 enabled:active, 링크(<a>)는 :enabled가 없어 active만 쓴다 — 요소별로 완성된 문자열을 따로 둔다.
const BUTTON_LAYOUT =
  "inline-flex min-h-11 cursor-pointer touch-manipulation items-center justify-center gap-1.5 rounded-xl " +
  "px-4.5 text-[0.9375rem]/[normal] font-semibold whitespace-nowrap no-underline transition-colors " +
  "disabled:cursor-not-allowed disabled:opacity-55 " +
  FOCUS_RING;

const SOLID = "border border-primary-600 bg-primary-600 text-white shadow-[0_6px_16px_-6px_rgba(138,43,226,0.55)]";
const OUTLINE = "border border-gray-200 bg-white text-gray-900 shadow-[0_1px_2px_rgba(17,24,39,0.04)]";
const SOFT = "border border-transparent bg-primary-50 text-primary-700";
const GHOST = "border border-transparent bg-transparent text-gray-700";

export const button = {
  /** 흰 글자 / primary-600 5.96:1 */
  solid: `${BUTTON_LAYOUT} ${SOLID} enabled:hover:bg-primary-700 enabled:active:bg-primary-800`,
  /** gray-900 / 흰 배경 */
  outline: `${BUTTON_LAYOUT} ${OUTLINE} enabled:hover:border-gray-300 enabled:hover:bg-gray-50 enabled:active:bg-gray-100`,
  /** 보조 강조: primary-700 / primary-50 7.0:1 */
  soft: `${BUTTON_LAYOUT} ${SOFT} enabled:hover:bg-primary-100 enabled:active:bg-primary-200`,
  /** 가장 가벼운 동작: gray-700 글자 */
  ghost: `${BUTTON_LAYOUT} ${GHOST} enabled:hover:bg-gray-100 enabled:active:bg-gray-200`,
  /** 흰 글자 / red-600 4.83:1 */
  danger: `${BUTTON_LAYOUT} border border-red-600 bg-red-600 text-white enabled:hover:bg-red-700 enabled:active:bg-red-700`,
  /** red-700 / 흰 배경 6.47:1 */
  dangerOutline: `${BUTTON_LAYOUT} border border-red-200 bg-white text-red-700 enabled:hover:bg-red-50 enabled:active:bg-red-50`,
} as const;

/** 버튼 모양의 링크 (<Link>, <a>) */
export const linkButton = {
  solid: `${BUTTON_LAYOUT} ${SOLID} hover:bg-primary-700 active:bg-primary-800`,
  outline: `${BUTTON_LAYOUT} ${OUTLINE} hover:border-gray-300 hover:bg-gray-50 active:bg-gray-100`,
  soft: `${BUTTON_LAYOUT} ${SOFT} hover:bg-primary-100 active:bg-primary-200`,
  ghost: `${BUTTON_LAYOUT} ${GHOST} hover:bg-gray-100 active:bg-gray-200`,
} as const;

// ---- 입력칸 ----
// text-base(16px): iOS Safari 입력 시 자동 확대 방지. 높이 48px 터치 영역.
// outline-hidden: 기본 외곽선은 숨기되 강제 색상 모드(Windows 고대비)에서는 유지된다.
const INPUT_BASE =
  "min-h-12 w-full rounded-xl border bg-white px-3.5 text-base/[normal] text-gray-900 outline-hidden transition-shadow " +
  "placeholder:text-gray-500 focus-visible:ring-4 disabled:bg-gray-100";

/** 입력칸 클래스 — 오류 여부에 따라 테두리·포커스 링 색을 한쪽만 붙인다 */
export const input = {
  normal: `${INPUT_BASE} border-gray-200 hover:border-gray-300 focus-visible:border-primary-600 focus-visible:ring-primary-600/15`,
  invalid: `${INPUT_BASE} border-red-600 focus-visible:ring-red-600/15`,
} as const;

// ---- 상태 뱃지 색 ----
const BADGE = "inline-flex shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-[0.8125rem]/[normal] font-semibold";

/**
 * 뱃지 색. 글자는 모두 각 색의 700(또는 gray-700)이라 같은 계열 50/100 배경에서 4.5:1 이상.
 * 쓰임 약속: primary=내 것·선택됨, sky=진행 중(채팅 중), amber=예약·주의, mint=완료·성공, coral=새 소식, gray=종료·비활성, red=오류·차단
 */
export const badgeTone = {
  gray: `${BADGE} bg-gray-100 text-gray-700`,
  primary: `${BADGE} bg-primary-50 text-primary-700`,
  sky: `${BADGE} bg-sky-50 text-sky-700`,
  amber: `${BADGE} bg-accent-100 text-gray-900`,
  mint: `${BADGE} bg-mint-50 text-mint-700`,
  coral: `${BADGE} bg-coral-50 text-coral-700`,
  red: `${BADGE} bg-red-50 text-red-700`,
} as const;
export type BadgeTone = keyof typeof badgeTone;

/** 매칭 상태별 뱃지 색 (CHATTING·RESERVED·COMPLETED·CANCELED) */
export const matchStatusTone = (status: string | null | undefined): BadgeTone =>
  status === "CHATTING" ? "sky" : status === "RESERVED" ? "amber" : status === "COMPLETED" ? "mint" : "gray";

// ---- 탭(세그먼트) ----
export const segment = {
  /** 회색 바탕 위 알약 탭 묶음 */
  group: "inline-flex w-full gap-1 rounded-2xl bg-gray-100 p-1 sm:w-auto",
  item: `inline-flex min-h-10 flex-1 cursor-pointer items-center justify-center gap-1.5 rounded-xl px-4 text-sm font-semibold whitespace-nowrap transition-colors sm:flex-none ${FOCUS_RING}`,
  idle: "text-gray-700 hover:text-gray-900",
  /** 선택됨: 흰 알약 + primary-700 글자 */
  active: "bg-white text-primary-700 shadow-[0_1px_3px_rgba(17,24,39,0.1)]",
} as const;

// ---- 레이아웃·텍스트 ----
const CARD = "min-w-0 rounded-[20px] border border-gray-200/70 bg-white p-5 shadow-[0_2px_12px_rgba(17,24,39,0.04)] sm:p-7";

export const ui = {
  /** AppLayout 콘텐츠 영역을 채우는 배경 (100dvh 대신 flex — 헤더 높이만큼 넘치지 않게). 위쪽에 아주 옅은 보라 빛 */
  page:
    "flex w-full flex-[1_0_auto] justify-center bg-surface bg-[radial-gradient(1200px_320px_at_50%_-120px,rgba(138,43,226,0.07),transparent)] " +
    "px-4 pt-8 pb-[calc(40px+env(safe-area-inset-bottom))] sm:px-6 sm:pt-10",
  container: "flex w-full max-w-[760px] min-w-0 flex-col gap-5 sm:gap-6",
  /** 넓은 화면용 (목록·상세 2단) */
  containerWide: "flex w-full max-w-[1040px] min-w-0 flex-col gap-5 sm:gap-6",
  /** 쪽 머리: 작은 분류 글자(eyebrow) + 제목 + 설명, 오른쪽 동작 버튼 */
  pageHeader: "flex flex-wrap items-end justify-between gap-x-4 gap-y-3 pb-1",
  eyebrow: "text-[0.8125rem]/[normal] font-semibold text-primary-700",
  pageTitle: "text-[1.625rem]/[1.3] font-bold tracking-[-0.01em] text-balance text-gray-900 break-keep",
  pageLead: "max-w-[560px] text-[0.9375rem]/[1.65] text-gray-600 break-keep",
  card: CARD,
  /** 누를 수 있는 카드(목록 항목 링크) */
  cardInteractive: `${CARD} block transition-[border-color,box-shadow,transform] hover:-translate-y-0.5 hover:border-primary-200 hover:shadow-[0_10px_30px_-12px_rgba(138,43,226,0.28)] ${FOCUS_RING}`,
  /** 카드 안 옅은 바탕 칸(요약·정보 묶음) */
  well: "rounded-2xl bg-gray-50 px-4 py-3.5",
  sectionTitle: "text-lg/[1.4] font-bold text-gray-900 break-keep",
  label: "text-sm/[normal] font-semibold text-gray-700",
  /** gray-700 / 흰 배경 10.31:1 */
  body: "text-[0.9375rem]/[1.65] text-gray-700",
  /** gray-500 / 흰 배경 4.83:1 (surface 배경 위에서는 4.5:1 근처라 카드 안에서만 쓴다) */
  muted: "text-sm/[normal] text-gray-500",
  fieldError: "text-[0.8125rem]/[normal] text-red-600",
  hint: "text-[0.8125rem]/[1.55] text-gray-500",
  /** 안내: sky-700 대신 primary 계열 유지 — primary-800 / primary-50 9.02:1, 왼쪽 색 막대 */
  notice: "rounded-2xl border-l-4 border-primary-400 bg-primary-50 px-4 py-3 text-sm/[1.6] text-primary-800",
  /** 경고(되돌릴 수 없는 동작 확인 등): gray-900 / accent-100 */
  warning: "rounded-2xl border-l-4 border-accent-500 bg-accent-50 px-4 py-3 text-sm/[1.6] text-gray-900",
  /** red-700 / red-50 */
  errorBox: "rounded-2xl border-l-4 border-red-600 bg-red-50 px-4 py-3 text-sm/[1.6] text-red-700",
  /** 성공: mint-700 / mint-50 */
  successBox: "rounded-2xl border-l-4 border-mint-500 bg-mint-50 px-4 py-3 text-sm/[1.6] text-mint-700",
  /** gray-700 / gray-100 9.37:1 (색 있는 상태 뱃지는 badgeTone) */
  badge: badgeTone.gray,
  /** 본문 속 링크: primary-700 / 흰 배경 7.67:1, 옅은 밑줄 */
  link:
    "font-semibold text-primary-700 underline decoration-primary-300 decoration-2 underline-offset-[3px] hover:decoration-primary-600 active:opacity-70 " +
    FOCUS_RING,
  /** 빈 목록 안내 */
  empty: "flex flex-col items-center gap-3 rounded-[20px] border border-dashed border-gray-300 bg-white/70 px-6 py-12 text-center",
  /** 상태 라이브 영역 — 항상 렌더링하고 텍스트만 바꾼다 */
  status: "text-sm/[normal] text-gray-700",
} as const;

/**
 * 라이브 영역(role="status")은 내용이 없을 때도 DOM에 남겨야 이후 변경이 읽힌다.
 * 텍스트가 없으면 sr-only(절대 위치 — flex gap·여백을 차지하지 않음), 있으면 보이는 스타일 중 하나를 고른다.
 */
export const liveRegionClass = (text: string | null | undefined, visibleClass: string): string =>
  text ? visibleClass : "sr-only";
