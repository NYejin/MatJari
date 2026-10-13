import { apiClient } from "./client";
import type { Ticket, TicketCreateRequest } from "../types/ticket";

// 티켓 (FR-03). 모두 로그인 필요, 본인 티켓만. JWT는 client.ts 인터셉터가 처리한다.
// 오류: 400 {zone|row|col|sessionId: 메시지}, 409 {message, code: SEAT_ALREADY_REGISTERED|MY_TICKET_ALREADY_REGISTERED|TICKET_RESERVED},
// 422 {code: TICKET_LIMIT_REACHED, message}
export const ticketsApi = {
  /** POST /api/tickets → 201 */
  async create(payload: TicketCreateRequest): Promise<Ticket> {
    const { data } = await apiClient.post<Ticket>("/tickets", payload);
    return data;
  },

  /** GET /api/tickets/me — 내 활성 티켓 (회차 시각 오름차순) */
  async listMine(signal?: AbortSignal): Promise<Ticket[]> {
    const { data } = await apiClient.get<Ticket[]>("/tickets/me", { signal });
    return data;
  },

  /** DELETE /api/tickets/{id} → 204. 예약 잠금이 걸려 있으면 409 TICKET_RESERVED */
  async deactivate(id: number): Promise<void> {
    await apiClient.delete(`/tickets/${id}`);
  },
};
