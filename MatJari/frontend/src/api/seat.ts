// 텍스트 좌석 입력 도우미 (좌석표 없음): 표기, 희망 범위 입력 검증, '총 N석' 미리보기 계산.
// 서버(SeatKeyNormalizer)가 최종 판정한다 — 여기 규칙은 같은 뜻으로 미리 안내하는 용도다.
// 정규화: NFKC, 공백 제거, 영문 대문자, 열은 끝의 '열'·번은 끝의 '번' 제거, 숫자는 앞 0 제거(1~999).

export const MAX_SEAT_NUMBER = 999;
export const ZONE_MAX = 50;
export const ROW_COL_MAX = 20;
/** 서버 안전 상한(DoS 방어용, application.yml exchange.want.*) — 서버 설정이 다르면 422 count/limit이 우선한다 */
export const WANT_MAX_SEATS = 5000;
export const WANT_MAX_RANGES = 50;

type Unit = "열" | "번";

/** 화면 표기: 이미 '열'·'번'이 붙은 입력이면 중복해서 붙이지 않는다 */
function withSuffix(label: string, suffix: Unit): string {
  return label.endsWith(suffix) ? label : `${label}${suffix}`;
}

/** "구역 · N열 M번" */
export function formatSeat(seat: { zone: string; row: string; col: string }): string {
  return `${seat.zone} · ${withSuffix(seat.row, "열")} ${withSuffix(seat.col, "번")}`;
}

/** 비교용 정규화 (구역은 접미사를 지우지 않는다) */
function normalizeBase(value: string): string {
  return value.normalize("NFKC").replace(/\s+/g, "").toUpperCase();
}

export function normalizeZone(value: string): string {
  return normalizeBase(value);
}

function normalizeUnit(value: string, unit: Unit): string {
  const v = normalizeBase(value);
  return v.endsWith(unit) && v.length > unit.length ? v.slice(0, -unit.length) : v;
}

type Token = { kind: "num"; n: number } | { kind: "key"; key: string };

const SIGNED = /^[+\-−]\d+$/;

function toToken(normalized: string): Token {
  if (/^\d+$/.test(normalized)) return { kind: "num", n: Number(normalized) };
  return { kind: "key", key: normalized };
}

export interface RangeInput {
  zone: string;
  rowFrom: string;
  rowTo: string;
  colFrom: string;
  colTo: string;
}

export type RangeField = keyof RangeInput;
export type RangeErrors = Partial<Record<RangeField, string>>;

/** 끝 칸이 비어 있으면 시작과 같은 값(한 칸)으로 본다 */
export function effectiveTo(from: string, to: string): string {
  return to.trim() ? to : from;
}

interface Dim {
  from: Token;
  to: Token;
}

/** 열 또는 번 한 축 검증. 오류는 칸 이름과 함께 errors에 넣는다 */
function validateAxis(
  fromRaw: string,
  toRaw: string,
  unit: Unit,
  fromKey: RangeField,
  toKey: RangeField,
  errors: RangeErrors
): Dim | null {
  const from = normalizeUnit(fromRaw, unit);
  const to = normalizeUnit(effectiveTo(fromRaw, toRaw), unit);
  if (!from) {
    errors[fromKey] = `${unit} 시작을 입력해주세요.`;
    return null;
  }
  if (SIGNED.test(from)) {
    errors[fromKey] = `${unit}은 부호 없는 숫자(1 이상)로 입력해주세요.`;
    return null;
  }
  if (SIGNED.test(to)) {
    errors[toKey] = `${unit}은 부호 없는 숫자(1 이상)로 입력해주세요.`;
    return null;
  }
  const a = toToken(from);
  const b = toToken(to);
  if (a.kind === "num" && b.kind === "num") {
    if (a.n < 1 || a.n > MAX_SEAT_NUMBER) {
      errors[fromKey] = `${unit}은 1~${MAX_SEAT_NUMBER} 사이 숫자로 입력해주세요.`;
      return null;
    }
    if (b.n < 1 || b.n > MAX_SEAT_NUMBER) {
      errors[toKey] = `${unit}은 1~${MAX_SEAT_NUMBER} 사이 숫자로 입력해주세요.`;
      return null;
    }
    if (a.n > b.n) {
      errors[toKey] = `${unit} 끝은 시작보다 작을 수 없어요.`;
      return null;
    }
    return { from: a, to: b };
  }
  if (a.kind === "key" && b.kind === "key") {
    if (a.key.length > ROW_COL_MAX) {
      errors[fromKey] = `${unit}은 ${ROW_COL_MAX}자 이하로 입력해주세요.`;
      return null;
    }
    if (a.key !== b.key) {
      errors[toKey] = `문자 ${unit}은 범위로 입력할 수 없어요. 하나씩 따로 추가해주세요.`;
      return null;
    }
    return { from: a, to: b };
  }
  // 숫자와 문자가 섞임
  errors[toKey] = `숫자와 문자를 섞어 범위로 입력할 수 없어요. 숫자 ${unit}만 범위(예: 3~5)로 입력할 수 있어요.`;
  return null;
}

/** 범위 카드 1장 검증. 오류가 없으면 빈 객체 */
export function validateRange(r: RangeInput): RangeErrors {
  const errors: RangeErrors = {};
  const zone = r.zone.trim();
  if (!zone) errors.zone = "구역을 입력해주세요.";
  else if (zone.length > ZONE_MAX) errors.zone = `구역은 ${ZONE_MAX}자 이하로 입력해주세요.`;
  validateAxis(r.rowFrom, r.rowTo, "열", "rowFrom", "rowTo", errors);
  validateAxis(r.colFrom, r.colTo, "번", "colFrom", "colTo", errors);
  return errors;
}

/** 전송용: 공백 정리, 끝 칸이 비어 있으면 시작 값으로 채운다 */
export function toPayloadRange(r: RangeInput): RangeInput {
  const rowFrom = r.rowFrom.trim();
  const colFrom = r.colFrom.trim();
  return {
    zone: r.zone.trim(),
    rowFrom,
    rowTo: effectiveTo(rowFrom, r.rowTo.trim()).trim(),
    colFrom,
    colTo: effectiveTo(colFrom, r.colTo.trim()).trim(),
  };
}

// ---- 총 N석 (구역별 직사각형 합집합) ----

interface Rect {
  zone: string;
  row: Dim;
  col: Dim;
}

/** 한 축의 원자 구간: 숫자는 경계점으로 쪼갠 구간(가중치=길이), 문자 키는 가중치 1 */
interface Atom {
  weight: number;
  covers: (d: Dim) => boolean;
}

function buildAtoms(dims: Dim[]): Atom[] {
  const points = new Set<number>();
  const keys = new Set<string>();
  for (const d of dims) {
    if (d.from.kind === "num" && d.to.kind === "num") {
      points.add(d.from.n);
      points.add(d.to.n + 1);
    } else if (d.from.kind === "key") {
      keys.add(d.from.key);
    }
  }
  const sorted = [...points].sort((x, y) => x - y);
  const atoms: Atom[] = [];
  for (let i = 0; i + 1 < sorted.length; i++) {
    const lo = sorted[i];
    const hi = sorted[i + 1];
    atoms.push({
      weight: hi - lo,
      covers: (d) => d.from.kind === "num" && d.to.kind === "num" && d.from.n <= lo && hi <= d.to.n + 1,
    });
  }
  for (const key of keys) {
    atoms.push({ weight: 1, covers: (d) => d.from.kind === "key" && d.from.key === key });
  }
  return atoms;
}

/**
 * 입력이 올바른 범위들로 만든 희망 좌석 수 — 같은 구역의 겹침은 합집합으로 한 번만 센다
 * (서버 wantSeatCount와 같은 의미). 올바르지 않은 카드는 제외하고, 제외한 개수도 돌려준다.
 */
export function countWantSeats(ranges: RangeInput[]): { count: number; invalid: number } {
  const rects: Rect[] = [];
  let invalid = 0;
  for (const raw of ranges) {
    const errors: RangeErrors = {};
    const zone = raw.zone.trim();
    if (!zone || zone.length > ZONE_MAX) {
      invalid++;
      continue;
    }
    const row = validateAxis(raw.rowFrom, raw.rowTo, "열", "rowFrom", "rowTo", errors);
    const col = validateAxis(raw.colFrom, raw.colTo, "번", "colFrom", "colTo", errors);
    if (!row || !col) {
      invalid++;
      continue;
    }
    rects.push({ zone: normalizeZone(zone), row, col });
  }

  const byZone = new Map<string, Rect[]>();
  for (const r of rects) {
    const list = byZone.get(r.zone);
    if (list) list.push(r);
    else byZone.set(r.zone, [r]);
  }

  let count = 0;
  for (const list of byZone.values()) {
    const rowAtoms = buildAtoms(list.map((r) => r.row));
    const colAtoms = buildAtoms(list.map((r) => r.col));
    for (const ra of rowAtoms) {
      const rowRects = list.filter((r) => ra.covers(r.row));
      if (rowRects.length === 0) continue;
      for (const ca of colAtoms) {
        if (rowRects.some((r) => ca.covers(r.col))) count += ra.weight * ca.weight;
      }
    }
  }
  return { count, invalid };
}

// ---- 겹침 + 추가금 유형 충돌 미리 경고 ----

/** 비교용 추가금 조건 키: submit과 같이 쉼표·공백을 제거, X/ANY는 금액 없음, 앞 0 제거 */
export function extraKey(type: string, amountText: string): string {
  if (type !== "POS" && type !== "NEG") return type;
  const t = amountText.replace(/[,\s]/g, "");
  return `${type}:${/^\d+$/.test(t) ? String(Number(t)) : t}`;
}

function axisOverlap(a: Dim, b: Dim): boolean {
  if (a.from.kind === "num" && a.to.kind === "num" && b.from.kind === "num" && b.to.kind === "num") {
    return a.from.n <= b.to.n && b.from.n <= a.to.n;
  }
  if (a.from.kind === "key" && b.from.kind === "key") return a.from.key === b.from.key;
  return false;
}

/**
 * 서로 겹치는데 추가금 조건(유형+금액)이 다른 범위 쌍의 인덱스([i, j], i<j)를 돌려준다.
 * 서버 WantExtra.equals와 같은 기준이다: 유형이 같고 금액도 같아야 같은 조건 (POS 10000 vs POS 20000은 충돌).
 * X/ANY는 금액이 없고, NEG는 부호만 반대로 전송하므로 양수 입력끼리 비교해도 같다. 조건이 같은 겹침(완전히 같은 범위 포함)은 허용이다. 입력이 올바르지 않은 범위는 건너뛴다.
 * 서버(WANT_EXTRA_CONFLICT)가 최종 판정한다 — 미리 안내하는 용도다.
 */
export function findExtraConflicts(
  items: (RangeInput & { extraType: string; extraAmount?: string })[]
): [number, number][] {
  const rects: ({ rect: Rect; type: string } | null)[] = items.map((raw) => {
    const zone = raw.zone.trim();
    if (!zone || zone.length > ZONE_MAX) return null;
    const errors: RangeErrors = {};
    const row = validateAxis(raw.rowFrom, raw.rowTo, "열", "rowFrom", "rowTo", errors);
    const col = validateAxis(raw.colFrom, raw.colTo, "번", "colFrom", "colTo", errors);
    if (!row || !col) return null;
    return { rect: { zone: normalizeZone(zone), row, col }, type: extraKey(raw.extraType, raw.extraAmount ?? "") };
  });
  const pairs: [number, number][] = [];
  for (let i = 0; i < rects.length; i++) {
    const a = rects[i];
    if (!a) continue;
    for (let j = i + 1; j < rects.length; j++) {
      const b = rects[j];
      if (!b || a.type === b.type || a.rect.zone !== b.rect.zone) continue;
      if (axisOverlap(a.rect.row, b.rect.row) && axisOverlap(a.rect.col, b.rect.col)) pairs.push([i, j]);
    }
  }
  return pairs;
}
