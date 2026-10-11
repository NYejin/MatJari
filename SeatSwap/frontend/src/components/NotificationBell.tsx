import { useCallback, useEffect, useId, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { subscribeNotificationPush } from "../api/realtimeHub";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { formatRelativeTime, notificationPath, notificationsApi, notificationText } from "../api/notifications";
import { adjustUnreadCount, refreshUnreadCount, useUnreadNotifications } from "../hooks/useUnreadNotifications";
import type { AppNotification } from "../types/notification";
import { button, FOCUS_RING, linkButton } from "./ui";

// 헤더 알림 벨: 안 읽은 개수 뱃지 + 누르면 바로 아래에 최근 알림 5건 말풍선(popover).
// 모바일(md 미만)은 헤더 폭만큼 펼치고, md 이상은 벨 오른쪽 끝에 맞춘 고정 폭으로 띄운다.
// 닫힘: 바깥 클릭 · Esc(포커스를 벨로) · 경로 이동 · 항목/버튼 선택.

const PREVIEW_SIZE = 5;

type PreviewState =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "success"; items: AppNotification[] };

const cls = {
  wrap: "shrink-0 md:relative",
  bell:
    "relative inline-flex size-11 shrink-0 cursor-pointer touch-manipulation items-center justify-center rounded-[10px] " +
    "border-0 bg-transparent p-0 text-gray-700 active:bg-gray-100 " +
    FOCUS_RING,
  /** 빨간 뱃지: 흰 글자 / red-600 4.83:1 */
  badge:
    "absolute top-0.5 right-0.5 inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-red-600 px-1 " +
    "text-xs/[normal] font-bold text-white",
  popover:
    "absolute top-full right-2 left-2 z-[110] mt-1 flex flex-col gap-2 rounded-2xl border border-gray-200 bg-white p-3 " +
    "shadow-[0_8px_24px_rgba(0,0,0,0.12)] outline-none md:right-auto md:left-0 md:w-88",
  item:
    "flex min-h-11 w-full cursor-pointer touch-manipulation flex-col gap-0.5 rounded-[10px] px-3 py-2 text-left " +
    "active:bg-gray-100 " +
    FOCUS_RING,
} as const;

export default function NotificationBell({ userId, onOpen }: { userId: number; onOpen?: () => void }) {
  const unread = useUnreadNotifications(true, userId);
  const [open, setOpen] = useState(false);
  const [preview, setPreview] = useState<PreviewState>({ status: "loading" });
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const popoverId = useId();
  const wrapRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  const loadPreview = useCallback(async (signal?: AbortSignal) => {
    try {
      const res = await notificationsApi.list(0, PREVIEW_SIZE, signal);
      if (!signal?.aborted) setPreview({ status: "success", items: res.content });
    } catch (err) {
      if (!signal?.aborted) {
        setPreview({ status: "error", message: exchangeErrorMessage(err, "알림을 불러오지 못했어요.").message });
      }
    }
  }, []);

  // 열릴 때마다 최신 알림을 읽고 포커스를 말풍선으로 옮긴다
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setPreview({ status: "loading" });
    void loadPreview(controller.signal);
    popoverRef.current?.focus();
    return () => controller.abort();
  }, [open, loadPreview]);

  // 말풍선이 열려 있는 동안 새 알림 신호가 오면 목록을 조용히 다시 읽는다 (로딩 표시 없이 교체)
  useEffect(() => {
    if (!open) return;
    return subscribeNotificationPush(() => void loadPreview());
  }, [open, loadPreview]);

  // 경로가 바뀌면 닫기
  useEffect(() => {
    setOpen(false);
  }, [pathname]);

  // 열려 있을 때만 Esc / 바깥 클릭 감지
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      setOpen(false);
      buttonRef.current?.focus();
    };
    const onPointerDown = (e: PointerEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [open]);

  const openItem = async (n: AppNotification) => {
    setOpen(false); // 두 번 눌러 개수가 두 번 줄지 않게 먼저 닫는다
    if (!n.read) {
      adjustUnreadCount(-1);
      try {
        await notificationsApi.markRead(n.id);
      } catch {
        // 읽음 처리 실패여도 이동은 막지 않는다. 개수는 아래에서 서버 값으로 바로잡는다
      }
      void refreshUnreadCount();
    }
    navigate(notificationPath(n));
  };

  const readAll = async () => {
    if (busy) return;
    setBusy(true);
    try {
      await notificationsApi.markAllRead();
      adjustUnreadCount(-unread);
      await loadPreview();
    } catch (err) {
      setPreview({ status: "error", message: exchangeErrorMessage(err, "모두 읽음 처리하지 못했어요.").message });
    } finally {
      setBusy(false);
      void refreshUnreadCount();
    }
  };

  return (
    <div ref={wrapRef} className={cls.wrap}>
      <button
        ref={buttonRef}
        type="button"
        className={cls.bell}
        aria-expanded={open}
        aria-controls={popoverId}
        aria-label={`알림 ${unread}개`}
        onClick={() => {
          if (!open) onOpen?.(); // 헤더의 모바일 메뉴가 열려 있으면 닫아 겹치지 않게
          setOpen((v) => !v);
        }}
      >
        <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M6 9a6 6 0 1 1 12 0c0 5 2 6.5 2 6.5H4S6 14 6 9Z" />
          <path d="M10 19a2 2 0 0 0 4 0" />
        </svg>
        {unread > 0 && (
          <span className={cls.badge} aria-hidden="true">
            {unread > 99 ? "99+" : unread}
          </span>
        )}
      </button>

      {open && (
        <div id={popoverId} ref={popoverRef} role="dialog" aria-label="최근 알림" tabIndex={-1} className={cls.popover}>
          {preview.status === "loading" && <p className="px-3 py-2 text-sm/[normal] text-gray-500">알림을 불러오는 중...</p>}
          {preview.status === "error" && (
            <div className="flex flex-col items-start gap-2 px-3 py-2">
              <p className="text-sm/[normal] text-red-700">{preview.message}</p>
              <button type="button" className={button.outline} onClick={() => { setPreview({ status: "loading" }); void loadPreview(); }}>
                다시 시도
              </button>
            </div>
          )}
          {preview.status === "success" && preview.items.length === 0 && (
            <p className="px-3 py-2 text-sm/[normal] text-gray-700">아직 알림이 없어요.</p>
          )}
          {preview.status === "success" && preview.items.length > 0 && (
            <ul className="flex max-h-[60vh] flex-col overflow-y-auto" aria-label="최근 알림 목록">
              {preview.items.map((n) => (
                <li key={n.id}>
                  <button type="button" className={cls.item} onClick={() => void openItem(n)}>
                    <span className={`text-sm/[1.35] ${n.matchClosed ? "text-gray-500" : "text-gray-900"} ${n.read ? "font-normal" : "font-semibold"}`}>
                      {!n.read && <span className="mr-1.5 inline-block size-2 rounded-full bg-primary-600 align-middle" aria-hidden="true" />}
                      {!n.read && <span className="sr-only">안 읽음. </span>}
                      {notificationText(n)}
                    </span>
                    <span className="text-xs/[normal] text-gray-500">
                      {formatRelativeTime(n.createdAt)}
                      {n.matchClosed && " · 종료된 매칭"}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
          <div className="flex flex-wrap gap-2">
            <button type="button" className={`${button.outline} grow`} onClick={() => void readAll()} disabled={busy || unread === 0}>
              모두 읽음
            </button>
            <Link to="/notifications" className={`${linkButton.solid} grow`} onClick={() => setOpen(false)}>
              알림 전체 보기
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}
