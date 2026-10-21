import { useState } from "react";
import { Link } from "react-router-dom";
import { formatKstDateTime } from "../api/dateTime";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import { adminUsersApi, type AdminUserItem, type UserRoleFilter, type UserStatusFilter } from "../api/adminUsers";
import TextField from "../components/TextField";
import { FOCUS_RING, button, input, ui } from "../components/adminUi";
import { tbl, tone } from "../components/adminUi";
import { usePagedList } from "../hooks/usePagedList";

// 관리자 이용자 목록 (/admin/users). 검색 + 상태·역할 필터.
const STATUS_OPTIONS: { value: UserStatusFilter; label: string }[] = [
  { value: "ALL", label: "상태 전체" },
  { value: "ACTIVE", label: "정상" },
  { value: "SUSPENDED", label: "이용 정지" },
  { value: "WITHDRAWN", label: "탈퇴" },
];
const ROLE_OPTIONS: { value: UserRoleFilter; label: string }[] = [
  { value: "ALL", label: "역할 전체" },
  { value: "USER", label: "일반 사용자" },
  { value: "ADMIN", label: "관리자" },
];

export default function AdminUsersPage() {
  const [draft, setDraft] = useState("");
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<UserStatusFilter>("ALL");
  const [role, setRole] = useState<UserRoleFilter>("ALL");
  const { state, loadingMore, moreError, loadMore, reload } = usePagedList<AdminUserItem>(
    (page, signal) => adminUsersApi.list({ query, status, role }, page, signal),
    `${query}|${status}|${role}`,
    (u) => u.id
  );

  return (
    <div className={ui.page}>
      <div className={ui.container}>
        <h1 className={ui.pageTitle}>이용자 관리</h1>

        <form
          className="flex flex-col gap-1.5"
          role="search"
          onSubmit={(e) => {
            e.preventDefault();
            setQuery(draft.trim());
          }}
        >
          <TextField dense
            id="admin-user-query"
            label="검색"
            hint="이메일·닉네임 일부 또는 회원 번호"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            maxLength={100}
          />
          <div className="flex flex-wrap gap-2">
            <select
              aria-label="상태 필터"
              className={`${input.normal} w-auto`}
              value={status}
              onChange={(e) => setStatus(e.target.value as UserStatusFilter)}
            >
              {STATUS_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
            <select
              aria-label="역할 필터"
              className={`${input.normal} w-auto`}
              value={role}
              onChange={(e) => setRole(e.target.value as UserRoleFilter)}
            >
              {ROLE_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
            <button type="submit" className={button.solid}>
              검색
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
              {exchangeErrorMessage(state.error, "이용자 목록을 불러오지 못했어요.").message}
            </p>
            <div>
              <button type="button" className={button.solid} onClick={reload}>
                다시 시도
              </button>
            </div>
          </div>
        )}
        {state.status === "success" && state.items.length === 0 && <p className={`${ui.card} ${ui.body}`}>해당하는 이용자가 없어요.</p>}
        {state.status === "success" && state.items.length > 0 && (
          <>
            <p className={ui.muted}>총 {state.totalElements}명</p>
            <div className={tbl.wrap}>
              <table className={tbl.table} aria-label="이용자 목록">
                <thead>
                  <tr>
                    {["#", "닉네임", "이메일", "역할", "상태", "정지 기한", "가입"].map((c) => (
                      <th key={c} className={tbl.th}>
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {state.items.map((u) => (
                    <tr key={u.id} className={tbl.tr}>
                      <td className={tbl.num}>
                        <Link to={`/admin/users/${u.id}`} className={`${ui.link} ${FOCUS_RING}`}>
                          {u.id}
                        </Link>
                      </td>
                      <td className={`${tbl.td} font-semibold break-all`}>
                        <Link to={`/admin/users/${u.id}`} className="text-gray-900 no-underline hover:underline">
                          {u.nickname}
                        </Link>
                      </td>
                      <td className={`${tbl.td} break-all`}>{u.emailPlaceholder ? <span className={tone.info}>소셜(이메일 없음)</span> : u.email}</td>
                      <td className={tbl.td}>{u.role === "ADMIN" ? <span className={tone.warn}>관리자</span> : "회원"}</td>
                      <td className={tbl.td}>
                        {u.withdrawn ? <span className={tone.mute}>탈퇴</span> : u.suspended ? <span className={tone.danger}>정지</span> : <span className={tone.ok}>활성</span>}
                      </td>
                      <td className={`${tbl.td} whitespace-nowrap`}>{u.suspended ? (u.suspendedUntil ? formatKstDateTime(u.suspendedUntil) : "무기한") : "-"}</td>
                      <td className={`${tbl.td} whitespace-nowrap`}>{formatKstDateTime(u.createdAt)}</td>
                    </tr>
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
