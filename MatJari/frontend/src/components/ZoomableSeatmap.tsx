import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from "react";
import {
  FULL_VIEW,
  ZOOM_BUTTON_FACTOR,
  ZOOM_DOUBLE,
  ZOOM_MAX,
  ZOOM_MIN,
  boxVisible,
  clampView,
  clientToSvg,
  fitBox,
  panBy,
  pinchScale,
  zoomAt,
  type Box,
  type ZoomView,
} from "../api/zoomMath";
import { FOCUS_RING, button, ui } from "./ui";

// 좌석표 미리보기 SVG 확대·축소·이동. 보이는 영역은 SVG viewBox만 바꾸므로 벡터라 글자가 흐려지지 않는다.
// 버튼(+ − 맞춤)·키보드(+ − 0, 확대 중 방향키)·두 손가락 핀치·한 손가락/마우스 드래그·Ctrl+휠·더블탭(더블클릭).
// 배율 1배에서는 touch-action: pan-y라 한 손가락 세로 스크롤이 페이지 스크롤로 이어진다.

interface Props {
  /** 전체 viewBox 크기 */
  width: number;
  height: number;
  /** 스크린리더용 설명(svg role="img") */
  label: string;
  /** 이 상자가 보이도록 자동 확대·이동한다(focusKey가 바뀔 때만) */
  focusBox?: Box | null;
  focusKey?: unknown;
  children: ReactNode;
}

const DOUBLE_TAP_MS = 320;
const TAP_SLOP_PX = 10;
const toolBtn = `${button.outline} !px-0 min-w-11 aria-disabled:cursor-not-allowed aria-disabled:opacity-60`;

export default function ZoomableSeatmap({ width: W, height: H, label, focusBox, focusKey, children }: Props) {
  const [view, setView] = useState<ZoomView>(FULL_VIEW);
  const viewRef = useRef<ZoomView>(FULL_VIEW);
  const boxRef = useRef<HTMLDivElement>(null);
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const pinch = useRef<{ dist: number; mx: number; my: number } | null>(null);
  const tap = useRef<{ x: number; y: number; moved: boolean } | null>(null);
  const lastTap = useRef<{ t: number; x: number; y: number } | null>(null);
  const [announce, setAnnounce] = useState("");

  const apply = useCallback(
    (next: ZoomView) => {
      const v = clampView(next, W, H);
      viewRef.current = v;
      setView(v);
    },
    [W, H]
  );

  // 도형 크기가 바뀌면(다른 비율) 처음 상태로
  useEffect(() => {
    apply(FULL_VIEW);
  }, [apply]);

  // 배율 낭독은 조작이 멈춘 뒤 한 번만
  const firstAnnounce = useRef(true);
  useEffect(() => {
    if (firstAnnounce.current) {
      firstAnnounce.current = false;
      return;
    }
    const t = window.setTimeout(() => setAnnounce(`좌석표 배율 ${Math.round(view.scale * 100)}%`), 400);
    return () => window.clearTimeout(t);
  }, [view.scale]);

  const rect = () => boxRef.current?.getBoundingClientRect();

  /** 화면 중심의 SVG 좌표 기준으로 배율 변경 */
  const zoomCenter = useCallback(
    (nextScale: number) => {
      const v = viewRef.current;
      apply(zoomAt(v, nextScale, v.x + W / v.scale / 2, v.y + H / v.scale / 2, W, H));
    },
    [apply, W, H]
  );

  const zoomIn = () => zoomCenter(viewRef.current.scale * ZOOM_BUTTON_FACTOR);
  const zoomOut = () => zoomCenter(viewRef.current.scale / ZOOM_BUTTON_FACTOR);
  const reset = () => apply(FULL_VIEW);

  // 목록의 '위치 보기'로 구역을 고르면 그 구역이 보이게 한다 (이미 다 보이면 그대로 둔다)
  const focusBoxRef = useRef<Box | null>(null);
  focusBoxRef.current = focusBox ?? null;
  useEffect(() => {
    const b = focusBoxRef.current;
    if (!b) return;
    if (!boxVisible(viewRef.current, b, W, H)) apply(fitBox(b, W, H));
  }, [focusKey, apply, W, H]);

  // Ctrl+휠(트랙패드 핀치 포함)만 확대·축소. 일반 휠은 페이지 스크롤을 막지 않는다 — 막으려면 passive:false 리스너가 필요하다
  useEffect(() => {
    const el = boxRef.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      const r = el.getBoundingClientRect();
      const v = viewRef.current;
      const a = clientToSvg(e.clientX - r.left, e.clientY - r.top, r.width, r.height, v, W, H);
      apply(zoomAt(v, v.scale * Math.exp(-e.deltaY * 0.01), a.x, a.y, W, H));
    };
    el.addEventListener("wheel", onWheel, { passive: false });
    return () => el.removeEventListener("wheel", onWheel);
  }, [apply, W, H]);

  const toggleDouble = (clientX: number, clientY: number) => {
    const r = rect();
    const v = viewRef.current;
    if (v.scale > 1.01) return apply(FULL_VIEW);
    if (!r) return apply({ ...v, scale: ZOOM_DOUBLE });
    const a = clientToSvg(clientX - r.left, clientY - r.top, r.width, r.height, v, W, H);
    apply(zoomAt(v, ZOOM_DOUBLE, a.x, a.y, W, H));
  };

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (e.pointerType === "mouse" && e.button !== 0) return;
    try {
      e.currentTarget.setPointerCapture(e.pointerId);
    } catch {
      /* 이미 끝난 포인터 */
    }
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (pointers.current.size === 1) {
      tap.current = { x: e.clientX, y: e.clientY, moved: false };
    } else {
      if (tap.current) tap.current.moved = true;
      const [p, q] = [...pointers.current.values()];
      pinch.current = { dist: Math.hypot(p.x - q.x, p.y - q.y), mx: (p.x + q.x) / 2, my: (p.y + q.y) / 2 };
    }
  };

  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    const prev = pointers.current.get(e.pointerId);
    const r = rect();
    if (!prev || !r) return;
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const v = viewRef.current;

    if (pointers.current.size >= 2 && pinch.current) {
      const [p, q] = [...pointers.current.values()];
      const dist = Math.hypot(p.x - q.x, p.y - q.y);
      const mx = (p.x + q.x) / 2;
      const my = (p.y + q.y) / 2;
      // 이전 중심점의 SVG 좌표를 고정한 채 배율을 바꾸고, 중심점이 움직인 만큼 끈다
      const a = clientToSvg(pinch.current.mx - r.left, pinch.current.my - r.top, r.width, r.height, v, W, H);
      const zoomed = zoomAt(v, pinchScale(v.scale, pinch.current.dist, dist), a.x, a.y, W, H);
      const ppu = clientToSvg(0, 0, r.width, r.height, zoomed, W, H).pxPerUnit;
      apply(panBy(zoomed, mx - pinch.current.mx, my - pinch.current.my, ppu, W, H));
      pinch.current = { dist, mx, my };
      return;
    }

    const dx = e.clientX - prev.x;
    const dy = e.clientY - prev.y;
    if (tap.current && Math.hypot(e.clientX - tap.current.x, e.clientY - tap.current.y) > TAP_SLOP_PX) tap.current.moved = true;
    if (v.scale > 1) {
      const ppu = clientToSvg(0, 0, r.width, r.height, v, W, H).pxPerUnit;
      apply(panBy(v, dx, dy, ppu, W, H));
    }
  };

  const endPointer = (e: PointerEvent<HTMLDivElement>, canceled: boolean) => {
    if (!pointers.current.delete(e.pointerId)) return;
    if (pointers.current.size < 2) pinch.current = null;
    if (pointers.current.size > 0) return;
    const t = tap.current;
    tap.current = null;
    // 마우스는 onDoubleClick, 손가락·펜은 연속 두 번의 짧은 탭으로 맞춤↔2.5배
    if (canceled || !t || t.moved || e.pointerType === "mouse") {
      lastTap.current = null;
      return;
    }
    const now = Date.now();
    const lt = lastTap.current;
    if (lt && now - lt.t < DOUBLE_TAP_MS && Math.hypot(e.clientX - lt.x, e.clientY - lt.y) < 40) {
      lastTap.current = null;
      toggleDouble(e.clientX, e.clientY);
    } else {
      lastTap.current = { t: now, x: e.clientX, y: e.clientY };
    }
  };

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return;
    const v = viewRef.current;
    const step = 0.15;
    switch (e.key) {
      case "+":
      case "=":
        zoomIn();
        break;
      case "-":
      case "_":
        zoomOut();
        break;
      case "0":
        reset();
        break;
      case "ArrowLeft":
      case "ArrowRight":
      case "ArrowUp":
      case "ArrowDown": {
        if (v.scale <= 1 || e.target !== e.currentTarget.querySelector("[data-seatmap-viewport]")) return;
        const dx = (e.key === "ArrowLeft" ? -1 : e.key === "ArrowRight" ? 1 : 0) * (W / v.scale) * step;
        const dy = (e.key === "ArrowUp" ? -1 : e.key === "ArrowDown" ? 1 : 0) * (H / v.scale) * step;
        apply({ ...v, x: v.x + dx, y: v.y + dy });
        break;
      }
      default:
        return;
    }
    e.preventDefault();
  };

  const zoomed = view.scale > 1;
  const atMax = view.scale >= ZOOM_MAX - 0.001;
  const atMin = view.scale <= ZOOM_MIN + 0.001;

  return (
    <div className="flex flex-col gap-1.5" onKeyDown={onKeyDown}>
      <div className="flex flex-wrap items-center gap-2" role="group" aria-label="좌석표 확대·축소">
        <button type="button" className={toolBtn} onClick={zoomOut} aria-label="좌석표 축소" aria-disabled={atMin || undefined}>
          <span aria-hidden="true" className="text-lg">
            &minus;
          </span>
        </button>
        <span className={`${ui.body} min-w-12 text-center tabular-nums`} aria-hidden="true">
          {Math.round(view.scale * 100)}%
        </span>
        <button type="button" className={toolBtn} onClick={zoomIn} aria-label="좌석표 확대" aria-disabled={atMax || undefined}>
          <span aria-hidden="true" className="text-lg">
            +
          </span>
        </button>
        <button
          type="button"
          className={`${button.outline} aria-disabled:cursor-not-allowed aria-disabled:opacity-60`}
          onClick={reset}
          aria-label="좌석표 맞춤 (원래 크기)"
          aria-disabled={atMin && view.x === 0 && view.y === 0 ? true : undefined}
        >
          맞춤
        </button>
      </div>
      <div
        ref={boxRef}
        data-seatmap-viewport=""
        tabIndex={0}
        role="group"
        aria-label="좌석표 미리보기. 더하기·빼기 키로 확대·축소, 0 키로 맞춤, 확대 중에는 방향키로 이동해요."
        className={`relative w-full max-h-[70vh] overflow-hidden rounded-[10px] border border-gray-300 bg-gray-50 select-none ${FOCUS_RING}`}
        style={{ aspectRatio: `${W} / ${H}`, touchAction: zoomed ? "none" : "pan-y", cursor: zoomed ? "grab" : undefined }}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={(e) => endPointer(e, false)}
        onPointerCancel={(e) => endPointer(e, true)}
        onDoubleClick={(e) => toggleDouble(e.clientX, e.clientY)}
      >
        <svg
          viewBox={`${view.x} ${view.y} ${W / view.scale} ${H / view.scale}`}
          role="img"
          aria-label={label}
          className="block size-full"
          preserveAspectRatio="xMidYMid meet"
        >
          {children}
        </svg>
      </div>
      <p className="sr-only" aria-live="polite">
        {announce}
      </p>
    </div>
  );
}
