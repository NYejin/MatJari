import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { ACTION_LABEL, TARGET_TYPE_LABEL, adminUsersApi, type AdminActionLog } from "../api/adminUsers";
import TextField from "../components/TextField";
import { button, input, ui } from "../components/adminUi";
import { tbl } from "../components/adminUi";
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
          className="flex flex-col gap-1.5"
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
          <TextField dense
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
          <div className="flex flex-col gap-1.5">
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
            <div className={tbl.wrap}>
              <table className={tbl.table} aria-label="조치 기록 목록">
                <thead>
                  <tr>
                    {["#", "시각", "조치", "대상", "처리자", "사유", "상세"].map((c) => (
                      <th key={c} className={tbl.th}>
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {state.items.map((l) => (
                    <LogRow key={l.id} log={l} />
                  ))}
                </tbody>
              </table>
            </div>
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

function LogRow({ log }: { log: AdminActionLog }) {
  const link = targetLink(log);
  const targetLabel = `${TARGET_TYPE_LABEL[log.targetType] ?? log.targetType} #${log.targetId}`;
  return (
    <tr className={tbl.tr}>
      <td className={tbl.num}>{log.id}</td>
      <td className={`${tbl.td} whitespace-nowrap`}>{formatKstDateTime(log.createdAt)}</td>
      <td className={`${tbl.td} font-semibold whitespace-nowrap`}>{ACTION_LABEL[log.action] ?? log.action}</td>
      <td className={`${tbl.td} whitespace-nowrap`}>
        {link ? (
          <Link to={link} className={ui.link}>
            {targetLabel}
          </Link>
        ) : (
          targetLabel
        )}
      </td>
      <td className={tbl.td}>{log.adminNickname}</td>
      <td className={`${tbl.td} max-w-72 break-words`}>{log.reason ?? ""}</td>
      <td className={`${tbl.td} max-w-96`}>
        {log.detail && (
          <details>
            <summary className={`${ui.muted} cursor-pointer`}>보기</summary>
            <pre className="mt-1 max-w-full overflow-x-auto rounded bg-gray-100 p-1.5 text-[11px]/[normal] break-all whitespace-pre-wrap text-gray-800">
              {prettyDetail(log.detail)}
            </pre>
          </details>
        )}
      </td>
    </tr>
  );
}
