import { useState, type InputHTMLAttributes } from "react";
import TextField from "./TextField";
import { hasShapedZone } from "./ZoneMap";
import ZoneMapTabs from "./ZoneMapTabs";
import { button, ui } from "./ui";
import type { ZoneData } from "../hooks/useZones";
import type { ZoneMapInfo } from "../types/performance";

// 구역 입력. 공연에 구역 지도가 있으면 지도에서 구역을 눌러(클릭·터치) 고르고, 없으면 기존 텍스트 입력 그대로.
// 지도에 없는 구역은 아래 입력 칸에 직접 쓸 수 있다. 값은 항상 구역 이름 문자열 하나라 저장 형식·검증은 그대로다.
// 반드시 모듈 최상위 컴포넌트로 쓴다.

interface ZonePickFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "id" | "className" | "value" | "onChange"> {
  /** 오류 포커스 이동에 쓰는 id — 텍스트 칸이 이 id를 가진다 */
  id: string;
  label: string;
  value: string;
  onValueChange: (value: string) => void;
  zoneData: ZoneData | null;
  error?: string;
  hint?: string;
}

export default function ZonePickField({ id, label, value, onValueChange, zoneData, error, hint, ...inputProps }: ZonePickFieldProps) {
  const [open, setOpen] = useState(true);
  const mapId = `${id}-map`;
  // maps 가 없으면(등록 화면의 입력 중 구역 등) 대표 지도 하나로 본다
  const maps: ZoneMapInfo[] = zoneData
    ? (zoneData.maps ?? [
        { sourceId: null, site: "", siteName: "", primary: true, status: "READY", aspect: zoneData.aspect, seatmapImage: zoneData.seatmapImage ?? null, zones: zoneData.zones },
      ])
    : [];
  const showMap = !!zoneData && (hasShapedZone(zoneData.zones) || maps.some((m) => hasShapedZone(m.zones) || m.status === "PENDING"));

  return (
    <div className="flex flex-col gap-3">
      {showMap && zoneData && (
        <div className="flex flex-col gap-1.5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className={ui.label}>지도에서 {label}을 눌러 고르세요</span>
            <button
              type="button"
              className={button.outline}
              onClick={() => setOpen((o) => !o)}
              aria-expanded={open}
              aria-controls={mapId}
              disabled={inputProps.disabled}
            >
              {open ? "지도 접기" : "지도 펼치기"}
            </button>
          </div>
          <p className="rounded-xl border border-accent-100 bg-accent-50 px-3 py-2 text-sm leading-relaxed text-gray-900" role="note">
            <strong className="font-semibold">AI가 인식한 구역 지도예요.</strong> 참고용으로만 봐 주세요. 지도 모양이 실제 좌석표와 다르면
            지도 대신 아래 칸에 티켓에 적힌 구역 이름을 직접 입력해 주세요.
          </p>
          <div id={mapId} hidden={!open}>
            <ZoneMapTabs
              maps={maps}
              selected={value.trim() || null}
              onSelect={inputProps.disabled ? undefined : onValueChange}
            />
          </div>
          <p className={ui.hint}>구역을 누르면 아래 칸에 채워져요. 지도는 +/− 버튼이나 두 손가락으로 확대하고 드래그로 옮길 수 있어요.</p>
        </div>
      )}
      <TextField
        id={id}
        label={label}
        value={value}
        onChange={(e) => onValueChange(e.target.value)}
        error={error}
        hint={hint}
        {...inputProps}
      />
    </div>
  );
}
