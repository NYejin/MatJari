import { useRef, useState, type ReactNode } from "react";
import { useTokens } from "./density";

// 좌석표 이미지 한 장을 골라 넘기는 버튼(JPEG·PNG, 5MB 이하). 서버(AI) 호출은 부모가 onFile 에서 한다.
// 이미지는 서버에 저장되지 않는다. 등록 화면·관리자 공연 관리 공용.

const MAX_BYTES = 5 * 1024 * 1024;
const TYPES = ["image/jpeg", "image/png"];

type Props = {
  label: string;
  busyLabel?: string;
  busy: boolean;
  disabled?: boolean;
  onFile: (file: File) => void;
  /** 버튼 아래 작은 글씨(남은 횟수 등) */
  sub?: ReactNode;
  className?: string;
  /** 버튼·오류 글자 클래스(관리자 화면은 촘촘한 adminUi 토큰을 넘긴다) */
  buttonClass?: string;
  errorClass?: string;
};

export default function SeatmapUpload({
  label,
  busyLabel = "좌석표 읽는 중...",
  busy,
  disabled = false,
  onFile,
  sub,
  className = "",
  buttonClass,
  errorClass,
}: Props) {
  const { button, ui } = useTokens();
  const inputRef = useRef<HTMLInputElement>(null);
  const [error, setError] = useState("");

  const pick = (file: File | undefined) => {
    if (!file) return;
    if (!TYPES.includes(file.type)) {
      setError("JPEG·PNG 이미지만 올릴 수 있어요.");
      return;
    }
    if (file.size > MAX_BYTES) {
      setError("이미지는 5MB 이하만 올릴 수 있어요.");
      return;
    }
    setError("");
    onFile(file);
  };

  return (
    <div className={`flex flex-col items-start gap-1 ${className}`}>
      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png"
        className="sr-only"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(e) => {
          pick(e.target.files?.[0]);
          e.target.value = ""; // 같은 파일을 다시 골라도 동작하게
        }}
      />
      <button
        type="button"
        className={`${buttonClass ?? button.outline} flex-col gap-0`}
        disabled={busy || disabled}
        aria-busy={busy}
        onClick={() => inputRef.current?.click()}
      >
        <span>{busy ? busyLabel : label}</span>
        {sub && <span className="text-xs/[normal] font-normal opacity-80">{sub}</span>}
      </button>
      {error && (
        <p className={errorClass ?? ui.fieldError} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
