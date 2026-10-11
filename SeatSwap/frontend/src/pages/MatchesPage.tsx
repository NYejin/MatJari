import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { exchangeApi } from "../api/exchange";
import { exchangeErrorMessage, formatExtra } from "../api/exchangeMessages";
import { formatKstDateTime } from "../api/dateTime";
import { formatSeat } from "../api/seat";
import { button, FOCUS_RING, linkButton, liveRegionClass, ui } from "../components/ui";
import { usePagedList } from "../hooks/usePagedList";
import type { ExchangeMatch, MatchRole, MatchStatus } from "../types/exchange";

// FR-04 내 매칭 목록 (보호 라우트 /exchange/matches): 보낸(내가 제안)/받은 탭, 상태 배지, 예약하기·예약 취소·거절·채팅 종료.
// 예약 중에는 '교환 수락'(2단계 확인)을 누를 수 있고, 양쪽이 모두 수락하면 교환 완료가 된다. 채팅 메시지는 아직 없다.
// 동작(예약·예약 취소·교환 수락·거절·종료) 뒤에는 제자리 패치 대신 첫 페이지부터 다시 불러온다 (서버가 updated_at 순으로 정렬해 더 보기 목록이 어긋날 수 있음).

const STATUS_LABEL: Record<MatchStatus, string> = {
  CHATTING: "진행 중",
  RESERVED: "예약 중",
  COMPLETED: "교환 완료",
  CANCELED: "취소됨",
};

/** 상태 배지 — 색만으로 구분하지 않고 항상 글자를 함께 쓴다. 모두 대비 AA 이상 */
const STATUS_CLASS: Record<MatchStatus, string> = {
  CHATTING: "bg-primary-50 text-primary-800",
  RESERVED: "bg-accent-100 text-gray-900",
  COMPLETED: "bg-gray-900 text-white",
  CANCELED: "bg-gray-100 text-gray-700",
};

const CANCELED_BY_LABEL = {
  ME: "내가 취소했어요.",
  COUNTERPART: "상대가 취소했어요.",
  SYSTEM: "교환 조건 삭제, 티켓 내림 등의 이유로 자동 취소됐어요.",
} as const;

const TABS: { role: MatchRole; label: string }[] = [
  { role: "ALL", label: "전체" },
  { role: "SENT", label: "보낸 제안" },
  { role: "RECEIVED", label: "받은 제안" },
];

/** 알림에서 들어올 때의 URL 쿼리: ?role=RECEIVED&matchId=123 */
function readRole(value: string | null): MatchRole {
  return value === "SENT" || value === "RECEIVED" ? value : "ALL";
}

function readMatchId(value: string | null): number | null {
  const n = value && /^\d+$/.test(value) ? Number(value) : null;
  return n !== null && Number.isSafeInteger(n) ? n : null;
}

function readNotice(state: unknown): string | null {
  if (typeof state !== "object" || state === null || !("notice" in state)) return null;
  const notice: unknown = (state as { notice: unknown }).notice;
  return typeof notice === "string" && notice ? notice : null;
}

export default function MatchesPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const [notice] = useState(() => readNotice(location.state));
  const [searchParams] = useSearchParams();
  const [role, setRole] = useState<MatchRole>(() => readRole(searchParams.get("role")));
  /** 알림에서 넘어온 카드 강조 대상 (같은 화면에서 다른 알림을 눌러 쿼리만 바뀌어도 따라간다) */
  const focusMatchId = readMatchId(searchParams.get("matchId"));
  const queryRole = searchParams.get("role");
  const focusedRef = useRef(false);
  useEffect(() => {
    focusedRef.current = false;
    setRole(readRole(queryRole));
  }, [focusMatchId, queryRole]);
  const [announce, setAnnounce] = useState("");
  const headingRef = useRef<HTMLHeadingElement>(null);

  useEffect(() => {
    if (notice) navigate(location.pathname, { replace: true, state: null });
    // 최초 1회
  }, []);

  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<ExchangeMatch>(
    (page, signal) => exchangeApi.listMatches({ role, page, size: 20 }, signal),
    role,
    (m) => m.id
  );

  // 알림에서 온 경우: 목록이 처음 뜨면 해당 카드로 스크롤한다 (목록에 없으면 그대로 둔다)
  useEffect(() => {
    if (focusMatchId === null || focusedRef.current || state.status !== "success") return;
    const card = document.getElementById(`match-card-${focusMatchId}`);
    if (!card) return;
    focusedRef.current = true;
    card.scrollIntoView({ block: "center" });
  }, [state, focusMatchId]);

  /** 첫 페이지부터 다시 불러온다. message가 있으면 결과 안내로 보여주고, 없으면 이전 안내를 지운다 */
  const refresh = (message = "") => {
    setAnnounce(message);
    // 처리한 카드가 사라지므로 포커스를 제목으로 옮겨 키보드 사용자가 위치를 잃지 않게 한다
    if (message) headingRef.current?.focus();
    reload();
  };

  const statusText =
    state.status === "loading"
      ? "내 매칭을 불러오는 중..."
      : announce
        ? announce
        : state.status === "success"
          ? `${role === "SENT" ? "보낸 제안" : role === "RECEIVED" ? "받은 제안" : "전체 매칭"} ${state.totalElements}개`
          : "";

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h1 ref={headingRef} tabIndex={-1} className={`${ui.pageTitle} outline-none`}>
            내 매칭
          </h1>
          <Link to="/tickets" className={linkButton.outline}>
            내 티켓
          </Link>
        </div>

        <p className={liveRegionClass(notice, ui.notice)} role="status">
          {notice ?? ""}
        </p>

        <div role="group" aria-label="제안 구분" className="grid grid-cols-3 gap-2">
          {TABS.map((tab) => (
            <button
              key={tab.role}
              type="button"
              className={role === tab.role ? button.solid : button.outline}
              aria-pressed={role === tab.role}
              onClick={() => {
                setRole(tab.role);
                setAnnounce("");
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* 상태 문구(live region, 항상 렌더)와 새로고침 아이콘을 한 행에 두고 가로 중심선을 맞춘다 */}
        <div className="flex items-center gap-1">
          <p className={liveRegionClass(statusText, ui.status)} role="status">
            {statusText}
          </p>
          {state.status === "success" && (
            <button
              type="button"
              className="-my-3 inline-flex min-h-11 min-w-11 cursor-pointer items-center justify-center rounded-full"
              onClick={() => refresh()}
              aria-label="새로고침"
            >
              <img src="/icons/refresh.svg" alt="" aria-hidden="true" className="block size-5 opacity-80 hover:opacity-100" />
            </button>
          )}
        </div>

        {state.status === "error" && (
          <div className={`${ui.card} flex flex-col gap-3`}>
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "내 매칭을 불러오지 못했습니다.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}

        {state.status === "success" && state.items.length === 0 && (
          <div className={`${ui.card} flex flex-col items-start gap-3`}>
            <p className={ui.body}>
              {role === "SENT" ? "아직 보낸 제안이 없어요. 후보 목록에서 마음에 드는 상대에게 제안해 보세요." : "아직 받은 제안이 없어요."}
            </p>
            <Link to="/tickets" className={linkButton.outline}>
              내 티켓에서 후보 찾기
            </Link>
          </div>
        )}

        {state.status === "success" && state.items.length > 0 && (
          <>
            <ul className="flex flex-col gap-3" aria-label={role === "SENT" ? "보낸 제안 목록" : role === "RECEIVED" ? "받은 제안 목록" : "전체 매칭 목록"}>
              {state.items.map((m) => (
                <li
                  key={m.id}
                  id={`match-card-${m.id}`}
                  className={`scroll-mt-20 rounded-2xl ${m.id === focusMatchId ? "outline-2 outline-offset-2 outline-primary-600" : ""}`}
                >
                  <MatchCard match={m} onChanged={refresh} onStale={() => refresh()} />
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

/** dt: 좁을 때는 회색 작은 글자(원래 레이아웃), 480px 이상에서는 배지 모양 (ui.badge와 같은 모양) */
const DT_CLASS =
  "text-sm/[normal] text-gray-500 min-[30rem]:inline-flex min-[30rem]:items-center min-[30rem]:rounded-full min-[30rem]:bg-gray-100 min-[30rem]:px-2.5 min-[30rem]:py-0.5 min-[30rem]:text-[0.8125rem]/[normal] min-[30rem]:text-gray-700";
/** 지금은 누를 수 없는 버튼 (aria-disabled). 대비는 AA를 유지하고 점선 테두리로 색 외에도 구분한다 */
const NOT_READY_CLASS =
  "inline-flex min-h-11 cursor-not-allowed items-center justify-center rounded-[10px] border border-dashed border-gray-500 bg-gray-100 px-4 text-[0.9375rem]/[normal] font-semibold whitespace-nowrap text-gray-700 touch-manipulation " + FOCUS_RING;
const DD_CLASS = "text-sm/[normal] text-gray-900 min-[30rem]:my-1 min-[30rem]:ml-2";

type Action = "reserve" | "unreserve" | "reject" | "cancel" | "complete";

/** '(삭제)' 표시 — 색이 아니라 글자로 알리고, 보조기기에는 긴 설명을 함께 읽어준다 */
function DeletedTag({ label, text }: { label: string; text: string }) {
  return (
    <span className="ml-1.5 text-sm/[normal] font-semibold text-gray-700">
      <span aria-hidden="true">{text}</span>
      <span className="sr-only">{label}</span>
    </span>
  );
}

function MatchCard({
  match: m,
  onChanged,
  onStale,
}: {
  match: ExchangeMatch;
  onChanged: (message: string) => void;
  onStale: () => void;
}) {
  const [confirming, setConfirming] = useState<"reject" | "cancel" | "unreserve" | "complete" | null>(null);
  const [busy, setBusy] = useState<Action | null>(null);
  const [error, setError] = useState<{ message: string; stale: boolean } | null>(null);
  /** 더블 탭 방지 (state는 다음 렌더 전까지 갱신되지 않는다) */
  const busyRef = useRef(false);
  const confirmRef = useRef<HTMLDivElement>(null);

  // 거절·취소 확인 영역이 열리면 포커스를 그리로 옮긴다 (누른 버튼이 사라지므로)
  // 목록이 갱신돼 상태가 바뀌면 낡은 확인 영역을 닫는다
  useEffect(() => {
    setConfirming(null);
  }, [m.status]);

  useEffect(() => {
    if (confirming) confirmRef.current?.focus();
  }, [confirming]);

  const run = async (action: Action) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(action);
    setError(null);
    try {
      let res: ExchangeMatch | null = null;
      if (action === "reserve") res = await exchangeApi.reserve(m.id);
      else if (action === "unreserve") res = await exchangeApi.unreserve(m.id);
      else if (action === "complete") res = await exchangeApi.complete(m.id);
      else if (action === "reject") await exchangeApi.reject(m.id);
      else await exchangeApi.cancel(m.id);
      setConfirming(null);
      onChanged(
        action === "reserve"
          ? // 화면이 낡아 상대가 먼저 예약했다면 서버는 멱등 응답을 줄 수 있다 — 예약자가 내가 아니면 사실대로 안내
            res?.reservedBy === "COUNTERPART"
            ? `${m.counterpartNickname}님이 이미 예약했어요.`
            : `${m.counterpartNickname}님과 예약했어요. 두 티켓이 예약 중이에요.`
          : action === "complete"
            ? res?.status === "COMPLETED"
              ? `${m.counterpartNickname}님과 교환이 완료됐어요. 새 자리는 내 티켓에서 확인하세요.`
              : res?.status === "RESERVED"
                ? "교환 수락했어요. 상대가 수락하면 교환이 완료돼요."
                : `${m.counterpartNickname}님과의 교환 상태를 확인했어요.`
          : action === "unreserve"
            ? res?.status === "CHATTING"
              ? `${m.counterpartNickname}님과의 예약을 취소했어요. 매칭은 그대로 유지돼요.`
              : `${m.counterpartNickname}님과의 예약 상태를 확인했어요.`
            : action === "reject"
              ? `${m.counterpartNickname}님의 제안을 거절했어요.`
              : `${m.counterpartNickname}님과의 채팅을 종료했어요.`
      );
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "처리하지 못했습니다.");
      setError({ message: parsed.message, stale: parsed.code === "MATCH_STATE_CONFLICT" || parsed.code === "TICKET_EXCHANGED" || parsed.code === "TICKET_ALREADY_RESERVED" });
    } finally {
      busyRef.current = false;
      setBusy(null);
    }
  };

  const titleId = `match-${m.id}-title`;
  const open = m.status === "CHATTING" || m.status === "RESERVED";
  const reserved = m.status === "RESERVED";
  const canReserve = m.status === "CHATTING";
  const ELSEWHERE = "(다른 사용자와 예약 중인 좌석)";
  const myElsewhere = m.status === "CHATTING" && m.myTicketReservedElsewhere;
  const counterpartElsewhere = m.status === "CHATTING" && m.counterpartTicketReservedElsewhere;
  const showReject = open && m.role === "RECEIVED";
  const lockedReasonId = `match-${m.id}-locked-reason`;
  const exchangedId = `match-${m.id}-exchanged`;
  const completed = m.status === "COMPLETED";
  /** 교환 완료로 EXCHANGED가 된 티켓이 걸린 채팅 중 매칭: 예약하기를 막고 안내한다 */
  const seatExchanged = m.status === "CHATTING" && (m.myTicketExchanged || m.counterpartTicketExchanged);
  const anyAccepted = m.myAccepted || m.counterpartAccepted;
  /** 삭제된 조건이 걸린 매칭은 회색으로 낮춰 보인다 (글자 표시를 함께 쓴다) */
  const anyDeleted = m.counterpartRequestDeleted || m.myRequestDeleted;

  return (
    <article
      className={`${ui.card} flex flex-col gap-3 ${anyDeleted ? "border-gray-300! bg-gray-100!" : ""}`}
      aria-labelledby={titleId}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id={titleId} className={`text-lg/[normal] font-bold ${anyDeleted ? "text-gray-700" : "text-gray-900"}`}>
          {m.counterpartNickname}
          {m.counterpartRequestDeleted && <DeletedTag label="상대가 교환 조건을 삭제했어요" text="(삭제)" />}
        </h2>
        <span
          className={`inline-flex shrink-0 items-center rounded-full px-2.5 py-0.5 text-[0.8125rem]/[normal] font-semibold ${STATUS_CLASS[m.status]}`}
        >
          {STATUS_LABEL[m.status]}
        </span>
      </div>

      {/* 좁은 화면(<480px): 라벨|값 한 줄 레이아웃, 넓은 화면: 2열 카드형(라벨 배지 위, 값 아래). 항목 래퍼는 좁을 때 contents라 dt/dd가 바로 그리드 칸이 된다 */}
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 min-[30rem]:grid-cols-2 min-[30rem]:gap-y-4">
        <div className="contents min-[30rem]:block">
          <dt className={DT_CLASS}>{completed ? "교환 전 내 자리" : "내 자리"}</dt>
          <dd className={`${DD_CLASS} min-w-0`}>
            <span className="font-semibold">{formatSeat(m.mySeat)}</span>
            {m.myRequestDeleted && <DeletedTag label="내 교환 조건을 삭제했어요" text="(내 조건 삭제됨)" />}
            {myElsewhere && <span className="block text-sm/[normal] text-gray-700">{ELSEWHERE}</span>}
            <span className={`block text-sm/[normal] ${anyDeleted ? "text-gray-600" : "text-gray-500"}`}>{formatKstDateTime(m.mySeat.startsAt)}</span>
          </dd>
        </div>
        <div className="contents min-[30rem]:block">
          <dt className={DT_CLASS}>{completed ? "받은 자리" : "상대 자리"}</dt>
          <dd className={`${DD_CLASS} min-w-0`}>
            <span className="font-semibold">{formatSeat(m.counterpartSeat)}</span>
            {m.counterpartRequestDeleted && <DeletedTag label="상대가 교환 조건을 삭제했어요" text="(삭제)" />}
            {counterpartElsewhere && <span className="block text-sm/[normal] text-gray-700">{ELSEWHERE}</span>}
            <span className={`block text-sm/[normal] ${anyDeleted ? "text-gray-600" : "text-gray-500"}`}>{formatKstDateTime(m.counterpartSeat.startsAt)}</span>
          </dd>
        </div>
        <div className="contents min-[30rem]:block">
          <dt className={DT_CLASS}>내 추가금</dt>
          <dd className={DD_CLASS}>{formatExtra(m.myExtraType, m.myExtraAmount)}</dd>
        </div>
        <div className="contents min-[30rem]:block">
          <dt className={DT_CLASS}>상대 추가금</dt>
          <dd className={DD_CLASS}>{formatExtra(m.counterpartExtraType, m.counterpartExtraAmount)}</dd>
        </div>
      </dl>

      {anyDeleted && (
        <p className="text-sm/[normal] text-gray-700">
          {m.counterpartRequestDeleted ? "상대가 교환 조건을 삭제했어요." : "내가 교환 조건을 삭제했어요."}
        </p>
      )}

      {reserved && (
        <p className={ui.notice}>
          {m.reservedBy === "ME"
            ? "내가 예약했어요."
            : m.reservedBy === "COUNTERPART"
              ? `${m.counterpartNickname}님이 예약했어요.`
              : "예약 중이에요."}{" "}
          두 티켓이 예약되어 다른 매칭에서는 쓸 수 없어요.
        </p>
      )}
      {reserved && m.counterpartAccepted && <p className={ui.notice}>{m.counterpartNickname}님이 교환 수락했어요.</p>}
      {completed && (
        <p className={ui.notice}>
          교환이 완료됐어요. 새 자리는{" "}
          <Link to="/tickets" className={`font-semibold underline ${FOCUS_RING}`}>
            내 티켓
          </Link>
          에서 확인하세요.
        </p>
      )}
      {m.status === "CANCELED" && (
        <p className={anyDeleted ? "text-sm/[normal] text-gray-600" : ui.muted}>{m.canceledBy ? CANCELED_BY_LABEL[m.canceledBy] : "취소됐어요."}</p>
      )}

      {open && (
        <div className="flex flex-wrap gap-2">
          {canReserve &&
            (seatExchanged ? (
              <button type="button" className={NOT_READY_CLASS} aria-disabled="true" aria-describedby={exchangedId} onClick={(e) => e.preventDefault()}>
                예약하기
              </button>
            ) : (
              <button type="button" className={button.solid} onClick={() => run("reserve")} disabled={busy !== null} aria-busy={busy === "reserve"}>
                {busy === "reserve" ? "처리 중..." : "예약하기"}
              </button>
            ))}
          {reserved && confirming !== "unreserve" && (
            <button type="button" className={button.outline} onClick={() => setConfirming("unreserve")} disabled={busy !== null}>
              예약 취소
            </button>
          )}
          {reserved &&
            confirming !== "complete" &&
            (m.myAccepted ? (
              /* 이미 수락함: 눌리지 않지만 포커스는 받게 aria-disabled로 둔다. 글자 자체가 상태 안내다 */
              <button type="button" className={`${NOT_READY_CLASS} !whitespace-normal text-center`} aria-disabled="true" onClick={(e) => e.preventDefault()}>
                교환 수락함 · 상대 수락을 기다려요
              </button>
            ) : (
              <button type="button" className={button.solid} onClick={() => setConfirming("complete")} disabled={busy !== null}>
                교환 수락
              </button>
            ))}
          {/* 예약 중에는 숨기지 않고 aria-disabled로 남겨 '왜 못 누르는지'를 보조기기와 터치 사용자 모두 알 수 있게 한다 */}
          {showReject && confirming !== "reject" && (
            <button
              type="button"
              className={reserved ? NOT_READY_CLASS : button.outline}
              onClick={reserved ? (e) => e.preventDefault() : () => setConfirming("reject")}
              disabled={reserved ? false : busy !== null}
              aria-disabled={reserved ? "true" : undefined}
              aria-describedby={reserved ? lockedReasonId : undefined}
              aria-label={`${m.counterpartNickname}님 제안 거절`}
            >
              거절
            </button>
          )}
          {confirming !== "cancel" && (
            <button
              type="button"
              className={reserved ? NOT_READY_CLASS : button.dangerOutline}
              onClick={reserved ? (e) => e.preventDefault() : () => setConfirming("cancel")}
              disabled={reserved ? false : busy !== null}
              aria-disabled={reserved ? "true" : undefined}
              aria-describedby={reserved ? lockedReasonId : undefined}
              aria-label={`${m.counterpartNickname}님과 채팅 종료`}
            >
              채팅 종료
            </button>
          )}
        </div>
      )}
      {reserved && (
        <div className="flex flex-col gap-1 text-sm/[normal] text-gray-700">
          <p id={lockedReasonId}>예약 중에는 거절·채팅 종료를 할 수 없어요. 먼저 예약을 취소해주세요.</p>
        </div>
      )}

      {confirming && (
        <div ref={confirmRef} tabIndex={-1} className="flex flex-col gap-3 outline-none">
          <p className={ui.warning}>
            {confirming === "reject"
              ? "이 제안을 거절할까요?"
              : confirming === "unreserve"
                ? `취소하면 예약이 풀리고 두 티켓을 다시 쓸 수 있어요.${anyAccepted ? " 교환 수락 표시도 초기화돼요." : ""} 예약을 취소할까요?`
                : confirming === "complete"
                  ? "티켓팅 사이트에서 양도를 마쳤나요? 두 사람 모두 수락하면 교환이 완료되고 취소할 수 없어요."
                  : "이 채팅을 종료할까요? 매칭이 취소돼요."}
          </p>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className={confirming === "complete" ? button.solid : button.danger}
              onClick={() => run(confirming)}
              disabled={busy !== null}
              aria-busy={busy === confirming}
            >
              {busy === confirming ? "처리 중..." : confirming === "reject" ? "거절할게요" : confirming === "unreserve" ? "예약 취소할게요" : confirming === "complete" ? "교환 수락할게요" : "종료할게요"}
            </button>
            <button type="button" className={button.outline} onClick={() => setConfirming(null)} disabled={busy !== null}>
              돌아가기
            </button>
          </div>
        </div>
      )}

      {error && (
        <div className={`${ui.errorBox} flex flex-col items-start gap-2`} role="alert">
          <span>{error.message}</span>
          {error.stale && (
            <button type="button" className={button.outline} onClick={onStale}>
              목록 새로고침
            </button>
          )}
        </div>
      )}

      {seatExchanged && (
        <p id={exchangedId} className="text-sm/[normal] font-semibold text-gray-700">
          이미 교환된 좌석이에요
        </p>
      )}

      {open && (
        <p className={anyDeleted ? "text-[0.8125rem]/[normal] text-gray-600" : ui.hint}>
          채팅 기능은 준비 중이에요.
        </p>
      )}
    </article>
  );
}
