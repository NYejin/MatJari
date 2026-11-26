import { useCallback, useEffect, useRef, useState } from "react";
import { useListingImageUrl } from "../hooks/useListingImageUrl";
import type { ListingImageRef } from "../types/listingImage";
import { button, FOCUS_RING } from "./ui";

// 티켓·희망 조건 사진 보기. 썸네일 줄(thumbs) 또는 "n장 보기" 버튼(button)으로 쓰고, 누르면 크게 보기 모달이 열린다.
// 사진은 인증 요청으로 받아야 해서 각 썸네일이 직접 불러온다. 404(볼 수 없음)는 조용히 숨긴다.

interface Props {
  images: ListingImageRef[] | null | undefined;
  /** 접근성 이름 앞부분 (예: "상대 자리 사진") */
  label: string;
  variant?: "thumbs" | "button";
  /** 썸네일 한 변(px). 기본 64 */
  size?: number;
}

export default function ListingImageGallery({ images, label, variant = "thumbs", size = 64 }: Props) {
  const list = images ?? [];
  const [hidden, setHidden] = useState<Set<number>>(() => new Set());
  const [openAt, setOpenAt] = useState<number | null>(null);
  const hide = useCallback((id: number) => setHidden((prev) => (prev.has(id) ? prev : new Set(prev).add(id))), []);
  const visible = list.filter((i) => !hidden.has(i.id));
  // 버튼형은 열어 보기 전에는 숨김 여부를 모르므로 처음엔 보이고, 전부 숨겨진 것으로 확인되면 감춘다
  if (visible.length === 0) return null;

  return (
    <>
      {variant === "thumbs" ? (
        <ul className="flex flex-wrap gap-1.5" aria-label={label}>
          {visible.map((img, idx) => (
            <li key={img.id}>
              <Thumb id={img.id} size={size} alt={`${label} ${idx + 1}`} onHidden={hide} onOpen={() => setOpenAt(idx)} />
            </li>
          ))}
        </ul>
      ) : (
        <button type="button" className={`${button.outline} min-h-10 !px-3 !text-[0.8125rem]`} onClick={() => setOpenAt(0)}>
          {label} {list.length}장 보기
        </button>
      )}
      {openAt !== null && (
        <Viewer
          images={variant === "button" ? list : visible}
          start={Math.min(openAt, Math.max(0, (variant === "button" ? list : visible).length - 1))}
          label={label}
          onHidden={hide}
          onClose={() => setOpenAt(null)}
        />
      )}
    </>
  );
}

function Thumb({ id, size, alt, onHidden, onOpen }: { id: number; size: number; alt: string; onHidden: (id: number) => void; onOpen: () => void }) {
  const [retry, setRetry] = useState(0);
  const state = useListingImageUrl(id, retry);
  useEffect(() => {
    if (state.kind === "hidden") onHidden(id);
  }, [state.kind, id, onHidden]);
  const box = { width: size, height: size };
  if (state.kind === "hidden") return null;
  if (state.kind === "loading") return <div role="img" aria-label={`${alt} 불러오는 중`} className="animate-pulse rounded-xl bg-gray-200" style={box} />;
  if (state.kind === "expired") {
    return (
      <div className="flex items-center justify-center rounded-xl bg-gray-100 p-1 text-center text-[0.6875rem]/[1.2] text-gray-600" style={box}>
        삭제된 사진
      </div>
    );
  }
  if (state.kind === "error") {
    return (
      <button type="button" className={`rounded-xl bg-gray-100 p-1 text-center text-[0.6875rem]/[1.2] text-gray-700 ${FOCUS_RING}`} style={box} onClick={() => setRetry((k) => k + 1)}>
        다시 불러오기
      </button>
    );
  }
  return (
    <button type="button" className={`block cursor-zoom-in touch-manipulation overflow-hidden rounded-xl bg-gray-100 p-0 ${FOCUS_RING}`} style={box} onClick={onOpen} aria-label={`${alt} 크게 보기`}>
      <img src={state.url} alt={alt} className="size-full object-cover" />
    </button>
  );
}

/** 크게 보기 모달. 여러 장이면 이전/다음, Esc·바깥 누름으로 닫는다 */
function Viewer({ images, start, label, onHidden, onClose }: { images: ListingImageRef[]; start: number; label: string; onHidden: (id: number) => void; onClose: () => void }) {
  const [index, setIndex] = useState(start);
  const closeRef = useRef<HTMLButtonElement>(null);
  const current = images[index];
  const state = useListingImageUrl(current.id);

  useEffect(() => {
    if (state.kind === "hidden") onHidden(current.id);
  }, [state.kind, current.id, onHidden]);

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        onClose();
      } else if (e.key === "ArrowLeft") setIndex((i) => Math.max(0, i - 1));
      else if (e.key === "ArrowRight") setIndex((i) => Math.min(images.length - 1, i + 1));
      else if (e.key === "Tab") {
        // 모달 안 버튼들 사이에서만 돈다
        const root = closeRef.current?.parentElement;
        const items = root ? Array.from(root.querySelectorAll<HTMLElement>("button:not([disabled])")) : [];
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (!root?.contains(document.activeElement)) {
          e.preventDefault();
          first.focus();
        } else if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      if (opener && opener.isConnected) opener.focus?.();
    };
  }, [images.length, onClose]);

  const alt = `${label} ${index + 1}/${images.length}`;
  return (
    <div role="dialog" aria-modal="true" aria-label={alt} className="fixed inset-0 z-[60] flex items-center justify-center bg-black/85 p-3" onClick={onClose}>
      <div className="contents" onClick={(e) => e.stopPropagation()}>
        <button ref={closeRef} type="button" className={`absolute top-3 right-3 inline-flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-full bg-white px-4 text-sm font-semibold text-gray-900 ${FOCUS_RING}`} onClick={onClose}>
          닫기
        </button>
        {state.kind === "ready" && <img src={state.url} alt={alt} className="max-h-full max-w-full object-contain" />}
        {state.kind === "loading" && <p className="text-sm text-white" role="status">불러오는 중...</p>}
        {state.kind === "expired" && <p className="rounded-2xl bg-white px-4 py-3 text-sm text-gray-800">보관 기간이 지나 삭제된 사진이에요</p>}
        {state.kind === "hidden" && <p className="rounded-2xl bg-white px-4 py-3 text-sm text-gray-800">볼 수 없는 사진이에요</p>}
        {state.kind === "error" && <p className="rounded-2xl bg-white px-4 py-3 text-sm text-gray-800">사진을 불러오지 못했어요.</p>}
        {images.length > 1 && (
          <div className="absolute bottom-4 left-1/2 flex -translate-x-1/2 items-center gap-2 rounded-full bg-white/95 px-2 py-1">
            <button type="button" className={`${button.ghost} min-h-11`} disabled={index === 0} onClick={() => setIndex(index - 1)} aria-label="이전 사진">
              이전
            </button>
            <span className="text-sm text-gray-800" aria-live="polite">
              {index + 1} / {images.length}
            </span>
            <button type="button" className={`${button.ghost} min-h-11`} disabled={index === images.length - 1} onClick={() => setIndex(index + 1)} aria-label="다음 사진">
              다음
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
