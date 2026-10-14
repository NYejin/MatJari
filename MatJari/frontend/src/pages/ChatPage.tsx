import { useCallback, useEffect, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { CHAT_AFTER_SIZE, CHAT_MAX_LENGTH, CHAT_PAGE_SIZE, chatApi, countCodePoints, findReadMarkerId, generateClientMessageId, mergeMessages, nextReadTarget, truncateCodePoints, visiblePending } from "../api/chat";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeApi } from "../api/exchange";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { getRealtimeStatus, subscribeNotificationPush } from "../api/realtimeHub";
import { formatSeat } from "../api/seat";
import ChatSafetyActions from "../components/ChatSafetyActions";
import { button, FOCUS_RING, linkButton, liveRegionClass, ui } from "../components/ui";
import { useAuth } from "../hooks/useAuth";
import { useChatSocket } from "../hooks/useChatSocket";
import type { ChatMessage, ChatPush, PendingChatMessage } from "../types/chat";
import type { ExchangeMatch } from "../types/exchange";

// 채팅 화면 (보호 라우트 /exchange/matches/:matchId/chat): 보내기는 REST, 받기는 STOMP(/user/queue/chat).
// 종료(취소·교환 완료)된 매칭은 읽기 전용이다. 매칭 상태는 메시지가 아니라 상단 배너로 보여준다(시스템 메시지는 저장하지 않음).
// 메시지 병합·중복 제거·읽음 대상 계산은 api/chat.ts, WS URL·백오프는 api/realtime.ts의 순수 함수다.

const AT_BOTTOM_PX = 40;
const READ_DEBOUNCE_MS = 600;
/** 알림 신호(STOMP)를 놓치거나 연결이 끊긴 경우를 위한 매칭 상태 재조회 간격. 연결 중에는 10번에 한 번(5분)만 확인한다 */
const MATCH_POLL_MS = 30_000;
const MATCH_POLL_CONNECTED_EVERY = 10;
/** 입력창 최대 높이: 5줄(24px) + 위아래 여백 20px */
const TEXTAREA_MAX_PX = 140;
/** 글자 수 카운터는 이 글자 수부터 눈에 보인다 */
const COUNT_VISIBLE_FROM = 900;

type ReserveAction = "propose" | "accept" | "decline" | "withdraw" | "unreserve";
type ReserveConfirm = "propose" | "accept" | "unreserve";
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

/** 상태 뱃지 문구 (앱에서 쓰는 용어) */
function statusLabel(status: ExchangeMatch["status"]): string {
  return status === "CHATTING" ? "채팅 중" : status === "RESERVED" ? "예약 중" : status === "COMPLETED" ? "교환 완료" : "종료";
}

const BADGE_BASE = "inline-flex shrink-0 items-center rounded-full px-2.5 py-0.5 text-[0.8125rem]/[normal] font-semibold";
function statusBadgeClass(status: ExchangeMatch["status"]): string {
  return status === "RESERVED" ? `${BADGE_BASE} bg-primary-50 text-primary-800` : status === "CHATTING" ? `${BADGE_BASE} bg-gray-100 text-gray-700` : `${BADGE_BASE} bg-gray-100 text-gray-600`;
}

/** 상태 안내 배너 묶음: 1개면 그대로, 2개 이상이면 첫 줄만 보이고 펼치기 (UI 전용 상태) */
function ChatBanners({ banners }: { banners: { text: string; strong?: boolean }[] }) {
  const [open, setOpen] = useState(false);
  if (banners.length === 0) return null;
  const cls = (b: { strong?: boolean }) => `${b.strong ? "bg-accent-100 text-gray-900" : "bg-primary-50 text-primary-800"} rounded-[10px] px-3 py-1 text-[0.8125rem]/[1.4]`;
  const shown = open ? banners : banners.slice(0, 1);
  return (
    <div className="flex flex-col gap-1">
      {shown.map((b) => (
        <p key={b.text} className={cls(b)}>
          {b.text}
        </p>
      ))}
      {banners.length > 1 && (
        <button type="button" className={`self-start rounded px-1 py-1 text-[0.8125rem]/[normal] text-gray-600 underline underline-offset-2 ${FOCUS_RING}`} aria-expanded={open} onClick={() => setOpen((v) => !v)}>
          {open ? "접기" : `안내 ${banners.length - 1}개 더 보기`}
        </button>
      )}
    </div>
  );
}

/** 메시지 목록 위 안전 안내 띠: 기본 한 줄 요약, 누르면 전체 문구 */
function SafetyStrip(): ReactNode {
  const [open, setOpen] = useState(false);
  return (
    <div className="shrink-0 border-b border-gray-200 bg-gray-50 px-4 py-1.5">
      <button type="button" className={`flex w-full items-center justify-between gap-2 rounded text-left text-[0.8125rem]/[normal] text-gray-600 ${FOCUS_RING}`} aria-expanded={open} aria-controls="chat-safety-text" onClick={() => setOpen((v) => !v)}>
        <span>안전하게 거래하세요</span>
        <span className="underline underline-offset-2">{open ? "접기" : "자세히"}</span>
      </button>
      {open && (
        <p id="chat-safety-text" className={`${ui.hint} pt-1`}>
          예매내역을 확인할 때 이름·연락처·결제 정보는 가리고 보여주세요. 메시지는 텍스트만 보낼 수 있고, 보낸 뒤에는 삭제·수정할 수 없어요.
        </p>
      )}
    </div>
  );
}

/** 거래 카드 오른쪽 슬롯의 작은 윤곽선 버튼 (터치 영역 40px) */
const SLOT_BUTTON = `inline-flex min-h-10 shrink-0 cursor-pointer touch-manipulation items-center justify-center rounded-lg border border-primary-600 bg-white px-3 text-[0.8125rem]/[normal] font-semibold whitespace-nowrap text-primary-700 enabled:active:bg-primary-50 disabled:cursor-not-allowed disabled:opacity-60 ${FOCUS_RING}`;
/** 카드 안 보조 동작(거절·철회·예약 취소)용 작은 윤곽선 버튼 */
const SMALL_OUTLINE = `inline-flex min-h-10 cursor-pointer touch-manipulation items-center justify-center rounded-lg border border-gray-300 bg-white px-3 text-[0.8125rem]/[normal] font-semibold whitespace-nowrap text-gray-900 enabled:active:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-60 ${FOCUS_RING}`;

const MSG_BUBBLE = "max-w-[75%] px-3.5 py-2 text-[0.9375rem]/[1.45] break-words whitespace-pre-wrap";

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
  const [reserveBusy, setReserveBusy] = useState<ReserveAction | null>(null);
  const [reserveConfirm, setReserveConfirm] = useState<ReserveConfirm | null>(null);
  const [reserveError, setReserveError] = useState<string | null>(null);
  const [reserveNotice, setReserveNotice] = useState("");
  /** 결과 문구를 보여 준 시점의 매칭 상태. 상대 때문에 상태가 바뀌면 낡은 문구는 숨긴다 */
  const [reserveNoticeKey, setReserveNoticeKey] = useState("");

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
  const reserveBusyRef = useRef(false);
  const reserveBlockRef = useRef<HTMLDivElement>(null);
  const reserveConfirmRef = useRef<HTMLDivElement>(null);

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
    setReserveConfirm(null);
    setReserveError(null);
    setReserveNotice("");
    setReserveNoticeKey("");
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

  // 상대의 예약 제안·수락·거절·예약 취소는 알림 신호(공유 STOMP 연결)로 받아 다시 읽는다.
  // 연결이 끊겨 있을 때만 화면이 보이면 30초마다 폴링한다(연결 중에는 5분 확인). 이 효과는 요청을 abort하지 않으므로 가드가 잠기지 않는다.
  useEffect(() => {
    if (matchId === null) return;
    const offPush = subscribeNotificationPush((p) => {
      if (p.matchId === matchId) void refreshMatch();
    });
    let tick = 0;
    const timer = window.setInterval(() => {
      tick++;
      if (document.visibilityState !== "visible") return;
      if (getRealtimeStatus() !== "connected" || tick % MATCH_POLL_CONNECTED_EVERY === 0) void refreshMatch();
    }, MATCH_POLL_MS);
    return () => {
      offPush();
      window.clearInterval(timer);
    };
  }, [matchId, refreshMatch]);

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

  // 입력창 높이: 내용에 맞춰 늘어나되 약 5줄(120px + 여백)까지만 (UI 전용)
  useLayoutEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = "auto";
    el.style.height = `${Math.min(el.scrollHeight, TEXTAREA_MAX_PX)}px`;
    // 최대 높이를 넘으면 스크롤은 되되 막대는 숨긴다(overflow-y-hidden이어도 스크립트 스크롤은 되므로 키보드 이동은 auto로 연다)
    el.style.overflowY = el.scrollHeight > TEXTAREA_MAX_PX ? "auto" : "hidden";
  }, [text]);

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

  // ---- 예약 제안·수락·거절·철회·취소 ----
  const reserveState = match ? `${match.status}:${match.reservationProposal}` : "";
  useEffect(() => {
    // 상대 쪽 변화로 상태가 바뀌면 낡은 확인 영역을 닫는다
    setReserveConfirm(null);
  }, [reserveState]);
  useEffect(() => {
    if (reserveConfirm) reserveConfirmRef.current?.focus();
  }, [reserveConfirm]);

  const runReserve = async (action: ReserveAction) => {
    if (matchId === null || reserveBusyRef.current) return;
    reserveBusyRef.current = true;
    setReserveBusy(action);
    setReserveError(null);
    try {
      const name = match?.counterpartNickname ?? "상대";
      const res =
        action === "propose"
          ? await exchangeApi.reserve(matchId)
          : action === "accept"
            ? await exchangeApi.acceptReservation(matchId)
            : action === "decline"
              ? await exchangeApi.declineReservation(matchId)
              : action === "withdraw"
                ? await exchangeApi.withdrawReservation(matchId)
                : await exchangeApi.unreserve(matchId);
      matchLoadedRef.current = true;
      setMatch(res);
      setReserveConfirm(null);
      setReserveNoticeKey(`${res.status}|${res.reservationProposal}`);
      setReserveNotice(
        action === "propose"
          ? `${name}님에게 예약을 제안했어요. 상대가 수락하면 예약돼요.`
          : action === "accept"
            ? "예약을 수락했어요. 두 티켓이 예약 중으로 잠겼어요."
            : action === "decline"
              ? "예약 제안을 거절했어요. 채팅은 계속할 수 있어요."
              : action === "withdraw"
                ? "예약 제안을 철회했어요."
                : "예약을 취소했어요. 채팅은 그대로 유지돼요."
      );
      // 누른 버튼이 사라지므로 예약 블록으로 포커스를 옮긴다
      window.setTimeout(() => reserveBlockRef.current?.focus(), 0);
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "처리하지 못했습니다.");
      setReserveError(parsed.message);
      setReserveConfirm(null);
      // 상대와 동시에 눌렀거나 화면이 낡은 경우: 최신 상태로 맞춘다
      if (parsed.code !== null) void refreshMatch();
    } finally {
      reserveBusyRef.current = false;
      setReserveBusy(null);
    }
  };

  // ---- 보내기 ----
  const shownReserveNotice =
    match !== null && reserveNotice !== "" && reserveNoticeKey === `${match.status}|${match.reservationProposal}` ? reserveNotice : "";
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
  const proposal = match && match.status === "CHATTING" ? match.reservationProposal : "NONE";
  /** 종료(CANCELED·COMPLETED)된 채팅은 예약 블록을 보이지 않는다 */
  const reservable = match !== null && (match.status === "CHATTING" || match.status === "RESERVED");
  const seatExchanged = match !== null && (match.myTicketExchanged || match.counterpartTicketExchanged);
  const reserveStateText =
    match === null
      ? ""
      : match.status === "RESERVED"
        ? "예약 중이에요. 두 티켓이 예약되어 다른 매칭에서는 쓸 수 없어요."
        : proposal === "MINE"
          ? "내가 예약을 제안했어요. 상대의 수락을 기다려요."
          : proposal === "THEIRS"
            ? `${match.counterpartNickname}님이 예약을 제안했어요. 수락하거나 거절해 주세요.`
            : "";
  const readMarkerId = findReadMarkerId(messages, me, counterpartRead);
  const counterpartName = match?.counterpartNickname ?? "상대";
  const connectionText = connection === "connected" ? "" : CONNECTION_TEXT[connection];
  const countId = "chat-count";
  const disabledReasonId = "chat-disabled-reason";
  const fatal = matchError !== null && match === null;

  const count = countCodePoints(text);
  const sameDay = (a: ChatMessage, b: ChatMessage) => a.createdAt.slice(0, 10) === b.createdAt.slice(0, 10);
  const sameGroup = (a: ChatMessage, b: ChatMessage) => a.senderId === b.senderId && sameDay(a, b);

  return (
    // 전역 헤더 아래 남은 높이를 채우는 채팅 레이아웃: 헤더 / 거래 카드 / 메시지(여기만 스크롤) / 입력줄
    <div className="flex min-h-0 flex-1 justify-center bg-surface">
      <div className="flex min-h-0 w-full max-w-2xl flex-col bg-white sm:border-x sm:border-gray-200">
        {/* 헤더 줄: ← 뒤로 / 상대 닉네임 / ⋯ 더보기(차단·신고) */}
        <div className="flex shrink-0 items-center gap-1 border-b border-gray-200 bg-white px-2 py-1.5">
          <Link to={backTo} aria-label="내 매칭으로" className={`inline-flex size-11 shrink-0 items-center justify-center rounded-full text-gray-900 active:bg-gray-100 ${FOCUS_RING}`}>
            <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M15 5l-7 7 7 7" />
            </svg>
          </Link>
          <h1 ref={headingRef} tabIndex={-1} className="min-w-0 flex-1 truncate text-xl/[normal] font-bold text-gray-900 outline-none">
            {match ? (
              <>
                {match.counterpartNickname}
                <span className="sr-only">님과 채팅</span>
              </>
            ) : (
              "채팅"
            )}
          </h1>
          {match && <ChatSafetyActions matchId={matchId} counterpartName={match.counterpartNickname} menu />}
        </div>

        {/* 연결 상태 띠 (정상이면 비어 있음) */}
        <p role="status" className={liveRegionClass(connectionText, connection === "failed" ? "shrink-0 bg-red-50 px-4 py-1.5 text-[0.8125rem]/[normal] text-red-700" : "shrink-0 bg-gray-100 px-4 py-1.5 text-[0.8125rem]/[normal] text-gray-700")}>
          {connectionText}
        </p>

        {fatal && (
          <p className={`${ui.errorBox} m-4`} role="alert">
            {matchError}
          </p>
        )}

        {/* 거래 정보 카드: 자리 요약 + 상태 뱃지, 안내 배너, 예약 블록. 길어지면 안쪽만 스크롤 */}
        {match && (
          <div className="relative max-h-[40dvh] shrink-0 overflow-y-auto overscroll-contain border-b border-gray-200 bg-white px-4 py-2">
            <div className="flex flex-col gap-1.5">
              <div className="flex flex-wrap items-center justify-between gap-x-2 gap-y-1.5">
                <div className="flex min-w-0 flex-1 basis-56 flex-col gap-0.5">
                  <div className="flex min-w-0 items-start gap-2">
                    <span className={statusBadgeClass(match.status)}>{statusLabel(match.status)}</span>
                    <p className="min-w-0 break-words text-[0.9375rem]/[1.4] font-bold text-gray-900">
                      내 {formatSeat(match.mySeat)} ↔ 상대 {formatSeat(match.counterpartSeat)}
                    </p>
                  </div>
                  <p className="text-[0.8125rem]/[1.4] text-gray-600">
                    {match.mySeat.startsAt === match.counterpartSeat.startsAt
                      ? formatKstDateTime(match.mySeat.startsAt)
                      : `내 ${formatKstDateTime(match.mySeat.startsAt)} · 상대 ${formatKstDateTime(match.counterpartSeat.startsAt)}`}
                  </p>
                </div>
                {/* 당근마켓의 '거래 후기' 자리: 지금 할 수 있는 예약 동작 하나 */}
                {reservable && reserveConfirm === null && !seatExchanged && proposal === "NONE" && match.status === "CHATTING" && (
                  <button type="button" className={SLOT_BUTTON} onClick={() => setReserveConfirm("propose")} disabled={reserveBusy !== null}>
                    예약 제안
                  </button>
                )}
                {reservable && reserveConfirm === null && !seatExchanged && proposal === "THEIRS" && (
                  <button type="button" className={SLOT_BUTTON} onClick={() => setReserveConfirm("accept")} disabled={reserveBusy !== null || match.myTicketReservedElsewhere || match.counterpartTicketReservedElsewhere}>
                    예약 수락
                  </button>
                )}
              </div>
              <ChatBanners banners={banners} />
              {reservable && (
                <div ref={reserveBlockRef} tabIndex={-1} aria-label="예약" className="flex flex-col gap-1.5 rounded-[10px] outline-none">
                  {reserveStateText && <p className={proposal === "THEIRS" ? "rounded-[10px] bg-accent-100 px-3 py-1.5 text-[0.8125rem]/[1.4] text-gray-900" : "rounded-[10px] bg-primary-50 px-3 py-1.5 text-[0.8125rem]/[1.4] text-primary-800"}>{reserveStateText}</p>}
                  {reserveConfirm === null && match.status === "CHATTING" && proposal === "NONE" && seatExchanged && (
                    <p className="text-[0.8125rem]/[1.4] text-gray-600">이미 교환된 좌석이라 예약을 제안할 수 없어요.</p>
                  )}
                  {reserveConfirm === null && (proposal === "MINE" || proposal === "THEIRS" || match.status === "RESERVED") && (
                    <div className="flex flex-wrap gap-2">
                      {proposal === "MINE" && (
                        <button type="button" className={SMALL_OUTLINE} onClick={() => void runReserve("withdraw")} disabled={reserveBusy !== null} aria-busy={reserveBusy === "withdraw"}>
                          {reserveBusy === "withdraw" ? "처리 중..." : "제안 철회"}
                        </button>
                      )}
                      {proposal === "THEIRS" && (
                        <button type="button" className={SMALL_OUTLINE} onClick={() => void runReserve("decline")} disabled={reserveBusy !== null} aria-busy={reserveBusy === "decline"}>
                          {reserveBusy === "decline" ? "처리 중..." : "거절"}
                        </button>
                      )}
                      {match.status === "RESERVED" && (
                        <button type="button" className={SMALL_OUTLINE} onClick={() => setReserveConfirm("unreserve")} disabled={reserveBusy !== null}>
                          예약 취소
                        </button>
                      )}
                    </div>
                  )}
                  {reserveConfirm !== null && (
                    <div ref={reserveConfirmRef} tabIndex={-1} className="flex flex-col gap-2 outline-none">
                      <p className="rounded-[10px] bg-accent-100 px-3 py-1.5 text-[0.8125rem]/[1.4] text-gray-900">
                        {reserveConfirm === "propose"
                          ? `${counterpartName}님에게 예약을 제안할까요? 상대가 수락하면 두 티켓이 예약 중으로 잠기고, 다른 매칭에서는 쓸 수 없어요.`
                          : reserveConfirm === "accept"
                            ? "예약을 수락할까요? 수락하면 두 티켓이 예약 중으로 잠겨 다른 매칭에서는 쓸 수 없어요."
                            : `취소하면 예약이 풀리고 두 티켓을 다시 쓸 수 있어요.${match.myAccepted || match.counterpartAccepted ? " 교환 수락 표시도 초기화돼요." : ""} 예약을 취소할까요?`}
                      </p>
                      <div className="flex flex-wrap gap-2">
                        <button
                          type="button"
                          className={reserveConfirm === "unreserve" ? button.danger : button.solid}
                          onClick={() => void runReserve(reserveConfirm)}
                          disabled={reserveBusy !== null}
                          aria-busy={reserveBusy !== null}
                        >
                          {reserveBusy !== null ? "처리 중..." : reserveConfirm === "propose" ? "제안할게요" : reserveConfirm === "accept" ? "수락할게요" : "예약 취소할게요"}
                        </button>
                        <button type="button" className={button.outline} onClick={() => setReserveConfirm(null)} disabled={reserveBusy !== null}>
                          돌아가기
                        </button>
                      </div>
                    </div>
                  )}
                  {reserveError && (
                    <p className={ui.errorBox} role="alert">
                      {reserveError}
                    </p>
                  )}
                  {/* 화면 안내일 뿐 저장되는 메시지가 아니다 */}
                  {match.status === "RESERVED" && (
                    <p className="text-[0.8125rem]/[1.4] text-gray-600">
                      교환이 끝나면{" "}
                      <Link to={backTo} className={`font-semibold text-primary-700 underline underline-offset-2 ${FOCUS_RING}`}>
                        내 매칭 목록
                      </Link>
                      에서 [교환 수락]을 눌러주세요.
                    </p>
                  )}
                </div>
              )}
              <p role="status" className={liveRegionClass(shownReserveNotice, ui.status)}>
                {shownReserveNotice}
              </p>
            </div>
          </div>
        )}

        {!fatal && (
          <section className="flex min-h-0 flex-1 flex-col" aria-label="채팅">
            <SafetyStrip />

            <div className="relative flex min-h-0 flex-1 flex-col">
              <div
                ref={listRef}
                onScroll={onScroll}
                tabIndex={0}
                role="region"
                aria-label="메시지 목록"
                className={`relative min-h-0 flex-1 overflow-y-auto overscroll-contain bg-surface px-3 py-3 ${FOCUS_RING}`}
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
                <ul className="flex flex-col">
                  {messages.map((m, i) => {
                    const mine = m.senderId === me;
                    const prev = i > 0 ? messages[i - 1] : null;
                    const next = i < messages.length - 1 ? messages[i + 1] : null;
                    const day = formatDay(m.createdAt);
                    const showDay = prev === null || !sameDay(prev, m);
                    const first = showDay || prev === null || !sameGroup(prev, m);
                    const last = next === null || !sameGroup(m, next);
                    // 시간은 같은 보낸 사람·같은 분의 연속 메시지 중 마지막에만
                    const showTime = last || next === null || next.createdAt.slice(0, 16) !== m.createdAt.slice(0, 16);
                    const marker = m.id === readMarkerId;
                    // 묶음 안에서는 꼬리 쪽 모서리만 살짝 각지게 (묶음의 마지막 말풍선)
                    const radius = mine ? (last ? "rounded-2xl rounded-br-md" : "rounded-2xl") : last ? "rounded-2xl rounded-bl-md" : "rounded-2xl";
                    const meta = (showTime || marker) && (
                      <span className={`flex shrink-0 flex-col ${mine ? "items-end" : "items-start"} text-[0.75rem]/[1.3] text-gray-600`}>
                        {marker && <span>읽음</span>}
                        {showTime && <span>{formatTime(m.createdAt)}</span>}
                      </span>
                    );
                    return (
                      <li key={m.id} className={first && prev !== null ? "mt-3" : "mt-0.5"}>
                        {showDay && day && (
                          <p className="mt-1 mb-3 flex justify-center">
                            <span className="rounded-full bg-gray-200 px-3 py-1 text-[0.75rem]/[normal] text-gray-700">{day}</span>
                          </p>
                        )}
                        <div className={`flex items-end gap-1.5 ${mine ? "justify-end" : "justify-start"}`}>
                          {mine && meta}
                          <div className={`${MSG_BUBBLE} ${radius} ${mine ? "bg-primary-600 text-white" : "bg-white text-gray-900"}`}>
                            <span className="sr-only">{mine ? "나: " : `${counterpartName}: `}</span>
                            {m.content}
                          </div>
                          {!mine && meta}
                        </div>
                      </li>
                    );
                  })}
                  {shownPending.map((p) => (
                    <li key={p.clientMessageId} className="mt-2 flex flex-col items-end gap-1">
                      <div className="flex items-end justify-end gap-1.5">
                        {p.status === "sending" && <span className="shrink-0 text-[0.75rem]/[1.3] text-gray-600">보내는 중...</span>}
                        <div className={`${MSG_BUBBLE} rounded-2xl rounded-br-md bg-primary-600 text-white opacity-70`}>
                          <span className="sr-only">나: </span>
                          {p.content}
                        </div>
                      </div>
                      {p.status !== "sending" && (
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

            {/* 입력줄: 하단 고정(flex 맨 아래), 키보드·홈 인디케이터에 가리지 않게 safe-area 패딩 */}
            <form onSubmit={onSubmit} className="flex shrink-0 flex-col gap-1.5 border-t border-gray-200 bg-white px-3 pt-2 pb-[calc(8px+env(safe-area-inset-bottom))]">
              {closedChat && (
                <p id={disabledReasonId} className={ui.muted}>
                  종료된 채팅이라 메시지를 보낼 수 없어요. 이전 대화만 볼 수 있어요.
                </p>
              )}
              <label htmlFor="chat-input" className="sr-only">
                {counterpartName}님에게 보낼 메시지
              </label>
              {/* 알약 입력창: 테두리는 바깥 알약이 맡고 textarea는 테두리·스크롤바 없이 높이만 늘어난다 */}
              <div className={`flex items-end rounded-3xl border pl-4 pr-1 focus-within:border-primary-600 focus-within:ring-3 focus-within:ring-primary-600/20 ${closedChat ? "border-gray-200 bg-gray-100" : "border-gray-300 bg-white"}`}>
                <textarea
                  id="chat-input"
                  ref={textareaRef}
                  rows={1}
                  value={text}
                  onChange={(e) => setText(truncateCodePoints(e.target.value, CHAT_MAX_LENGTH))}
                  onKeyDown={onKeyDown}
                  disabled={closedChat}
                  placeholder="메시지 보내기"
                  aria-describedby={closedChat ? `${countId} ${disabledReasonId}` : countId}
                  className="box-border min-h-11 min-w-0 flex-1 resize-none overflow-y-hidden border-0 bg-transparent px-0 py-2.5 text-base/6 text-gray-900 outline-hidden [scrollbar-width:none] placeholder:text-gray-500 disabled:cursor-not-allowed [&::-webkit-scrollbar]:hidden"
                />
                <button
                  type="submit"
                  aria-label="보내기"
                  className={`inline-flex size-11 shrink-0 cursor-pointer touch-manipulation items-center justify-center rounded-full text-primary-600 enabled:active:bg-primary-50 disabled:cursor-not-allowed disabled:text-gray-400 ${FOCUS_RING}`}
                  disabled={closedChat || text.trim().length === 0}
                >
                  <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M21 3L3 10.5l7 3 3 7L21 3z" />
                    <path d="M10 13.5L21 3" />
                  </svg>
                </button>
              </div>
              {/* 평소에는 낭독용으로만 존재하고 900자 이상일 때 눈에 보인다 */}
              <span id={countId} className={count >= COUNT_VISIBLE_FROM ? `${ui.hint} self-end pr-1` : "sr-only"}>
                {count}/{CHAT_MAX_LENGTH}자
              </span>
            </form>
          </section>
        )}
      </div>
    </div>
  );
}
