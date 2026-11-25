import { useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import { getDisplayName, getVisibleEmail, useAuth } from "../hooks/useAuth";
import { useLogout } from "../hooks/useLogout";
import NicknameEditor from "../components/NicknameEditor";
import NotificationSettingsCard from "../components/NotificationSettingsCard";
import PasswordChangeCard from "../components/PasswordChangeCard";
import SocialLinkCard from "../components/SocialLinkCard";
import WithdrawDialog from "../components/WithdrawDialog";
import { clearTakenWithdrawReauthCode, takeWithdrawReauthCode } from "../api/socialLink";
import { button, FOCUS_RING, ui } from "../components/ui";

// FR-01 회원 — 마이페이지 (보호 라우트 /me). 내 정보(/api/users/me) 표시 + 로그아웃.
// 내 티켓·내 매칭·교환 이력 등은 링크, 회원탈퇴는 확인 모달(WithdrawDialog)을 연다.

const cls = {
  nickname: "truncate text-xl/[normal] font-bold text-gray-900",
  email: "mt-1 truncate text-sm/[normal] text-gray-600",
  /** 상태 라이브 영역 — 항상 렌더링하고 텍스트만 바꾼다 (비어 있으면 높이·여백 0) */
  status: "text-sm/[normal] text-gray-600 not-empty:mb-3",
  /** 프로필 카드: 그라데이션 띠 위에 이니셜 아바타. 재시도 후 포커스 대상이라 외곽선 표시 */
  profileCard: "relative overflow-hidden " + FOCUS_RING,
  banner: "absolute inset-x-0 top-0 h-20 bg-linear-to-r from-primary-200 via-primary-100 to-coral-100",
  avatar:
    "relative flex size-16 shrink-0 items-center justify-center rounded-full bg-linear-to-br from-primary-600 to-coral-600 " +
    "text-2xl/[normal] font-bold text-white ring-4 ring-white",
  activityGrid: "mt-5 grid grid-cols-2 gap-3 sm:grid-cols-3 sm:gap-4",
  tile: "flex min-h-[116px] flex-col items-start gap-3 !p-4 sm:!p-5",
  tileIcon: "flex size-11 items-center justify-center rounded-2xl",
  /** 회원탈퇴·로그아웃: 맨 아래 옅은 위험 영역 */
  dangerZone:
    "flex flex-col gap-3 rounded-[20px] border border-red-100 bg-red-50/50 p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6",
  accountActions: "flex flex-col gap-2 sm:flex-row sm:justify-end",
} as const;

/** 활동 타일 색 (글자·아이콘은 각 색 700 / 배경 100) */
const TONES = {
  primary: "bg-primary-100 text-primary-700",
  sky: "bg-sky-100 text-sky-700",
  mint: "bg-mint-100 text-mint-700",
  coral: "bg-coral-100 text-coral-700",
  amber: "bg-accent-100 text-gray-900",
  gray: "bg-gray-100 text-gray-700",
} as const;

const ACTIVITY_LINKS = [
  { to: "/tickets", label: "내 티켓", tone: "primary", icon: "M4 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4zM14 6v12" },
  { to: "/exchange/matches", label: "내 매칭", tone: "mint", icon: "M7 7h11l-3-3M17 17H6l3 3" },
  { to: "/my/chats", label: "내 채팅", tone: "sky", icon: "M5 5h14a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1h-8l-4 3v-3H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z" },
  { to: "/exchange/history", label: "교환 이력", tone: "mint", icon: "M12 7v5l3 2M4 12a8 8 0 1 0 2.5-5.8M4 4v4h4" },
  { to: "/my/edit-proposals", label: "내 수정 제안", tone: "primary", icon: "M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4" },
  { to: "/my/seat-claims", label: "내 티켓 인증 요청", tone: "amber", icon: "M12 3l7 3v5c0 5-3 8-7 10-4-2-7-5-7-10V6zM9 12l2 2 4-4" },
  { to: "/my/blocks", label: "차단 목록", tone: "gray", icon: "M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16zM6.5 6.5l11 11" },
  { to: "/my/reports", label: "내 신고 내역", tone: "coral", icon: "M6 21V4M6 5h11l-2 4 2 4H6" },
] as const;

export default function MyPage() {
  const { user, profile, profileStatus, profileError, reloadProfile } = useAuth();
  const { logout } = useAuth();
  const logoutAndGoHome = useLogout();
  const navigate = useNavigate();
  // 소셜 전용 계정이 연결된 소셜 계정으로 다시 인증하고 돌아온 경우(/me#linkCode=): 코드를 탈퇴 모달에 넘기며 모달을 바로 연다
  const [reauthCode] = useState<string | null>(() => takeWithdrawReauthCode());
  const [withdrawOpen, setWithdrawOpen] = useState(reauthCode !== null);

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

  // 탈퇴 성공: 보호 화면을 먼저 벗어난 뒤 토큰을 지운다 (useLogout과 같은 순서). 안내는 홈에서 한 번만 보여준다
  const handleWithdrawn = () => {
    navigate("/", { replace: true, state: { notice: "탈퇴가 완료됐어요. 그동안 이용해 주셔서 고마워요." } });
    logout();
  };

  const displayName = getDisplayName(user, profile);
  const visibleEmail = getVisibleEmail(user, profile);
  const statusText = profileStatus === "loading" ? "내 정보를 불러오는 중..." : "";
  const initial = Array.from(displayName.trim())[0] ?? "?";

  let profileBody: ReactNode = null;
  if (profile) {
    // 성공 또는 재조회 중·재조회 실패(이전 값 유지)
    profileBody = (
      <>
        <NicknameEditor nickname={displayName} />
        {visibleEmail ? (
          <p className={cls.email} title={visibleEmail}>
            {visibleEmail}
          </p>
        ) : (
          <p className={cls.email}>소셜 계정으로 가입해 이메일이 없어요</p>
        )}
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
    <div className={ui.page}>
      <div className={ui.container}>
        <header className={ui.pageHeader}>
          <div>
            <p className={ui.eyebrow}>내 계정</p>
            <h1 className={ui.pageTitle}>마이페이지</h1>
          </div>
        </header>

        <section ref={cardRef} tabIndex={-1} className={`${ui.card} ${cls.profileCard}`} aria-label="내 정보">
          <div aria-hidden="true" className={cls.banner} />
          <div className="relative flex items-end gap-4 pt-6">
            <div aria-hidden="true" className={cls.avatar}>
              {initial}
            </div>
          </div>
          <p className={`${cls.status} mt-4`} role="status">
            {statusText}
          </p>
          <div className="mt-3">{profileBody}</div>
          {/* profile이 있어도 재조회가 실패하면 오류 안내와 재시도를 함께 보여준다 */}
          {profileStatus === "error" && (
            <>
              <p className={`${ui.errorBox} mt-3`} role="alert">
                {profileError ?? "내 정보를 불러오지 못했습니다."}
              </p>
              <button type="button" className={`${button.solid} mt-3`} onClick={handleRetry}>
                다시 시도
              </button>
            </>
          )}
        </section>

        {/* 관리자 계정은 거래 활동 메뉴를 두지 않는다(2026-10-21) */}
        {profile?.role !== "ADMIN" && (
          <section aria-labelledby="mypage-coming-soon">
            <h2 id="mypage-coming-soon" className={ui.sectionTitle}>
              내 활동
            </h2>
            <ul className={cls.activityGrid}>
              {ACTIVITY_LINKS.map((item) => (
                <li key={item.to} className="flex">
                  <Link to={item.to} className={`${ui.cardInteractive} ${cls.tile} w-full no-underline`}>
                    <span aria-hidden="true" className={`${cls.tileIcon} ${TONES[item.tone]}`}>
                      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                        <path d={item.icon} />
                      </svg>
                    </span>
                    <span className="text-[0.9375rem]/[1.4] font-semibold break-keep text-gray-900">{item.label}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        )}

        {profile && (profile.passwordLoginEnabled ?? true) && <PasswordChangeCard />}

        <NotificationSettingsCard />

        <SocialLinkCard />

        <div className={cls.dangerZone}>
          <p className="text-sm/[1.6] break-keep text-gray-700">계정 관련 동작이에요. 회원탈퇴는 되돌릴 수 없어요.</p>
          <div className={cls.accountActions}>
            <button type="button" className={button.outline} onClick={logoutAndGoHome}>
              로그아웃
            </button>
            <button
              type="button"
              className={button.dangerOutline}
              onClick={() => setWithdrawOpen(true)}
              disabled={!profile}
              title={profile ? undefined : "내 정보를 불러온 뒤 탈퇴할 수 있어요"}
            >
              회원탈퇴
            </button>
          </div>
        </div>
      </div>
      {withdrawOpen && profile && (
        <WithdrawDialog
          passwordLoginEnabled={profile.passwordLoginEnabled ?? true}
          reauthCode={reauthCode ?? undefined}
          onClose={() => {
            clearTakenWithdrawReauthCode();
            setWithdrawOpen(false);
          }}
          onWithdrawn={handleWithdrawn}
        />
      )}
    </div>
  );
}
