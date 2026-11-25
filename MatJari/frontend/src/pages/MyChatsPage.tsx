import { useMemo, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { exchangeApi } from "../api/exchange";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { kstMinuteString } from "../api/dateTime";
import { formatSeat } from "../api/seat";
import Avatar from "../components/Avatar";
import { badgeTone, button, FOCUS_RING, matchStatusTone, segment, ui } from "../components/ui";
import { usePagedList } from "../hooks/usePagedList";
import type { ExchangeMatch, MatchStatus } from "../types/exchange";

// 내 채팅 (보호 라우트 /my/chats): 채팅 목록만 모아 보는 화면. 새 API 없이 내 매칭 목록(listMatches)으로 만든다.
// 채팅은 매칭 상태와 무관하게 열리므로 CHATTING·RESERVED·COMPLETED·CANCELED 모두 넣고, 종료는 연한 '종료' 라벨로 구분한다.
// 카드에는 마지막 메시지 미리보기·시각(lastMessage*)과 자리 요약을 보인다. 정렬은 안 읽음 먼저, 그다음 마지막 메시지(없으면 생성) 시각 최신순. 마운트(경로 진입)마다 새로 조회한다.

type Filter = "ALL" | "UNREAD";

const STATUS_LABEL: Record<MatchStatus, string> = {
  CHATTING: "진행 중",
  RESERVED: "예약 중",
  COMPLETED: "교환 완료",
  CANCELED: "취소됨",
};

const isClosed = (s: MatchStatus) => s === "COMPLETED" || s === "CANCELED";

/** 정렬·표시 기준 시각: 마지막 메시지가 있으면 그 시각, 없으면 매칭 생성 시각 */
const chatTime = (m: ExchangeMatch) => m.lastMessageAt ?? m.createdAt;

/** 안 읽은 채팅이 있는 것을 먼저, 그다음 lastMessageAt ?? createdAt 최신순, 같으면 id 내림차순 (같은 형식 ISO 문자열 비교) */
function sortChats(items: ExchangeMatch[]): ExchangeMatch[] {
  return [...items].sort((a, b) => {
    const ua = a.unreadChatCount > 0 ? 1 : 0;
    const ub = b.unreadChatCount > 0 ? 1 : 0;
    if (ua !== ub) return ub - ua;
    const ta = chatTime(a);
    const tb = chatTime(b);
    return ta < tb ? 1 : ta > tb ? -1 : b.id - a.id;
  });
}

/** KST 기준: 오늘이면 '오후 3:05', 올해면 '11월 3일', 그 외 '2025. 11. 3.' */
function formatChatTime(value: string, now: number = Date.now()): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(value);
  if (!m) return "";
  const [y, mo, d, h, mi] = [+m[1], +m[2], +m[3], +m[4], +m[5]];
  const today = kstMinuteString(now);
  if (value.slice(0, 10) === today.slice(0, 10)) {
    return `${h < 12 ? "오전" : "오후"} ${h % 12 === 0 ? 12 : h % 12}:${String(mi).padStart(2, "0")}`;
  }
  if (y === +today.slice(0, 4)) return `${mo}월 ${d}일`;
  return `${y}. ${mo}. ${d}.`;
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
      <div className={ui.container}>
        <div className={ui.pageHeader}>
          <div className="flex flex-col gap-1.5">
            <p className={ui.eyebrow}>교환</p>
            <h1 className={ui.pageTitle}>내 채팅</h1>
          </div>
        </div>

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
            <div role="group" aria-label="채팅 필터" className={segment.group}>
              {FILTERS.map((f) => (
                <button
                  key={f.key}
                  type="button"
                  className={`${segment.item} ${filter === f.key ? segment.active : segment.idle}`}
                  aria-pressed={filter === f.key}
                  onClick={() => setFilter(f.key)}
                >
                  {f.label}
                </button>
              ))}
            </div>

            {shown.length === 0 ? (
              <div className={ui.empty}>
                <p className={ui.body}>{filter === "UNREAD" ? "안 읽은 대화가 없어요." : "아직 대화가 없어요."}</p>
              </div>
            ) : (
              <ul className="flex flex-col gap-3" aria-label={filter === "UNREAD" ? "안 읽은 채팅 목록" : "채팅 목록"}>
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
  const preview = m.lastMessagePreview ?? null;
  return (
    <Link
      to={`/exchange/matches/${m.id}/chat`}
      className={`flex items-start gap-3.5 rounded-[20px] border bg-white px-4 py-4 no-underline shadow-[0_2px_12px_rgba(17,24,39,0.04)] transition-[border-color,box-shadow] hover:shadow-[0_10px_30px_-12px_rgba(138,43,226,0.28)] active:bg-gray-50 ${
        unread ? "border-primary-300" : "border-gray-200/70"
      } ${closed ? "opacity-90" : ""} ${FOCUS_RING}`}
    >
      {/* 안 읽은 채팅은 아바타 왼쪽 위에 점 (색 외에도 굵은 글자·개수 뱃지로 구분) */}
      <span className="relative shrink-0">
        <Avatar name={m.counterpartNickname} />
        {unread && <span aria-hidden="true" className="absolute -top-0.5 -left-0.5 size-3 rounded-full border-2 border-white bg-primary-600" />}
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="flex items-center gap-2">
          <span className="min-w-0 truncate text-base/[normal] font-bold text-gray-900">{m.counterpartNickname}</span>
          <span className={badgeTone[matchStatusTone(m.status)]}>{STATUS_LABEL[m.status]}</span>
          {closed && <span className="sr-only">종료</span>}
          <time dateTime={chatTime(m)} className="ml-auto shrink-0 text-xs/[normal] text-gray-500">
            {formatChatTime(chatTime(m))}
          </time>
        </span>
        <span className="flex items-center gap-2">
          {preview ? (
            <span className={`min-w-0 flex-1 truncate text-sm/[normal] ${unread ? "font-bold text-gray-900" : "text-gray-700"}`}>
              {m.lastMessageMine ? "나: " : ""}
              {preview}
            </span>
          ) : (
            <span className="min-w-0 flex-1 truncate text-sm/[normal] text-gray-500">아직 대화가 없어요</span>
          )}
          {unread && (
            <span
              aria-label={`안 읽은 메시지 ${m.unreadChatCount}개`}
              className="inline-flex min-w-6 shrink-0 items-center justify-center rounded-full bg-primary-600 px-2 py-0.5 text-xs/[normal] font-bold text-white"
            >
              {m.unreadChatCount > 99 ? "99+" : m.unreadChatCount}
            </span>
          )}
        </span>
        <span className={`mt-1 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1.5 text-sm/[normal] ${closed ? "text-gray-500" : "text-gray-700"}`}>
          <span className="inline-flex items-center gap-1.5 rounded-xl bg-gray-50 px-2.5 py-1 whitespace-nowrap">
            <span className="text-[0.6875rem]/[1.3] font-bold text-gray-600">내</span>
            <span className="font-normal text-gray-600">{formatSeat(m.mySeat)}</span>
          </span>
          <span aria-hidden="true" className="text-lg/[1] font-light text-primary-400">→</span>
          <span className="inline-flex items-center gap-1.5 rounded-xl bg-primary-50 px-2.5 py-1 whitespace-nowrap">
            <span className="text-[0.6875rem]/[1.3] font-bold text-primary-700">상대</span>
            <span className="font-extrabold text-primary-800">{formatSeat(m.counterpartSeat)}</span>
          </span>
        </span>
      </span>
    </Link>
  );
}
