import type { PerformanceZone } from "../types/performance";

// 공연 구역(이름 + 모양) 정리와 AI가 읽은 회차 병합 — 화면과 분리한 순수 함수.

export const ZONE_NAME_MAX = 30;
export const ZONES_MAX = 100;

/** 구역 한 줄 (편집 중). id는 목록 키용 */
export type ZoneRow = { id: number; name: string; shapePolygons: number[][][] | null };

let zoneRowSeq = 0;
export const newZoneRowId = () => ++zoneRowSeq;

export function toZoneRows(zones: PerformanceZone[]): ZoneRow[] {
  return zones.map((z) => ({ id: newZoneRowId(), name: z.name, shapePolygons: z.shapePolygons ?? null }));
}

/** 한 줄의 이름 오류 문구(없으면 undefined). 같은 이름은 정확히 같은 문자열만 중복으로 본다 */
export function zoneNameError(name: string, others: string[]): string | undefined {
  const t = name.trim();
  if (!t) return "구역 이름을 입력해주세요.";
  if (t.length > ZONE_NAME_MAX) return `구역 이름은 ${ZONE_NAME_MAX}자 이하로 입력해주세요.`;
  if (others.some((o) => o.trim() === t)) return "같은 이름의 구역이 이미 있어요.";
  return undefined;
}

/** 줄 id → 오류 문구 (오류가 있는 줄만) */
export function validateZoneRows(rows: ZoneRow[]): Record<number, string> {
  const out: Record<number, string> = {};
  for (const r of rows) {
    const err = zoneNameError(
      r.name,
      rows.filter((o) => o.id !== r.id).map((o) => o.name)
    );
    if (err) out[r.id] = err;
  }
  return out;
}

/** 등록 요청에 담을 구역. 이름은 앞뒤 공백을 정리하고 모양은 그대로 둔다 */
export function toPayloadZones(rows: ZoneRow[]): PerformanceZone[] {
  return rows.map((r) => ({ name: r.name.trim(), shapePolygons: r.shapePolygons }));
}

/**
 * AI가 읽은 구역을 기존 줄에 합친다. 이미 있는 이름은 건드리지 않되 모양이 없던 줄에는 모양을 채운다.
 * 상한(100개)을 넘는 새 구역은 버리고 개수를 돌려준다.
 */
export function mergeAiZones(current: ZoneRow[], incoming: PerformanceZone[]): { rows: ZoneRow[]; added: number; dropped: number } {
  const rows = current.map((r) => ({ ...r }));
  let added = 0;
  let dropped = 0;
  for (const z of incoming) {
    const name = z.name.trim();
    if (!name) continue;
    const same = rows.find((r) => r.name.trim() === name);
    if (same) {
      if (!same.shapePolygons && z.shapePolygons) same.shapePolygons = z.shapePolygons;
      continue;
    }
    if (rows.length >= ZONES_MAX) {
      dropped += 1;
      continue;
    }
    rows.push({ id: newZoneRowId(), name, shapePolygons: z.shapePolygons ?? null });
    added += 1;
  }
  return { rows, added, dropped };
}

/** 모양이 있는 줄이 하나라도 있는가 */
export const hasAnyShape = (rows: ZoneRow[]) => rows.some((r) => r.shapePolygons && r.shapePolygons.length > 0);

/** 미리보기 viewBox 높이. 가로를 1000으로 보고 H = 1000 / aspect (이상한 값은 정사각형, 극단값은 제한) */
export function previewHeight(aspect: number | null | undefined): number {
  if (typeof aspect !== "number" || !Number.isFinite(aspect) || aspect <= 0) return 1000;
  return Math.min(3000, Math.max(250, 1000 / aspect));
}

/** 다각형 조각들을 SVG points 문자열로. 꼭짓점이 3개 미만이거나 숫자가 아닌 조각은 건너뛴다 */
/** sy: 세로 환산 배율(= 미리보기 높이 / 1000). 좌표는 가로·세로를 각각 1000으로 정규화한 값이라 세로는 sy 를 곱해 viewBox 에 맞춘다 */
export function polygonPoints(polygons: number[][][] | null, sy = 1): string[] {
  if (!polygons) return [];
  const out: string[] = [];
  for (const piece of polygons) {
    if (!Array.isArray(piece) || piece.length < 3) continue;
    const pts = piece.filter((p) => Array.isArray(p) && p.length >= 2 && Number.isFinite(p[0]) && Number.isFinite(p[1]));
    if (pts.length < 3) continue;
    out.push(pts.map((p) => `${p[0]},${p[1] * sy}`).join(" "));
  }
  return out;
}

/** 가장 큰(경계 상자 기준) 조각의 중심 — 이름 라벨 위치. 조각이 없으면 null */
export function labelAnchor(polygons: number[][][] | null, sy = 1): { x: number; y: number } | null {
  if (!polygons) return null;
  let best: { x: number; y: number; area: number } | null = null;
  for (const piece of polygons) {
    const pts = (piece ?? []).filter((p) => Array.isArray(p) && Number.isFinite(p[0]) && Number.isFinite(p[1]));
    if (pts.length < 3) continue;
    const xs = pts.map((p) => p[0]);
    const ys = pts.map((p) => p[1]);
    const minX = Math.min(...xs);
    const maxX = Math.max(...xs);
    const minY = Math.min(...ys);
    const maxY = Math.max(...ys);
    const area = (maxX - minX) * (maxY - minY);
    if (!best || area > best.area) best = { x: (minX + maxX) / 2, y: (minY + maxY) / 2, area };
  }
  return best ? { x: best.x, y: best.y * sy } : null;
}

// ---- 회차 병합 ----

/** 회차 한 줄 값: 시각까지 있으면 "yyyy-MM-ddTHH:mm", 날짜만이면 "yyyy-MM-dd" */
export type SessionRowLike = { id: number; value: string };

export interface MergeSessionsResult<T extends SessionRowLike> {
  rows: T[];
  /** 날짜는 있고 시각이 비어 있던 줄에 시각만 채운 개수 */
  filled: number;
  /** 새 줄로 추가한 개수 */
  added: number;
  /** 이미 있거나(같은 일시) 받아들일 수 없어(지난 시각·10분 단위 아님·상한 초과) 건너뛴 개수 */
  skipped: number;
}

/**
 * AI·본문 규칙이 읽은 회차를 기존 줄에 합친다. 사용자가 입력한 줄은 덮어쓰지 않는다.
 * - 같은 일시가 이미 있으면 건너뛴다.
 * - 같은 날짜에 시각이 비어 있는 줄이 있으면 그 줄에 시각만 채운다(줄마다 한 번).
 * - 그 외에는 새 줄을 추가한다(하루 두 회차도 새 줄). 상한을 넘으면 건너뛴다.
 * accept(value): 지난 시각·10분 단위 같은 호출 쪽 규칙. 거절하면 건너뛴다.
 */
export function mergeAiSessions<T extends SessionRowLike>(
  current: T[],
  incoming: { date: string; time: string }[],
  options: { max: number; accept: (value: string) => boolean; makeRow: (value: string) => T }
): MergeSessionsResult<T> {
  const rows = current.map((r) => ({ ...r }));
  const seen = new Set(rows.map((r) => r.value));
  let filled = 0;
  let added = 0;
  let skipped = 0;
  for (const s of incoming) {
    const date = typeof s.date === "string" ? s.date.slice(0, 10) : "";
    const time = typeof s.time === "string" ? s.time.slice(0, 5) : "";
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{2}:\d{2}$/.test(time)) {
      skipped += 1;
      continue;
    }
    const value = `${date}T${time}`;
    if (seen.has(value) || !options.accept(value)) {
      skipped += 1;
      continue;
    }
    const blank = rows.find((r) => r.value === date);
    if (blank) {
      blank.value = value;
      seen.add(value);
      filled += 1;
      continue;
    }
    if (rows.length >= options.max) {
      skipped += 1;
      continue;
    }
    const row = options.makeRow(value);
    rows.push(row);
    seen.add(value);
    added += 1;
  }
  return { rows, filled, added, skipped };
}
