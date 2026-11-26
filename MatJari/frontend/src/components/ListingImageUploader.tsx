import { useEffect, useMemo, useRef, useState, type ChangeEvent } from "react";
import { exchangeErrorMessage } from "../api/exchangeMessages";
import type { ListingImageOwnerApi } from "../api/listingImages";
import { LISTING_IMAGE_MAX, type ListingImageRef } from "../types/listingImage";
import ChatImageRedactDialog from "./ChatImageRedactDialog";
import ListingImageGallery from "./ListingImageGallery";
import { button, ui } from "./ui";

// 티켓('내 자리')·교환 희망 조건('원하는 자리') 사진 올리기. 파일을 고르면 채팅과 같은 가리기 단계를 거친 JPEG만 올린다.
// - live: 이미 있는 티켓·요청에 바로 올리고 지운다(api + ownerId)
// - pending: 등록 폼용. 가리기까지 마친 사진을 대기 목록에 두고, 부모가 저장 성공 뒤 올린다(api/pendingImages.ts)

interface Common {
  /** 안내 문구 앞에 붙는 추가 설명 (예: 좌석표에 원하는 자리를 표시한 스크린샷) */
  guide?: string;
  disabled?: boolean;
  /** 입력 id 접두사(한 화면에 둘 이상일 때 구분) */
  idPrefix?: string;
}
interface LiveProps extends Common {
  mode: "live";
  api: ListingImageOwnerApi;
  ownerId: number;
  images: ListingImageRef[];
  onImagesChange: (images: ListingImageRef[]) => void;
}
interface PendingProps extends Common {
  mode: "pending";
  pending: Blob[];
  onPendingChange: (pending: Blob[]) => void;
}
type Props = LiveProps | PendingProps;

const PRIVACY_NOTICE = "예매번호·이름 같은 개인정보는 가려서 올려 주세요. 사진은 교환 후보와 채팅 상대에게만 보여요.";

export default function ListingImageUploader(props: Props) {
  const { guide, disabled = false, idPrefix = "listing-image" } = props;
  const [file, setFile] = useState<File | null>(null);
  const [confirmId, setConfirmId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState("");
  const [deleting, setDeleting] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const count = props.mode === "live" ? props.images.length : props.pending.length;
  const remaining = Math.max(0, LISTING_IMAGE_MAX - count);

  const onPick = (e: ChangeEvent<HTMLInputElement>) => {
    const picked = e.target.files?.[0] ?? null;
    e.target.value = ""; // 같은 파일을 다시 골라도 change가 나게
    if (!picked) return;
    setError(null);
    if (!/^image\/(jpeg|png)$/.test(picked.type)) {
      setError("JPEG 또는 PNG 사진만 올릴 수 있어요.");
      return;
    }
    setFile(picked);
  };

  /** 가리기 대화상자의 '올리기'. 대기 목록에 담거나(pending) 서버에 올린다(live). 실패하면 대화상자에 문구가 뜬다 */
  const onRedacted = async (blob: Blob, signal: AbortSignal) => {
    if (props.mode === "pending") {
      props.onPendingChange([...props.pending, blob].slice(0, LISTING_IMAGE_MAX));
      setStatus("사진을 담았어요. 저장하면 함께 올라가요.");
      setFile(null);
      return;
    }
    try {
      const saved = await props.api.upload(props.ownerId, blob, signal);
      props.onImagesChange([...props.images, { id: saved.id }]);
      setStatus("사진을 올렸어요.");
      setFile(null);
    } catch (err) {
      if (signal.aborted) throw err;
      throw new Error(exchangeErrorMessage(err, "사진을 올리지 못했어요.").message);
    }
  };

  const removeLive = async (id: number) => {
    if (props.mode !== "live" || deleting) return;
    setDeleting(true);
    setError(null);
    try {
      await props.api.remove(props.ownerId, id);
      props.onImagesChange(props.images.filter((i) => i.id !== id));
      setStatus("사진을 지웠어요.");
      setConfirmId(null);
    } catch (err) {
      setError(exchangeErrorMessage(err, "사진을 지우지 못했어요.").message);
    } finally {
      setDeleting(false);
    }
  };

  const removePending = (index: number) => {
    if (props.mode !== "pending") return;
    props.onPendingChange(props.pending.filter((_, i) => i !== index));
    setConfirmId(null);
    setStatus("사진을 뺐어요.");
  };

  const inputId = `${idPrefix}-file`;

  return (
    <div className="flex flex-col gap-2.5">
      {guide && <p className={ui.hint}>{guide}</p>}
      <p className={ui.hint}>{PRIVACY_NOTICE}</p>

      {count > 0 && (
        <ul className="flex flex-wrap gap-3" aria-label="올린 사진">
          {props.mode === "live"
            ? props.images.map((img, idx) => (
                <li key={img.id} className="flex flex-col items-start gap-1.5">
                  <ListingImageGallery images={[img]} label={`사진 ${idx + 1}`} size={88} />
                  {confirmId === img.id ? (
                    <span className="flex flex-col gap-1.5">
                      <span className="text-[0.8125rem] text-gray-700">이 사진을 지울까요?</span>
                      <span className="flex gap-1.5">
                        <button type="button" className={`${button.danger} min-h-10 !px-3`} disabled={deleting || disabled} onClick={() => void removeLive(img.id)}>
                          지우기
                        </button>
                        <button type="button" className={`${button.outline} min-h-10 !px-3`} disabled={deleting} onClick={() => setConfirmId(null)}>
                          취소
                        </button>
                      </span>
                    </span>
                  ) : (
                    <button type="button" className={`${button.dangerOutline} min-h-10 !px-3 !text-[0.8125rem]`} disabled={disabled} onClick={() => setConfirmId(img.id)} aria-label={`사진 ${idx + 1} 지우기`}>
                      지우기
                    </button>
                  )}
                </li>
              ))
            : props.pending.map((blob, idx) => (
                <PendingThumb key={idx} blob={blob} index={idx} onRemove={() => removePending(idx)} disabled={disabled} />
              ))}
        </ul>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <input id={inputId} ref={inputRef} type="file" accept="image/jpeg,image/png" className="hidden" onChange={onPick} tabIndex={-1} aria-hidden="true" />
        <button type="button" className={button.outline} disabled={disabled || remaining === 0} onClick={() => inputRef.current?.click()}>
          사진 추가
        </button>
        <span className={ui.hint}>{remaining === 0 ? `최대 ${LISTING_IMAGE_MAX}장까지 올렸어요.` : `${remaining}장 더 올릴 수 있어요 (${count}/${LISTING_IMAGE_MAX})`}</span>
      </div>

      {error && (
        <p className={ui.errorBox} role="alert">
          {error}
        </p>
      )}
      <p className="sr-only" role="status">
        {status}
      </p>

      {file && (
        <ChatImageRedactDialog
          file={file}
          title={props.mode === "pending" ? "사진 가리기" : "사진 올리기"}
          sendLabel={props.mode === "pending" ? "담기" : "올리기"}
          busyLabel="사진을 올리는 중..."
          onSend={onRedacted}
          onClose={() => setFile(null)}
        />
      )}
    </div>
  );
}

function PendingThumb({ blob, index, onRemove, disabled }: { blob: Blob; index: number; onRemove: () => void; disabled: boolean }) {
  const url = useMemo(() => URL.createObjectURL(blob), [blob]);
  useEffect(() => () => URL.revokeObjectURL(url), [url]);
  const [confirm, setConfirm] = useState(false);
  return (
    <li className="flex flex-col items-start gap-1.5">
      <img src={url} alt={`담은 사진 ${index + 1}`} className="size-[88px] rounded-xl object-cover" />
      {confirm ? (
        <span className="flex gap-1.5">
          <button type="button" className={`${button.danger} min-h-10 !px-3`} disabled={disabled} onClick={onRemove}>
            빼기
          </button>
          <button type="button" className={`${button.outline} min-h-10 !px-3`} onClick={() => setConfirm(false)}>
            취소
          </button>
        </span>
      ) : (
        <button type="button" className={`${button.dangerOutline} min-h-10 !px-3 !text-[0.8125rem]`} disabled={disabled} onClick={() => setConfirm(true)} aria-label={`담은 사진 ${index + 1} 빼기`}>
          빼기
        </button>
      )}
    </li>
  );
}
