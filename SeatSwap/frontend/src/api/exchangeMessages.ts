import axios from "axios";
import { getErrorCode, parseApiError, type ParsedApiError } from "./errors";
import type { ExtraType, WantRange } from "../types/exchange";

// 교환 화면 공용: 서버 오류 코드 -> 사용자 안내 문구, 추가금 표기.
// 코드에 맞는 문구가 없으면 서버 message(한국어)를 그대로 쓴다.

const CODE_MESSAGES: Record<string, string> = {
  MATCH_ALREADY_OPEN: "이미 이 상대와 진행 중인 매칭이 있어요.",
  NOT_A_CANDIDATE: "조건이 바뀌어 더 이상 후보가 아니에요. 후보 목록을 새로 확인해 주세요.",
  TICKET_LOCKED: "내 티켓이 다른 상대와 예약되어 있어요. 그 예약을 취소하기 전에는 새로 제안할 수 없어요.",
  TICKET_ALREADY_RESERVED: "이미 다른 교환으로 예약된 티켓이에요. 그 예약이 취소되면 다시 예약할 수 있어요.",
  TICKET_EXCHANGED: "이미 교환된 좌석이에요. 이 좌석으로는 예약하거나 내릴 수 없어요.",
  RESERVATION_PROPOSAL_PENDING: "상대가 이미 예약을 제안했어요. 수락하거나 거절해 주세요.",
  RESERVATION_PROPOSAL_NOT_FOUND: "수락할 예약 제안이 없어요. 상대가 철회했거나 이미 처리됐을 수 있어요.",
  RESERVATION_PROPOSAL_OWN: "내가 한 제안은 상대만 수락할 수 있어요. 철회하려면 '제안 철회'를 눌러 주세요.",
  RESERVATION_PROPOSAL_NOT_OWN: "상대가 낸 예약 제안은 철회할 수 없어요. 거절하거나 수락해 주세요.",
  // MATCH_STATE_CONFLICT는 서버 message를 그대로 쓴다 (예약 중 채팅 종료, 예약 전 교환 수락 등 상황별 문구)
  ACTIVE_MATCH_EXISTS: "예약된 교환이 있어 변경할 수 없어요. 먼저 예약을 취소해주세요.",
  TICKET_NOT_ACTIVE: "내린 티켓이거나 닫힌 교환 요청이에요.",
  SESSION_CLOSED: "회차 당일이 지나 마감된 티켓이에요.",
  WANT_INCLUDES_OWN_SEAT: "희망 회차가 내 티켓의 회차뿐이면 희망 좌석에 내 자리를 넣을 수 없어요.",
  TICKET_RESERVED: "예약된 티켓은 내릴 수 없어요. 먼저 예약을 취소해 주세요.",
  REQUEST_ALREADY_EXISTS: "이 티켓에는 이미 교환 조건이 있어요. 화면을 새로 열어 수정해 주세요.",
  SEAT_ALREADY_REGISTERED: "이미 등록된 좌석이에요.",
  MY_TICKET_ALREADY_REGISTERED: "이미 내가 등록한 좌석이에요.",
  TICKET_LIMIT_REACHED: "등록할 수 있는 티켓 수를 넘었어요. 쓰지 않는 티켓을 내린 뒤 다시 시도해 주세요.",
  REQUEST_DELETED: "이미 삭제된 교환 조건이에요. 내 티켓에서 새로 만들어 주세요.",
  WANT_EXTRA_CONFLICT: "겹치는 범위에 서로 다른 추가금 조건이 있어요. 겹치는 범위의 추가금을 같게 맞춰주세요.",
  BLOCK_LIMIT_REACHED: "차단할 수 있는 사용자 수를 넘었어요. 차단 목록에서 해제한 뒤 다시 시도해 주세요.",
  REPORT_ALREADY_OPEN: "이 채팅에 접수된 신고가 아직 처리 중이에요. 처리 결과는 '내 신고 내역'에서 볼 수 있어요.",
  REPORT_LIMIT_REACHED: "처리 중인 신고가 너무 많아요. 처리가 끝난 뒤 다시 신고해 주세요.",
  BUSY: "요청이 몰려 처리하지 못했어요. 잠시 후 다시 시도해주세요.",
};

/** 코드별 문구가 있으면 그것을, 없으면 서버 message를 쓴다. code는 화면이 분기할 때 쓴다 */
export function exchangeErrorMessage(error: unknown, fallback: string): ParsedApiError & { code: string | null } {
  const parsed = parseApiError(error, fallback);
  const code = getErrorCode(error);
  const mapped = code ? CODE_MESSAGES[code] : undefined;
  return { ...parsed, message: mapped ?? parsed.message, code };
}

export function formatWon(n: number): string {
  return `${n.toLocaleString("ko-KR")}원`;
}

/** 추가금 조건 표기: X='추가금 없이', ANY='상관없음', POS='받고 싶어요 N원', NEG='낼 수 있어요 N원'(절댓값) */
export function formatExtra(type: ExtraType, amount: number | null): string {
  switch (type) {
    case "X":
      return "추가금 없이";
    case "ANY":
      return "상관없음";
    case "POS":
      return amount === null ? "받고 싶어요" : `받고 싶어요 ${formatWon(Math.abs(amount))}`;
    case "NEG":
      return amount === null ? "낼 수 있어요" : `낼 수 있어요 ${formatWon(Math.abs(amount))}`;
  }
}

/** 범위별 추가금 요약 (내 티켓 목록용): 모두 같으면 그 조건, 다르면 '범위별 추가금' */
export function summarizeRangeExtras(ranges: WantRange[]): string {
  if (ranges.length === 0) return "";
  const first = ranges[0];
  const same = ranges.every((r) => r.extraType === first.extraType && r.extraAmount === first.extraAmount);
  return same ? `추가금 ${formatExtra(first.extraType, first.extraAmount)}` : "범위별 추가금";
}

/** 422 WANT_EXTRA_CONFLICT 본문의 최상위 `conflicts: [[i, j], ...]`에서 충돌한 범위 인덱스(0부터, 중복 제거·오름차순)를 뽑는다. */
export function getConflictIndexes(error: unknown): number[] {
  if (!axios.isAxiosError(error)) return [];
  const data: unknown = error.response?.data;
  if (typeof data !== "object" || data === null) return [];
  const body = data as Record<string, unknown>;
  const list = body.conflicts;
  if (!Array.isArray(list)) return [];
  const out = new Set<number>();
  for (const item of list) {
    const nums = Array.isArray(item)
      ? item
      : typeof item === "object" && item !== null
        ? Object.values(item as Record<string, unknown>)
        : [item];
    for (const n of nums) if (typeof n === "number" && Number.isInteger(n) && n >= 0) out.add(n);
  }
  return [...out].sort((a, b) => a - b);
}
