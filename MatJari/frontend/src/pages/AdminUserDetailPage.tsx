import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { Link, useParams } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { formatUntil } from "../api/adminReports";
import {
  ACTION_REASON_MAX,
  ADMIN_USER_CODE_MESSAGES,
  FORCE_WITHDRAW_CONFIRM,
  adminUsersApi,
  type AdminUserDetail,
  type AdminUserSanction,
} from "../api/adminUsers";
import TextField from "../components/TextField";
import { button, input, ui } from "../components/adminUi";
import { useAuth } from "../hooks/useAuth";

// 관리자 이용자 상세·조치 (/admin/users/:id). 조치마다 사유 입력 → 확인 단계 → 실행.

type LoadState = { status: "loading" } | { status: "error"; message: string } | { status: "success"; user: AdminUserDetail };

export default function AdminUserDetailPage() {
  const { id } = useParams();
  const userId = Number(id);
  const { user: me } = useAuth();
  const [state, setState] = useState<LoadState>({ status: "loading" });
  const [reloadKey, setReloadKey] = useState(0);
  const reload = useCallback(() => setReloadKey((k) => k + 1), []);

  useEffect(() => {
    if (!Number.isSafeInteger(userId) || userId <= 0) {
      setState({ status: "error", message: "잘못된 이용자 번호예요." });
      return;
    }
    const controller = new AbortController();
    adminUsersApi
      .get(userId, controller.signal)
      .then((user) => setState({ status: "success", user }))
      .catch((err: unknown) => {
        if (controller.signal.aborted) return;
        setState({ status: "error", message: exchangeErrorMessage(err, "이용자 정보를 불러오지 못했어요.").message });
      });
    return () => controller.abort();
  }, [userId, reloadKey]);

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <p>
          <Link to="/admin/users" className={ui.link}>
            ← 이용자 목록
          </Link>
        </p>
        {state.status === "loading" && (
          <p className={ui.status} role="status">
            불러오는 중...
          </p>
        )}
        {state.status === "error" && (
          <div className="flex flex-col gap-1.5">
            <p className={ui.errorBox} role="alert">
              {state.message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}
        {state.status === "success" && <UserBody user={state.user} isSelf={me?.id === state.user.id} onChanged={reload} />}
      </div>
    </div>
  );
}

function UserBody({ user: u, isSelf, onChanged }: { user: AdminUserDetail; isSelf: boolean; onChanged: () => void }) {
  return (
    <div className="grid grid-cols-1 items-start gap-2 lg:grid-cols-2 2xl:grid-cols-3">
      <section className={`${ui.card} flex flex-col gap-2`} aria-label="기본 정보">
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-[13px]/[normal] font-bold break-all text-gray-900">{u.nickname}</h1>
          {u.role === "ADMIN" && <span className={ui.badge}>관리자</span>}
          {u.suspended && <span className={ui.badge}>이용 정지 중 ({formatUntil(u.suspendedUntil)})</span>}
          {u.withdrawn && <span className={ui.badge}>탈퇴</span>}
          {isSelf && <span className={ui.badge}>나</span>}
        </div>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-xs/[normal] text-gray-700">
          <dt className="text-gray-500">회원 번호</dt>
          <dd>#{u.id}</dd>
          <dt className="text-gray-500">이메일</dt>
          <dd className="break-all">{u.emailPlaceholder ? "없음 (소셜 가입)" : u.email}</dd>
          <dt className="text-gray-500">가입일</dt>
          <dd>{formatKstDateTime(u.createdAt)}</dd>
          <dt className="text-gray-500">로그인 수단</dt>
          <dd>
            {[u.passwordLoginEnabled ? "비밀번호" : null, ...u.socialProviders].filter(Boolean).join(", ") || "없음"}
          </dd>
          <dt className="text-gray-500">활성 티켓</dt>
          <dd>{u.activeTicketCount}개</dd>
          <dt className="text-gray-500">진행 중 매칭</dt>
          <dd>{u.openMatchCount}건</dd>
          <dt className="text-gray-500">접수된 신고</dt>
          <dd>처리 대기 {u.openReportsAgainstCount}건</dd>
          {u.withdrawnAt && (
            <>
              <dt className="text-gray-500">탈퇴일</dt>
              <dd>{formatKstDateTime(u.withdrawnAt)}</dd>
            </>
          )}
        </dl>
        <p>
          <Link to={`/admin/logs?targetType=USER&targetId=${u.id}`} className={ui.link}>
            이 이용자의 조치 기록
          </Link>
        </p>
      </section>

      <section className={`${ui.card} flex flex-col gap-1.5`} aria-label="제재 내역">
        <h2 className={ui.sectionTitle}>제재 내역</h2>
        {u.sanctions.length === 0 ? (
          <p className={ui.body}>제재 내역이 없어요.</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {u.sanctions.map((s) => (
              <li key={s.id}>
                <SanctionRow sanction={s} />
              </li>
            ))}
          </ul>
        )}
      </section>

      {u.withdrawn ? (
        <p className={ui.notice} role="status">
          이미 탈퇴한 사용자라 더 할 수 있는 조치가 없어요.
        </p>
      ) : (
        <>
          <SuspendPanel user={u} onDone={onChanged} />
          <RolePanel user={u} isSelf={isSelf} onDone={onChanged} />
          <ForceWithdrawPanel user={u} isSelf={isSelf} onDone={onChanged} />
        </>
      )}
    </div>
  );
}

function SanctionRow({ sanction: s }: { sanction: AdminUserSanction }) {
  const status = s.revokedAt
    ? `해제됨 (${formatKstDateTime(s.revokedAt)})`
    : s.endsAt === null || new Date(s.endsAt).getTime() > Date.now()
      ? "정지 중"
      : "기간 만료";
  return (
    <div className="flex flex-col gap-0.5 rounded bg-gray-50 p-1.5">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className={ui.badge}>{status}</span>
        <span className={ui.muted}>
          {formatKstDateTime(s.startsAt)} 시작 · {formatUntil(s.endsAt)}
        </span>
      </div>
      <p className={`${ui.body} break-words`}>사유: {s.note}</p>
      <p className={ui.muted}>
        처리 {s.createdByNickname}
        {s.reportId !== null && (
          <>
            {" · "}
            <Link to={`/admin/reports/${s.reportId}`} className={ui.link}>
              신고 #{s.reportId}
            </Link>
          </>
        )}
      </p>
    </div>
  );
}

interface ActionPanelProps {
  title: string;
  description: string;
  /** 있으면 조치 입력을 막고 이 안내만 보인다 */
  disabledReason?: string;
  /** 사유 입력 필수 여부 */
  reasonRequired: boolean;
  reasonLabel?: string;
  submitLabel: string;
  danger?: boolean;
  /** 사유 칸 앞에 놓는 추가 입력(기간 선택 등) */
  extra?: ReactNode;
  /** 추가 입력이 유효한지 (기본 true) */
  extraValid?: boolean;
  /** 사유 외에 직접 입력해야 하는 확인 문구 */
  confirmPhrase?: string;
  confirmMessage: string;
  run: (reason: string) => Promise<void>;
  onDone: () => void;
  successMessage: string;
}

// 조치 하나의 입력 → 확인 → 실행 흐름. 모듈 최상위에 정의해 렌더마다 새로 만들어지지 않게 한다.
function ActionPanel(p: ActionPanelProps) {
  const [reason, setReason] = useState("");
  const [phrase, setPhrase] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const busyRef = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const fieldId = `action-${p.title.replace(/\s/g, "-")}`;

  const reasonTrimmed = reason.trim();
  const reasonOk = p.reasonRequired ? reasonTrimmed.length > 0 : true;
  const phraseOk = p.confirmPhrase === undefined || phrase === p.confirmPhrase;
  const canSubmit = reasonOk && phraseOk && (p.extraValid ?? true);

  const execute = async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await p.run(reasonTrimmed);
      setDone(p.successMessage);
      setConfirming(false);
      setReason("");
      setPhrase("");
      p.onDone();
    } catch (err) {
      const parsed = exchangeErrorMessage(err, "조치하지 못했어요.");
      setError((parsed.code && ADMIN_USER_CODE_MESSAGES[parsed.code]) || parsed.message);
      setConfirming(false);
      // 대상 상태가 이미 바뀐 경우 화면을 새로 맞춘다
      if (parsed.code === "TARGET_WITHDRAWN" || parsed.code === "SANCTION_ALREADY_ACTIVE") p.onDone();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  return (
    <section className={`${ui.card} flex flex-col gap-1.5 ${p.danger ? "border border-red-200" : ""}`} aria-label={p.title}>
      <h2 className={p.danger ? "text-lg/[normal] font-bold text-red-700" : ui.sectionTitle}>{p.title}</h2>
      <p className={ui.body}>{p.description}</p>
      {p.disabledReason ? (
        <p className={ui.notice} role="status">
          {p.disabledReason}
        </p>
      ) : (
        <>
          {p.extra}
          <div className="flex flex-col gap-1.5">
            <label htmlFor={`${fieldId}-reason`} className={ui.label}>
              {p.reasonLabel ?? "사유"}
              {p.reasonRequired ? " (필수)" : " (선택)"}
            </label>
            <textarea
              id={`${fieldId}-reason`}
              className={`${input.normal} min-h-24 py-2.5`}
              value={reason}
              maxLength={ACTION_REASON_MAX}
              onChange={(e) => setReason(e.target.value)}
              disabled={busy || confirming}
            />
            <p className={ui.hint}>
              {reason.length}/{ACTION_REASON_MAX}자 · 조치 기록에 남아요.
            </p>
          </div>
          {p.confirmPhrase !== undefined && (
            <TextField dense
              id={`${fieldId}-phrase`}
              label={`확인 문구: "${p.confirmPhrase}"를 그대로 입력해 주세요`}
              value={phrase}
              onChange={(e) => setPhrase(e.target.value)}
              autoComplete="off"
              disabled={busy || confirming}
            />
          )}
          {error && (
            <p className={ui.errorBox} role="alert">
              {error}
            </p>
          )}
          {done && !error && (
            <p className={ui.notice} role="status">
              {done}
            </p>
          )}
          {!confirming && (
            <div className="flex justify-end">
              <button
                type="button"
                className={p.danger ? button.danger : button.solid}
                disabled={!canSubmit || busy}
                onClick={() => {
                  setDone(null);
                  setConfirming(true);
                }}
              >
                {p.submitLabel}
              </button>
            </div>
          )}
          {confirming && (
            <div className="flex flex-col gap-1.5">
              <p className={ui.warning} role="alert">
                {p.confirmMessage}
              </p>
              <div className="flex flex-wrap justify-end gap-2">
                <button type="button" className={button.outline} onClick={() => setConfirming(false)} disabled={busy}>
                  돌아가기
                </button>
                <button
                  type="button"
                  className={p.danger ? button.danger : button.solid}
                  onClick={() => void execute()}
                  disabled={busy}
                  aria-busy={busy}
                >
                  {busy ? "처리 중..." : "확정"}
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}

const DURATIONS: { value: string; label: string; days: 7 | 30 | null }[] = [
  { value: "7", label: "7일", days: 7 },
  { value: "30", label: "30일", days: 30 },
  { value: "forever", label: "무기한", days: null },
];

function SuspendPanel({ user: u, onDone }: { user: AdminUserDetail; onDone: () => void }) {
  const [duration, setDuration] = useState("7");
  const days = DURATIONS.find((d) => d.value === duration)?.days ?? 7;

  if (u.suspended) {
    return (
      <UnsuspendPanel user={u} onDone={onDone} />
    );
  }
  return (
    <ActionPanel
      title="이용 정지"
      description="신고 없이 바로 이용 정지를 걸어요. 정지되면 로그인할 수 없고 진행 중인 매칭이 모두 취소돼요."
      disabledReason={u.role === "ADMIN" ? "관리자에게는 이용 정지를 걸 수 없어요. 먼저 일반 사용자로 내려야 해요." : undefined}
      reasonRequired
      submitLabel="이용 정지하기"
      danger
      extra={
        <fieldset className="flex flex-col gap-2">
          <legend className={ui.label}>정지 기간</legend>
          <div className="flex flex-wrap gap-2">
            {DURATIONS.map((d) => (
              <button
                key={d.value}
                type="button"
                className={duration === d.value ? button.solid : button.outline}
                aria-pressed={duration === d.value}
                onClick={() => setDuration(d.value)}
              >
                {d.label}
              </button>
            ))}
          </div>
        </fieldset>
      }
      confirmMessage={`${u.nickname}님에게 ${days === null ? "무기한" : `${days}일`} 이용 정지를 걸까요? 진행 중인 교환 채팅은 모두 취소돼요.`}
      run={(reason) => adminUsersApi.suspend(u.id, days, reason)}
      onDone={onDone}
      successMessage="이용 정지를 걸었어요."
    />
  );
}

function UnsuspendPanel({ user: u, onDone }: { user: AdminUserDetail; onDone: () => void }) {
  return (
    <ActionPanel
      title="정지 해제"
      description={`이용 정지 중이에요 (${formatUntil(u.suspendedUntil)}). 해제해도 이미 취소된 채팅은 되살아나지 않아요.`}
      reasonRequired={false}
      submitLabel="정지 해제하기"
      confirmMessage={`${u.nickname}님의 이용 정지를 모두 해제할까요?`}
      run={(reason) => adminUsersApi.unsuspend(u.id, reason)}
      onDone={onDone}
      successMessage="이용 정지를 해제했어요."
    />
  );
}

function RolePanel({ user: u, isSelf, onDone }: { user: AdminUserDetail; isSelf: boolean; onDone: () => void }) {
  const toAdmin = u.role !== "ADMIN";
  return (
    <ActionPanel
      title="역할 변경"
      description={toAdmin ? "일반 사용자를 관리자로 올려요. 관리자는 신고·공연·이용자를 관리할 수 있어요." : "관리자를 일반 사용자로 내려요."}
      disabledReason={
        isSelf
          ? "내 역할은 직접 바꿀 수 없어요."
          : toAdmin && u.suspended
            ? "이용 정지 중인 사용자는 관리자로 올릴 수 없어요. 정지를 먼저 해제해 주세요."
            : undefined
      }
      reasonRequired
      submitLabel={toAdmin ? "관리자로 올리기" : "일반 사용자로 내리기"}
      confirmMessage={
        toAdmin
          ? `${u.nickname}님을 관리자로 올릴까요? 바로 관리자 권한이 생겨요.`
          : `${u.nickname}님을 일반 사용자로 내릴까요? 마지막 관리자는 내릴 수 없어요.`
      }
      run={(reason) => adminUsersApi.changeRole(u.id, toAdmin ? "ADMIN" : "USER", reason)}
      onDone={onDone}
      successMessage="역할을 바꿨어요."
    />
  );
}

function ForceWithdrawPanel({ user: u, isSelf, onDone }: { user: AdminUserDetail; isSelf: boolean; onDone: () => void }) {
  return (
    <ActionPanel
      title="강제 탈퇴 (위험)"
      description="비밀번호 확인 없이 계정을 탈퇴 처리해요. 열린 매칭이 취소되고 티켓이 내려가며 계정은 익명화돼요. 되돌릴 수 없어요."
      disabledReason={
        isSelf
          ? "내 계정은 여기서 탈퇴시킬 수 없어요."
          : u.role === "ADMIN"
            ? "먼저 일반 사용자로 내려야 해요."
            : undefined
      }
      reasonRequired
      submitLabel="강제 탈퇴시키기"
      danger
      confirmPhrase={FORCE_WITHDRAW_CONFIRM}
      confirmMessage={`${u.nickname}님을 강제 탈퇴시킬까요? 이 작업은 되돌릴 수 없어요.`}
      run={(reason) => adminUsersApi.forceWithdraw(u.id, FORCE_WITHDRAW_CONFIRM, reason)}
      onDone={onDone}
      successMessage="강제 탈퇴 처리했어요."
    />
  );
}
