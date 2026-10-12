import { useRef, useState } from "react";
import { blocksApi, type BlockedUser } from "../api/blocks";
import { formatKstDateTime } from "../api/dateTime";
import { parseApiError } from "../api/errors";
import { usePagedList } from "../hooks/usePagedList";
import { button, liveRegionClass, ui } from "../components/ui";

// 차단 목록 (/my/blocks, 보호 라우트). 차단은 채팅 화면에서만 시작하고 해제는 여기서 한다(확인 후).
// 해제해도 종료된 매칭은 되살아나지 않는다(다시 제안은 가능).

const PAGE_SIZE = 20;

export default function MyBlocksPage() {
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<BlockedUser>(
    (page, signal) => blocksApi.list(page, PAGE_SIZE, signal),
    "mine",
    (b) => b.id
  );
  const [confirmId, setConfirmId] = useState<number | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [done, setDone] = useState("");
  const titleRef = useRef<HTMLHeadingElement>(null);
  const busyRef = useRef(false);

  const unblock = async (b: BlockedUser) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusyId(b.id);
    setActionError(null);
    try {
      await blocksApi.unblock(b.id);
      setConfirmId(null);
      setDone(`${b.blockedNickname}님의 차단을 해제했어요. 종료된 교환은 되살아나지 않아요.`);
      reload();
      // 눌렀던 버튼이 사라지므로 제목으로 포커스를 옮긴다
      titleRef.current?.focus();
    } catch (err) {
      setActionError(parseApiError(err, "차단을 해제하지 못했어요.").message);
    } finally {
      busyRef.current = false;
      setBusyId(null);
    }
  };

  const statusText =
    state.status === "loading" ? "불러오는 중..." : loadingMore ? "더 불러오는 중..." : state.status === "success" ? done || `차단한 사용자 ${state.totalElements}명` : "";

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 ref={titleRef} tabIndex={-1} className={`${ui.pageTitle} outline-none`}>
          차단 목록
        </h1>
        <p className={ui.hint}>차단한 사용자와는 서로 후보에 나오지 않고 채팅할 수 없어요. 상대에게는 알리지 않아요.</p>
        <p className={liveRegionClass(statusText, ui.status)} role="status">
          {statusText}
        </p>

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {parseApiError(state.error, "차단 목록을 불러오지 못했습니다.").message}
            </p>
            <button type="button" className={button.solid} onClick={reload}>
              다시 시도
            </button>
          </div>
        )}

        {actionError && (
          <p className={ui.errorBox} role="alert">
            {actionError}
          </p>
        )}

        {state.status === "success" && state.items.length === 0 && (
          <div className={ui.card}>
            <p className={ui.body}>차단한 사용자가 없어요. 차단은 채팅 화면의 '차단' 버튼으로 할 수 있어요.</p>
          </div>
        )}

        {state.status === "success" && state.items.length > 0 && (
          <ul className="flex flex-col gap-3">
            {state.items.map((b) => {
              const confirming = confirmId === b.id;
              const busy = busyId === b.id;
              return (
                <li key={b.id} className={`${ui.card} flex flex-col gap-3`}>
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="min-w-0">
                      <p className="truncate text-base/[normal] font-semibold text-gray-900">{b.blockedNickname}</p>
                      <p className={ui.muted}>{formatKstDateTime(b.createdAt)} 차단</p>
                    </div>
                    {!confirming && (
                      <button
                        type="button"
                        className={button.outline}
                        aria-label={`${b.blockedNickname}님 차단 해제`}
                        onClick={() => {
                          setActionError(null);
                          setDone("");
                          setConfirmId(b.id);
                        }}
                        disabled={busyId !== null}
                      >
                        차단 해제
                      </button>
                    )}
                  </div>
                  {confirming && (
                    <div className="flex flex-col gap-2" role="group" aria-label={`${b.blockedNickname}님 차단 해제 확인`}>
                      <p className={ui.warning}>차단을 해제하면 다시 서로 후보에 나와요. 이미 종료된 교환은 되살아나지 않아요. 해제할까요?</p>
                      <div className="flex flex-wrap justify-end gap-2">
                        <button type="button" className={button.outline} onClick={() => setConfirmId(null)} disabled={busy}>
                          취소
                        </button>
                        <button type="button" className={button.solid} onClick={() => void unblock(b)} disabled={busy} aria-busy={busy}>
                          {busy ? "해제하는 중..." : "해제하기"}
                        </button>
                      </div>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
        )}

        {state.status === "success" && state.page + 1 < state.totalPages && (
          <div className="flex flex-col items-center gap-2">
            {moreError && (
              <p className={ui.errorBox} role="alert">
                {moreError}
              </p>
            )}
            <button type="button" className={button.outline} onClick={loadMore} disabled={loadingMore}>
              더 보기
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
