import { useId, useState, type KeyboardEvent } from "react";
import ZoneMap, { hasShapedZone } from "./ZoneMap";
import { segment, ui } from "./ui";
import type { ZoneMapInfo } from "../types/performance";

// 예매처별 구역 지도. 지도가 2개 이상이면 위에 예매처 탭을 두고 고른 탭의 지도를 보인다(좌표계가 달라 합치지 않는다).
// 한 개면 탭 없이 지도만. 반드시 모듈 최상위 컴포넌트로 쓴다.

interface Props {
  maps: ZoneMapInfo[];
  selected?: string | null;
  onSelect?: (name: string) => void;
}

const mapLabel = (m: ZoneMapInfo) => `${m.siteName || "대표"} 지도`;

/** 처음 열 때 고를 탭: 현재 값의 구역이 대표 지도에 없고 다른 지도에만 있으면 그 지도 */
function initialIndex(maps: ZoneMapInfo[], value: string | null | undefined): number {
  const v = value?.trim();
  if (!v) return 0;
  const has = (m: ZoneMapInfo) => m.zones.some((z) => z.name === v);
  if (has(maps[0])) return 0;
  const i = maps.findIndex(has);
  return i >= 0 ? i : 0;
}

export default function ZoneMapTabs({ maps, selected, onSelect }: Props) {
  const [index, setIndex] = useState(() => initialIndex(maps, selected));
  const baseId = useId();
  const cur = maps[Math.min(index, maps.length - 1)];

  const body = !cur ? null : cur.status === "PENDING" ? (
    <p className={ui.notice} role="status">
      이 예매처 좌석표를 읽는 중이에요.
    </p>
  ) : hasShapedZone(cur.zones) ? (
    <ZoneMap zones={cur.zones} aspect={cur.aspect} seatmapImage={cur.seatmapImage} selected={selected} onSelect={onSelect} />
  ) : (
    <p className={ui.hint}>이 예매처 구역 지도는 아직 없어요.</p>
  );

  if (maps.length < 2) return <>{body}</>;

  const onKeyDown = (e: KeyboardEvent<HTMLButtonElement>) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    const next = (index + step + maps.length) % maps.length;
    setIndex(next);
    document.getElementById(`${baseId}-tab-${next}`)?.focus();
  };

  return (
    <div className="flex flex-col gap-2">
      <div role="tablist" aria-label="예매처별 구역 지도" className="overflow-x-auto">
        <div className={segment.group}>
          {maps.map((m, i) => (
            <button
              key={m.sourceId ?? `primary-${i}`}
              type="button"
              role="tab"
              id={`${baseId}-tab-${i}`}
              aria-selected={i === index}
              aria-controls={`${baseId}-panel`}
              tabIndex={i === index ? 0 : -1}
              className={`${segment.item} ${i === index ? segment.active : segment.idle}`}
              onClick={() => setIndex(i)}
              onKeyDown={onKeyDown}
            >
              {mapLabel(m)}
            </button>
          ))}
        </div>
      </div>
      <div role="tabpanel" id={`${baseId}-panel`} aria-labelledby={`${baseId}-tab-${index}`}>
        {body}
      </div>
      <p className={ui.hint}>예매처마다 파는 구역이 달라요. 내 티켓을 산 예매처 지도에서 고르세요.</p>
    </div>
  );
}
