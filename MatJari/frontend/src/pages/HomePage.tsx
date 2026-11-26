import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { performancesApi } from "../api/performances";
import { parseApiError } from "../api/errors";
import { formatKstDateTime, kstMinuteString } from "../api/dateTime";
import { useAuth } from "../hooks/useAuth";
import { useDebouncedValue } from "../hooks/useDebouncedValue";
import PerformanceThumb from "../components/PerformanceThumb";
import type { PerformanceSummary } from "../types/performance";
import { FOCUS_RING, badgeTone, button, input, linkButton, liveRegionClass, ui } from "../components/ui";

// 홈 = 공연 목록 (FR-02). 공개 라우트: 목록 API가 로그인 필요라 비로그인 사용자에게는 API를 부르지 않고
// 서비스 안내 + 로그인/회원가입 버튼을 보여준다 (헤더와 같은 진입점 — 로그인 후 "/"로 복귀).

const cls = {
  hero:
    "relative flex flex-col gap-4 overflow-hidden rounded-[28px] bg-gradient-to-br from-primary-600 via-primary-500 to-coral-500 " +
    "px-6 py-9 text-white shadow-[0_20px_40px_-20px_rgba(138,43,226,0.6)] sm:px-10 sm:py-12",
  heroEyebrow: "text-[0.8125rem]/[normal] font-semibold text-white/90",
  heroTitle: "text-[1.75rem]/[1.3] font-bold tracking-[-0.01em] text-balance break-keep sm:text-[2.25rem]",
  heroLead: "max-w-[520px] text-[0.9375rem]/[1.7] text-white/95 break-keep sm:text-base/[1.7]",
  /** 히어로 위 버튼: 흰 바탕 + 보라 글자 */
  heroBtn:
    "inline-flex min-h-11 touch-manipulation items-center justify-center rounded-xl bg-white px-5 text-[0.9375rem]/[normal] font-semibold text-primary-700 no-underline shadow-[0_6px_16px_-8px_rgba(0,0,0,0.4)] hover:bg-primary-50 " +
    FOCUS_RING,
  heroBtn2:
    "inline-flex min-h-11 touch-manipulation items-center justify-center rounded-xl border border-white/70 bg-white/15 px-5 text-[0.9375rem]/[normal] font-semibold text-white no-underline hover:bg-white/25 " +
    FOCUS_RING,
  list: "grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3",
  /** 카드 전체가 상세 링크 */
  item: `${ui.cardInteractive} flex h-full flex-col gap-3 !p-4 no-underline sm:!p-4`,
  itemTitle: "line-clamp-2 text-lg/[1.4] font-bold break-keep text-gray-900",
  itemVenue: "truncate text-[0.9375rem]/[normal] text-gray-700",
  itemMeta: "mt-auto flex flex-wrap items-center justify-between gap-2",
  itemNext: "text-sm/[normal] font-semibold text-primary-700",
  itemNoSession: "text-sm/[normal] text-gray-700",
} as const;

type ListState =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "success"; items: PerformanceSummary[]; page: number; totalPages: number; totalElements: number };

/** 다른 화면에서 홈으로 넘어올 때 한 번 보이는 안내(회원탈퇴 완료 등) */
function readNotice(state: unknown): string | null {
  if (typeof state !== "object" || state === null || !("notice" in state)) return null;
  const notice: unknown = (state as { notice: unknown }).notice;
  return typeof notice === "string" && notice ? notice : null;
}

export default function HomePage() {
  const { isAuthenticated, isInitializing } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  // 일회성 안내(state.notice) — 로그인 상태가 바뀌어도(탈퇴 직후 등) 한 번만 보이도록 여기서 읽고 히스토리에서 지운다
  const [notice] = useState(() => readNotice(location.state));
  useEffect(() => {
    if (notice) navigate(location.pathname, { replace: true, state: null });
    // 최초 1회
  }, []);
  if (isInitializing) return null;
  return isAuthenticated ? <PerformanceList notice={notice} /> : <GuestIntro notice={notice} />;
}

/** 처음 온 사용자를 위한 교환 안내 링크 한 줄 */
function GuideLink() {
  return (
    <p className="text-[0.9375rem]/[normal] text-gray-700">
      처음이라면:{" "}
      <Link to="/guide" className={`${ui.link} inline-flex min-h-11 items-center`}>
        교환 안내
      </Link>
    </p>
  );
}

function GuestIntro({ notice }: { notice: string | null }) {
  return (
    <div className={ui.page}>
      <div className={ui.containerWide}>
        <p className={liveRegionClass(notice, ui.notice)} role="status">
          {notice ?? ""}
        </p>
        <section className={cls.hero} aria-labelledby="home-intro-title">
          <p className={cls.heroEyebrow}>맞자리</p>
          <h1 id="home-intro-title" className={cls.heroTitle}>
            같은 공연, 원하는 자리로
          </h1>
          <p className={cls.heroLead}>
            맞자리는 같은 공연 티켓을 가진 사람끼리 좌석을 교환하는 서비스예요. 로그인하면 등록된 공연을 보고, 새
            공연도 등록할 수 있어요.
          </p>
          <div className="flex flex-wrap gap-2">
            <Link to="/login" state={{ from: { pathname: "/" } }} className={cls.heroBtn}>
              로그인
            </Link>
            <Link to="/signup" state={{ from: { pathname: "/" } }} className={cls.heroBtn2}>
              회원가입
            </Link>
          </div>
        </section>
        <div className={`${ui.card} flex items-center`}>
          <GuideLink />
        </div>
      </div>
    </div>
  );
}

function PerformanceList({ notice }: { notice: string | null }) {
  const { profile } = useAuth();
  const isAdmin = profile?.role === "ADMIN";
  const [query, setQuery] = useState("");
  const debouncedQuery = useDebouncedValue(query.trim(), 300);
  const [state, setState] = useState<ListState>({ status: "loading" });
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreError, setMoreError] = useState<string | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  /** 검색어가 바뀐 뒤 늦게 도착한 "더 보기" 응답을 버리기 위한 세대 번호 */
  const generation = useRef(0);
  /** 목록 기준 시각 — 검색어별 첫 요청 때 고정해 "더 보기"에도 같은 값을 보낸다 */
  const asOf = useRef(kstMinuteString());

  // 검색어가 바뀌면 첫 페이지부터 다시
  useEffect(() => {
    const gen = ++generation.current;
    const controller = new AbortController();
    asOf.current = kstMinuteString();
    setState({ status: "loading" });
    setMoreError(null);
    // 진행 중이던 "더 보기"는 이전 세대라 버려지므로 로딩 표시도 여기서 해제 (고착 방지)
    setLoadingMore(false);
    performancesApi
      .list({ query: debouncedQuery, page: 0, asOf: asOf.current }, controller.signal)
      .then((res) => {
        if (gen !== generation.current) return;
        setState({
          status: "success",
          items: res.content,
          page: res.page,
          totalPages: res.totalPages,
          totalElements: res.totalElements,
        });
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted || gen !== generation.current) return;
        setState({ status: "error", message: parseApiError(err, "공연 목록을 불러오지 못했습니다.").message });
      });
    return () => controller.abort();
  }, [debouncedQuery, retryKey]);

  const loadMore = async () => {
    if (state.status !== "success" || loadingMore) return;
    const gen = generation.current;
    setLoadingMore(true);
    setMoreError(null);
    try {
      const res = await performancesApi.list({ query: debouncedQuery, page: state.page + 1, asOf: asOf.current });
      if (gen !== generation.current) return;
      setState((prev) =>
        prev.status === "success"
          ? {
              status: "success",
              // 페이지 사이에 새 공연이 등록되어 항목이 밀려도 중복 없이
              items: [...prev.items, ...res.content.filter((item) => !prev.items.some((p) => p.id === item.id))],
              page: res.page,
              totalPages: res.totalPages,
              totalElements: res.totalElements,
            }
          : prev
      );
    } catch (err) {
      if (gen === generation.current) setMoreError(parseApiError(err, "더 불러오지 못했습니다.").message);
    } finally {
      if (gen === generation.current) setLoadingMore(false);
    }
  };

  const statusText =
    state.status === "loading"
      ? "공연 목록을 불러오는 중..."
      : state.status === "success"
        ? debouncedQuery
          ? `"${debouncedQuery}" 검색 결과 ${state.totalElements}개`
          : `공연 ${state.totalElements}개`
        : "";

  return (
    <div className={ui.page}>
      <div className={ui.containerWide}>
        <section className={cls.hero} aria-labelledby="home-title">
          <p className={cls.heroEyebrow}>공연 목록</p>
          <h1 id="home-title" className={cls.heroTitle}>
            같은 공연, 원하는 자리로
          </h1>
          <p className={cls.heroLead}>가진 티켓을 등록하면 서로 조건이 맞는 상대를 후보로 보여드려요.</p>
          {/* 공연은 티켓 등록 안에서만 등록한다(찾는 공연이 없을 때). 관리자는 티켓 없이 공연만 등록할 수 있어 버튼을 하나 더 둔다 */}
          <div className="flex flex-wrap gap-2">
            <Link to="/tickets/new" className={cls.heroBtn}>
              티켓 등록
            </Link>
            {isAdmin && (
              <Link to="/performances/new" className={cls.heroBtn2}>
                공연 등록
              </Link>
            )}
            <Link to="/guide" className={cls.heroBtn2}>
              교환 안내
            </Link>
          </div>
        </section>

        <p className={liveRegionClass(notice, ui.notice)} role="status">
          {notice ?? ""}
        </p>

        <form role="search" className="flex flex-col gap-2" onSubmit={(e) => e.preventDefault()}>
          <label htmlFor="home-search" className={ui.label}>
            공연 제목 검색
          </label>
          <input
            id="home-search"
            type="search"
            className={`${input.normal} !min-h-14 !rounded-2xl !px-5`}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="공연 제목을 입력하세요"
            autoComplete="off"
          />
        </form>

        <p className={liveRegionClass(statusText, ui.status)} role="status">
          {statusText}
        </p>

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {state.message}
            </p>
            <button type="button" className={button.solid} onClick={() => setRetryKey((k) => k + 1)}>
              다시 시도
            </button>
          </div>
        )}

        {state.status === "success" && state.items.length === 0 && (
          <div className={ui.empty}>
            <p className={ui.body}>
              {debouncedQuery
                ? "검색 결과가 없어요. 이 공연의 티켓이 있다면 티켓을 등록하면서 공연도 함께 등록할 수 있어요."
                : "아직 등록된 공연이 없어요. 티켓이 있다면 티켓을 등록하면서 공연도 함께 등록할 수 있어요."}
            </p>
            <Link to="/performances/new" className={linkButton.outline}>
              새 공연과 내 티켓 등록하기
            </Link>
          </div>
        )}

        {state.status === "success" && state.items.length > 0 && (
          <>
            <ul className={cls.list} aria-label="공연 목록">
              {state.items.map((item) => (
                <li key={item.id}>
                  <Link to={`/performances/${item.id}`} className={cls.item}>
                    <PerformanceThumb
                      performanceId={item.id}
                      title={item.title}
                      nextSessionStartsAt={item.nextSessionStartsAt}
                    />
                    <span className={cls.itemTitle}>{item.title}</span>
                    <span className={cls.itemVenue}>{item.venueName}</span>
                    <span className={cls.itemMeta}>
                      {item.nextSessionStartsAt ? (
                        <span className={cls.itemNext}>
                          다음 회차 · {formatKstDateTime(item.nextSessionStartsAt)}
                        </span>
                      ) : (
                        <span className={cls.itemNoSession}>예정된 회차 없음</span>
                      )}
                      <span className={badgeTone.sky}>회차 {item.sessionCount}개</span>
                    </span>
                  </Link>
                </li>
              ))}
            </ul>

            {moreError && (
              <p className={ui.errorBox} role="alert">
                {moreError}
              </p>
            )}
            {state.page + 1 < state.totalPages && (
              <button
                type="button"
                className={button.outline}
                onClick={loadMore}
                disabled={loadingMore}
                aria-busy={loadingMore}
              >
                {loadingMore ? "불러오는 중..." : "더 보기"}
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}
