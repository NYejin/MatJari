import { useCallback, useEffect, useState, type KeyboardEvent } from "react";
import { labelAnchor, polygonPoints, previewHeight } from "../api/zones";
import type { PerformanceZone, SeatmapImage } from "../types/performance";
import { parsePosterUrl } from "./PosterImage";
import ZoomableSeatmap from "./ZoomableSeatmap";
import { ui } from "./ui";

// 구역 지도(SVG). 모양이 있는 구역만 그린다. onSelect 가 있으면 구역을 클릭·터치(키보드는 Tab 후 Enter/Space)해서 고를 수 있다.
// 확대·이동(드래그·핀치)과 구분하려고 짧은 탭만 선택으로 본다. 반드시 모듈 최상위 컴포넌트로 쓴다.
// seatmapImage 가 있으면 원본 좌석표를 인라인 링크로 깔고(서버는 저장하지 않음) 구역을 투명 클릭 영역으로 얹는다.
// 원본을 못 불러오거나 허용 호스트가 아니면 기존 색 블록 지도 그대로다. 원본과 다각형은 같은 SVG 안이라 확대·이동이 함께 적용된다.

interface ZoneMapProps {
  zones: PerformanceZone[];
  aspect: number | null | undefined;
  /** 선택된 구역 이름(진하게 표시) */
  selected?: string | null;
  /** 있으면 구역을 눌러 고를 수 있다 */
  onSelect?: (name: string) => void;
  /** 원본 좌석표(인라인 링크). 못 불러오면 색 블록 지도로 돌아간다 */
  seatmapImage?: SeatmapImage | null;
}

export const hasShapedZone = (zones: PerformanceZone[] | null | undefined) =>
  !!zones && zones.some((z) => polygonPoints(z.shapePolygons).length > 0);

interface OriginalState {
  href: string;
  natural: { w: number; h: number } | null;
  failed: boolean;
}
const NONE = { href: null, source: "", natural: null, loading: false } as const;

/** 원본 이미지를 미리 불러 크기를 읽는다. 허용 호스트·https가 아니거나 실패하면 href 가 null(색 블록 지도) */
export function useOriginal(image: SeatmapImage | null | undefined) {
  const parsed = image ? parsePosterUrl(image.url) : null;
  const okBox =
    !!image && [image.left, image.top, image.width, image.height].every(Number.isFinite) && image.width > 0 && image.height > 0;
  const href = okBox && parsed ? parsed.href : null;
  const [state, setState] = useState<OriginalState | null>(null);

  useEffect(() => {
    if (!href) return;
    let alive = true;
    const img = new Image();
    img.referrerPolicy = "no-referrer";
    img.onload = () => {
      if (alive) setState({ href, natural: { w: img.naturalWidth, h: img.naturalHeight }, failed: img.naturalWidth <= 0 || img.naturalHeight <= 0 });
    };
    img.onerror = () => {
      if (alive) setState({ href, natural: null, failed: true });
    };
    img.src = href;
    return () => {
      alive = false;
    };
  }, [href]);

  if (!href || !parsed) return NONE;
  const cur = state && state.href === href ? state : null;
  if (cur?.failed) return NONE;
  return { href, source: parsed.source, natural: cur?.natural ?? null, loading: !cur };
}

/** 원본 좌석표 SVG 레이어(지도 좌표 0~1000 × height). 불러오는 중이면 회색 자리표시, 원본을 쓰지 않으면 null */
export function originalLayer(seatmapImage: SeatmapImage | null | undefined, orig: ReturnType<typeof useOriginal>, height: number): React.ReactNode {
  if (!seatmapImage || !orig.href) return null;
  if (!orig.natural) return <rect x={0} y={0} width={1000} height={height} className="fill-gray-100" />;
  const w = 1000 / seatmapImage.width;
  const h = w * (orig.natural.h / orig.natural.w);
  return (
    <image
      href={orig.href}
      x={-seatmapImage.left * w}
      y={-seatmapImage.top * h}
      width={w}
      height={h}
      preserveAspectRatio="none"
      {...{ referrerPolicy: "no-referrer" }}
      aria-hidden="true"
    />
  );
}

export default function ZoneMap({ zones, aspect, selected = null, onSelect, seatmapImage = null }: ZoneMapProps) {
  const shaped = zones.filter((z) => polygonPoints(z.shapePolygons).length > 0);
  const height = previewHeight(aspect);
  const sy = height / 1000;
  const selectable = !!onSelect;
  const orig = useOriginal(seatmapImage);
  // 원본을 쓰는 중(불러오는 동안 포함). 실패하면 href 가 null 이 되어 색 블록 지도로 돌아간다
  const overlay = !!orig.href && !!seatmapImage;
  const ready = overlay && !!orig.natural;

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

  // 원본: 보이는 영역(비율 사각형)이 지도 상자를 채우도록 이미지를 키워 왼쪽 위를 맞춘다(SVG x·y 단위 길이는 같다)
  let original: React.ReactNode = null;
  if (overlay && seatmapImage) {
    if (ready && orig.natural && orig.href) {
      const w = 1000 / seatmapImage.width;
      const h = w * (orig.natural.h / orig.natural.w);
      original = (
        <image
          href={orig.href}
          x={-seatmapImage.left * w}
          y={-seatmapImage.top * h}
          width={w}
          height={h}
          preserveAspectRatio="none"
          {...{ referrerPolicy: "no-referrer" }}
          aria-hidden="true"
        />
      );
    } else {
      original = <rect x={0} y={0} width={1000} height={height} className="fill-gray-100" />;
    }
  }

  return (
    <div className="flex flex-col gap-1.5">
      <ZoomableSeatmap width={1000} height={height} label={label} onTap={selectable ? onTap : undefined}>
        {original}
        {shaped.map((z) => {
          const on = z.name === selected;
          const groupClass = [selectable ? "cursor-pointer outline-none focus-visible:[&>polygon]:stroke-[4]" : "", overlay ? "group/zone" : ""]
            .filter(Boolean)
            .join(" ");
          return (
            <g
              key={z.name}
              data-zone={z.name}
              role={selectable ? "button" : undefined}
              tabIndex={selectable ? 0 : undefined}
              aria-label={selectable ? `${z.name} 구역 선택` : undefined}
              aria-pressed={selectable ? on : undefined}
              onKeyDown={selectable ? (e) => key(e, z.name) : undefined}
              className={groupClass || undefined}
            >
              {polygonPoints(z.shapePolygons, sy).map((pts, i) => (
                <polygon
                  key={i}
                  points={pts}
                  strokeLinejoin="round"
                  vectorEffect="non-scaling-stroke"
                  className={
                    overlay
                      ? on
                        ? "fill-primary-600/45 stroke-primary-700"
                        : "fill-transparent stroke-primary-600/15 hover:fill-primary-500/25 hover:stroke-primary-600 group-focus-visible/zone:fill-primary-500/25 group-focus-visible/zone:stroke-primary-600"
                      : on
                        ? "fill-accent-100 stroke-gray-900"
                        : "fill-primary-50 stroke-primary-700"
                  }
                  strokeWidth={overlay ? (on ? 4 : 1) : on ? 3 : 1.5}
                  fillOpacity={overlay ? undefined : 0.85}
                />
              ))}
            </g>
          );
        })}
        {shaped.map((z) => {
          const on = z.name === selected;
          // 원본에 번호가 인쇄돼 있어 평소 라벨은 숨기고 선택된 구역만 알약으로 보인다
          if (overlay && !on) return null;
          const a = labelAnchor(z.shapePolygons, sy);
          if (!a) return null;
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
              strokeWidth={overlay ? 10 : 6}
              paintOrder="stroke"
              aria-hidden="true"
            >
              {z.name}
            </text>
          );
        })}
      </ZoomableSeatmap>
      {ready && <p className={ui.hint}>원본 좌석표 · 출처: {orig.source} · 저작권은 원 권리자에게 있어요</p>}
    </div>
  );
}
