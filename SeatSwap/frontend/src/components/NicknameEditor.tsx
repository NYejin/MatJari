import { useRef, useState, type FormEvent } from "react";
import { usersApi } from "../api/users";
import { parseApiError } from "../api/errors";
import { normalizeNickname, validateNickname } from "../api/authValidation";
import { useAuth } from "../hooks/useAuth";
import TextField from "./TextField";
import { button } from "./ui";

// 마이페이지 프로필 카드의 닉네임 인라인 편집. 저장 성공 시 AuthProvider 프로필을 즉시 교체해 헤더도 함께 바뀐다.

interface Props {
  nickname: string;
}

export default function NicknameEditor({ nickname }: Props) {
  const { applyProfile } = useAuth();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(nickname);
  const [error, setError] = useState<string | undefined>();
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState("");
  const editButtonRef = useRef<HTMLButtonElement>(null);

  const startEdit = () => {
    setValue(nickname);
    setError(undefined);
    setNotice("");
    setEditing(true);
  };

  const closeEdit = () => {
    setEditing(false);
    setError(undefined);
    // 편집 영역이 사라지므로 포커스를 '수정' 버튼이 다시 생긴 뒤 돌려준다
    requestAnimationFrame(() => editButtonRef.current?.focus());
  };

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (saving) return;
    const message = validateNickname(value);
    if (message) {
      setError(message);
      return;
    }
    setSaving(true);
    setError(undefined);
    try {
      const updated = await usersApi.updateNickname(normalizeNickname(value));
      applyProfile(updated);
      setNotice("닉네임을 바꿨어요.");
      closeEdit();
    } catch (err) {
      const parsed = parseApiError(err, "닉네임을 저장하지 못했어요.");
      setError(parsed.fieldErrors.nickname ?? parsed.message);
    } finally {
      setSaving(false);
    }
  };

  if (!editing) {
    return (
      <div className="flex items-center justify-between gap-3">
        <p className="min-w-0 truncate text-xl/[normal] font-bold text-gray-900" title={nickname}>
          {nickname}
        </p>
        <button ref={editButtonRef} type="button" className={`${button.outline} shrink-0`} onClick={startEdit}>
          수정
        </button>
        <p className="sr-only" role="status">
          {notice}
        </p>
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-3" aria-label="닉네임 수정">
      <TextField
        id="profile-nickname"
        label="닉네임"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        error={error}
        liveError
        hint="2~20자로 입력해 주세요."
        autoComplete="nickname"
        autoFocus
        disabled={saving}
      />
      <div className="flex justify-end gap-2">
        <button type="button" className={button.outline} onClick={closeEdit} disabled={saving}>
          취소
        </button>
        <button type="submit" className={button.solid} disabled={saving}>
          {saving ? "저장 중..." : "저장"}
        </button>
      </div>
    </form>
  );
}
