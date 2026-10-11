import { useEffect, useState } from "react";
import { httpStatus, seatClaimsApi } from "../api/seatClaims";
import { button, ui } from "./ui";

// 인증 요청 사진 보기: 버튼을 누르면 blob으로 받아 object URL로 표시하고, 닫거나 사라질 때 해제한다.
type State =
  | { status: "loading" }
  | { status: "shown"; url: string }
  | { status: "error"; message: string };

export default function SeatClaimImage({ claimId, available }: { claimId: number; available: boolean }) {
  const [state, setState] = useState<State>({ status: "loading" });
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    let url: string | null = null;
    seatClaimsApi
      .fetchImage(claimId, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return;
        url = URL.createObjectURL(blob);
        setState({ status: "shown", url });
      })
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        const s = httpStatus(err);
        setState({
          status: "error",
          message:
            s === 410
              ? "처리가 끝나 사진이 삭제됐어요."
              : s === 404
                ? "사진을 찾을 수 없어요."
                : "사진을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.",
        });
      });
    return () => {
      controller.abort();
      if (url) URL.revokeObjectURL(url);
    };
  }, [open, claimId]);

  if (!available) return <p className={ui.muted}>처리가 끝나 사진이 삭제됐어요.</p>;

  return (
    <div className="flex flex-col items-start gap-2">
      <button
        type="button"
        className={button.outline}
        onClick={() => {
          if (!open) setState({ status: "loading" });
          setOpen((o) => !o);
        }}
        aria-expanded={open}
      >
        {open ? "사진 닫기" : "사진 보기"}
      </button>
      {open && state.status === "loading" && (
        <p className={ui.status} role="status">
          사진을 불러오는 중...
        </p>
      )}
      {open && state.status === "error" && (
        <p className={ui.errorBox} role="alert">
          {state.message}
        </p>
      )}
      {open && state.status === "shown" && (
        <img
          src={state.url}
          alt={`인증 요청 ${claimId}번 예매내역 사진`}
          className="max-h-[70dvh] max-w-full rounded-[10px] border border-gray-200 object-contain"
        />
      )}
    </div>
  );
}
