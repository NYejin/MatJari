// 교환 희망 조건·후보·매칭 (FR-04). 백엔드 계약(SeatSwap/backend/README.md)과 1:1 대응.
// 좌석표(좌표) 개념은 없다 — 좌석은 (구역, 열, 번) 텍스트다.

/** 추가금 유형: X=추가금 없이 / ANY=상관없음 / POS=받고 싶어요(금액 양수) / NEG=낼 수 있어요(금액 음수로 전송) */
export type ExtraType = "X" | "ANY" | "POS" | "NEG";

export interface WantSessionItem {
  sessionId: number;
  priority: number;
  startsAt: string;
}

/** 희망 범위 1건 (범위마다 추가금이 다르다). 서버 응답의 열·번은 정규화 값(`03열` -> `3`). extraAmount: POS 양수, NEG 음수, X/ANY는 null */
export interface WantRange {
  zone: string;
  rowFrom: string;
  rowTo: string;
  colFrom: string;
  colTo: string;
  extraType: ExtraType;
  extraAmount: number | null;
}

/** 요청 본문의 범위 1건. extraAmount: POS 양수, NEG 음수, X/ANY는 보내지 않는다 */
export interface WantRangePayload {
  zone: string;
  rowFrom: string;
  rowTo: string;
  colFrom: string;
  colTo: string;
  extraType: ExtraType;
  extraAmount?: number;
}

/** GET /api/exchange/requests/me 항목, POST/PATCH 응답 */
export interface ExchangeRequest {
  id: number;
  ticketId: number;
  /** OPEN | CLOSED */
  status: string;
  wantSessions: WantSessionItem[];
  ranges: WantRange[];
  /** 겹침 제거 후 펼친 희망 좌석 수 */
  wantSeatCount: number;
  createdAt: string;
  updatedAt: string;
}

/** PATCH 본문 (POST는 ticketId 추가). 추가금은 요청 단위가 아니라 범위(ranges[i])마다 있다 */
export interface ExchangeRequestPayload {
  wantSessions: { sessionId: number; priority: number }[];
  ranges: WantRangePayload[];
}

export interface ExchangeRequestCreatePayload extends ExchangeRequestPayload {
  ticketId: number;
}

/** 참고용 금액 구간 (매칭 여부와 무관) */
export interface SettlementHint {
  min: number;
  max: number;
}

/** GET /api/exchange/requests/{id}/candidates 항목 */
export interface ExchangeCandidate {
  requestId: number;
  ticketId: number;
  zone: string;
  row: string;
  col: string;
  sessionId: number;
  startsAt: string;
  nickname: string;
  /** 내 희망 회차 중 상대 티켓 회차의 우선순위 (1이 가장 높음) */
  wantPriority: number;
  /** 상대 쪽 추가금: 상대 요청에서 내 좌석이 속한 범위의 추가금 */
  extraType: ExtraType;
  extraAmount: number | null;
  /** 내 쪽 추가금: 내 요청에서 상대 좌석이 속한 범위의 추가금 */
  myExtraType: ExtraType;
  myExtraAmount: number | null;
  settlementHint: SettlementHint | null;
  requestedAt: string;
}

export type MatchStatus = "CHATTING" | "RESERVED" | "COMPLETED" | "CANCELED";
/** A=제안한 쪽, B=제안받은 쪽 */
export type MatchSide = "A" | "B";
export type MatchRole = "ALL" | "SENT" | "RECEIVED";
export type CanceledBy = "ME" | "COUNTERPART" | "SYSTEM";

/** 매칭에 걸린 자리 하나 (목록 응답) */
export interface MatchSeat {
  zone: string;
  row: string;
  col: string;
  sessionId: number;
  startsAt: string;
}

/**
 * 매칭 (호출자 기준). POST proposals/reserve/unreserve/reject/cancel 응답, GET /matches/me 항목, GET /matches/{id}가 같은 모양이다.
 * RESERVED일 때 reservedBy가 예약한 쪽이다(ME 또는 COUNTERPART, 그 외 null).
 * myAccepted/counterpartAccepted는 '교환 수락' 여부. COMPLETED의 좌석은 항상 교환 전 자리다.
 */
export interface ExchangeMatch {
  id: number;
  status: MatchStatus;
  mySide: MatchSide;
  role: MatchRole;
  myRequestId: number;
  myTicketId: number;
  mySeat: MatchSeat;
  counterpartRequestId: number;
  counterpartTicketId: number;
  counterpartSeat: MatchSeat;
  counterpartNickname: string;
  myExtraType: ExtraType;
  myExtraAmount: number | null;
  counterpartExtraType: ExtraType;
  counterpartExtraAmount: number | null;
  /** 요청이 소프트 삭제됐는지 (삭제되면 채팅 단계 매칭은 자동 취소된다) */
  myRequestDeleted: boolean;
  counterpartRequestDeleted: boolean;
  reservedBy: "ME" | "COUNTERPART" | null;
  reservedAt: string | null;
  myAccepted: boolean;
  counterpartAccepted: boolean;
  /** 내/상대 티켓이 교환 완료로 EXCHANGED가 됐는지 (그 티켓이 걸린 CHATTING 카드에서 예약 불가 안내용) */
  myTicketExchanged: boolean;
  counterpartTicketExchanged: boolean;
  /** 내/상대 티켓이 이 매칭이 아닌 다른 매칭에서 예약 잠금 중인지 (CHATTING 카드에서만 의미) */
  myTicketReservedElsewhere: boolean;
  counterpartTicketReservedElsewhere: boolean;
  /** 상대가 보낸 안 읽은 채팅 메시지 수 (종료된 매칭도 센다) */
  unreadChatCount: number;
  canceledBy: CanceledBy | null;
  canceledAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MatchListParams {
  role?: MatchRole | "ALL";
  status?: MatchStatus;
  page?: number;
  size?: number;
}
