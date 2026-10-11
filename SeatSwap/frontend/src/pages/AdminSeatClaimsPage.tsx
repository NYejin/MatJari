import { useRef, useState } from "react";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { formatSeat } from "../api/seat";
import {
  CLAIM_BADGE,
  CLAIM_STATUS_LABEL,
  SEAT_CLAIM_NOTE_MAX,
  seatClaimsApi,
  type AdminSeatClaim,
  type HolderTicketStatus,
  type SeatClaimStatus,
} from "../api/seatClaims";
import SeatClaimImage from "../components/SeatClaimImage";
import { button, input, ui } from "../components/ui";
import { usePagedList } from "../hooks/usePagedList";

// 관리자 좌석 인증 요청 처리 (/admin/seat-claims, ADMIN 전용 — 가드는 라우트에서).
// 승인: 지금 그 좌석을 가진 활성 티켓이 내려간다(2단계 확인). 거절: 사유 입력 + 확인. 처리하면 사진은 서버가 삭제한다.

const TABS: { status: SeatClaimStatus; label: string }[] = [
  { status: "PENDING", label: "확인 중" },
  { status: "APPROVED", label: "승인됨" },
  { status: "REJECTED", label: "거절됨" },
];

const HOLDER_TEXT: Record<HolderTicketStatus, string> = {
  ACTIVE: "활성 티켓 (승인하면 내려가요)",
  RESERVED_LOCKED: "교환 예약으로 잠긴 티켓 (승인 불가)",
  EXCHANGED: "이미 교환된 티켓 (승인 불가)",
  INACTIVE: "내려간 티켓 (이미 비어 있어요)",
};

const APPROVE_BLOCK: Record<string, string> = {
  TICKET_RESERVED: "상대 티켓이 교환 예약으로 잠겨 있어 승인할 수 없어요. 거절만 할 수 있어요.",
  TICKET_EXCHANGED: "상대 티켓이 이미 교환 완료된 좌석이라 승인할 수 없어요. 거절만 할 수 있어요.",
  CLAIM_ALREADY_REVIEWED: "이미 처리된 요청이에요. 목록을 새로 불러와 주세요.",
};

export default function AdminSeatClaimsPage() {
  const [status, setStatus] = useState<SeatClaimStatus>("PENDING");
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<AdminSeatClaim>(
    (page, signal) => seatClaimsApi.adminList(status, page, signal),
    status,
    (c) => c.id
  );

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>좌석 인증 요청</h1>

        <div role="group" aria-label="처리 상태" className="flex gap-2">
          {TABS.map((t) => (
            <button
              key={t.status}
              type="button"
              className={status === t.status ? button.solid : button.outline}
              aria-pressed={status === t.status}
              onClick={() => setStatus(t.status)}
            >
              {t.label}
            </button>
          ))}
        </div>

        {state.status === "loading" && (
          <p className={ui.status} role="status">
            불러오는 중...
          </p>
        )}
        {state.status === "error" && (
          <div className="flex flex-col gap-3">
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "인증 요청을 불러오지 못했습니다.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}
        {state.status === "success" && state.items.length === 0 && (
          <p className={`${ui.card} ${ui.body}`}>{CLAIM_STATUS_LABEL[status]} 요청이 없어요.</p>
        )}
        {state.status === "success" && state.items.length > 0 && (
          <>
            <ul className="flex flex-col gap-3" aria-label={`${CLAIM_STATUS_LABEL[status]} 인증 요청 목록`}>
              {state.items.map((c) => (
                <li key={c.id}>
                  <ClaimCard claim={c} onDone={reload} />
                </li>
              ))}
            </ul>
            {moreError && (
              <p className={ui.errorBox} role="alert">
                {moreError}
              </p>
            )}
            {state.page + 1 < state.totalPages && (
              <button type="button" className={button.outline} onClick={loadMore} disabled={loadingMore} aria-busy={loadingMore}>
                {loadingMore ? "불러오는 중..." : "더 보기"}
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

type Mode = "idle" | "approve" | "reject";

function ClaimCard({ claim, onDone }: { claim: AdminSeatClaim; onDone: () => void }) {
  const [mode, setMode] = useState<Mode>("idle");
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [blocked, setBlocked] = useState(false);
  const pending = claim.status === "PENDING";
  const holderStatus = claim.holder?.ticketStatus;
  const cannotApprove = blocked || holderStatus === "RESERVED_LOCKED" || holderStatus === "EXCHANGED";
  const idBase = `admin-claim-${claim.id}`;

  const run = async (action: () => Promise<unknown>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await action();
      onDone();
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "처리하지 못했습니다.");
      const known = parsed.code ? APPROVE_BLOCK[parsed.code] : undefined;
      if (parsed.code === "TICKET_RESERVED" || parsed.code === "TICKET_EXCHANGED") setBlocked(true);
      setError(known ?? parsed.message);
      setMode("idle");
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <article className={`${ui.card} flex flex-col gap-3`} aria-labelledby={`${idBase}-title`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={`${idBase}-title`} className="text-base/[normal] font-bold break-keep text-gray-900">
          {claim.performanceTitle}
        </h2>
        <span className={CLAIM_BADGE[claim.status]}>{CLAIM_STATUS_LABEL[claim.status]}</span>
      </div>
      <p className={ui.body}>
        {formatKstDateTime(claim.sessionStartsAt)} · {formatSeat(claim)}
      </p>
      <p className={ui.muted}>
        요청자 {claim.claimantNickname} · {formatKstDateTime(claim.createdAt)}
      </p>
      {claim.memo && <p className={ui.body}>메모: {claim.memo}</p>}

      <div className="rounded-[10px] bg-gray-100 px-3.5 py-3 text-sm/[normal] text-gray-900">
        <span className="font-semibold">지금 이 좌석: </span>
        {claim.holder ? (
          <>
            {claim.holder.holderNickname} · {HOLDER_TEXT[claim.holder.ticketStatus]}
          </>
        ) : (
          "등록된 티켓이 없어요"
        )}
      </div>

      <SeatClaimImage claimId={claim.id} available={claim.imageAvailable} />

      {!pending && (
        <p className={ui.muted}>
          {claim.reviewerNickname ?? "관리자"} 처리{claim.reviewedAt && ` · ${formatKstDateTime(claim.reviewedAt)}`}
          {claim.status === "REJECTED" && ` · 사유: ${claim.reviewNote || "없음"}`}
        </p>
      )}

      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}
      {pending && cannotApprove && !error && (
        <p className={ui.errorBox}>
          {holderStatus === "EXCHANGED" ? APPROVE_BLOCK.TICKET_EXCHANGED : APPROVE_BLOCK.TICKET_RESERVED}
        </p>
      )}

      {pending && mode === "idle" && (
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" className={button.dangerOutline} onClick={() => setMode("reject")} disabled={busy}>
            거절
          </button>
          <button
            type="button"
            className={button.solid}
            onClick={() => setMode("approve")}
            disabled={busy || cannotApprove}
            aria-describedby={cannotApprove ? undefined : `${idBase}-title`}
          >
            승인
          </button>
        </div>
      )}

      {pending && mode === "approve" && (
        <div className="flex flex-col gap-3">
          <p className={ui.warning} role="alert">
            승인하면 지금 이 좌석에 등록된 티켓({claim.holder ? claim.holder.holderNickname : "없음"})이 내려가고, 그 티켓의 교환 요청과
            진행 중인 채팅도 정리돼요. 되돌릴 수 없어요. 승인할까요?
          </p>
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={() => setMode("idle")} disabled={busy}>
              돌아가기
            </button>
            <button
              type="button"
              className={button.solid}
              onClick={() => void run(() => seatClaimsApi.approve(claim.id))}
              disabled={busy}
              aria-busy={busy}
            >
              {busy ? "처리 중..." : "승인 확정"}
            </button>
          </div>
        </div>
      )}

      {pending && mode === "reject" && (
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1.5">
            <label htmlFor={`${idBase}-note`} className={ui.label}>
              거절 사유 (요청자에게 보여요)
            </label>
            <textarea
              id={`${idBase}-note`}
              className={`${input.normal} min-h-20 py-3`}
              value={note}
              maxLength={SEAT_CLAIM_NOTE_MAX}
              onChange={(e) => setNote(e.target.value)}
              disabled={busy}
            />
            <p className={ui.hint}>
              {note.length}/{SEAT_CLAIM_NOTE_MAX}자 · 거절하면 사진이 삭제돼요.
            </p>
          </div>
          <div className="flex flex-wrap justify-end gap-2">
            <button type="button" className={button.outline} onClick={() => setMode("idle")} disabled={busy}>
              돌아가기
            </button>
            <button
              type="button"
              className={button.danger}
              onClick={() => void run(() => seatClaimsApi.reject(claim.id, note.trim() || null))}
              disabled={busy}
              aria-busy={busy}
            >
              {busy ? "처리 중..." : "거절 확정"}
            </button>
          </div>
        </div>
      )}
    </article>
  );
}
