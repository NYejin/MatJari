// 좌석표 미리보기 확대·축소 — 화면과 분리한 순수 계산.
// 보이는 영역(view)은 SVG 좌표의 사각형 {x, y, scale}이고 폭 W/scale, 높이 H/scale이다(W·H는 전체 viewBox).

export const ZOOM_MIN = 1;
export const ZOOM_MAX = 6;
export const ZOOM_BUTTON_FACTOR = 1.25;
export const ZOOM_DOUBLE = 2.5;

export type ZoomView = { x: number; y: number; scale: number };
export type Box = { minX: number; minY: number; maxX: number; maxY: number };

export const clampScale = (s: number) => (Number.isFinite(s) ? Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, s)) : ZOOM_MIN);

/** 이동 범위 제한: 보이는 영역이 전체 그림 밖으로 벗어나지 않게 한다 */
export function clampView(v: ZoomView, W: number, H: number): ZoomView {
  const scale = clampScale(v.scale);
  const maxX = W - W / scale;
  const maxY = H - H / scale;
  return {
    scale,
    x: Math.min(maxX, Math.max(0, Number.isFinite(v.x) ? v.x : 0)),
    y: Math.min(maxY, Math.max(0, Number.isFinite(v.y) ? v.y : 0)),
  };
}

export const FULL_VIEW: ZoomView = { x: 0, y: 0, scale: 1 };

/** (ax, ay) SVG 점을 화면에서 제자리에 둔 채 배율을 바꾼다 */
export function zoomAt(v: ZoomView, nextScale: number, ax: number, ay: number, W: number, H: number): ZoomView {
  const ns = clampScale(nextScale);
  const fx = (ax - v.x) / (W / v.scale);
  const fy = (ay - v.y) / (H / v.scale);
  return clampView({ scale: ns, x: ax - fx * (W / ns), y: ay - fy * (H / ns) }, W, H);
}

/** 컨테이너 안 화면 좌표(px, 컨테이너 왼쪽 위 기준) → SVG 좌표. preserveAspectRatio="xMidYMid meet" 기준 */
export function clientToSvg(px: number, py: number, rectW: number, rectH: number, v: ZoomView, W: number, H: number) {
  const w = W / v.scale;
  const h = H / v.scale;
  const s = Math.min(rectW / w, rectH / h) || 1;
  const offX = (rectW - w * s) / 2;
  const offY = (rectH - h * s) / 2;
  return { x: v.x + (px - offX) / s, y: v.y + (py - offY) / s, pxPerUnit: s };
}

/** 화면 px 이동량만큼 그림을 끈다(손가락이 오른쪽으로 가면 그림도 오른쪽으로 → view.x 감소) */
export function panBy(v: ZoomView, dxPx: number, dyPx: number, pxPerUnit: number, W: number, H: number): ZoomView {
  if (!(pxPerUnit > 0)) return v;
  return clampView({ ...v, x: v.x - dxPx / pxPerUnit, y: v.y - dyPx / pxPerUnit }, W, H);
}

/** 핀치: 이전·현재 두 손가락 거리 비율로 배율을 바꾼다(중심점 기준은 호출 쪽이 anchor로 넘긴다) */
export function pinchScale(scale: number, prevDist: number, dist: number): number {
  if (!(prevDist > 0) || !(dist > 0)) return scale;
  return clampScale(scale * (dist / prevDist));
}

/** 다각형 조각들의 경계 상자. 유효한 점이 없으면 null */
export function polygonsBox(polygons: number[][][] | null): Box | null {
  if (!polygons) return null;
  let b: Box | null = null;
  for (const piece of polygons) {
    for (const p of piece ?? []) {
      if (!Array.isArray(p) || !Number.isFinite(p[0]) || !Number.isFinite(p[1])) continue;
      b = b
        ? { minX: Math.min(b.minX, p[0]), minY: Math.min(b.minY, p[1]), maxX: Math.max(b.maxX, p[0]), maxY: Math.max(b.maxY, p[1]) }
        : { minX: p[0], minY: p[1], maxX: p[0], maxY: p[1] };
    }
  }
  return b;
}

/** 상자가 지금 보이는 영역 안에 완전히 들어오는가 */
export function boxVisible(v: ZoomView, box: Box, W: number, H: number): boolean {
  return box.minX >= v.x && box.minY >= v.y && box.maxX <= v.x + W / v.scale && box.maxY <= v.y + H / v.scale;
}

/** 상자가 영역의 약 60%를 차지하도록 확대하고 가운데로 옮긴다(최대 배율 제한) */
export function fitBox(box: Box, W: number, H: number): ZoomView {
  const bw = Math.max(box.maxX - box.minX, 1);
  const bh = Math.max(box.maxY - box.minY, 1);
  const scale = clampScale(Math.min(W / (bw / 0.6), H / (bh / 0.6)));
  const cx = (box.minX + box.maxX) / 2;
  const cy = (box.minY + box.maxY) / 2;
  return clampView({ scale, x: cx - W / scale / 2, y: cy - H / scale / 2 }, W, H);
}
