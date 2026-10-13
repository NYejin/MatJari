import type { RangeErrors, RangeField, RangeInput } from "../api/seat";
import type { ExtraType } from "../types/exchange";
import ExtraFields from "./ExtraFields";
import TextField from "./TextField";
import { button, ui } from "./ui";

// 희망 좌석 범위 카드 1장: (구역, 열 시작~끝, 번 시작~끝). 좌석표 없이 텍스트로 입력한다.
// 추가금도 범위마다 따로 고른다(범위-추가금이 짝). 회차는 요청 단위라 여기에 없다. 반드시 모듈 최상위 컴포넌트로 쓴다.

interface SeatRangeCardProps {
  /** 카드 고유 번호 (입력 id에 쓰인다 — 배열 순서가 바뀌어도 포커스·입력이 유지된다) */
  uid: number;
  /** 화면에 보이는 순번 (1부터) */
  number: number;
  value: RangeInput & { extraType: ExtraType; extraAmount: string };
  errors: RangeErrors & { extraType?: string; extraAmount?: string; conflict?: string };
  /** 클라이언트가 미리 찾은 겹침·유형 충돌 안내 (경고일 뿐 전송은 막지 않는다) */
  warning?: string;
  disabled?: boolean;
  onChange: (field: RangeField, value: string) => void;
  onExtraChange: (field: "extraType" | "extraAmount", value: string) => void;
  onRemove: () => void;
  onDuplicate: () => void;
  canDuplicate: boolean;
  /** 카드가 1장뿐일 때는 지울 수 없다 */
  removable: boolean;
}

/** 입력 요소 id (오류 포커스 이동용) */
export const rangeFieldId = (uid: number, field: RangeField) => `range-${uid}-${field}`;

export default function SeatRangeCard({
  uid,
  number,
  value,
  errors,
  warning,
  disabled = false,
  onChange,
  onExtraChange,
  onRemove,
  onDuplicate,
  canDuplicate,
  removable,
}: SeatRangeCardProps) {
  const field = (name: RangeField, label: string, extra?: { hint?: string; placeholder?: string }) => (
    <TextField
      id={rangeFieldId(uid, name)}
      label={label}
      value={value[name]}
      onChange={(e) => onChange(name, e.target.value)}
      error={errors[name]}
      hint={extra?.hint}
      placeholder={extra?.placeholder}
      disabled={disabled}
      autoComplete="off"
      autoCapitalize="characters"
      spellCheck={false}
    />
  );

  return (
    <fieldset className="flex min-w-0 flex-col gap-6 rounded-[10px] border border-gray-200 p-3.5" disabled={disabled}>
      <legend className="px-1 text-sm/[normal] font-bold text-gray-900">희망 범위 {number}</legend>
      {field("zone", "구역", { hint: "티켓에 적힌 그대로 입력해주세요. (예: 1층 A구역)", placeholder: "예: 1층 A구역" })}
      <div className="grid grid-cols-2 gap-3 text-center sm:grid-cols-4">
        {field("rowFrom", "열 시작", { placeholder: "예: 3" })}
        {field("rowTo", "열 끝", { placeholder: "비우면 한 열" })}
        {field("colFrom", "번 시작", { placeholder: "예: 3" })}
        {field("colTo", "번 끝", { placeholder: "비우면 한 자리" })}
      </div>
      <ExtraFields
        uid={uid}
        number={number}
        type={value.extraType}
        amount={value.extraAmount}
        onTypeChange={(t) => onExtraChange("extraType", t)}
        onAmountChange={(v) => onExtraChange("extraAmount", v)}
        typeError={errors.extraType}
        amountError={errors.extraAmount}
        disabled={disabled}
      />
      {errors.conflict && <p className={ui.fieldError}>{errors.conflict}</p>}
      {warning && !errors.conflict && <p className={ui.warning}>{warning}</p>}
      <div className="flex flex-wrap gap-2">
        <button type="button" className={button.outline} onClick={onDuplicate} disabled={disabled || !canDuplicate}>
          희망 범위 {number} 복제
        </button>
        {removable && (
          <button type="button" className={button.dangerOutline} onClick={onRemove} disabled={disabled}>
            희망 범위 {number} 삭제
          </button>
        )}
      </div>
    </fieldset>
  );
}
