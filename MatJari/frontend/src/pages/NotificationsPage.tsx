import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { subscribeNotificationPush } from "../api/realtimeHub";
import { formatRelativeTime, notificationPath, notificationsApi, notificationText } from "../api/notifications";
import { button, FOCUS_RING, liveRegionClass, ui } from "../components/ui";
import { usePagedList } from "../hooks/usePagedList";
import { adjustUnreadCount, refreshUnreadCount } from "../hooks/useUnreadNotifications";
import type { AppNotification } from "../types/notification";

// 알림 전체 보기 (보호 라우트 /notifications): 최신순 더 보기 목록, 안 읽음 표시, 모두 읽음, 읽은 알림 삭제(2단계 확인).
// 닫힌 매칭(취소·완료)의 알림도 개수·목록에서 빼지 않고 회색 '종료된 매칭'으로만 표시한다.
// 알림이 사라지는 유일한 경로는 '읽은 알림 삭제'이며 안 읽은 알림은 지워지지 않는다.

export default function NotificationsPage() {
  const navigate = useNavigate();
  const [announce, setAnnounce] = useState("");
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirmingDelete, setConfirmingDelete] = useState(false);
  const [busy, setBusy] = useState<"readAll" | "delete" | null>(null);
  const busyRef = useRef(false);
  const confirmRef = useRef<HTMLDivElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<AppNotification>(
    (page, signal) => notificationsApi.list(page, 20, signal),
    "all",
    (n) => n.id
  );

  // 이 페이지가 열려 있는 동안 새 알림 신호가 오면 목록을 바로 바꾸지 않고(보던 위치·불러온 페이지 유지) 안내 버튼만 띄운다
  const [hasNew, setHasNew] = useState(false);
  useEffect(() => subscribeNotificationPush(() => setHasNew(true)), []);
  const showNew = () => {
    setHasNew(false);
    reload();
  };

  const run = async (kind: "readAll" | "delete") => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(kind);
    setActionError(null);
    try {
      if (kind === "readAll") {
        await notificationsApi.markAllRead();
        setAnnounce("모든 알림을 읽음으로 표시했어요.");
      } else {
        const deleted = await notificationsApi.deleteRead();
        setAnnounce(deleted > 0 ? `읽은 알림 ${deleted}개를 지웠어요.` : "지울 읽은 알림이 없어요.");
        setConfirmingDelete(false);
        headingRef.current?.focus();
      }
      reload();
    } catch (err) {
      setActionError(exchangeErrorMessage(err, "처리하지 못했습니다.").message);
    } finally {
      busyRef.current = false;
      setBusy(null);
      void refreshUnreadCount();
    }
  };

  const openItem = async (n: AppNotification) => {
    if (!n.read) {
      adjustUnreadCount(-1);
      try {
        await notificationsApi.markRead(n.id);
      } catch {
        // 읽음 처리 실패여도 이동은 막지 않는다
      }
      void refreshUnreadCount();
    }
    navigate(notificationPath(n));
  };

  const statusText =
    state.status === "loading"
      ? "알림을 불러오는 중..."
      : announce
        ? announce
        : state.status === "success"
          ? `알림 ${state.totalElements}개`
          : "";
  // 불러오지 않은 뒤쪽 페이지에 안 읽은 알림이 있을 수 있으면 버튼을 켜 둔다
  const hasUnread = state.status === "success" && (state.items.some((n) => !n.read) || state.items.length < state.totalElements);

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 ref={headingRef} tabIndex={-1} className={`${ui.pageTitle} outline-none`}>
          알림
        </h1>

        <div role="status">
          {hasNew && (
            <button type="button" className={`${button.outline} w-full`} onClick={showNew}>
              새 알림이 있어요. 눌러서 불러오기
            </button>
          )}
        </div>

        <div className="grid grid-cols-2 gap-2">
          <button type="button" className={button.outline} onClick={() => void run("readAll")} disabled={busy !== null || !hasUnread} aria-busy={busy === "readAll"}>
            {busy === "readAll" ? "처리 중..." : "모두 읽음"}
          </button>
          <button
            type="button"
            className={button.dangerOutline}
            onClick={() => {
              setConfirmingDelete(true);
              // 확인 영역은 다음 렌더에서 나타나므로 한 틱 뒤에 포커스를 옮긴다
              window.setTimeout(() => confirmRef.current?.focus(), 0);
            }}
            disabled={busy !== null || confirmingDelete || state.status !== "success" || state.items.length === 0}
          >
            읽은 알림 삭제
          </button>
        </div>

        {confirmingDelete && (
          <div ref={confirmRef} tabIndex={-1} className="flex flex-col gap-3 outline-none">
            <p className={ui.warning}>
              읽은 알림을 모두 지울까요? 지운 알림은 되돌릴 수 없어요. 안 읽은 알림은 지워지지 않아요.
            </p>
            <div className="flex flex-wrap gap-2">
              <button type="button" className={button.danger} onClick={() => void run("delete")} disabled={busy !== null} aria-busy={busy === "delete"}>
                {busy === "delete" ? "처리 중..." : "지울게요"}
              </button>
              <button type="button" className={button.outline} onClick={() => setConfirmingDelete(false)} disabled={busy !== null}>
                돌아가기
              </button>
            </div>
          </div>
        )}

        <p className={liveRegionClass(statusText, ui.status)} role="status">
          {statusText}
        </p>

        {actionError && (
          <p className={ui.errorBox} role="alert">
            {actionError}
          </p>
        )}

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "알림을 불러오지 못했습니다.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}

        {state.status === "success" && state.items.length === 0 && (
          <div className={ui.card}>
            <p className={ui.body}>아직 알림이 없어요. 새 제안·예약·채팅 메시지가 생기면 여기에 알려드려요.</p>
          </div>
        )}

        {state.status === "success" && state.items.length > 0 && (
          <>
            <ul className="flex flex-col gap-2" aria-label="알림 목록">
              {state.items.map((n) => (
                <li key={n.id}>
                  <button
                    type="button"
                    onClick={() => void openItem(n)}
                    className={
                      "flex min-h-11 w-full cursor-pointer touch-manipulation flex-col gap-1 rounded-2xl p-4 text-left active:bg-gray-100 " +
                      `${n.matchClosed ? "bg-gray-100" : "bg-white shadow-[0_4px_24px_rgba(0,0,0,0.06)]"} ` +
                      FOCUS_RING
                    }
                  >
                    <span className={`text-[0.9375rem]/[1.4] ${n.matchClosed ? "text-gray-700" : "text-gray-900"} ${n.read ? "font-normal" : "font-semibold"}`}>
                      {!n.read && <span className="mr-2 inline-block size-2 rounded-full bg-primary-600 align-middle" aria-hidden="true" />}
                      {!n.read && <span className="sr-only">안 읽음. </span>}
                      {notificationText(n)}
                    </span>
                    <span className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm/[normal] text-gray-600">
                      <span>{formatRelativeTime(n.createdAt)}</span>
                      {n.matchClosed && <span className="rounded-full bg-gray-200 px-2.5 py-0.5 text-[0.8125rem]/[normal] font-semibold text-gray-700">종료된 매칭</span>}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
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
