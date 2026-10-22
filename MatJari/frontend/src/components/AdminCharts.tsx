// 관리자 대시보드용 작은 SVG 그래프(외부 라이브러리 없음).
// 실제 픽셀 크기를 재서 그린다: 폭은 부모에 맞추고 높이는 최소 height 에서 패널 남는 공간만큼 늘어난다
// (viewBox 비율 축소를 쓰면 창이 좁아질 때 그래프만 낮아져 아래 여백이 생긴다).
import { useEffect, useRef, useState, type ReactNode } from "react";

function ChartBox({ height, title, children }: { height: number; title: string; children: (w: number, h: number) => ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<{ w: number; h: number } | null>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => {
      const w = Math.round(e.contentRect.width);
      const h = Math.round(e.contentRect.height);
      setSize((s) => (s && s.w === w && s.h === h ? s : { w, h }));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return (
    <div ref={ref} className="relative min-h-0 w-full flex-1" style={{ minHeight: height }}>
      {size && size.w > 0 && (
        <svg width={size.w} height={Math.max(height, size.h)} className="absolute inset-0 block" role="img" aria-label={title}>
          {children(size.w, Math.max(height, size.h))}
        </svg>
      )}
    </div>
  );
}

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
export function LineChart({ labels, series, height = 140, title }: { labels: string[]; series: Series[]; height?: number; title: string }) {
  return <ChartBox height={height} title={title}>{(W, H) => <LinePlot labels={labels} series={series} W={W} H={H} />}</ChartBox>;
}

function LinePlot({ labels, series, W, H }: { labels: string[]; series: Series[]; W: number; H: number }) {
  const L = 30;
  const B = 18;
  const T = 12;
  const max = niceMax(Math.max(1, ...series.flatMap((s) => s.values)));
  const n = labels.length;
  const x = (i: number) => L + (n <= 1 ? 0 : (i * (W - L - 16)) / (n - 1));
  const y = (v: number) => T + (H - T - B) * (1 - v / max);
  return (
    <>
      {[0, 0.5, 1].map((f) => (
        <g key={f}>
          <line x1={L} x2={W - 4} y1={y(max * f)} y2={y(max * f)} stroke={GRID} />
          <text x={L - 3} y={y(max * f) + 3} textAnchor="end" fontSize="11" fill={AXIS}>
            {Math.round(max * f)}
          </text>
        </g>
      ))}
      {labels.map((l, i) =>
        i % 2 === (n - 1) % 2 ? (
          <text key={l} x={x(i)} y={H - 4} textAnchor="middle" fontSize="11" fill={AXIS}>
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
    </>
  );
}

/** 막대(날짜 축) 한 계열. */
export function BarChart({ labels, values, color, height = 110, title }: { labels: string[]; values: number[]; color: string; height?: number; title: string }) {
  return <ChartBox height={height} title={title}>{(W, H) => <BarPlot labels={labels} values={values} color={color} W={W} H={H} />}</ChartBox>;
}

function BarPlot({ labels, values, color, W, H }: { labels: string[]; values: number[]; color: string; W: number; H: number }) {
  const L = 30;
  const B = 18;
  const T = 12;
  const max = niceMax(Math.max(1, ...values));
  const n = values.length;
  const bw = (W - L - 6) / Math.max(1, n);
  const y = (v: number) => T + (H - T - B) * (1 - v / max);
  return (
    <>
      {[0, 0.5, 1].map((f) => (
        <g key={f}>
          <line x1={L} x2={W - 4} y1={y(max * f)} y2={y(max * f)} stroke={GRID} />
          <text x={L - 3} y={y(max * f) + 3} textAnchor="end" fontSize="11" fill={AXIS}>
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
            <text x={L + i * bw + bw / 2} y={y(v) - 2} textAnchor="middle" fontSize="11" fill="#374151">
              {v}
            </text>
          )}
          {i % 2 === (n - 1) % 2 && (
            <text x={L + i * bw + bw / 2} y={H - 4} textAnchor="middle" fontSize="11" fill={AXIS}>
              {labels[i]}
            </text>
          )}
        </g>
      ))}
    </>
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

/** 남은 비율 게이지(많이 남을수록 초록, 20% 아래 주황, 10% 아래 빨강). */
export function RemainGauge({ ratio, label, value }: { ratio: number; label: string; value: string }) {
  const r = Math.max(0, Math.min(1, ratio));
  const color = r < 0.1 ? "#dc2626" : r < 0.2 ? "#d97706" : "#059669";
  return (
    <div className="flex flex-col gap-0.5">
      <div className="flex items-baseline justify-between text-[11px]/[normal] text-gray-600">
        <span>{label}</span>
        <span>
          <b className="text-[13px] tabular-nums text-gray-900">{value}</b> <b className="tabular-nums" style={{ color }}>{Math.round(r * 100)}%</b>
        </span>
      </div>
      <div className="h-3 rounded-sm bg-gray-100" role="img" aria-label={`${label} ${Math.round(r * 100)}%`}>
        <div className="h-full rounded-sm" style={{ width: `${r * 100}%`, background: color }} />
      </div>
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
