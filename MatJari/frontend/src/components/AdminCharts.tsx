// 관리자 대시보드용 작은 SVG 그래프(외부 라이브러리 없음). 폭은 부모에 맞춰 늘어나고 높이는 고정이다.

export interface Series {
  label: string;
  color: string;
  values: number[];
}

const AXIS = "#9ca3af";
const GRID = "#e5e7eb";

function niceMax(v: number): number {
  if (v <= 4) return 4;
  const p = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}

export function Legend({ items }: { items: { label: string; color: string; value?: number | string }[] }) {
  return (
    <ul className="flex flex-wrap gap-x-3 gap-y-0.5 text-[11px]/[normal] text-gray-600">
      {items.map((i) => (
        <li key={i.label} className="flex items-center gap-1">
          <span className="inline-block size-2 rounded-sm" style={{ background: i.color }} aria-hidden="true" />
          {i.label}
          {i.value !== undefined && <b className="tabular-nums text-gray-900">{i.value}</b>}
        </li>
      ))}
    </ul>
  );
}

/** 여러 계열 꺾은선(날짜 축). labels 는 x 축 글자(MM/DD). */
export function LineChart({ labels, series, height = 140, width = 480, title }: { labels: string[]; series: Series[]; height?: number; width?: number; title: string }) {
  const W = width;
  const H = height;
  const L = 26;
  const B = 16;
  const T = 6;
  const max = niceMax(Math.max(1, ...series.flatMap((s) => s.values)));
  const n = labels.length;
  const x = (i: number) => L + (n <= 1 ? 0 : (i * (W - L - 6)) / (n - 1));
  const y = (v: number) => T + (H - T - B) * (1 - v / max);
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label={title}>
      {[0, 0.5, 1].map((f) => (
        <g key={f}>
          <line x1={L} x2={W - 4} y1={y(max * f)} y2={y(max * f)} stroke={GRID} />
          <text x={L - 3} y={y(max * f) + 3} textAnchor="end" fontSize="9" fill={AXIS}>
            {Math.round(max * f)}
          </text>
        </g>
      ))}
      {labels.map((l, i) =>
        i % 2 === (n - 1) % 2 ? (
          <text key={l} x={x(i)} y={H - 4} textAnchor="middle" fontSize="9" fill={AXIS}>
            {l}
          </text>
        ) : null,
      )}
      {series.map((s) => (
        <g key={s.label}>
          <polyline fill="none" stroke={s.color} strokeWidth="1.8" points={s.values.map((v, i) => `${x(i)},${y(v)}`).join(" ")} />
          {s.values.map((v, i) => (
            <circle key={i} cx={x(i)} cy={y(v)} r="1.8" fill={s.color}>
              <title>{`${labels[i]} ${s.label} ${v}`}</title>
            </circle>
          ))}
        </g>
      ))}
    </svg>
  );
}

/** 막대(날짜 축) 한 계열. */
export function BarChart({ labels, values, color, height = 110, title }: { labels: string[]; values: number[]; color: string; height?: number; title: string }) {
  const W = 480;
  const H = height;
  const L = 26;
  const B = 16;
  const T = 6;
  const max = niceMax(Math.max(1, ...values));
  const n = values.length;
  const bw = (W - L - 6) / Math.max(1, n);
  const y = (v: number) => T + (H - T - B) * (1 - v / max);
  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="block h-auto w-full" role="img" aria-label={title}>
      {[0, 0.5, 1].map((f) => (
        <g key={f}>
          <line x1={L} x2={W - 4} y1={y(max * f)} y2={y(max * f)} stroke={GRID} />
          <text x={L - 3} y={y(max * f) + 3} textAnchor="end" fontSize="9" fill={AXIS}>
            {Math.round(max * f)}
          </text>
        </g>
      ))}
      {values.map((v, i) => (
        <g key={i}>
          <rect x={L + i * bw + bw * 0.15} y={y(v)} width={bw * 0.7} height={Math.max(0, H - B - y(v))} fill={color} rx="1.5">
            <title>{`${labels[i]} ${v}`}</title>
          </rect>
          {v > 0 && (
            <text x={L + i * bw + bw / 2} y={y(v) - 2} textAnchor="middle" fontSize="8" fill="#374151">
              {v}
            </text>
          )}
          {i % 2 === (n - 1) % 2 && (
            <text x={L + i * bw + bw / 2} y={H - 4} textAnchor="middle" fontSize="9" fill={AXIS}>
              {labels[i]}
            </text>
          )}
        </g>
      ))}
    </svg>
  );
}

/** 가로 막대(이름 → 값). */
export function HBars({ items, color, empty = "없음" }: { items: { label: string; value: number }[]; color: string; empty?: string }) {
  const max = Math.max(1, ...items.map((i) => i.value));
  if (items.length === 0) return <p className="text-[11px] text-gray-500">{empty}</p>;
  return (
    <ul className="flex flex-col gap-1">
      {items.map((i) => (
        <li key={i.label} className="grid grid-cols-[5.5rem_1fr_2rem] items-center gap-1.5 text-[11px]/[normal]">
          <span className="truncate text-gray-700">{i.label}</span>
          <span className="h-2.5 rounded-sm bg-gray-100">
            <span className="block h-full rounded-sm" style={{ width: `${(i.value / max) * 100}%`, background: color }} />
          </span>
          <span className="text-right font-semibold tabular-nums text-gray-900">{i.value}</span>
        </li>
      ))}
    </ul>
  );
}

/** 한 줄 누적 막대(구성 비율). */
export function StackBar({ parts }: { parts: { label: string; value: number; color: string }[] }) {
  const total = parts.reduce((a, p) => a + p.value, 0);
  return (
    <div className="flex flex-col gap-1">
      <div className="flex h-3 w-full overflow-hidden rounded-sm bg-gray-100" role="img" aria-label={parts.map((p) => `${p.label} ${p.value}`).join(", ")}>
        {total > 0 &&
          parts.map((p) =>
            p.value > 0 ? <span key={p.label} style={{ width: `${(p.value / total) * 100}%`, background: p.color }} title={`${p.label} ${p.value}`} /> : null,
          )}
      </div>
      <Legend items={parts.map((p) => ({ label: p.label, color: p.color, value: p.value }))} />
    </div>
  );
}

/** 게이지(사용량/상한). */
export function Meter({ value, max, label }: { value: number; max: number; label: string }) {
  const ratio = max > 0 ? Math.min(1, value / max) : 0;
  const color = ratio >= 1 ? "#dc2626" : ratio >= 0.8 ? "#d97706" : "#2563eb";
  return (
    <div className="flex flex-col gap-0.5">
      <div className="flex justify-between text-[11px]/[normal] text-gray-600">
        <span>{label}</span>
        <b className="tabular-nums text-gray-900">
          {value}/{max}
        </b>
      </div>
      <div className="h-2 rounded-sm bg-gray-100">
        <div className="h-full rounded-sm" style={{ width: `${ratio * 100}%`, background: color }} />
      </div>
    </div>
  );
}
