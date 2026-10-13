// 티켓 (FR-03). 백엔드 TicketResponse / TicketCreateRequest와 1:1 대응.
// zone/row/col은 사용자가 입력한 표시용 원문(공백만 정리)이다. startsAt은 KST 현지 시각 "yyyy-MM-ddTHH:mm".

export interface Ticket {
  id: number;
  performanceId: number;
  performanceTitle: string;
  venueName: string;
  sessionId: number;
  startsAt: string;
  zone: string;
  row: string;
  col: string;
  status: string;
  createdAt: string;
}

/** POST /api/tickets */
export interface TicketCreateRequest {
  sessionId: number;
  zone: string;
  row: string;
  col: string;
}
