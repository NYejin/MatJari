import { isAxiosError } from "axios";
import { useCallback, useEffect, useRef, useState } from "react";
import { button, FOCUS_RING } from "./ui";

// 채팅 사진 말풍선(썸네일) + 전체 화면 보기. 사진은 Authorization 헤더가 필요해 fetch(blob)로 받아 blob URL로 보여 주고,
// 화면을 떠나면 URL을 해제한다. 받는 방법(채팅/관리자 경로)은 load 콜백으로 주입한다.

interface Props {
  load: (signal: AbortSignal) => Promise<Blob>;
  /** 서버가 보관 기간이 지났다고 알려 준 사진 */
  expired: boolean;
  mine: boolean;
  /** 썸네일 최대 폭(px). 기본 220 */
  maxWidth?: number;
}

type State = { kind: "loading" } | { kind: "ready"; url: string } | { kind: "expired" } | { kind: "error" };

export default function ChatImage({ load, expired, mine, maxWidth = 220 }: Props) {
  const [state, setState] = useState<State>(expired ? { kind: "expired" } : { kind: "loading" });
  const [open, setOpen] = useState(false);
  const [retryKey, setRetryKey] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    if (expired) {
      setState({ kind: "expired" });
      return;
    }
    const controller = new AbortController();
    let url: string | null = null;
    setState({ kind: "loading" });
    loadRef
      .current(controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return;
        url = URL.createObjectURL(blob);
        setState({ kind: "ready", url });
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        setState(isAxiosError(err) && err.response?.status === 410 ? { kind: "expired" } : { kind: "error" });
      });
    return () => {
      controller.abort();
      if (url) URL.revokeObjectURL(url);
    };
  }, [expired, retryKey]);

  const alt = mine ? "보낸 사진" : "받은 사진";

  if (state.kind === "expired") {
    return <div className="rounded-2xl bg-gray-200 px-3.5 py-2 text-[0.8125rem]/[1.4] text-gray-700">보관 기간이 지나 삭제된 사진이에요</div>;
  }
  if (state.kind === "error") {
    return (
      <div className="flex flex-col items-start gap-1 rounded-2xl bg-gray-200 px-3.5 py-2 text-[0.8125rem]/[1.4] text-gray-700">
        <span>사진을 불러오지 못했어요.</span>
        <button type="button" className={`${button.outline} min-h-10`} onClick={() => setRetryKey((k) => k + 1)}>
          다시 불러오기
        </button>
      </div>
    );
  }
  if (state.kind === "loading") {
    return (
      <div role="img" aria-label={`${alt} 불러오는 중`} className="flex h-[140px] animate-pulse items-center justify-center rounded-2xl bg-gray-200 text-[0.75rem] text-gray-600" style={{ width: maxWidth }}>
        불러오는 중...
      </div>
    );
  }
  return (
    <>
      <button type="button" className={`block cursor-zoom-in touch-manipulation overflow-hidden rounded-2xl bg-gray-200 p-0 ${FOCUS_RING}`} onClick={() => setOpen(true)} aria-label={`${alt} 크게 보기`}>
        <img src={state.url} alt={alt} style={{ maxWidth }} className="block h-auto max-h-[320px] w-auto object-contain" />
      </button>
      {open && <Lightbox url={state.url} alt={alt} onClose={() => setOpen(false)} />}
    </>
  );
}

function Lightbox({ url, alt, onClose }: { url: string; alt: string; onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null);
  const close = useCallback(() => onClose(), [onClose]);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        close();
      } else if (e.key === "Tab") {
        // 포커스 가두기: 닫기 버튼이 유일한 포커스 대상이다
        e.preventDefault();
        closeRef.current?.focus();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      if (opener && opener.isConnected) opener.focus?.();
    };
  }, [close]);

  return (
    <div role="dialog" aria-modal="true" aria-label={alt} className="fixed inset-0 z-[60] flex items-center justify-center bg-black/85 p-3" onClick={close}>
      <button ref={closeRef} type="button" className={`absolute top-3 right-3 inline-flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-full bg-white px-4 text-sm font-semibold text-gray-900 ${FOCUS_RING}`} onClick={close}>
        닫기
      </button>
      <img src={url} alt={alt} className="max-h-full max-w-full object-contain" onClick={(e) => e.stopPropagation()} />
    </div>
  );
}
