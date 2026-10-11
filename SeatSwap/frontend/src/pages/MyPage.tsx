import { useEffect, useRef, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { getDisplayName, useAuth } from "../hooks/useAuth";
import { useLogout } from "../hooks/useLogout";
import { FOCUS_RING } from "../components/ui";

// FR-01 회원 — 마이페이지 (보호 라우트 /me). 내 정보(/api/users/me) 표시 + 로그아웃.
// 내 티켓·내 매칭·교환 이력 등은 링크, 회원탈퇴만 "준비 중" 자리로 둔다.

const cls = {
  /** AppLayout 콘텐츠 영역을 채우는 배경 (로그인 화면과 같은 톤) */
  page: "flex w-full flex-[1_0_auto] justify-center bg-surface px-4 pt-6 pb-[calc(24px+env(safe-area-inset-bottom))]",
  container: "flex w-full max-w-[640px] flex-col gap-4",
  title: "text-2xl/[normal] font-bold text-gray-900",
  card: "rounded-2xl bg-white p-5 shadow-[0_4px_24px_rgba(0,0,0,0.06)] sm:p-6",
  /** 프로필 카드 상단 장식선은 보조색 (장식 용도 — 글자에는 쓰지 않음). 재시도 후 포커스 대상이라 외곽선 표시 */
  profileCard: "border-t-4 border-secondary-500 " + FOCUS_RING,
  nickname: "truncate text-xl/[normal] font-bold text-gray-900",
  email: "mt-1 truncate text-sm/[normal] text-gray-500",
  scoreRow: "mt-5 flex items-baseline justify-between gap-3 border-t border-gray-200 pt-4",
  scoreLabel: "text-sm/[normal] font-semibold text-gray-700",
  /** 흰 배경 + primary-700 글자 7.67:1 */
  scoreValue: "text-2xl/[normal] font-bold text-primary-700",
  /** 상태 라이브 영역 — 항상 렌더링하고 텍스트만 바꾼다 (비어 있으면 높이·여백 0) */
  status: "text-sm/[normal] text-gray-500 not-empty:mb-3",
  errorBox: "mt-3 rounded-[10px] bg-red-50 px-3.5 py-3 text-sm/[normal] text-red-700",
  sectionTitle: "text-base/[normal] font-semibold text-gray-900",
  comingList: "mt-2 divide-y divide-gray-200",
  comingItem: "flex min-h-11 items-center justify-between gap-3 text-[0.9375rem]/[normal] text-gray-700",
  activityLink: "font-semibold text-gray-900 no-underline active:bg-gray-100 " + FOCUS_RING,
  /** gray-100 배경 + gray-700 글자 9.37:1 (gray-500은 4.39:1로 AA 미달) */
  comingBadge: "shrink-0 rounded-full bg-gray-100 px-2.5 py-0.5 text-[0.8125rem]/[normal] text-gray-700",
  /** 하단 계정 동작 버튼 줄 (오른쪽 정렬) */
  accountActions: "flex justify-end gap-2",
  /** 터치 영역 48px */
  button:
    "inline-flex min-h-12 w-full cursor-pointer touch-manipulation items-center justify-center rounded-[10px] " +
    "px-4 text-base/[normal] font-semibold sm:w-auto " +
    FOCUS_RING,
  retryButton: "mt-3",
  buttonOutline: "border border-gray-300 bg-white text-gray-900 active:bg-gray-100",
  buttonSolid: "border border-primary-600 bg-primary-600 text-white active:bg-primary-700",
} as const;

const outlineButton = `${cls.button} ${cls.buttonOutline}`;
/** 준비 중 기능 버튼: 비활성 + "준비 중" 배지(정보는 배지가 전달 — gray-100 배경 gray-700 글자 9.37:1) */
const comingSoonButton =
  `${cls.button} gap-2 border border-gray-200 bg-white text-gray-500 disabled:cursor-not-allowed`;
const retryButton = `${cls.button} ${cls.buttonSolid} ${cls.retryButton}`;

const ACTIVITY_LINKS = [
  { to: "/tickets", label: "내 티켓" },
  { to: "/exchange/matches", label: "내 매칭" },
  { to: "/exchange/history", label: "교환 이력" },
  { to: "/my/edit-proposals", label: "내 수정 제안" },
  { to: "/my/seat-claims", label: "내 티켓 인증 요청" },
] as const;

/**
 * 신뢰도 점수는 소수 1자리. 값이 없으면(null) 대시.
 * 신규 회원은 백엔드 기본값 0.0이 그대로 표시된다 — 기획 결정 사항이므로 "-" 등으로 바꾸지 않는다.
 */
function formatTrustScore(score: number | null): string {
  return typeof score === "number" && Number.isFinite(score) ? score.toFixed(1) : "-";
}

export default function MyPage() {
  const { user, profile, profileStatus, profileError, reloadProfile } = useAuth();
  const logoutAndGoHome = useLogout();

  // "다시 시도" 버튼은 로딩이 시작되면 사라지므로, 누르는 순간 포커스를 카드로 옮기고
  // 결과가 나오면 다시 카드에 둔다 (포커스가 원래 카드 안에 있었을 때만)
  const cardRef = useRef<HTMLElement>(null);
  const focusCardAfterReload = useRef(false);

  const handleRetry = () => {
    const card = cardRef.current;
    if (card && card.contains(document.activeElement)) {
      focusCardAfterReload.current = true;
      card.focus();
    }
    reloadProfile();
  };

  useEffect(() => {
    if (profileStatus === "loading" || !focusCardAfterReload.current) return;
    focusCardAfterReload.current = false;
    cardRef.current?.focus();
  }, [profileStatus]);

  // ProtectedRoute 하위라 user는 항상 있다 (타입 좁히기용)
  if (!user) return null;

  const displayName = getDisplayName(user, profile);
  const statusText = profileStatus === "loading" ? "내 정보를 불러오는 중..." : "";

  let profileBody: ReactNode = null;
  if (profile) {
    // 성공 또는 재조회 중·재조회 실패(이전 값 유지)
    profileBody = (
      <>
        <p className={cls.nickname} title={displayName}>
          {displayName}
        </p>
        <p className={cls.email} title={profile.email}>
          {profile.email}
        </p>
        <div className={cls.scoreRow}>
          <span className={cls.scoreLabel}>신뢰도 점수</span>
          <span className={cls.scoreValue}>{formatTrustScore(profile.trustScore)}</span>
        </div>
      </>
    );
  } else if (profileStatus === "error") {
    // 토큰의 email은 항상 있으므로 오류여도 최소 정보는 보여준다
    profileBody = (
      <p className={cls.nickname} title={displayName}>
        {displayName}
      </p>
    );
  }

  return (
    <div className={cls.page}>
      <div className={cls.container}>
        <h1 className={cls.title}>마이페이지</h1>

        <section ref={cardRef} tabIndex={-1} className={`${cls.card} ${cls.profileCard}`} aria-label="내 정보">
          <p className={cls.status} role="status">
            {statusText}
          </p>
          {profileBody}
          {/* profile이 있어도 재조회가 실패하면 오류 안내와 재시도를 함께 보여준다 */}
          {profileStatus === "error" && (
            <>
              <p className={cls.errorBox} role="alert">
                {profileError ?? "내 정보를 불러오지 못했습니다."}
              </p>
              <button type="button" className={retryButton} onClick={handleRetry}>
                다시 시도
              </button>
            </>
          )}
        </section>

        <section className={cls.card} aria-labelledby="mypage-coming-soon">
          <h2 id="mypage-coming-soon" className={cls.sectionTitle}>
            내 활동
          </h2>
          <ul className={cls.comingList}>
            {ACTIVITY_LINKS.map((item) => (
              <li key={item.to}>
                <Link to={item.to} className={`${cls.comingItem} ${cls.activityLink}`}>
                  <span>{item.label}</span>
                  <span aria-hidden="true">›</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>

        <div className={cls.accountActions}>
          <button type="button" className={outlineButton} onClick={logoutAndGoHome}>
            로그아웃
          </button>
          {/* 회원탈퇴는 아직 미구현 — 비활성 + 준비 중 배지 */}
          <button type="button" className={comingSoonButton} disabled>
            회원탈퇴
            <span className={cls.comingBadge}>준비 중</span>
          </button>
        </div>
      </div>
    </div>
  );
}
