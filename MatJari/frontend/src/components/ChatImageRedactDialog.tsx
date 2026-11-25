import { useEffect, useRef, useState, type PointerEvent } from "react";
import { button, FOCUS_RING, ui } from "./ui";

// 채팅 사진 보내기 전 가리기 단계(하단 시트/대화상자).
// 가림 사각형은 원본 픽셀 좌표로 저장하고(화면 크기와 무관), 보낼 때 canvas에 원본 + 검은 사각형을 그려
// JPEG로 다시 만든다. 그래서 가린 부분이 원본에 남지 않고 EXIF도 사라진다.

const MAX_SIDE = 4000;
const MAX_BYTES = 5 * 1024 * 1024;
const MIN_RECT_PX = 4;

interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/** 화면 좌표(CSS px)를 원본 픽셀 좌표로 바꾼다. 화면에 보이는 크기가 달라도 같은 원본 위치를 가리킨다 */
export function toImagePoint(clientX: number, clientY: number, box: DOMRect, imgW: number, imgH: number): { x: number; y: number } {
  const x = ((clientX - box.left) / box.width) * imgW;
  const y = ((clientY - box.top) / box.height) * imgH;
  return { x: Math.min(imgW, Math.max(0, x)), y: Math.min(imgH, Math.max(0, y)) };
}

function toBlob(canvas: HTMLCanvasElement, quality: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
}

/** 원본 + 가림 사각형을 JPEG(0.9)로 다시 만든다. 긴 변 4,000px 초과는 줄이고, 5 MiB 초과면 품질→크기 순으로 낮춘다 */
async function renderRedacted(bitmap: ImageBitmap, rects: Rect[]): Promise<Blob> {
  let scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height));
  for (let attempt = 0; attempt < 6; attempt++) {
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("canvas");
    ctx.fillStyle = "#fff"; // 투명 PNG가 검게 나오지 않게
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "#000";
    const sx = canvas.width / bitmap.width;
    const sy = canvas.height / bitmap.height;
    for (const r of rects) {
      // 가장자리가 반올림으로 비지 않게 바깥으로 내림/올림
      const x = Math.floor(r.x * sx);
      const y = Math.floor(r.y * sy);
      ctx.fillRect(x, y, Math.ceil((r.x + r.w) * sx) - x, Math.ceil((r.y + r.h) * sy) - y);
    }
    for (const q of [0.9, 0.8, 0.7, 0.6, 0.5]) {
      const blob = await toBlob(canvas, q);
      if (!blob) throw new Error("toBlob");
      if (blob.size <= MAX_BYTES) return blob;
    }
    scale *= 0.75;
  }
  throw new Error("too-large");
}

interface Props {
  file: File;
  /** 가린 JPEG를 올린다. 실패하면 사용자에게 보일 문구를 담은 Error를 throw한다 */
  onSend: (blob: Blob, signal: AbortSignal, contentKey: string) => Promise<void>;
  onClose: () => void;
}

const PREVIEW_MAX_SIDE = 1600;
const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), [tabindex]:not([tabindex="-1"])';

export default function ChatImageRedactDialog({ file, onSend, onClose }: Props) {
  const [bitmap, setBitmap] = useState<ImageBitmap | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [rects, setRects] = useState<Rect[]>([]);
  const [draft, setDraft] = useState<Rect | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [cancelWarn, setCancelWarn] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const startRef = useRef<{ x: number; y: number } | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const cancelRef = useRef<() => void>(() => {});
  useEffect(() => {
    let cancelled = false;
    // 미리보기와 칠하기가 같은 bitmap을 쓴다. EXIF 회전을 bitmap 단계에서 확정해(from-image) 브라우저마다 다르지 않게 한다
    createImageBitmap(file, { imageOrientation: "from-image" })
      .then((b) => {
        if (cancelled) b.close();
        else setBitmap(b);
      })
      .catch(() => {
        if (!cancelled) setLoadError("사진을 열 수 없어요. 다른 사진을 골라 주세요.");
      });
    return () => {
      cancelled = true;
    };
  }, [file]);

  useEffect(() => () => bitmap?.close(), [bitmap]);

  // 미리보기: 칠하기에 쓰는 같은 bitmap을 canvas에 그린다 (화면에 보이는 것 = 보내는 것)
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !bitmap) return;
    const s = Math.min(1, PREVIEW_MAX_SIDE / Math.max(bitmap.width, bitmap.height));
    canvas.width = Math.max(1, Math.round(bitmap.width * s));
    canvas.height = Math.max(1, Math.round(bitmap.height * s));
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    ctx.fillStyle = "#fff";
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  }, [bitmap]);

  cancelRef.current = () => {
    // 올리는 중에 닫으면 이미 서버에 도착한 사진이 보내질 수 있어 한 번 더 확인한다
    if (busy && !cancelWarn) {
      setCancelWarn(true);
      return;
    }
    abortRef.current?.abort();
    onClose();
  };

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    titleRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.stopPropagation();
        cancelRef.current();
        return;
      }
      if (e.key !== "Tab") return;
      const root = dialogRef.current;
      if (!root) return;
      const items = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (items.length === 0) {
        e.preventDefault();
        return;
      }
      const first = items[0];
      const last = items[items.length - 1];
      const active = document.activeElement;
      if (!root.contains(active) || active === titleRef.current) {
        e.preventDefault();
        (e.shiftKey ? last : first).focus();
      } else if (e.shiftKey && active === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && active === last) {
        e.preventDefault();
        first.focus();
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      if (opener && opener.isConnected) opener.focus?.();
    };
  }, []);

  const point = (e: PointerEvent) => {
    const box = boxRef.current;
    if (!box || !bitmap) return null;
    return toImagePoint(e.clientX, e.clientY, box.getBoundingClientRect(), bitmap.width, bitmap.height);
  };

  const onDown = (e: PointerEvent) => {
    if (busy || !bitmap) return;
    const p = point(e);
    if (!p) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    startRef.current = p;
    setDraft({ x: p.x, y: p.y, w: 0, h: 0 });
  };
  const onMove = (e: PointerEvent) => {
    const s = startRef.current;
    const p = point(e);
    if (!s || !p) return;
    setDraft({ x: Math.min(s.x, p.x), y: Math.min(s.y, p.y), w: Math.abs(p.x - s.x), h: Math.abs(p.y - s.y) });
  };
  const onUp = () => {
    startRef.current = null;
    if (draft && bitmap) {
      // 화면에서 4px보다 작은 사각형은 실수로 보고 버린다 (원본 픽셀로 환산)
      const box = boxRef.current?.getBoundingClientRect();
      const minPx = box ? (MIN_RECT_PX / box.width) * bitmap.width : MIN_RECT_PX;
      if (draft.w >= minPx && draft.h >= minPx) setRects((prev) => [...prev, draft]);
    }
    setDraft(null);
  };

  const send = async () => {
    if (!bitmap || busy) return;
    setBusy(true);
    setError(null);
    setCancelWarn(false);
    const controller = new AbortController();
    abortRef.current = controller;
    // 사진·가림 사각형이 같으면 같은 키 → 재시도는 같은 clientMessageId(서버 멱등), 바뀌면 새 id
    const contentKey = JSON.stringify(rects.map((r) => [Math.round(r.x), Math.round(r.y), Math.round(r.w), Math.round(r.h)]));
    try {
      const blob = await renderRedacted(bitmap, rects);
      await onSend(blob, controller.signal, contentKey);
    } catch (err) {
      if (controller.signal.aborted) return;
      const code = err instanceof Error ? err.message : "";
      setError(
        code === "too-large"
          ? "사진 용량이 너무 커서 줄이지 못했어요. 다른 사진을 골라 주세요."
          : code === "canvas" || code === "toBlob"
            ? "사진을 처리하지 못했어요. 다른 사진을 골라 주세요."
            : code || "보내지 못했어요."
      );
      setBusy(false);
    }
  };

  const shown = draft ? [...rects, draft] : rects;
  const pct = (v: number, total: number) => `${(v / total) * 100}%`;

  return (
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/50 sm:items-center" role="presentation">
      <div ref={dialogRef} role="dialog" aria-modal="true" aria-labelledby="redact-title" className="flex max-h-[95dvh] w-full max-w-lg flex-col gap-3 overflow-y-auto rounded-t-[24px] bg-white p-5 pb-[calc(20px+env(safe-area-inset-bottom))] shadow-[0_-8px_40px_-12px_rgba(17,24,39,0.35)] sm:rounded-[24px]">
        <h2 id="redact-title" ref={titleRef} tabIndex={-1} className="text-base font-semibold text-gray-900 outline-none">
          사진 보내기
        </h2>
        <p className={ui.hint}>이름·연락처·결제 정보·예매번호 일부는 가려 주세요. 공연명·회차·구역·열·번은 보이게 해 주세요.</p>
        <p className={ui.hint}>
          사진 위를 손가락이나 마우스로 끌어서 검은 사각형으로 가릴 수 있어요. 키보드만 쓰는 경우 사진을 미리 편집해 올려 주세요.
        </p>

        {loadError && (
          <p className={ui.errorBox} role="alert">
            {loadError}
          </p>
        )}
        {!loadError && (
          <div className="flex justify-center rounded-2xl bg-gray-100 p-1.5">
            <div
              ref={boxRef}
              className="relative max-w-full touch-none select-none"
              style={bitmap ? { aspectRatio: `${bitmap.width} / ${bitmap.height}`, width: "100%", maxWidth: `calc(50dvh * ${bitmap.width / bitmap.height})` } : { width: "100%", height: 160 }}
              onPointerDown={onDown}
              onPointerMove={onMove}
              onPointerUp={onUp}
              onPointerCancel={onUp}
            >
              <canvas ref={canvasRef} role="img" aria-label="보낼 사진 미리보기" className="pointer-events-none block h-full w-full" />
              {bitmap &&
                shown.map((r, i) => (
                  <div key={i} aria-hidden="true" className="pointer-events-none absolute bg-black" style={{ left: pct(r.x, bitmap.width), top: pct(r.y, bitmap.height), width: pct(r.w, bitmap.width), height: pct(r.h, bitmap.height) }} />
                ))}
            </div>
          </div>
        )}

        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className={button.outline} disabled={busy || rects.length === 0} onClick={() => setRects((prev) => prev.slice(0, -1))}>
            되돌리기
          </button>
          <button type="button" className={button.outline} disabled={busy || rects.length === 0} onClick={() => setRects([])}>
            모두 지우기
          </button>
          <span className={ui.hint} role="status">
            가린 영역 {rects.length}개
          </span>
        </div>

        {busy && (
          <p className={ui.status} role="status">
            사진을 보내는 중...
          </p>
        )}
        {error && (
          <p className={ui.errorBox} role="alert">
            {error}
          </p>
        )}

        {cancelWarn && (
          <p className={ui.warning} role="alert">
            이미 서버에 도착했으면 사진이 보내질 수 있어요. 그래도 취소할까요?
          </p>
        )}

        <div className="flex justify-end gap-2">
          <button type="button" className={`${button.outline} ${FOCUS_RING}`} onClick={() => cancelRef.current()}>
            {cancelWarn ? "그래도 취소" : "취소"}
          </button>
          <button type="button" className={button.solid} disabled={busy || !bitmap} aria-busy={busy} onClick={() => void send()}>
            {error ? "다시 보내기" : "보내기"}
          </button>
        </div>
      </div>
    </div>
  );
}
