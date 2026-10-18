import { useCallback, type KeyboardEvent } from "react";
import { labelAnchor, polygonPoints, previewHeight } from "../api/zones";
import type { PerformanceZone } from "../types/performance";
import ZoomableSeatmap from "./ZoomableSeatmap";

// 구역 지도(SVG). 모양이 있는 구역만 그린다. onSelect 가 있으면 구역을 클릭·터치(키보드는 Tab 후 Enter/Space)해서 고를 수 있다.
// 확대·이동(드래그·핀치)과 구분하려고 짧은 탭만 선택으로 본다. 반드시 모듈 최상위 컴포넌트로 쓴다.

interface ZoneMapProps {
  zones: PerformanceZone[];
  aspect: number | null | undefined;
  /** 선택된 구역 이름(진하게 표시) */
  selected?: string | null;
  /** 있으면 구역을 눌러 고를 수 있다 */
  onSelect?: (name: string) => void;
}

export const hasShapedZone = (zones: PerformanceZone[] | null | undefined) =>
  !!zones && zones.some((z) => polygonPoints(z.shapePolygons).length > 0);

export default function ZoneMap({ zones, aspect, selected = null, onSelect }: ZoneMapProps) {
  const shaped = zones.filter((z) => polygonPoints(z.shapePolygons).length > 0);
  const height = previewHeight(aspect);
  const selectable = !!onSelect;

  const onTap = useCallback(
    (x: number, y: number) => {
      if (!onSelect) return;
      const el = document.elementFromPoint(x, y)?.closest("[data-zone]");
      const name = el?.getAttribute("data-zone");
      if (name) onSelect(name);
    },
    [onSelect]
  );

  const key = (e: KeyboardEvent, name: string) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      e.stopPropagation();
      onSelect?.(name);
    }
  };

  if (shaped.length === 0) return null;
  const label = `구역 지도. 구역 ${shaped.length}개: ${shaped.map((z) => z.name).join(", ")}${selectable ? ". 구역을 눌러 선택해요." : ""}`;

  return (
    <ZoomableSeatmap width={1000} height={height} label={label} onTap={selectable ? onTap : undefined}>
      {shaped.map((z) => {
        const on = z.name === selected;
        return (
          <g
            key={z.name}
            data-zone={z.name}
            role={selectable ? "button" : undefined}
            tabIndex={selectable ? 0 : undefined}
            aria-label={selectable ? `${z.name} 구역 선택` : undefined}
            aria-pressed={selectable ? on : undefined}
            onKeyDown={selectable ? (e) => key(e, z.name) : undefined}
            className={selectable ? "cursor-pointer outline-none focus-visible:[&>polygon]:stroke-[4]" : undefined}
          >
            {polygonPoints(z.shapePolygons).map((pts, i) => (
              <polygon
                key={i}
                points={pts}
                strokeLinejoin="round"
                vectorEffect="non-scaling-stroke"
                className={on ? "fill-accent-100 stroke-gray-900" : "fill-primary-50 stroke-primary-700"}
                strokeWidth={on ? 3 : 1.5}
                fillOpacity={0.85}
              />
            ))}
          </g>
        );
      })}
      {shaped.map((z) => {
        const a = labelAnchor(z.shapePolygons);
        if (!a) return null;
        const on = z.name === selected;
        return (
          <text
            key={`t-${z.name}`}
            x={a.x}
            y={a.y}
            textAnchor="middle"
            dominantBaseline="central"
            fontSize={on ? 40 : 30}
            fontWeight={on ? 700 : 500}
            className="pointer-events-none fill-gray-900"
            stroke="#fff"
            strokeWidth={6}
            paintOrder="stroke"
            aria-hidden="true"
          >
            {z.name}
          </text>
        );
      })}
    </ZoomableSeatmap>
  );
}
