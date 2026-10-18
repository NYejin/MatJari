import { useState, type InputHTMLAttributes } from "react";
import TextField from "./TextField";
import ZoneMap, { hasShapedZone } from "./ZoneMap";
import { button, ui } from "./ui";
import type { ZoneData } from "../hooks/useZones";

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
  const showMap = !!zoneData && hasShapedZone(zoneData.zones);

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
          <div id={mapId} hidden={!open}>
            <ZoneMap
              zones={zoneData.zones}
              aspect={zoneData.aspect}
              selected={value.trim() || null}
              onSelect={inputProps.disabled ? undefined : onValueChange}
            />
          </div>
          <p className={ui.hint}>구역을 누르면 아래 칸에 채워져요. 지도는 +/− 버튼이나 두 손가락으로 확대하고 드래그로 옮길 수 있어요.</p>
        </div>
      )}
      <TextField
        id={id}
        label={showMap ? `${label} (지도에서 선택, 직접 입력도 가능)` : label}
        value={value}
        onChange={(e) => onValueChange(e.target.value)}
        error={error}
        hint={hint}
        {...inputProps}
      />
    </div>
  );
}
