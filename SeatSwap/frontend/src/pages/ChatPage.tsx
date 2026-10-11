import { useCallback, useEffect, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { Link, useParams } from "react-router-dom";
import { CHAT_AFTER_SIZE, CHAT_MAX_LENGTH, CHAT_PAGE_SIZE, chatApi, countCodePoints, findReadMarkerId, generateClientMessageId, mergeMessages, nextReadTarget, truncateCodePoints, visiblePending } from "../api/chat";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeApi } from "../api/exchange";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { formatSeat } from "../api/seat";
import { button, FOCUS_RING, input, linkButton, liveRegionClass, ui } from "../components/ui";
import { useAuth } from "../hooks/useAuth";
import { useChatSocket } from "../hooks/useChatSocket";
import type { ChatMessage, ChatPush, PendingChatMessage } from "../types/chat";
import type { ExchangeMatch } from "../types/exchange";

// 채팅 화면 (보호 라우트 /exchange/matches/:matchId/chat): 보내기는 REST, 받기는 STOMP(/user/queue/chat).
// 종료(취소·교환 완료)된 매칭은 읽기 전용이다. 매칭 상태는 메시지가 아니라 상단 배너로 보여준다(시스템 메시지는 저장하지 않음).
// 메시지 병합·중복 제거·읽음 대상 계산은 api/chat.ts, WS URL·백오프는 api/realtime.ts의 순수 함수다.

const AT_BOTTOM_PX = 40;
const READ_DEBOUNCE_MS = 600;
const ELSEWHERE = "(다른 사용자와 예약 중인 좌석)";

const CONNECTION_TEXT = {
  connecting: "실시간 연결 중이에요.",
  reconnecting: "연결이 끊겨 다시 연결하는 중이에요. 연결되면 놓친 메시지를 불러와요.",
  failed: "실시간 연결에 실패했어요. 메시지를 보내는 것은 가능하지만, 새 메시지를 받으려면 화면을 새로 열어 주세요.",
} as const;

function readMatchId(value: string | undefined): number | null {
  const n = Number(value);
  return Number.isInteger(n) && n > 0 ? n : null;
}

/** "yyyy-MM-ddTHH:mm:ss" → "오후 7:05" */
function formatTime(createdAt: string): string {
  const h = Number(createdAt.slice(11, 13));
  const mm = createdAt.slice(14, 16);
  if (Number.isNaN(h) || !mm) return "";
  return `${h < 12 ? "오전" : "오후"} ${h % 12 === 0 ? 12 : h % 12}:${mm}`;
}

/** "yyyy-MM-dd" → "2026년 10월 11일" (날짜 구분선) */
function formatDay(createdAt: string): string {
  const [y, m, d] = createdAt.slice(0, 10).split("-").map(Number);
  return y && m && d ? `${y}년 ${m}월 ${d}일` : "";
}

/** 매칭 조회 결과로 상단 상태 배너를 만든다 (시스템 메시지를 저장하지 않으므로 항상 현재 상태 기준) */
function deriveBanners(m: ExchangeMatch): { text: string; strong?: boolean }[] {
  const out: { text: string; strong?: boolean }[] = [];
  const name = m.counterpartNickname;
  if (m.status === "CANCELED") {
    const who = m.canceledBy === "ME" ? "내가 채팅을 종료했어요." : m.canceledBy === "COUNTERPART" ? `${name}님이 채팅을 종료했어요.` : "교환 조건 삭제, 티켓 내림 등의 이유로 자동 종료됐어요.";
    out.push({ text: `종료된 채팅이에요. ${who} 이전 대화는 읽을 수만 있어요.`, strong: true });
    return out;
  }
  if (m.status === "COMPLETED") {
    out.push({ text: "교환이 완료된 채팅이에요. 이전 대화는 읽을 수만 있어요.", strong: true });
    return out;
  }
  if (m.status === "RESERVED") {
    const who = m.reservedBy === "ME" ? "내가 예약했어요." : m.reservedBy === "COUNTERPART" ? `${name}님이 예약했어요.` : "예약 중이에요.";
    out.push({ text: `[예약 중] ${who}`, strong: true });
    const mine = m.myAccepted ? "내 교환 수락: 완료" : "내 교환 수락: 아직";
    const theirs = m.counterpartAccepted ? `${name}님 교환 수락: 완료` : `${name}님 교환 수락: 아직`;
    out.push({ text: `${mine} · ${theirs}` });
  }
  if (m.myTicketExchanged || m.counterpartTicketExchanged) {
    out.push({ text: "이미 교환된 좌석이에요. 예약·교환 수락은 할 수 없어요.", strong: true });
  }
  if (m.status === "CHATTING" && (m.myTicketReservedElsewhere || m.counterpartTicketReservedElsewhere)) {
    out.push({ text: ELSEWHERE });
  }
  return out;
}

export default function ChatPage() {
  const params = useParams();
  const matchId = readMatchId(params.matchId);
  const { user } = useAuth();
  const me = user?.id ?? -1;

  const [match, setMatch] = useState<ExchangeMatch | null>(null);
  const [matchError, setMatchError] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [olderLoading, setOlderLoading] = useState(false);
  const [olderError, setOlderError] = useState<string | null>(null);
  const [counterpartRead, setCounterpartRead] = useState(0);
  const [pending, setPending] = useState<PendingChatMessage[]>([]);
  const [text, setText] = useState("");
  const [atBottom, setAtBottom] = useState(true);
  const [newBelow, setNewBelow] = useState(0);
  const [announce, setAnnounce] = useState("");
  const [visible, setVisible] = useState(() => document.visibilityState === "visible");
  const [reloadKey, setReloadKey] = useState(0);

  const headingRef = useRef<HTMLHeadingElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const messagesRef = useRef<ChatMessage[]>([]);
  const loadedRef = useRef(false);
  const needFillRef = useRef(false);
  const fillingRef = useRef(false);
  const refillRef = useRef(false);
  const reportedReadRef = useRef(0);
  const atBottomRef = useRef(true);
  const anchorRef = useRef<{ height: number; top: number } | null>(null);
  const prevLastIdRef = useRef<number | null>(null);
  const prevPendingRef = useRef(0);
  const olderBusyRef = useRef(false);
  const matchLoadedRef = useRef(false);
  const focusedRef = useRef(false);
  const announceTimerRef = useRef<number | undefined>(undefined);

  const commit = useCallback((next: ChatMessage[]) => {
    messagesRef.current = next;
    setMessages(next);
  }, []);

  // 다른 매칭으로 이동하면 상태를 모두 비운다
  useEffect(() => {
    messagesRef.current = [];
    loadedRef.current = false;
    needFillRef.current = false;
    reportedReadRef.current = 0;
    prevLastIdRef.current = null;
    prevPendingRef.current = 0;
    anchorRef.current = null;
    atBottomRef.current = true;
    setMessages([]);
    setLoaded(false);
    setLoadError(null);
    setHasMore(false);
    setCounterpartRead(0);
    setPending([]);
    setText("");
    setNewBelow(0);
    setAnnounce("");
    setAtBottom(true);
    matchLoadedRef.current = false;
    focusedRef.current = false;
    setMatch(null);
    setMatchError(null);
  }, [matchId]);

  // ---- 매칭(상태 배너용): 진입·포커스·탭 복귀·재연결·409 때 다시 읽는다 ----
  const refreshMatch = useCallback(async () => {
    if (matchId === null) return;
    try {
      const m = await exchangeApi.getMatch(matchId);
      matchLoadedRef.current = true;
      setMatch(m);
      setMatchError(null);
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "채팅을 불러오지 못했습니다.");
      // 처음 불러올 때만 화면 오류로 보여준다 (이미 보이는 중이면 이전 상태를 유지)
      if (!matchLoadedRef.current) setMatchError(parsed.message);
    }
  }, [matchId]);

  useEffect(() => {
    void refreshMatch();
  }, [refreshMatch]);

  useEffect(() => {
    const onVisibility = () => {
      const v = document.visibilityState === "visible";
      setVisible(v);
      if (v) void refreshMatch();
    };
    const onFocus = () => void refreshMatch();
    document.addEventListener("visibilitychange", onVisibility);
    window.addEventListener("focus", onFocus);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      window.removeEventListener("focus", onFocus);
    };
  }, [refreshMatch]);

  // ---- 최초 메시지 로드 ----
  useEffect(() => {
    if (matchId === null) return;
    const ctrl = new AbortController();
    chatApi
      .list(matchId, { size: CHAT_PAGE_SIZE }, ctrl.signal)
      .then((page) => {
        commit(mergeMessages(messagesRef.current, page.messages));
        setHasMore(page.hasMore);
        setCounterpartRead((p) => Math.max(p, page.counterpartLastReadMessageId));
        reportedReadRef.current = Math.max(reportedReadRef.current, page.myLastReadMessageId);
        loadedRef.current = true;
        setLoaded(true);
        setLoadError(null);
        if (needFillRef.current) {
          needFillRef.current = false;
          void fillGapRef.current();
        }
      })
      .catch((err: unknown) => {
        if (ctrl.signal.aborted) return;
        setLoadError(exchangeErrorMessage(err, "메시지를 불러오지 못했습니다.").message);
      });
    return () => ctrl.abort();
  }, [matchId, reloadKey, commit]);

  // ---- 재연결 후 빈 구간 채우기 (after 커서, 100건씩 hasMore가 끝날 때까지) ----
  const fillGap = useCallback(async () => {
    if (matchId === null) return;
    if (!loadedRef.current) {
      needFillRef.current = true;
      return;
    }
    if (fillingRef.current) {
      refillRef.current = true;
      return;
    }
    fillingRef.current = true;
    try {
      do {
        refillRef.current = false;
        let more = true;
        while (more) {
          const last = messagesRef.current[messagesRef.current.length - 1]?.id;
          const page = await chatApi.list(matchId, last === undefined ? { size: CHAT_PAGE_SIZE } : { after: last, size: CHAT_AFTER_SIZE });
          commit(mergeMessages(messagesRef.current, page.messages));
          setCounterpartRead((p) => Math.max(p, page.counterpartLastReadMessageId));
          if (last === undefined) {
            // 메시지가 없던 상태의 최신 조회: 더 오래된 메시지가 있는지만 반영하고, 이후는 after 조회로 이어 간다
            setHasMore(page.hasMore);
            more = false;
          } else {
            more = page.hasMore && page.messages.length > 0;
          }
        }
      } while (refillRef.current);
    } catch {
      // 일시 오류: 다음 재연결·푸시 때 다시 채워진다
    } finally {
      fillingRef.current = false;
    }
  }, [matchId, commit]);
  const fillGapRef = useRef(fillGap);
  fillGapRef.current = fillGap;

  // ---- STOMP ----
  const onPush = useCallback(
    (push: ChatPush) => {
      if (push.matchId !== matchId) return;
      if (push.type === "MESSAGE") commit(mergeMessages(messagesRef.current, [push.message]));
      else setCounterpartRead((p) => Math.max(p, push.lastReadMessageId));
    },
    [matchId, commit]
  );
  const onConnected = useCallback(() => {
    void fillGap();
    void refreshMatch();
  }, [fillGap, refreshMatch]);
  const connection = useChatSocket({ enabled: matchId !== null, onPush, onConnected });

  // ---- 스크롤 ----
  const shownPending = visiblePending(pending, messages);

  useLayoutEffect(() => {
    const el = listRef.current;
    if (!el || !loaded) return;
    if (anchorRef.current) {
      // 이전 메시지를 앞에 붙였으면 보던 위치를 유지한다
      el.scrollTop = el.scrollHeight - anchorRef.current.height + anchorRef.current.top;
      anchorRef.current = null;
    }
    const last = (messages[messages.length - 1]?.id ?? 0);
    const prevLast = prevLastIdRef.current;
    if (prevLast === null) {
      el.scrollTop = el.scrollHeight;
      prevLastIdRef.current = last;
    } else if (last > prevLast) {
      const fresh = messages.filter((m) => m.id > prevLast);
      const fromOthers = fresh.filter((m) => m.senderId !== me);
      const hasMine = fresh.length > fromOthers.length;
      if (atBottomRef.current || hasMine) el.scrollTop = el.scrollHeight;
      else setNewBelow((n) => n + fromOthers.length);
      if (fromOthers.length > 0) {
        const lastOther = fromOthers[fromOthers.length - 1];
        // 같은 내용이 연속돼도 낭독되도록 한 번 비웠다가 다시 채운다
        const message = fromOthers.length > 1 ? `새 메시지 ${fromOthers.length}개가 도착했어요.` : `새 메시지: ${lastOther.content}`;
        window.clearTimeout(announceTimerRef.current);
        setAnnounce("");
        announceTimerRef.current = window.setTimeout(() => setAnnounce(message), 50);
      }
      prevLastIdRef.current = last;
    }
    if (shownPending.length > prevPendingRef.current) el.scrollTop = el.scrollHeight;
    prevPendingRef.current = shownPending.length;
  }, [messages, shownPending.length, loaded, me]);

  useEffect(() => () => window.clearTimeout(announceTimerRef.current), []);

  const onScroll = () => {
    const el = listRef.current;
    if (!el) return;
    const bottom = el.scrollHeight - el.scrollTop - el.clientHeight < AT_BOTTOM_PX;
    atBottomRef.current = bottom;
    setAtBottom(bottom);
    if (bottom) setNewBelow(0);
  };

  const scrollToBottom = () => {
    const el = listRef.current;
    if (el) el.scrollTop = el.scrollHeight;
    setNewBelow(0);
  };

  const loadOlder = async () => {
    if (matchId === null || olderBusyRef.current) return;
    const first = messagesRef.current[0];
    if (!first) return;
    olderBusyRef.current = true;
    setOlderLoading(true);
    setOlderError(null);
    try {
      const page = await chatApi.list(matchId, { before: first.id, size: CHAT_PAGE_SIZE });
      const el = listRef.current;
      if (el) anchorRef.current = { height: el.scrollHeight, top: el.scrollTop };
      commit(mergeMessages(messagesRef.current, page.messages));
      setHasMore(page.hasMore);
      setCounterpartRead((p) => Math.max(p, page.counterpartLastReadMessageId));
    } catch (err) {
      setOlderError(exchangeErrorMessage(err, "이전 메시지를 불러오지 못했습니다.").message);
    } finally {
      olderBusyRef.current = false;
      setOlderLoading(false);
    }
  };

  // ---- 읽음: 화면이 보이고 맨 아래일 때 디바운스로 PUT ----
  useEffect(() => {
    if (matchId === null || !loaded || !visible || !atBottom) return;
    const target = nextReadTarget(messages, me, reportedReadRef.current);
    if (target === null) return;
    const timer = window.setTimeout(() => {
      chatApi
        .markRead(matchId, target)
        .then(() => {
          reportedReadRef.current = Math.max(reportedReadRef.current, target);
        })
        .catch(() => {
          // 실패하면 다음 메시지·포커스 때 다시 시도한다
        });
    }, READ_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [matchId, messages, loaded, visible, atBottom, me]);

  useEffect(() => {
    // 첫 로드(또는 매칭이 바뀐 직후) 한 번만 제목으로 포커스를 옮긴다. 재조회마다 옮기면 입력 중 포커스를 빼앗는다.
    if (focusedRef.current) return;
    if (loaded || match || matchError || loadError) {
      focusedRef.current = true;
      headingRef.current?.focus();
    }
  }, [loaded, match, matchError, loadError]);

  // ---- 보내기 ----
  const closedChat = match !== null && (match.status === "CANCELED" || match.status === "COMPLETED");

  const sendMessage = async (content: string, clientMessageId: string) => {
    if (matchId === null) return;
    setPending((prev) => {
      const entry: PendingChatMessage = { clientMessageId, content, status: "sending", error: null };
      return prev.some((p) => p.clientMessageId === clientMessageId)
        ? prev.map((p) => (p.clientMessageId === clientMessageId ? entry : p))
        : [...prev, entry];
    });
    try {
      const saved = await chatApi.send(matchId, content, clientMessageId);
      commit(mergeMessages(messagesRef.current, [saved]));
      setPending((prev) => prev.filter((p) => p.clientMessageId !== clientMessageId));
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "메시지를 보내지 못했습니다.");
      if (parsed.code === "MATCH_STATE_CONFLICT") void refreshMatch();
      setPending((prev) => prev.map((p) => (p.clientMessageId === clientMessageId ? { ...p, status: "failed", error: parsed.message } : p)));
    }
  };

  const submit = () => {
    const content = text.trim();
    if (!content || closedChat) return;
    setText("");
    void sendMessage(content, generateClientMessageId());
    textareaRef.current?.focus();
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    submit();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key !== "Enter" || e.shiftKey) return;
    // 한글 등 IME 조합 중의 Enter는 조합 확정이므로 전송하지 않는다 (Safari는 isComposing이 false여도 keyCode 229)
    if (e.nativeEvent.isComposing || e.keyCode === 229) return;
    e.preventDefault();
    submit();
  };

  if (matchId === null) {
    return (
      <div className={ui.page}>
        <div className={ui.container}>
          <p className={ui.errorBox} role="alert">
            잘못된 채팅 주소예요.
          </p>
          <Link to="/exchange/matches" className={linkButton.outline}>
            내 매칭으로
          </Link>
        </div>
      </div>
    );
  }

  const backTo = `/exchange/matches?matchId=${matchId}`;
  const banners = match ? deriveBanners(match) : [];
  const readMarkerId = findReadMarkerId(messages, me, counterpartRead);
  const counterpartName = match?.counterpartNickname ?? "상대";
  const connectionText = connection === "connected" ? "" : CONNECTION_TEXT[connection];
  const countId = "chat-count";
  const disabledReasonId = "chat-disabled-reason";
  const fatal = matchError !== null && match === null;

  return (
    <div className={`${ui.page} min-h-0`}>
      <div className={`${ui.container} min-h-0 flex-1`}>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h1 ref={headingRef} tabIndex={-1} className={`${ui.pageTitle} outline-none`}>
            {match ? `${match.counterpartNickname}님과 채팅` : "채팅"}
          </h1>
          <Link to={backTo} className={linkButton.outline}>
            내 매칭으로
          </Link>
        </div>

        {fatal && (
          <p className={ui.errorBox} role="alert">
            {matchError}
          </p>
        )}

        {match && (
          <div className="flex flex-col gap-2">
            <p className={ui.muted}>
              내 자리 {formatSeat(match.mySeat)} ({formatKstDateTime(match.mySeat.startsAt)}) ↔ 상대 자리 {formatSeat(match.counterpartSeat)} ({formatKstDateTime(match.counterpartSeat.startsAt)})
            </p>
            {banners.map((b) => (
              <p key={b.text} className={b.strong ? ui.warning : ui.notice}>
                {b.text}
              </p>
            ))}
          </div>
        )}

        {!fatal && (
          <section className={`${ui.card} flex min-h-0 flex-1 flex-col gap-3`} aria-label="채팅">
            <p className={ui.hint}>예매내역을 확인할 때 이름·연락처·결제 정보는 가리고 보여주세요. 메시지는 텍스트만 보낼 수 있고, 보낸 뒤에는 삭제·수정할 수 없어요.</p>

            <p role="status" className={liveRegionClass(connectionText, connection === "failed" ? ui.errorBox : ui.status)}>
              {connectionText}
            </p>

            <div className="relative flex min-h-0 flex-1 flex-col">
              <div
                ref={listRef}
                onScroll={onScroll}
                tabIndex={0}
                role="region"
                aria-label="메시지 목록"
                className={`min-h-48 max-h-[min(60vh,32rem)] flex-1 overflow-y-auto overscroll-contain rounded-[10px] bg-surface p-3 ${FOCUS_RING}`}
              >
                {!loaded && !loadError && <p className={ui.muted}>메시지를 불러오는 중...</p>}
                {loadError && (
                  <div className={`${ui.errorBox} flex flex-col items-start gap-2`} role="alert">
                    <span>{loadError}</span>
                    <button type="button" className={button.outline} onClick={() => setReloadKey((k) => k + 1)}>
                      다시 불러오기
                    </button>
                  </div>
                )}
                {loaded && hasMore && (
                  <div className="mb-3 flex flex-col items-center gap-2">
                    <button type="button" className={button.outline} onClick={loadOlder} disabled={olderLoading} aria-busy={olderLoading}>
                      {olderLoading ? "불러오는 중..." : "이전 메시지 보기"}
                    </button>
                    {olderError && (
                      <p className={ui.fieldError} role="alert">
                        {olderError}
                      </p>
                    )}
                  </div>
                )}
                {loaded && messages.length === 0 && shownPending.length === 0 && <p className={ui.muted}>아직 메시지가 없어요. 먼저 인사를 건네 보세요.</p>}
                <ul className="flex flex-col gap-2">
                  {messages.map((m, i) => {
                    const mine = m.senderId === me;
                    const day = formatDay(m.createdAt);
                    const showDay = i === 0 || formatDay(messages[i - 1].createdAt) !== day;
                    return (
                      <li key={m.id} className="flex flex-col gap-2">
                        {showDay && day && <p className="self-center text-[0.8125rem]/[normal] text-gray-600">{day}</p>}
                        <div className={`flex flex-col gap-0.5 ${mine ? "items-end" : "items-start"}`}>
                          <div className={`max-w-[85%] rounded-2xl px-3.5 py-2 text-[0.9375rem]/[1.45] break-words whitespace-pre-wrap ${mine ? "bg-primary-600 text-white" : "bg-white text-gray-900"}`}>
                            <span className="sr-only">{mine ? "나: " : `${counterpartName}: `}</span>
                            {m.content}
                          </div>
                          <span className="text-[0.75rem]/[normal] text-gray-600">
                            {formatTime(m.createdAt)}
                            {m.id === readMarkerId && <span> · 읽음</span>}
                          </span>
                        </div>
                      </li>
                    );
                  })}
                  {shownPending.map((p) => (
                    <li key={p.clientMessageId} className="flex flex-col items-end gap-1">
                      <div className="max-w-[85%] rounded-2xl bg-primary-600 px-3.5 py-2 text-[0.9375rem]/[1.45] break-words whitespace-pre-wrap text-white opacity-70">
                        <span className="sr-only">나: </span>
                        {p.content}
                      </div>
                      {p.status === "sending" ? (
                        <span className="text-[0.75rem]/[normal] text-gray-600">보내는 중...</span>
                      ) : (
                        <div className="flex flex-col items-end gap-1">
                          <span className="text-[0.8125rem]/[normal] text-red-700" role="alert">
                            {p.error ?? "보내지 못했어요."}
                          </span>
                          <div className="flex gap-2">
                            <button type="button" className={button.outline} onClick={() => void sendMessage(p.content, p.clientMessageId)}>
                              다시 보내기
                            </button>
                            <button type="button" className={button.outline} onClick={() => setPending((prev) => prev.filter((x) => x.clientMessageId !== p.clientMessageId))}>
                              삭제
                            </button>
                          </div>
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
              {newBelow > 0 && (
                <button type="button" className={`${button.solid} absolute bottom-3 left-1/2 -translate-x-1/2 shadow-md`} onClick={scrollToBottom}>
                  새 메시지 {newBelow}개
                </button>
              )}
            </div>

            {/* 새로 도착한 메시지만 낭독한다 (목록 자체는 라이브 영역이 아니다) */}
            <p role="status" className="sr-only">
              {announce}
            </p>

            <form onSubmit={onSubmit} className="sticky bottom-0 flex flex-col gap-2 bg-white pt-2 pb-[env(safe-area-inset-bottom)]">
              {closedChat && (
                <p id={disabledReasonId} className={ui.muted}>
                  종료된 채팅이라 메시지를 보낼 수 없어요. 이전 대화만 볼 수 있어요.
                </p>
              )}
              <label htmlFor="chat-input" className="sr-only">
                {counterpartName}님에게 보낼 메시지
              </label>
              <textarea
                id="chat-input"
                ref={textareaRef}
                rows={2}
                value={text}
                onChange={(e) => setText(truncateCodePoints(e.target.value, CHAT_MAX_LENGTH))}
                onKeyDown={onKeyDown}
                disabled={closedChat}
                placeholder="메시지를 입력하세요 (Shift+Enter로 줄바꿈)"
                aria-describedby={closedChat ? `${countId} ${disabledReasonId}` : countId}
                className={`${input.normal} resize-none py-2.5`}
              />
              <div className="flex items-center justify-between gap-2">
                <span id={countId} className={ui.hint}>
                  {countCodePoints(text)}/{CHAT_MAX_LENGTH}자
                </span>
                <button type="submit" className={button.solid} disabled={closedChat || text.trim().length === 0}>
                  보내기
                </button>
              </div>
            </form>
          </section>
        )}
      </div>
    </div>
  );
}
