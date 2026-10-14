import { useMemo, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { exchangeApi } from "../api/exchange";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { formatKstDateTime } from "../api/dateTime";
import { formatSeat } from "../api/seat";
import { button, FOCUS_RING, ui } from "../components/ui";
import { usePagedList } from "../hooks/usePagedList";
import type { ExchangeMatch, MatchStatus } from "../types/exchange";

// 내 채팅 (보호 라우트 /my/chats): 채팅 목록만 모아 보는 화면. 새 API 없이 내 매칭 목록(listMatches)으로 만든다.
// 채팅은 매칭 상태와 무관하게 열리므로 CHATTING·RESERVED·COMPLETED·CANCELED 모두 넣고, 종료는 연한 '종료' 라벨로 구분한다.
// 매칭 응답에 마지막 메시지가 없어 미리보기 대신 '내 자리 ↔ 상대 자리' 요약을 보인다. 마운트(경로 진입)마다 새로 조회한다.

type Filter = "ALL" | "UNREAD";

const STATUS_LABEL: Record<MatchStatus, string> = {
  CHATTING: "진행 중",
  RESERVED: "예약 중",
  COMPLETED: "교환 완료",
  CANCELED: "취소됨",
};

const isClosed = (s: MatchStatus) => s === "COMPLETED" || s === "CANCELED";

/** 안 읽은 채팅이 있는 것을 먼저, 그다음 updatedAt 최신순 (ISO 문자열 비교) */
function sortChats(items: ExchangeMatch[]): ExchangeMatch[] {
  return [...items].sort((a, b) => {
    const ua = a.unreadChatCount > 0 ? 1 : 0;
    const ub = b.unreadChatCount > 0 ? 1 : 0;
    if (ua !== ub) return ub - ua;
    return a.updatedAt < b.updatedAt ? 1 : a.updatedAt > b.updatedAt ? -1 : b.id - a.id;
  });
}

export default function MyChatsPage() {
  const location = useLocation();
  const [filter, setFilter] = useState<Filter>("ALL");

  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<ExchangeMatch>(
    (page, signal) => exchangeApi.listMatches({ role: "ALL", page, size: 100 }, signal),
    location.key, // 화면에 들어올 때마다 최신 목록
    (m) => m.id
  );

  const items = useMemo(() => (state.status === "success" ? sortChats(state.items) : []), [state]);
  const unreadChats = items.filter((m) => m.unreadChatCount > 0);
  const shown = filter === "UNREAD" ? unreadChats : items;

  const FILTERS: { key: Filter; label: string }[] = [
    { key: "ALL", label: `전체 ${items.length}` },
    { key: "UNREAD", label: `안 읽음 ${unreadChats.length}` },
  ];

  return (
    <div className={ui.page}>
      <div className="flex w-full max-w-2xl min-w-0 flex-col gap-4">
        <h1 className={ui.pageTitle}>내 채팅</h1>

        {state.status === "loading" && (
          <p className={ui.status} role="status">
            내 채팅을 불러오는 중...
          </p>
        )}

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "내 채팅을 불러오지 못했습니다.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 불러오기
              </button>
            </div>
          </div>
        )}

        {state.status === "success" && (
          <>
            <div role="group" aria-label="채팅 필터" className="grid grid-cols-2 gap-2">
              {FILTERS.map((f) => (
                <button
                  key={f.key}
                  type="button"
                  className={filter === f.key ? button.solid : button.outline}
                  aria-pressed={filter === f.key}
                  onClick={() => setFilter(f.key)}
                >
                  {f.label}
                </button>
              ))}
            </div>

            {shown.length === 0 ? (
              <div className={ui.card}>
                <p className={ui.body}>{filter === "UNREAD" ? "안 읽은 대화가 없어요." : "아직 대화가 없어요."}</p>
              </div>
            ) : (
              <ul className="flex flex-col gap-2" aria-label={filter === "UNREAD" ? "안 읽은 채팅 목록" : "채팅 목록"}>
                {shown.map((m) => (
                  <li key={m.id}>
                    <ChatRow match={m} />
                  </li>
                ))}
              </ul>
            )}

            {moreError && (
              <p className={ui.errorBox} role="alert">
                {moreError}
              </p>
            )}
            {state.page + 1 < state.totalPages && (
              <button type="button" className={button.outline} onClick={loadMore} disabled={loadingMore} aria-busy={loadingMore}>
                {loadingMore ? "불러오는 중..." : "더 보기"}
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function ChatRow({ match: m }: { match: ExchangeMatch }) {
  const unread = m.unreadChatCount > 0;
  const closed = isClosed(m.status);
  return (
    <Link
      to={`/exchange/matches/${m.id}/chat`}
      className={`flex min-h-16 flex-col gap-1 rounded-2xl border bg-white px-4 py-3 no-underline shadow-[0_2px_12px_rgba(0,0,0,0.04)] active:bg-gray-100 ${
        unread ? "border-primary-600" : "border-gray-200"
      } ${FOCUS_RING}`}
    >
      <span className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate text-base/[normal] font-bold text-gray-900">{m.counterpartNickname}</span>
        {closed && <span className="shrink-0 rounded-full bg-gray-100 px-2 py-0.5 text-xs/[normal] text-gray-600">종료</span>}
        {unread && (
          <span
            aria-label={`안 읽은 메시지 ${m.unreadChatCount}개`}
            className="inline-flex min-w-6 shrink-0 items-center justify-center rounded-full bg-primary-600 px-2 py-0.5 text-xs/[normal] font-bold text-white"
          >
            {m.unreadChatCount > 99 ? "99+" : m.unreadChatCount}
          </span>
        )}
      </span>
      <span className="text-sm/[normal] text-gray-600">
        {STATUS_LABEL[m.status]} · {formatKstDateTime(m.updatedAt)}
      </span>
      <span className={`truncate text-sm/[normal] ${unread ? "font-bold text-gray-900" : closed ? "text-gray-500" : "text-gray-700"}`}>
        내 {formatSeat(m.mySeat)} ↔ 상대 {formatSeat(m.counterpartSeat)}
      </span>
    </Link>
  );
}
