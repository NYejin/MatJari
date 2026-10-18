import { useEffect, useState, type InputHTMLAttributes } from "react";
import TextField from "./TextField";
import { input, ui } from "./ui";

// 구역 입력. 공연에 등록된 구역 목록(zoneNames)이 있으면 드롭다운(+ 마지막 "직접 입력")으로, 없으면 기존 텍스트 입력 그대로.
// 값은 항상 구역 이름 문자열 하나라 저장 형식·검증은 그대로다. 반드시 모듈 최상위 컴포넌트로 쓴다.

const CUSTOM = "__custom__";

interface ZoneSelectFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "id" | "className" | "value" | "onChange"> {
  /** 오류 포커스 이동에 쓰는 id — 드롭다운이면 select, 직접 입력이면 텍스트 칸이 이 id를 가진다 */
  id: string;
  label: string;
  value: string;
  onValueChange: (value: string) => void;
  zoneNames: string[] | null;
  error?: string;
  hint?: string;
}

export default function ZoneSelectField({ id, label, value, onValueChange, zoneNames, error, hint, ...inputProps }: ZoneSelectFieldProps) {
  const [custom, setCustom] = useState(false);
  const hasList = !!zoneNames && zoneNames.length > 0;
  // 이미 입력된 값이 목록에 없으면 "직접 입력" 상태로 보인다
  const derivedCustom = hasList && value !== "" && !zoneNames.includes(value);
  useEffect(() => {
    // 직접 입력 중 글자를 지워도 갑자기 드롭다운으로 바뀌지 않게 한 번 정해지면 유지한다
    if (derivedCustom) setCustom(true);
  }, [derivedCustom]);

  if (!hasList) {
    return <TextField id={id} label={label} value={value} onChange={(e) => onValueChange(e.target.value)} error={error} hint={hint} {...inputProps} />;
  }

  const isCustom = custom || derivedCustom;
  const selectId = isCustom ? `${id}-select` : id;
  const errorId = `${id}-error`;
  const hintId = `${id}-hint`;
  const showHint = !!hint && !error;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-1.5">
        <label htmlFor={selectId} className={ui.label}>
          {label}
        </label>
        <select
          id={selectId}
          className={error && !isCustom ? input.invalid : input.normal}
          value={isCustom ? CUSTOM : value}
          disabled={inputProps.disabled}
          onChange={(e) => {
            if (e.target.value === CUSTOM) {
              setCustom(true);
              onValueChange("");
            } else {
              setCustom(false);
              onValueChange(e.target.value);
            }
          }}
          aria-invalid={!!error && !isCustom}
          aria-describedby={error && !isCustom ? errorId : showHint && !isCustom ? hintId : undefined}
        >
          <option value="">구역을 선택하세요</option>
          {zoneNames.map((n) => (
            <option key={n} value={n}>
              {n}
            </option>
          ))}
          <option value={CUSTOM}>직접 입력</option>
        </select>
        {!isCustom && showHint && (
          <p id={hintId} className={ui.hint}>
            {hint}
          </p>
        )}
        {!isCustom && error && (
          <p id={errorId} className={ui.fieldError}>
            {error}
          </p>
        )}
      </div>
      {isCustom && (
        <TextField
          id={id}
          label={`${label} 직접 입력`}
          value={value}
          onChange={(e) => onValueChange(e.target.value)}
          error={error}
          hint={hint}
          {...inputProps}
        />
      )}
    </div>
  );
}
