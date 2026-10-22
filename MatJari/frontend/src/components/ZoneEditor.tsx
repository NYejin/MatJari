import { useState, type FormEvent } from "react";
import {
  ZONES_MAX,
  ZONE_NAME_MAX,
  labelAnchor,
  newZoneRowId,
  polygonPoints,
  previewHeight,
  validateZoneRows,
  zoneNameError,
  type ZoneRow,
} from "../api/zones";
import { polygonsBox } from "../api/zoomMath";
import ZoomableSeatmap from "./ZoomableSeatmap";
import { useTokens } from "./density";

// 공연 등록 2단계의 "구역 확인" — 이름 수정·삭제·직접 추가와 (모양이 있으면) 작은 SVG 미리보기.
// 이름을 고쳐도 모양은 유지하고, 구역을 지우면 모양도 함께 사라진다. 반드시 모듈 최상위 컴포넌트로 쓴다.

interface ZoneEditorProps {
  rows: ZoneRow[];
  aspect: number | null;
  onChange: (rows: ZoneRow[]) => void;
  disabled?: boolean;
  /** 서버가 돌려준 구역 오류 */
  serverError?: string;
}

export const ZONE_ADD_INPUT_ID = "perf-zone-add";
export const zoneNameInputId = (id: number) => `perf-zone-${id}`;

export default function ZoneEditor({ rows, aspect, onChange, disabled = false, serverError }: ZoneEditorProps) {
  const { button, input, ui } = useTokens();
  const [draft, setDraft] = useState("");
  const [addError, setAddError] = useState<string>();
  const [active, setActive] = useState<number | null>(null);
  const [status, setStatus] = useState("");
  const [listOpen, setListOpen] = useState(false);

  const errors = validateZoneRows(rows);
  // 구역이 많으면 목록이 길어 스크롤이 불편하다: 기본은 접어 두고, 이름 오류가 있거나 서버 오류가 있으면 자동으로 펼친다
  const open = listOpen || Object.keys(errors).length > 0 || !!serverError;
  const shaped = rows.filter((r) => polygonPoints(r.shapePolygons).length > 0);
  const height = previewHeight(aspect);
  const activeRow = shaped.find((r) => r.id === active);
  const activeBox = activeRow ? polygonsBox(activeRow.shapePolygons, height / 1000) : null;

  const add = (e: FormEvent) => {
    e.preventDefault();
    if (rows.length >= ZONES_MAX) {
      setAddError(`구역은 한 번에 ${ZONES_MAX.toLocaleString()}개까지 추가할 수 있어요.`);
      return;
    }
    const err = zoneNameError(draft, rows.map((r) => r.name));
    if (err) {
      setAddError(err);
      return;
    }
    onChange([...rows, { id: newZoneRowId(), name: draft.trim(), shapePolygons: null }]);
    setStatus(`구역 ${draft.trim()}을(를) 추가했어요.`);
    setDraft("");
    setAddError(undefined);
  };

  const remove = (row: ZoneRow) => {
    onChange(rows.filter((r) => r.id !== row.id));
    if (active === row.id) setActive(null);
    setStatus(`구역 ${row.name.trim() || "(이름 없음)"}을(를) 삭제했어요.`);
    // 목록이 비어 가는 동안에도 키보드 사용자가 길을 잃지 않게 추가 칸으로 포커스
    document.getElementById(ZONE_ADD_INPUT_ID)?.focus();
  };

  const rename = (row: ZoneRow, name: string) => onChange(rows.map((r) => (r.id === row.id ? { ...r, name } : r)));

  const previewLabel = `구역 모양 미리보기. 구역 ${shaped.length}개: ${shaped.map((r) => r.name.trim() || "이름 없음").join(", ")}`;

  return (
    <div className="flex flex-col gap-3">
      {shaped.length > 0 && (
        <div className="flex flex-col gap-1">
          <ZoomableSeatmap width={1000} height={height} label={previewLabel} focusBox={activeBox} focusKey={active}>
            {shaped.map((r) => {
              const on = r.id === active;
              return polygonPoints(r.shapePolygons, height / 1000).map((pts, i) => (
                <polygon
                  key={`${r.id}-${i}`}
                  points={pts}
                  strokeLinejoin="round"
                  vectorEffect="non-scaling-stroke"
                  className={on ? "fill-accent-100 stroke-gray-900" : "fill-primary-50 stroke-primary-700"}
                  strokeWidth={on ? 3 : 1.5}
                  fillOpacity={0.85}
                />
              ));
            })}
            {shaped.map((r) => {
              const a = labelAnchor(r.shapePolygons, height / 1000);
              if (!a) return null;
              const on = r.id === active;
              return (
                <text
                  key={`t-${r.id}`}
                  x={a.x}
                  y={a.y}
                  textAnchor="middle"
                  dominantBaseline="central"
                  fontSize={on ? 40 : 30}
                  fontWeight={on ? 700 : 500}
                  className="fill-gray-900"
                  stroke="#fff"
                  strokeWidth={6}
                  paintOrder="stroke"
                  aria-hidden="true"
                >
                  {r.name.trim()}
                </text>
              );
            })}
          </ZoomableSeatmap>
          <p className={ui.hint}>목록의 구역 이름 옆 &lsquo;위치 보기&rsquo;를 누르면 지도에서 진하게 표시되고 그 구역으로 확대돼요. 지도는 +/− 버튼이나 두 손가락으로 확대하고 드래그로 옮길 수 있어요. 이름과 위치가 맞는지 확인하세요.</p>
        </div>
      )}

      <div className="flex flex-wrap items-center justify-between gap-2 rounded-[10px] border border-gray-200 px-3 py-2">
        <span className={ui.body}>
          구역 {rows.length}개{shaped.length > 0 ? ` (지도에 모양이 있는 구역 ${shaped.length}개)` : ""}
        </span>
        <button type="button" className={button.outline} onClick={() => setListOpen((o) => !o)} aria-expanded={open} aria-controls="perf-zone-list">
          {open ? "구역 목록 접기" : "구역 목록 펼쳐서 고치기"}
        </button>
      </div>

      {open && (
        <div id="perf-zone-list" className="flex flex-col gap-3">
      {rows.length === 0 ? (
        <p className={ui.body}>아직 구역이 없어요. 구역은 선택 사항이에요. 아래에서 직접 추가할 수 있어요.</p>
      ) : (
        <ul className="flex flex-col divide-y divide-gray-200 rounded-[10px] border border-gray-200 px-3" aria-label="구역 목록">
          {rows.map((r) => {
            const err = errors[r.id];
            const hasShape = polygonPoints(r.shapePolygons).length > 0;
            const on = r.id === active;
            const errId = `${zoneNameInputId(r.id)}-error`;
            return (
              <li key={r.id} className="flex flex-col gap-1.5 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <label htmlFor={zoneNameInputId(r.id)} className="sr-only">
                    구역 이름
                  </label>
                  <input
                    id={zoneNameInputId(r.id)}
                    type="text"
                    className={`${err ? input.invalid : input.normal} !w-auto min-w-0 flex-1 basis-40`}
                    value={r.name}
                    disabled={disabled}
                    maxLength={ZONE_NAME_MAX + 20 /* AI가 긴 이름을 줘도 보이고 오류로 알려 고치게 한다 */}
                    onChange={(e) => rename(r, e.target.value)}
                    onFocus={() => hasShape && setActive(r.id)}
                    aria-invalid={!!err}
                    aria-describedby={err ? errId : undefined}
                    autoComplete="off"
                    spellCheck={false}
                  />
                  {hasShape && (
                    <button
                      type="button"
                      className={button.outline}
                      aria-pressed={on}
                      onClick={() => setActive(on ? null : r.id)}
                      aria-label={`${r.name.trim() || "이름 없는 구역"} 위치 보기`}
                    >
                      위치 보기
                    </button>
                  )}
                  <button
                    type="button"
                    className={button.dangerOutline}
                    onClick={() => remove(r)}
                    disabled={disabled}
                    aria-label={`${r.name.trim() || "이름 없는 구역"} 삭제`}
                  >
                    삭제
                  </button>
                </div>
                {err && (
                  <p id={errId} className={ui.fieldError}>
                    {err}
                  </p>
                )}
              </li>
            );
          })}
        </ul>
      )}

      <form className="flex flex-col gap-1.5" onSubmit={add} noValidate>
        <label htmlFor={ZONE_ADD_INPUT_ID} className={ui.label}>
          구역 추가 (직접 입력)
        </label>
        <div className="flex flex-wrap items-start gap-2">
          <input
            id={ZONE_ADD_INPUT_ID}
            type="text"
            className={`${addError ? input.invalid : input.normal} !w-auto min-w-0 flex-1 basis-40`}
            value={draft}
            maxLength={ZONE_NAME_MAX}
            disabled={disabled}
            onChange={(e) => {
              setDraft(e.target.value);
              setAddError(undefined);
            }}
            aria-invalid={!!addError}
            aria-describedby={addError ? "perf-zone-add-error" : "perf-zone-add-hint"}
            placeholder="예: 1층 A구역"
            autoComplete="off"
          />
          <button type="submit" className={button.outline} disabled={disabled}>
            구역 추가
          </button>
        </div>
        {addError ? (
          <p id="perf-zone-add-error" className={ui.fieldError}>
            {addError}
          </p>
        ) : (
          <p id="perf-zone-add-hint" className={ui.hint}>
            이름은 {ZONE_NAME_MAX}자 이하, 같은 이름은 한 번만 쓸 수 있어요. (현재 {rows.length}개)
          </p>
        )}
      </form>
        </div>
      )}
      {serverError && <p className={ui.fieldError}>{serverError}</p>}
      <p className="sr-only" role="status">
        {status}
      </p>
    </div>
  );
}
