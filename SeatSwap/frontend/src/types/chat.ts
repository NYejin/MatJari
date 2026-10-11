// 채팅 (feature/chat): 보내기는 REST POST, 받기는 STOMP 사용자 큐(/user/queue/chat).

export interface ChatMessage {
  id: number;
  senderId: number;
  content: string;
  /** 클라이언트가 만든 멱등 키 (같은 id 재전송은 같은 메시지를 돌려준다) */
  clientMessageId: string;
  /** 오프셋 없는 KST 초 단위 "yyyy-MM-ddTHH:mm:ss" */
  createdAt: string;
}

/** GET /exchange/matches/{id}/messages 응답. messages는 오름차순 */
export interface ChatMessagePage {
  messages: ChatMessage[];
  hasMore: boolean;
  myLastReadMessageId: number;
  counterpartLastReadMessageId: number;
}

export interface SendChatMessageRequest {
  content: string;
  clientMessageId: string;
}

export interface ChatMessagePush {
  type: "MESSAGE";
  matchId: number;
  message: ChatMessage;
}

/** 상대가 읽음 포인터를 옮겼을 때 */
export interface ChatReadPush {
  type: "READ";
  matchId: number;
  lastReadMessageId: number;
}

export type ChatPush = ChatMessagePush | ChatReadPush;

/** 서버에 아직 확정되지 않은 내 메시지 (전송 중/실패) */
export interface PendingChatMessage {
  clientMessageId: string;
  content: string;
  status: "sending" | "failed";
  error: string | null;
}

export type RealtimeStatus = "connecting" | "connected" | "reconnecting" | "failed";
