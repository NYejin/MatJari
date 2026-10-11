import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Link, NavLink, useLocation } from "react-router-dom";
import { getDisplayName, useAuth } from "../hooks/useAuth";
import { useAuthRedirect, type AuthRouteState } from "../hooks/useAuthRedirect";
import { useLogout } from "../hooks/useLogout";
import NotificationBell from "./NotificationBell";
import { FOCUS_RING } from "./ui";

// 공통 상단 헤더 (FR-01 로그인 상태 표시 + 주요 화면 진입점 + 알림 벨)

/** 데스크톱(가로 메뉴) 전환 기준. index.css @theme --breakpoint-md(768px)와 같은 값 (클래스의 md: 접두사) */
const DESKTOP_MEDIA_QUERY = "(min-width: 768px)";

// ---- Tailwind 클래스 조합 (모바일 우선: 기본은 햄버거 + 펼침 패널, md 이상에서 가로 배치) ----
const cls = {
  /** 하단 경계선은 보조색(장식 용도 — 글자에는 쓰지 않음) */
  logoImage: "h-11 w-auto",
  header: "sticky top-0 z-[100] border-b border-secondary-200 bg-white pt-[env(safe-area-inset-top)]",
  bar: "relative mx-auto flex min-h-14 max-w-[1080px] items-center gap-2 pr-2 pl-4 md:gap-4 md:px-4",
  logo:
    "mr-auto inline-flex min-h-11 shrink-0 items-center text-xl/[normal] font-extrabold tracking-[-0.02em] " +
    "whitespace-nowrap text-primary-600 no-underline active:opacity-70 " +
    FOCUS_RING,
  menuToggle:
    "inline-flex size-11 shrink-0 cursor-pointer touch-manipulation items-center justify-center rounded-[10px] " +
    "border-0 bg-transparent p-0 md:hidden " +
    FOCUS_RING,
  /** 햄버거 아이콘: 가운데 막대 + before/after 막대 */
  menuIcon:
    "relative block h-0.5 w-5 rounded-[1px] bg-gray-900 " +
    "before:absolute before:-top-1.5 before:left-0 before:block before:h-0.5 before:w-5 before:rounded-[1px] before:bg-gray-900 " +
    "after:absolute after:top-1.5 after:left-0 after:block after:h-0.5 after:w-5 after:rounded-[1px] after:bg-gray-900",
  /** 패널 공통: md 이상에서는 항상 가로 배치 */
  panel:
    "md:static md:flex md:min-w-0 md:flex-row md:items-center md:gap-4 md:border-0 md:bg-transparent md:p-0 md:shadow-none",
  /** 모바일 닫힘: display:none으로 숨겨 탭 순서에서도 제외 */
  panelClosed: "hidden",
  /** 모바일 열림: 바 아래로 펼침 */
  panelOpen:
    "absolute inset-x-0 top-full flex flex-col gap-1 border-b border-gray-200 bg-white px-4 pt-2 pb-4 " +
    "shadow-[0_8px_24px_rgba(0,0,0,0.08)]",
  nav: "flex flex-col md:shrink-0 md:flex-row md:gap-1",
  navLink:
    "flex min-h-11 items-center rounded-[10px] px-3 text-base/[normal] font-semibold whitespace-nowrap no-underline " +
    "active:bg-gray-100 md:text-[0.9375rem]/[normal] " +
    FOCUS_RING,
  navLinkIdle: "text-gray-700",
  navLinkActive: "bg-primary-50 text-primary-600",
  auth:
    "flex min-w-0 flex-col gap-1 border-t border-gray-200 pt-2 whitespace-nowrap " +
    "md:flex-row md:items-center md:gap-2 md:border-t-0 md:pt-0",
  /** 초기화 중: 로그인 상태 영역과 같은 크기로 자리만 차지 */
  authPending: "invisible",
  /** 768px 기준 로고+알림 벨(44px+gap 16)+메뉴+이름+마이페이지+로그아웃 합계 약 706px(가용 736px) — 이름은 md에서 최대 104px(lg 248px)에서 말줄임.
      관리자는 '관리자' 링크(약 73px)가 더 붙어 이름을 md에서 최대 40px로 줄인다(706+73-64=715px) */
  userName: "flex min-w-0 items-baseline gap-2 px-3 py-2 md:max-w-[104px] md:px-1 md:py-0 lg:max-w-[248px]",
  userNameAdmin: "md:max-w-[40px]! lg:max-w-[248px]!",
  /** 표시 이름(닉네임, 없으면 email) */
  userNameMain: "min-w-0 truncate text-sm/[normal] text-gray-500",
  /** 모바일 패널에서만 닉네임 옆에 작은 email (흰 배경 gray-500 4.83:1). md 이상은 공간 계산상 숨김 */
  userEmail: "min-w-0 flex-1 truncate text-xs/[normal] text-gray-500 md:hidden",
  /** 초기화 중 자리 표시 — em 기준을 표시 이름(text-sm)과 맞춰 높이 유지 */
  userNamePending: "min-h-[1.25em] text-sm/[normal] md:w-[104px] lg:w-[248px]",
  button:
    "inline-flex min-h-11 shrink-0 cursor-pointer touch-manipulation items-center justify-center rounded-[10px] " +
    "px-4 text-base/[normal] font-semibold whitespace-nowrap no-underline md:px-3.5 md:text-[0.9375rem]/[normal] " +
    FOCUS_RING,
  buttonOutline: "border border-gray-300 bg-white text-gray-900 active:bg-gray-100",
  buttonSolid: "border border-primary-600 bg-primary-600 text-white active:bg-primary-700",
  /** 로그인 상태의 마이페이지·로그아웃을 한 줄로 묶고 두 항목이 폭을 나눠 가진다 */
  accountActions: "flex gap-1",
  accountActionItem: "grow justify-center",
  /** 계정 줄의 마이페이지 — 모바일 패널(md 미만)은 메인색 채운 버튼(흰 글자/primary-600 5.96:1),
      md 이상은 메뉴 링크 모양. navLink는 무접두 active:bg-gray-100이 있어 섞지 않고 별도 조합으로 둔다 */
  accountNav:
    "flex min-h-11 grow items-center justify-center rounded-[10px] px-4 text-base/[normal] font-semibold " +
    "whitespace-nowrap no-underline md:px-3 md:text-[0.9375rem]/[normal] " +
    "border border-primary-600 bg-primary-600 text-white active:bg-primary-700 " +
    FOCUS_RING,
  accountNavIdle: "md:border-0 md:bg-transparent md:text-gray-700 md:active:bg-gray-100",
  accountNavActive: "md:border-0 md:bg-primary-50 md:text-primary-600 md:active:bg-gray-100",
} as const;

const outlineButton = `${cls.button} ${cls.buttonOutline}`;
const solidButton = `${cls.button} ${cls.buttonSolid}`;

const AUTH_PATHS = ["/login", "/signup"];

// 로그인한 사용자에게만 보이는 주요 메뉴 (FR-03 내 티켓, FR-04 내 매칭)
const MENU: readonly { to: string; label: string }[] = [
  { to: "/tickets", label: "내 티켓" },
  { to: "/exchange/matches", label: "내 매칭" },
];

// ADMIN일 때만 덧붙이는 메뉴 (UI 분기용 — 서버가 최종 판정)
const ADMIN_MENU_ITEM = { to: "/admin", label: "관리자" } as const;

const navClass =({ isActive }: { isActive: boolean }) =>
  `${cls.navLink} ${isActive ? cls.navLinkActive : cls.navLinkIdle}`;
// 모바일은 /me에서도 채운 버튼 모양 유지, md 이상에서만 활성 여부로 분기 (aria-current는 NavLink 기본)
const accountNavClass = ({ isActive }: { isActive: boolean }) =>
  `${cls.accountNav} ${isActive ? cls.accountNavActive : cls.accountNavIdle}`;
const accountLogoutButton = `${outlineButton} ${cls.accountActionItem}`;

export default function Header() {
  const { user, profile, isInitializing } = useAuth();
  const logoutAndGoHome = useLogout();
  const { forwardState } = useAuthRedirect();
  const location = useLocation();
  const isAdmin = profile?.role === "ADMIN";
  const [menuOpen, setMenuOpen] = useState(false);
  const menuId = useId();
  const headerRef = useRef<HTMLElement>(null);
  const toggleRef = useRef<HTMLButtonElement>(null);
  const logoRef = useRef<HTMLAnchorElement>(null);

  // 경로가 바뀌면 모바일 메뉴 닫기
  useEffect(() => {
    setMenuOpen(false);
  }, [location.pathname]);

  // 메뉴가 열려 있을 때만 Esc / 바깥 클릭 / 데스크톱 폭 전환 감지
  useEffect(() => {
    if (!menuOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setMenuOpen(false);
      // 포커스가 헤더 안에 있을 때만 토글로 돌려준다 (다른 영역의 포커스를 빼앗지 않음)
      if (headerRef.current?.contains(document.activeElement)) toggleRef.current?.focus();
    };
    const onPointerDown = (e: PointerEvent) => {
      if (headerRef.current && !headerRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    const desktop = window.matchMedia(DESKTOP_MEDIA_QUERY);
    const onMediaChange = () => setMenuOpen(false);

    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    desktop.addEventListener("change", onMediaChange);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
      desktop.removeEventListener("change", onMediaChange);
    };
  }, [menuOpen]);

  // 같은 경로 링크를 눌러 pathname이 안 바뀌는 경우에도 닫히도록 링크 클릭 시 직접 닫는다
  const closeMenu = () => setMenuOpen(false);

  const handleLogout = () => {
    // 홈 이동 후 세션 삭제 (순서 이유는 useLogout 주석)
    logoutAndGoHome();
    closeMenu();
    // 로그아웃 버튼이 사라지며 포커스가 body로 떨어지지 않게 로고로 옮긴다
    logoRef.current?.focus();
  };

  // 인증 화면에서는 기존 from을 그대로 넘기고, 그 밖에서는 현재 위치의 필요한 필드만 넘긴다
  const authLinkState: AuthRouteState | undefined = AUTH_PATHS.includes(location.pathname)
    ? forwardState
    : { from: { pathname: location.pathname, search: location.search, hash: location.hash } };

  let authArea: ReactNode;
  if (isInitializing) {
    // 토큰 확인 중: 로그인 상태 영역과 같은 구조를 보이지 않게 렌더해 자리를 유지 (깜빡임/레이아웃 이동 방지).
    // 초기화는 refresh token이 있을 때만 일어나므로 대개 로그인 상태로 끝난다
    authArea = (
      <div className={`${cls.auth} ${cls.authPending}`} aria-hidden="true">
        <span className={`${cls.userName} ${cls.userNamePending}`} />
        <div className={cls.accountActions}>
          <span className={accountNavClass({ isActive: false })}>마이페이지</span>
          <span className={accountLogoutButton}>로그아웃</span>
        </div>
      </div>
    );
  } else if (user) {
    // 닉네임 우선, 프로필 로딩 중·실패 시 email
    const name = getDisplayName(user, profile);
    // 닉네임이 있을 때만 email을 덧붙인다 (email로 대체 표시 중이면 두 번 보이지 않게)
    const hasNickname = !!profile?.nickname;
    authArea = (
      <div className={cls.auth}>
        <span className={`${cls.userName} ${isAdmin ? cls.userNameAdmin : ""}`} title={hasNickname ? `${name} (${user.email})` : name}>
          <span className={cls.userNameMain}>{name}</span>
          {hasNickname && <span className={cls.userEmail}>{user.email}</span>}
        </span>
        <div className={cls.accountActions}>
          <NavLink to="/me" className={accountNavClass} onClick={closeMenu}>
            마이페이지
          </NavLink>
          <button type="button" className={accountLogoutButton} onClick={handleLogout}>
            로그아웃
          </button>
        </div>
      </div>
    );
  } else {
    authArea = (
      <div className={cls.auth}>
        <Link to="/login" state={authLinkState} className={outlineButton} onClick={closeMenu}>
          로그인
        </Link>
        <Link to="/signup" state={authLinkState} className={solidButton} onClick={closeMenu}>
          회원가입
        </Link>
      </div>
    );
  }

  return (
    <header ref={headerRef} className={cls.header}>
      <div className={cls.bar}>
        <Link ref={logoRef} to="/" className={cls.logo} onClick={closeMenu}>
          <img src="/logo.png" alt="SeatSwap" className={cls.logoImage} />
        </Link>

        {/* 알림 벨: 로그인 상태에서만. 모바일은 로고와 햄버거 사이(패널 밖)라 메뉴를 열지 않아도 보인다 */}
        {user && !isInitializing && <NotificationBell userId={user.id} onOpen={closeMenu} />}

        <button
          ref={toggleRef}
          type="button"
          className={cls.menuToggle}
          aria-expanded={menuOpen}
          aria-controls={menuId}
          aria-label={menuOpen ? "메뉴 닫기" : "메뉴 열기"}
          onClick={() => setMenuOpen((open) => !open)}
        >
          <span className={cls.menuIcon} aria-hidden="true" />
        </button>

        <div id={menuId} className={`${cls.panel} ${menuOpen ? cls.panelOpen : cls.panelClosed}`}>
          <nav className={cls.nav} aria-label="주요 메뉴">
            {(user ? (isAdmin ? [...MENU, ADMIN_MENU_ITEM] : MENU) : []).map((item) => (
              <NavLink key={item.to} to={item.to} className={navClass} onClick={closeMenu}>
                {item.label}
              </NavLink>
            ))}
          </nav>
          {authArea}
        </div>
      </div>
    </header>
  );
}
