import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { ACTION_LABEL, TARGET_TYPE_LABEL, adminUsersApi, type AdminActionLog } from "../api/adminUsers";
import TextField from "../components/TextField";
import { button, input, ui } from "../components/ui";
import { usePagedList } from "../hooks/usePagedList";

// 관리자 조치 기록 (/admin/logs). 액션·대상 필터, 상세 JSON은 접어서 본다.
function prettyDetail(detail: string): string {
  try {
    return JSON.stringify(JSON.parse(detail), null, 2);
  } catch {
    return detail;
  }
}

function targetLink(log: AdminActionLog): string | null {
  if (log.targetType === "PERFORMANCE") return `/admin/performances/${log.targetId}`;
  if (log.targetType === "USER") return `/admin/users/${log.targetId}`;
  return null;
}

export default function AdminLogsPage() {
  // 이용자 상세의 "조치 기록" 링크가 ?targetType=&targetId= 로 필터를 미리 채운다
  const [params] = useSearchParams();
  const initialId = (params.get("targetId") ?? "").replace(/\D/g, "").slice(0, 18);
  const [action, setAction] = useState("");
  const [targetType, setTargetType] = useState(params.get("targetType") ?? "");
  const [targetIdDraft, setTargetIdDraft] = useState(initialId);
  const [targetId, setTargetId] = useState(initialId);
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<AdminActionLog>(
    (page, signal) => adminUsersApi.actionLogs({ action, targetType, targetId }, page, signal),
    `${action}|${targetType}|${targetId}`,
    (l) => l.id
  );

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>조치 기록</h1>

        <form
          className="flex flex-col gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            setTargetId(targetIdDraft.trim());
          }}
        >
          <div className="flex flex-wrap gap-2">
            <select aria-label="액션 필터" className={`${input.normal} w-auto`} value={action} onChange={(e) => setAction(e.target.value)}>
              <option value="">액션 전체</option>
              {Object.entries(ACTION_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
            <select
              aria-label="대상 종류 필터"
              className={`${input.normal} w-auto`}
              value={targetType}
              onChange={(e) => setTargetType(e.target.value)}
            >
              <option value="">대상 전체</option>
              {Object.entries(TARGET_TYPE_LABEL).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </div>
          <TextField
            id="admin-log-target-id"
            label="대상 번호"
            hint="비우면 전체예요. 숫자만 입력해 주세요."
            inputMode="numeric"
            value={targetIdDraft}
            onChange={(e) => setTargetIdDraft(e.target.value.replace(/\D/g, "").slice(0, 18))}
          />
          <div>
            <button type="submit" className={button.solid}>
              적용
            </button>
          </div>
        </form>

        {state.status === "loading" && (
          <p className={ui.status} role="status">
            불러오는 중...
          </p>
        )}
        {state.status === "error" && (
          <div className="flex flex-col gap-3">
            <p className={ui.errorBox} role="alert">
              {exchangeErrorMessage(state.error, "조치 기록을 불러오지 못했어요.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}
        {state.status === "success" && state.items.length === 0 && <p className={`${ui.card} ${ui.body}`}>조치 기록이 없어요.</p>}
        {state.status === "success" && state.items.length > 0 && (
          <>
            <ul className="flex flex-col gap-3" aria-label="조치 기록 목록">
              {state.items.map((l) => (
                <li key={l.id}>
                  <LogCard log={l} />
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

function LogCard({ log }: { log: AdminActionLog }) {
  const link = targetLink(log);
  const targetLabel = `${TARGET_TYPE_LABEL[log.targetType] ?? log.targetType} #${log.targetId}`;
  return (
    <article className={`${ui.card} flex flex-col gap-2`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-base/[normal] font-bold text-gray-900">{ACTION_LABEL[log.action] ?? log.action}</h2>
        <span className={ui.muted}>{formatKstDateTime(log.createdAt)}</span>
      </div>
      <p className={ui.body}>
        대상:{" "}
        {link ? (
          <Link to={link} className={ui.link}>
            {targetLabel}
          </Link>
        ) : (
          targetLabel
        )}{" "}
        · 처리 {log.adminNickname}
      </p>
      {log.reason && <p className={`${ui.body} break-words`}>사유: {log.reason}</p>}
      {log.detail && (
        <details>
          <summary className={`${ui.muted} cursor-pointer`}>상세 보기</summary>
          <pre className="mt-2 max-w-full overflow-x-auto rounded-[10px] bg-gray-100 p-3 text-xs/[normal] break-all whitespace-pre-wrap text-gray-800">
            {prettyDetail(log.detail)}
          </pre>
        </details>
      )}
    </article>
  );
}
