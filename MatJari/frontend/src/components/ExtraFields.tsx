import type { ExtraType } from "../types/exchange";
import { input, ui } from "./ui";

// 추가금 선택 (희망 범위 카드 1장마다): 라디오 4개 + POS/NEG일 때만 금액 입력.
// 금액은 사용자가 양수로 입력하고, 부호(NEG는 음수)는 전송 직전에 페이지가 붙인다.
// 금액은 매칭 계산에 쓰이지 않고 후보 목록에 참고로만 표시된다. 반드시 모듈 최상위 컴포넌트로 쓴다.

interface ExtraFieldsProps {
  /** 카드 고유 번호 (id·라디오 그룹 이름에 쓰인다) */
  uid: number;
  /** 화면 순번 (범위 N) — 그룹 이름용 */
  number: number;
  type: ExtraType;
  amount: string;
  onTypeChange: (type: ExtraType) => void;
  onAmountChange: (amount: string) => void;
  typeError?: string;
  amountError?: string;
  disabled?: boolean;
}

const OPTIONS: { value: ExtraType; label: string; desc: string }[] = [
  { value: "X", label: "추가금 X", desc: "추가금을 주고받지 않아요" },
  { value: "ANY", label: "상관없음", desc: "추가금이 있어도 없어도 괜찮아요" },
  { value: "POS", label: "받고 싶어요", desc: "받고 싶은 최소 금액을 적어요" },
  { value: "NEG", label: "낼 수 있어요", desc: "낼 수 있는 최대 금액을 적어요" },
];

export const extraTypeId = (uid: number, type: ExtraType) => `range-${uid}-extra-${type}`;
export const extraAmountId = (uid: number) => `range-${uid}-extra-amount`;

export default function ExtraFields({
  uid,
  number,
  type,
  amount,
  onTypeChange,
  onAmountChange,
  typeError,
  amountError,
  disabled = false,
}: ExtraFieldsProps) {
  const showAmount = type === "POS" || type === "NEG";
  const noteId = `range-${uid}-extra-note`;
  const typeErrorId = `range-${uid}-extra-type-error`;
  const amountErrorId = `range-${uid}-extra-amount-error`;
  const amountLabel = type === "POS" ? "받고 싶은 최소 금액 (원)" : "낼 수 있는 최대 금액 (원)";

  return (
    <fieldset
      className="flex min-w-0 flex-col gap-3"
      disabled={disabled}
      aria-describedby={typeError ? typeErrorId : noteId}
    >
      <legend className="mb-1 text-[0.9375rem]/[normal] font-bold text-gray-900">범위 {number}의 추가금</legend>
      <div className="grid grid-cols-2 gap-x-2 gap-y-1">
        {OPTIONS.map((o) => (
          <label
            key={o.value}
            htmlFor={extraTypeId(uid, o.value)}
            className="flex min-h-11 min-w-0 cursor-pointer items-center gap-2 rounded-[10px] px-1 py-1.5"
          >
            <input
              id={extraTypeId(uid, o.value)}
              type="radio"
              name={`range-${uid}-extra-type`}
              className="size-5 shrink-0 accent-primary-600"
              checked={type === o.value}
              onChange={() => onTypeChange(o.value)}
            />
            <span className="flex min-w-0 flex-col">
              <span className="text-[0.9375rem]/[normal] font-semibold text-gray-900">{o.label}</span>
              <span className={ui.muted}>{o.desc}</span>
            </span>
          </label>
        ))}
      </div>

      {showAmount && (
        <div className="flex flex-col gap-1.5">
          <label htmlFor={extraAmountId(uid)} className={ui.label}>
            {amountLabel}
          </label>
          <input
            id={extraAmountId(uid)}
            type="text"
            inputMode="numeric"
            autoComplete="off"
            className={amountError ? input.invalid : input.normal}
            value={amount}
            onChange={(e) => onAmountChange(e.target.value)}
            aria-invalid={!!amountError}
            aria-describedby={amountError ? amountErrorId : noteId}
            placeholder="예: 10000"
          />
          {amountError && (
            <p id={amountErrorId} className={ui.fieldError}>
              {amountError}
            </p>
          )}
        </div>
      )}

      {typeError && (
        <p id={typeErrorId} className={ui.fieldError}>
          {typeError}
        </p>
      )}
      <p id={noteId} className={ui.hint}>
        금액은 매칭 판정에 쓰이지 않고 상대에게 참고로 보여져요. ‘받고 싶어요’끼리, 또는 ‘추가금 X’와 ‘받고 싶어요’는 서로 매칭되지 않아요.
      </p>
    </fieldset>
  );
}
